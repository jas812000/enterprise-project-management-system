"""Regression tests for EPMS HTML quality-gate rendering."""

import unittest

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

    SEVERITY = {
        "critical": 0,
        "high": 0,
        "medium": 0,
        "low": 0,
        "info": 0,
    }

    def test_status_badges(self):
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
        for status in ("error", "skipped"):
            with self.subTest(status=status):
                html = summary_row(
                    "checkstyle", "Checkstyle", status, []
                )

                self.assertIn(status_html(status), html)
                self.assertIn("<td>&mdash;</td>", html)

    def test_detailed_sections(self):
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
        cases = {
            "passed": "No findings.",
            "failed": (
                "Scanner reported violations, "
                "but no findings are available."
            ),
            "error": "Scanner could not complete successfully.",
            "skipped": "Scanner was not executed.",
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
        cases = [
            ("passed", "passed", "passed", 0, "APPROVED"),
            ("passed", "failed", "passed", 1, "FAILED"),
            ("passed", "error", "passed", 0, "ERROR"),
            ("passed", "skipped", "passed", 0, "INCOMPLETE"),
            ("failed", "skipped", "passed", 1, "INCOMPLETE"),
            ("error", "failed", "passed", 1, "ERROR"),
            ("error", "skipped", "passed", 0, "ERROR"),
        ]

        for cs, pmd, sb, failed, expected in cases:
            with self.subTest(
                checkstyle=cs, pmd=pmd, spotbugs=sb
            ):
                context = {
                    "cs_status": cs,
                    "pmd_status": pmd,
                    "sb_status": sb,
                    "failed": failed,
                }

                _, message = _build_banner(context)
                actual = message.split(" — ", 1)[0]

                self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
