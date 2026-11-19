"""Tests for additional Local Quality Gate scanner orchestration."""

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from quality.Local_Scans.checks.run_additional_scanners import run


class AdditionalScannerTests(unittest.TestCase):
    """Verify execution isolation and status reporting."""

    def test_scanner_failure_does_not_stop_other_scanners(self):
        """Verify scanner failure does not stop other scanners."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "repo"
            raw = root / "raw"
            tools = root / "tools"
            repo.mkdir()

            with (
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_python_scanners.run_pylint",
                    side_effect=RuntimeError("test failure"),
                ),
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_python_scanners.run_bandit",
                    side_effect=RuntimeError("test failure"),
                ),
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_htmlhint.run"
                ),
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_stylelint.run"
                ),
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_gitleaks.run"
                ),
                patch(
                    "quality.Local_Scans.checks."
                    "run_additional_scanners."
                    "check_trufflehog.run"
                ),
            ):
                run(repo, raw, tools)

            for name in (
                "pylint", "bandit", "htmlhint",
                "stylelint", "gitleaks", "trufflehog",
            ):
                status_path = raw / f"{name}.status.json"
                self.assertTrue(status_path.exists())

                status = json.loads(
                    status_path.read_text(encoding="utf-8")
                )
                self.assertEqual(status["status"], "failed")


if __name__ == "__main__":
    unittest.main()
