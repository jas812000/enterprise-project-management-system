"""Regression coverage for shared quality scanner utilities."""

import json
import tempfile
import unittest
from pathlib import Path

from quality.shared.artifact_io import (
    prepare_artifact,
    read_json_artifact,
    record_findings,
)
from quality.shared.scanner_status import scanner_is_disabled
from quality.shared.severity import aggregate_severity_counts


class SharedScannerUtilitiesTests(unittest.TestCase):
    """Verify shared helpers preserve scanner contracts."""

    def test_status_probe(self):
        """Only valid disabled status objects bypass parser execution."""
        with tempfile.TemporaryDirectory() as directory:
            status = Path(directory) / "scanner.status.json"
            self.assertFalse(scanner_is_disabled(status))
            for payload, expected in [
                ('{"status": "disabled"}', True),
                ('{"status": "completed"}', False),
                ('["disabled"]', False),
                ('{invalid json', False),
            ]:
                status.write_text(payload, encoding="utf-8")
                self.assertEqual(scanner_is_disabled(status), expected)

    def test_severity_aggregation(self):
        """Count recognized severities and ignore unsupported labels."""
        levels = ["critical", "high", "medium", "low", "info"]
        results = [
            {"severity_counts": {"high": 2, "low": 1, "unknown": 5}},
            {"severity_counts": {"high": 1, "info": 3}},
        ]
        self.assertEqual(
            aggregate_severity_counts(results, levels),
            {"critical": 0, "high": 3, "medium": 0, "low": 1, "info": 3},
        )

    def test_artifact_preparation(self):
        """A missing artifact fails closed; an existing one marks execution."""
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "scanner.json"
            missing = {"artifact_present": True, "runtime_error": False}
            self.assertFalse(prepare_artifact(missing, artifact))
            self.assertFalse(missing["artifact_present"])
            self.assertTrue(missing["runtime_error"])
            artifact.write_text("[]", encoding="utf-8")
            present = {"artifact_present": False, "executed": False}
            self.assertTrue(prepare_artifact(present, artifact))
            self.assertTrue(present["artifact_present"])
            self.assertTrue(present["executed"])

    def test_record_findings(self):
        """Preserve scanner-specific findings and maximum severity."""
        result = {}
        findings = [{"rule": "example"}]
        record_findings(result, findings, "medium")
        self.assertIs(result["findings"], findings)
        self.assertEqual(result["violation_count"], 1)
        self.assertEqual(result["max_severity"], "medium")

    def test_json_reader(self):
        """Preserve parsed JSON structures for scanner-specific handling."""
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "scanner.json"
            artifact.write_text(json.dumps([{"type": "warning"}]), encoding="utf-8")
            self.assertEqual(read_json_artifact(artifact), [{"type": "warning"}])


if __name__ == "__main__":
    unittest.main()
