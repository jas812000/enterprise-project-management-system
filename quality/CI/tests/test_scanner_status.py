"""Tests for EPMS CI scanner execution-status handling."""

import json
import tempfile
from contextlib import ExitStack, contextmanager
import unittest
from pathlib import Path

from quality.CI.gate.scanner_status import apply_scanner_status
from quality.CI.gate.schemas import base_tool_result


@contextmanager
def _temporary_workspace():
    """Provide a temporary workspace with guaranteed cleanup."""
    with tempfile.TemporaryDirectory() as folder:
        yield folder


class ScannerStatusTests(unittest.TestCase):
    """Regression tests for the corresponding EPMS quality component."""

    def setUp(self):
        """Set up the test fixture."""
        self._resources = ExitStack()
        self.addCleanup(self._resources.close)
        self.temp_dir = self._resources.enter_context(_temporary_workspace())
        self.raw_dir = Path(self.temp_dir)

    def tearDown(self):
        """Set up the test fixture."""

    def write_status(self, tool, data):
        """Set up the test fixture."""
        path = self.raw_dir / f"{tool}.status.json"
        path.write_text(json.dumps(data), encoding="utf-8")

    def test_completed_scanner_preserves_findings(self):
        """Verify completed scanner preserves findings."""
        self.write_status(
            "semgrep",
            {"status": "completed", "exit_code": 0},
        )

        result = base_tool_result("semgrep")
        result["artifact_present"] = True
        result["executed"] = True
        result["violation_count"] = 2

        updated = apply_scanner_status(
            "semgrep", result, self.raw_dir
        )

        self.assertTrue(updated["executed"])
        self.assertFalse(updated["runtime_error"])
        self.assertEqual(updated["violation_count"], 2)
        self.assertEqual(updated["metadata"]["status"], "completed")

    def test_failed_scanner_blocks_even_with_report(self):
        """Verify failed scanner blocks even with report."""
        self.write_status(
            "semgrep",
            {
                "status": "failed",
                "exit_code": 2,
                "reason": "Scanner execution error",
            },
        )

        result = base_tool_result("semgrep")
        result["artifact_present"] = True
        result["executed"] = True
        result["violation_count"] = 1

        updated = apply_scanner_status(
            "semgrep", result, self.raw_dir
        )

        self.assertTrue(updated["runtime_error"])
        self.assertEqual(updated["violation_count"], 1)
        self.assertEqual(updated["metadata"]["status"], "failed")

    def test_disabled_scanner_is_not_executed(self):
        """Verify disabled scanner is not executed."""
        self.write_status(
            "stylelint",
            {
                "status": "disabled",
                "reason": "No frontend project detected",
            },
        )

        result = base_tool_result("stylelint")

        updated = apply_scanner_status(
            "stylelint", result, self.raw_dir
        )

        self.assertFalse(updated["executed"])
        self.assertFalse(updated["runtime_error"])
        self.assertEqual(updated["metadata"]["status"], "disabled")

    def test_missing_status_fails_closed(self):
        """Verify missing status fails closed."""
        result = base_tool_result("semgrep")
        result["artifact_present"] = True
        result["executed"] = True

        updated = apply_scanner_status(
            "semgrep", result, self.raw_dir
        )

        self.assertTrue(updated["runtime_error"])
        self.assertEqual(updated["metadata"]["status"], "failed")

    def test_invalid_status_fails_closed(self):
        """Verify invalid status fails closed."""
        self.write_status(
            "semgrep",
            {"status": "unknown"},
        )

        result = base_tool_result("semgrep")

        updated = apply_scanner_status(
            "semgrep", result, self.raw_dir
        )

        self.assertTrue(updated["runtime_error"])
        self.assertEqual(updated["metadata"]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
