"""Tests for Local Quality Gate scanner-status-aware report parsing."""

import unittest

from quality.Local_Scans.reporting.report_parsers import (
    parse_scanner_report,
)


class TestReportStatus(unittest.TestCase):
    """Regression tests for Local Quality Gate report status handling."""

    def test_passed_scanner_calls_parser(self):
        """Verify passed scanner calls parser."""
        expected = ([{"rule": "Example"}], {"high": 1})

        def parser(path):
            """Set up the test fixture."""
            self.assertEqual(path, "valid.xml")
            return expected

        result = parse_scanner_report(
            "passed", parser, "valid.xml"
        )

        self.assertEqual(result, expected)

    def test_failed_scanner_calls_parser(self):
        """Verify failed scanner calls parser."""
        expected = ([{"rule": "Violation"}], {"medium": 1})

        def parser(_path):
            """Set up the test fixture."""
            return expected

        result = parse_scanner_report(
            "failed", parser, "violations.xml"
        )

        self.assertEqual(result, expected)

    def test_error_scanner_skips_parser(self):
        """Verify error scanner skips parser."""
        def parser(_path):
            """Set up the test fixture."""
            self.fail("Parser must not execute for scanner errors.")

        findings, severity = parse_scanner_report(
            "error", parser, "missing.xml"
        )

        self.assertEqual(findings, [])
        self.assertEqual(sum(severity.values()), 0)
        self.assertEqual(
            set(severity),
            {"critical", "high", "medium", "low", "info"},
        )

    def test_skipped_scanner_skips_parser(self):
        """Verify skipped scanner skips parser."""
        def parser(_path):
            """Set up the test fixture."""
            self.fail("Parser must not execute for skipped scanners.")

        findings, severity = parse_scanner_report(
            "skipped", parser, "missing.xml"
        )

        self.assertEqual(findings, [])
        self.assertEqual(sum(severity.values()), 0)

    def test_unknown_status_raises_error(self):
        """Verify unknown status raises error."""
        with self.assertRaises(RuntimeError):
            parse_scanner_report(
                "unknown", lambda path: ([], {}), "test.xml"
            )

    def test_missing_report_for_passed_scanner_raises(self):
        """Verify missing report for passed scanner raises."""
        def parser(path):
            """Set up the test fixture."""
            raise RuntimeError("Report is missing.")

        with self.assertRaises(RuntimeError):
            parse_scanner_report(
                "passed", parser, "missing.xml"
            )

    def test_malformed_report_for_failed_scanner_raises(self):
        """Verify malformed report for failed scanner raises."""
        def parser(path):
            """Set up the test fixture."""
            raise RuntimeError("Malformed XML.")

        with self.assertRaises(RuntimeError):
            parse_scanner_report(
                "failed", parser, "malformed.xml"
            )


if __name__ == "__main__":
    unittest.main()
