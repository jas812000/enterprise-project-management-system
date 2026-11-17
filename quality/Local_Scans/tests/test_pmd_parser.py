"""Unit tests for the EPMS PMD XML report parser."""

import tempfile
import unittest
from pathlib import Path

from quality.Local_Scans.reporting.report_parsers import parse_pmd


REPO_ROOT = "/example/epms"
PMD_NAMESPACE = "http://pmd.sourceforge.net/report/2.0.0"


class TestPmdParser(unittest.TestCase):
    """Verify PMD XML parsing and severity mapping."""

    def parse_xml(self, xml_content):
        """Write temporary XML and parse it."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pmd.xml"
            path.write_text(xml_content, encoding="utf-8")
            return parse_pmd(path, REPO_ROOT)

    def test_empty_report(self):
        """Verify empty report."""
        xml = f'<pmd xmlns="{PMD_NAMESPACE}"></pmd>'
        findings, counts = self.parse_xml(xml)

        self.assertEqual(len(findings), 0)
        self.assertEqual(sum(counts.values()), 0)

    def test_single_violation(self):
        """Verify single violation."""
        xml = f"""
        <pmd xmlns="{PMD_NAMESPACE}">
          <file name="/example/epms/backend/src/main/java/Test.java">
            <violation beginline="12"
                       rule="UnusedLocalVariable"
                       priority="3">
              Unused local variable
            </violation>
          </file>
        </pmd>
        """

        findings, counts = self.parse_xml(xml)

        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["severity"], "medium")
        self.assertEqual(findings[0]["line"], "12")
        self.assertEqual(findings[0]["rule"], "UnusedLocalVariable")
        self.assertEqual(
            findings[0]["file"],
            "backend/src/main/java/Test.java",
        )
        self.assertEqual(counts["medium"], 1)

    def test_priority_mapping(self):
        """Verify priority mapping."""
        violations = "\n".join(
            f'<violation beginline="{priority}" '
            f'rule="TestRule" priority="{priority}">Test</violation>'
            for priority in range(1, 6)
        )

        xml = f"""
        <pmd xmlns="{PMD_NAMESPACE}">
          <file name="/example/epms/Test.java">
            {violations}
          </file>
        </pmd>
        """

        findings, counts = self.parse_xml(xml)

        self.assertEqual(len(findings), 5)

        for severity in ("critical", "high", "medium", "low", "info"):
            self.assertEqual(counts[severity], 1)

    def test_missing_priority_defaults_to_medium(self):
        """Verify missing priority defaults to medium."""
        xml = f"""
        <pmd xmlns="{PMD_NAMESPACE}">
          <file name="/example/epms/Test.java">
            <violation beginline="1" rule="TestRule">Test</violation>
          </file>
        </pmd>
        """

        findings, counts = self.parse_xml(xml)

        self.assertEqual(findings[0]["severity"], "medium")
        self.assertEqual(counts["medium"], 1)

    def test_malformed_xml_raises_error(self):
        """Verify malformed xml raises error."""
        with self.assertRaises(RuntimeError):
            self.parse_xml("<pmd><file></pmd>")

    def test_wrong_root_raises_error(self):
        """Verify wrong root raises error."""
        with self.assertRaises(RuntimeError):
            self.parse_xml("<not_pmd></not_pmd>")


if __name__ == "__main__":
    unittest.main()
