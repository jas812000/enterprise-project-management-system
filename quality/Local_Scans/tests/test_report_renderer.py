"""Regression tests for EPMS HTML quality-gate rendering."""

import unittest
from quality.Local_Scans.reporting.report_parsers import empty_severity_counts

from quality.Local_Scans.reporting.render_report.report_badges import (
    border_color,
    status_html,
)
from quality.Local_Scans.reporting.render_report.report_builder import (
    _build_banner,
)
from quality.Local_Scans.reporting.render_report.report_rows import (
    summary_row,
)
from quality.Local_Scans.reporting.render_report.report_sections import (
    tool_section,
)


class TestReportRenderer(unittest.TestCase):
    """Verify scanner status rendering and overall gate precedence."""

    SEVERITY = empty_severity_counts()

    def test_status_badges(self):
        """Verify status badges."""
        cases = {
            "passed": ("PASSED", "#27ae60"),
            "failed": ("FAILED", "#c0392b"),
            "error": ("ERROR", "#8e44ad"),
            "skipped": ("SKIPPED", "#7f8c8d"),
        }

        for status, (label, color) in cases.items():
            with self.subTest(status=status):
                badge = status_html(status)

                self.assertIn(f">{label}</span>", badge)
                self.assertIn(color, badge)
                self.assertEqual(border_color(status), color)

    def test_summary_rows(self):
        """Verify summary rows."""
        cases = [
            ("checkstyle", "Checkstyle", "passed", 0),
            ("pmd", "PMD", "failed", 3),
            ("spotbugs", "SpotBugs", "passed", 0),
        ]

        for tool_id, name, status, count in cases:
            with self.subTest(scanner=name):
                findings = [{} for _ in range(count)]
                html = summary_row(tool_id, name, status, findings)

                self.assertIn(f'href="#tool-{tool_id}"', html)
                self.assertIn(f">{name}</a>", html)
                self.assertIn(status_html(status), html)
                self.assertIn(f"<td>{count}</td>", html)

    def test_error_and_skipped_summary_counts(self):
        """Verify error and skipped summary counts."""
        for status in ("error", "skipped"):
            with self.subTest(status=status):
                html = summary_row(
                    "checkstyle", "Checkstyle", status, []
                )

                self.assertIn(status_html(status), html)
                self.assertIn("<td>&mdash;</td>", html)

    def test_detailed_sections(self):
        """Verify detailed sections."""
        cases = [
            ("checkstyle", "Checkstyle", "passed", "#27ae60"),
            ("pmd", "PMD", "failed", "#c0392b"),
            ("spotbugs", "SpotBugs", "passed", "#27ae60"),
        ]

        for tool_id, name, status, color in cases:
            with self.subTest(scanner=name):
                html = tool_section(
                    tool_id, name, status, [], self.SEVERITY
                )

                self.assertIn(f'id="tool-{tool_id}"', html)
                self.assertIn(f'class="tool-name">{name}</span>', html)
                self.assertIn(status_html(status), html)
                self.assertIn(
                    f"border-left:4px solid {color}", html
                )

    def test_empty_finding_messages(self):
        """Verify empty finding messages."""
        cases = {
            "passed": "No findings.",
            "failed": "Findings were reported but details are unavailable.",
            "error": "Scanner execution failed.",
            "skipped": "Scanner was disabled or not executed.",
        }

        for status, message in cases.items():
            with self.subTest(status=status):
                html = tool_section(
                    "checkstyle",
                    "Checkstyle",
                    status,
                    [],
                    self.SEVERITY,
                )

                self.assertIn(message, html)

    def test_banner_precedence(self):
        """Verify advisory banner behavior."""
        cases = [
            (0, 0, "No findings detected"),
            (3, 0, "findings detected"),
            (0, 1, "could not complete"),
            (3, 1, "could not complete"),
        ]

        for findings, errors, expected in cases:
            with self.subTest(
                findings=findings,
                errors=errors,
            ):
                context = {
                    "normalized": {
                        "total_violations": findings,
                        "scanner_errors": errors,
                    },
                }

                _, message = _build_banner(context)

                self.assertIn("ADVISORY", message)
                self.assertIn(expected, message)



if __name__ == "__main__":
    unittest.main()
