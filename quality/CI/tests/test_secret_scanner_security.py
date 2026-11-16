
"""
Regression tests for EPMS secret scanner security.

Verify that Gitleaks and TruffleHog exclude sensitive
scanner metadata and that file paths are validated.
"""

import json
import tempfile
import unittest
from pathlib import Path

from quality.CI.gate.parsers.gitleaks import _build_gitleaks_finding
from quality.CI.gate.parsers.trufflehog import (
    _build_finding,
    _is_known_safe_jdbc_url,
    parse_trufflehog,
)
from quality.CI.gate.parsers.path_safety import safe_finding_path


SECRET = "EPMS_SYNTHETIC_SECRET_DO_NOT_USE_123456"


class SecretScannerSecurityTests(unittest.TestCase):

    def test_gitleaks_excludes_sensitive_metadata(self):
        record = {
            "Secret": SECRET,
            "Match": f"token={SECRET}",
            "RuleID": f"rule-{SECRET}",
            "Description": f"Credential: {SECRET}",
            "File": "backend/application.properties",
            "StartLine": 12,
            "Tags": ["high", SECRET],
        }

        finding, severity = _build_gitleaks_finding(record)

        self.assertNotIn(SECRET, json.dumps(finding))
        self.assertEqual(finding["rule"], "gitleaks")
        self.assertEqual(finding["severity"], "high")
        self.assertEqual(finding["native_severity"], "high")
        self.assertEqual(severity, "high")

    def test_gitleaks_recognized_rule(self):
        record = {
            "RuleID": "generic-api-key",
            "Description": f"Untrusted: {SECRET}",
            "File": "backend/application.properties",
        }

        finding, _ = _build_gitleaks_finding(record)

        self.assertEqual(finding["rule"], "generic-api-key")
        self.assertIn("Generic API key detected", finding["message"])
        self.assertNotIn(SECRET, json.dumps(finding))

    def test_trufflehog_excludes_sensitive_metadata(self):
        record = {
            "DetectorName": SECRET,
            "Verified": True,
            "Raw": SECRET,
            "ExtraData": {
                "rotation_guide": f"https://example.com/?token={SECRET}"
            },
        }

        finding, severity = _build_finding(record)

        self.assertNotIn(SECRET, json.dumps(finding))
        self.assertEqual(finding["rule"], "TruffleHog")
        self.assertEqual(finding["rule_url"], "")
        self.assertEqual(severity, "critical")

    def test_trufflehog_recognized_detector(self):
        record = {
            "DetectorName": "Github",
            "Verified": False,
        }

        finding, severity = _build_finding(record)

        self.assertEqual(finding["rule"], "Github")
        self.assertEqual(finding["severity"], "high")
        self.assertEqual(severity, "high")

    def test_valid_repository_paths(self):
        paths = (
            "backend/src/main/App.java",
            "frontend/src/App.tsx",
            "quality/CI/gate/normalize.py",
        )

        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(safe_finding_path(path), path)

    def test_invalid_repository_paths(self):
        paths = (
            "../secrets.txt",
            "/etc/passwd",
            "backend/../../secrets.txt",
            "backend\\secrets.txt",
            "backend/\x00secret.txt",
            "",
            None,
            123,
        )

        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(safe_finding_path(path), "unknown")

    def test_gitleaks_rejects_invalid_file_path(self):
        record = {
            "RuleID": "generic-api-key",
            "File": "../secrets.txt",
        }

        finding, _ = _build_gitleaks_finding(record)

        self.assertEqual(finding["file"], "unknown")

    def test_trufflehog_rejects_invalid_file_path(self):
        record = {
            "DetectorName": "Github",
            "Verified": False,
            "SourceMetadata": {
                "Data": {
                    "Filesystem": {
                        "file": "/repo/../../secrets.txt",
                        "line": 12,
                    }
                }
            },
        }

        finding, _ = _build_finding(record)

        self.assertEqual(finding["file"], "unknown")


    def test_known_safe_jdbc_url_is_excluded(self):
        record = {
            "DetectorName": "JDBC",
            "Verified": False,
            "Raw": "jdbc:postgresql://localhost:5432/epms",
        }

        path = "backend/src/main/resources/application.properties"

        self.assertTrue(_is_known_safe_jdbc_url(record, path))


    def test_other_jdbc_urls_are_not_excluded(self):
        path = "backend/src/main/resources/application.properties"

        records = (
            {
                "DetectorName": "JDBC",
                "Verified": False,
                "Raw": "jdbc:postgresql://db.example.com:5432/epms",
            },
            {
                "DetectorName": "JDBC",
                "Verified": True,
                "Raw": "jdbc:postgresql://localhost:5432/epms",
            },
            {
                "DetectorName": "JDBC",
                "Verified": False,
                "Raw": "jdbc:postgresql://localhost:5432/epms?password=secret",
            },
        )

        for record in records:
            with self.subTest(record=record):
                self.assertFalse(_is_known_safe_jdbc_url(record, path))


    def test_trufflehog_parser_filters_only_known_safe_jdbc_url(self):
        """
        Verify the complete parser excludes only the approved
        JDBC false positive and preserves other findings.
        """
        config_path = "/repo/backend/src/main/resources/application.properties"

        def make_record(raw_value, line_number):
            return {
                "DetectorName": "JDBC",
                "Verified": False,
                "Raw": raw_value,
                "SourceMetadata": {
                    "Data": {
                        "Filesystem": {
                            "file": config_path,
                            "line": line_number,
                        }
                    }
                },
            }

        records = [
            make_record(
                "jdbc:postgresql://localhost:5432/epms",
                3,
            ),
            make_record(
                "jdbc:postgresql://db.example.com:5432/epms",
                4,
            ),
            make_record(
                "jdbc:postgresql://localhost:5432/epms?password=example",
                5,
            ),
        ]

        with tempfile.TemporaryDirectory() as temporary_directory:
            raw_dir = Path(temporary_directory)
            artifact = raw_dir / "trufflehog.jsonl"

            artifact.write_text(
                "\n".join(json.dumps(record) for record in records) + "\n",
                encoding="utf-8",
            )

            result = parse_trufflehog(raw_dir)

        self.assertTrue(result["executed"])
        self.assertTrue(result["artifact_present"])
        self.assertFalse(result["runtime_error"])

        # The approved localhost URL is excluded.
        self.assertEqual(result["violation_count"], 2)

        # Both remaining unverified findings are high severity.
        self.assertEqual(result["severity_counts"]["high"], 2)
        self.assertEqual(result["max_severity"], "high")

        # Ensure only the unsafe or unapproved URLs remain.
        finding_lines = {
            finding["line"] for finding in result["findings"]
        }

        self.assertEqual(finding_lines, {4, 5})

        # Raw connection strings must not appear in normalized output.
        normalized_output = json.dumps(result)

        self.assertNotIn(
            "jdbc:postgresql://db.example.com:5432/epms",
            normalized_output,
        )
        self.assertNotIn(
            "password=example",
            normalized_output,
        )


if __name__ == "__main__":
    unittest.main()
