"""Tests for Local Quality Gate scanner integration."""

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from quality.Local_Scans.local_pipeline import integrate


class LocalPipelineTests(unittest.TestCase):
    """Verify raw retention and normalized output."""

    def test_pipeline_retains_raw_and_normalized_results(self):
        """Verify pipeline retains raw and normalized results."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "repo"
            work = root / "work"
            reports = root / "reports"
            tools = root / "tools"

            repo.mkdir()
            work.mkdir()

            # Test valid empty Java reports.
            (work / "checkstyle.xml").write_text(
                '<?xml version="1.0"?>'
                '<checkstyle version="10.0"/>',
                encoding="utf-8",
            )
            (work / "pmd.xml").write_text(
                '<?xml version="1.0"?>'
                '<pmd version="7.0"/>',
                encoding="utf-8",
            )
            (work / "spotbugs.xml").write_text(
                '<?xml version="1.0"?>'
                '<BugCollection/>',
                encoding="utf-8",
            )

            def fake_scanners(_repo, raw, _tools):
                """Set up the test fixture."""
                for name in (
                    "pylint", "bandit", "htmlhint",
                    "stylelint", "gitleaks", "trufflehog", "trivy",
                ):
                    (raw / f"{name}.status.json").write_text(
                        json.dumps({
                            "status": "disabled",
                            "reason": "Test fixture",
                        }),
                        encoding="utf-8",
                    )

            with patch(
                "quality.Local_Scans.local_pipeline."
                "run_additional",
                side_effect=fake_scanners,
            ):
                document = integrate(
                    repo,
                    work,
                    reports,
                    tools,
                    {
                        "CS_STATUS": "passed",
                        "PMD_STATUS": "passed",
                        "SB_STATUS": "passed",
                    },
                )

            self.assertEqual(document["tool_count"], 10)
            self.assertEqual(document["mode"], "advisory")

            self.assertTrue(
                (reports / "raw" / "checkstyle.xml").exists()
            )
            self.assertTrue(
                (reports / "normalized" / "normalized.json").exists()
            )
            self.assertTrue(
                (work / "normalized.json").exists()
            )


if __name__ == "__main__":
    unittest.main()
