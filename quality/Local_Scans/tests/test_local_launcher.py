"""Regression tests for Local Quality Gate launcher initialization."""

import json
import os
import shutil
import subprocess  # nosec B404
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
LAUNCHER = REPO_ROOT / "quality/Local_Scans/run-local-checks.sh"

# Resolve the shell executable for the controlled launcher regression test.
SHELL = shutil.which("sh")
if SHELL is None:
    raise RuntimeError("Required POSIX shell executable is unavailable")


class LocalLauncherTests(unittest.TestCase):
    """Verify launcher configuration failures occur before scanner execution."""

    def test_invalid_configuration_stops_before_scanners(self):
        """Reject unsupported configuration without starting scanners."""
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "invalid-project.json"
            config.write_text(
                json.dumps({"unsupported": True}),
                encoding="utf-8",
            )

            environment = os.environ.copy()
            environment["QUALITY_CONFIG"] = str(config)

            # Fixed executable and repository-local launcher; shell=False.
            result = subprocess.run(  # nosec B603
                [SHELL, str(LAUNCHER)],
                cwd=REPO_ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )

            self.assertEqual(result.returncode, 2)
            self.assertIn(
                "ERROR: Unable to load Quality Gates project configuration.",
                result.stderr,
            )
            self.assertNotIn("--- Checkstyle ---", result.stdout)
            self.assertNotIn("--- PMD ---", result.stdout)
            self.assertNotIn("--- SpotBugs ---", result.stdout)


if __name__ == "__main__":
    unittest.main()
