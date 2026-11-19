"""Tests for Local Quality Gate per-scanner console outcome messages."""

import unittest

from quality.Local_Scans.local_normalize import format_scanner_outcome


class ConsoleOutcomeTests(unittest.TestCase):
    """Verify clean, findings, error, and disabled messages."""

    def test_clean(self):
        """Clean scanner results produce a consistent success message."""
        result = {"tool": "pylint", "metadata": {"status": "completed"},
                  "violation_count": 0, "runtime_error": False}
        self.assertEqual(format_scanner_outcome(result),
                         "Pylint passed. No findings found.")

    def test_findings(self):
        """Detected findings are reported without implying scanner failure."""
        result = {"tool": "gitleaks", "metadata": {"status": "completed"},
                  "violation_count": 3, "runtime_error": False}
        self.assertEqual(format_scanner_outcome(result),
                         "Gitleaks completed. 3 findings detected.")

    def test_error(self):
        """Scanner execution failures are reported as errors."""
        result = {"tool": "bandit", "metadata": {"status": "failed"},
                  "violation_count": 0, "runtime_error": True}
        self.assertEqual(format_scanner_outcome(result),
                         "Bandit: error (scanner failed).")

    def test_disabled(self):
        """Disabled scanners are not incorrectly reported as passing."""
        result = {"tool": "stylelint", "metadata": {"status": "disabled"},
                  "violation_count": 0, "runtime_error": False}
        self.assertEqual(format_scanner_outcome(result),
                         "Stylelint: disabled.")


if __name__ == "__main__":
    unittest.main()
