"""Integration tests for EPMS CI scanner-status normalization."""

import json
import tempfile
from contextlib import ExitStack, contextmanager
import unittest
from pathlib import Path
from unittest.mock import patch

from quality.CI.gate import normalize as normalization
from quality.CI.gate.schemas import base_tool_result


@contextmanager
def _temporary_workspace():
    """Provide a temporary workspace with guaranteed cleanup."""
    with tempfile.TemporaryDirectory() as folder:
        yield folder


class NormalizationIntegrationTests(unittest.TestCase):
    """Regression tests for the corresponding EPMS quality component."""

    def setUp(self):
        """Set up the test fixture."""
        self._resources = ExitStack()
        self.addCleanup(self._resources.close)
        self.temp_dir = self._resources.enter_context(_temporary_workspace())
        self.root = Path(self.temp_dir)
        self.raw_dir = self.root / "raw"
        self.normalized_dir = self.root / "normalized"
        self.raw_dir.mkdir()

        self.patches = [
            patch.object(normalization, "RAW_DIR", self.raw_dir),
            patch.object(normalization, "NORMALIZED_DIR", self.normalized_dir),
            patch.object(
                normalization,
                "OUTPUT_FILE",
                self.normalized_dir / "normalized.json",
            ),
        ]

        for item in self.patches:
            item.start()

    def tearDown(self):
        """Set up the test fixture."""
        for item in reversed(self.patches):
            item.stop()

    def write_status(self, tool, status, **fields):
        """Set up the test fixture."""
        data = {"status": status, **fields}
        path = self.raw_dir / f"{tool}.status.json"
        path.write_text(json.dumps(data), encoding="utf-8")

    def run_normalization(self, tool, parser):
        """Set up the test fixture."""
        with patch.object(
            normalization,
            "TOOL_PARSERS",
            [(tool, parser)],
        ):
            results = normalization.normalize()

        output = json.loads(
            (self.normalized_dir / "normalized.json").read_text(
                encoding="utf-8"
            )
        )

        self.assertEqual(output["tool_count"], 1)
        self.assertEqual(len(results), 1)
        return results[0]

    def test_completed_scanner_preserves_findings(self):
        """Verify completed scanner preserves findings."""
        self.write_status("semgrep", "completed", exit_code=0)

        def parser(_raw_dir):
            """Set up the test fixture."""
            result = base_tool_result("semgrep")
            result["artifact_present"] = True
            result["executed"] = True
            result["violation_count"] = 2
            return result

        result = self.run_normalization("semgrep", parser)

        self.assertEqual(result["metadata"]["status"], "completed")
        self.assertEqual(result["violation_count"], 2)
        self.assertFalse(result["runtime_error"])

    def test_disabled_scanner_skips_parser(self):
        """Verify disabled scanner skips parser."""
        self.write_status(
            "stylelint",
            "disabled",
            reason="No frontend project detected",
        )

        def parser(raw_dir):
            """Set up the test fixture."""
            raise AssertionError(
                "Disabled scanner parser must not execute"
            )

        result = self.run_normalization(
            "stylelint", parser
        )

        self.assertEqual(result["metadata"]["status"], "disabled")
        self.assertFalse(result["executed"])
        self.assertFalse(result["runtime_error"])

    def test_disabled_scanner_with_existing_report_fails(self):
        """Verify disabled scanner with existing report fails."""
        self.write_status(
            "stylelint",
            "disabled",
            reason="No frontend project detected",
        )

        report = self.raw_dir / "stylelint.json"
        report.write_text("[]", encoding="utf-8")

        def parser(raw_dir):
            """Set up the test fixture."""
            raise AssertionError(
                "Disabled scanner parser must not execute"
            )

        result = self.run_normalization("stylelint", parser)

        self.assertEqual(result["metadata"]["status"], "failed")
        self.assertTrue(result["runtime_error"])

    def test_missing_status_fails_closed(self):
        """Verify missing status fails closed."""
        def parser(_raw_dir):
            """Set up the test fixture."""
            result = base_tool_result("semgrep")
            result["artifact_present"] = True
            result["executed"] = True
            return result

        result = self.run_normalization("semgrep", parser)

        self.assertEqual(result["metadata"]["status"], "failed")
        self.assertTrue(result["runtime_error"])

    def test_parser_exception_does_not_stop_normalization(self):
        """Verify parser exception does not stop normalization."""
        self.write_status("semgrep", "completed", exit_code=0)

        def parser(raw_dir):
            """Set up the test fixture."""
            raise ValueError("Simulated parser failure")

        result = self.run_normalization("semgrep", parser)

        self.assertEqual(result["metadata"]["status"], "failed")
        self.assertTrue(result["runtime_error"])


if __name__ == "__main__":
    unittest.main()
