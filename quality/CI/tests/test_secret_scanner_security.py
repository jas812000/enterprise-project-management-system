
"""
Regression tests for EPMS secret scanner security.

Verify that Gitleaks and TruffleHog exclude sensitive
scanner metadata and that file paths are validated.
"""

import json
import unittest

from quality.CI.gate.parsers.gitleaks import _build_gitleaks_finding
from quality.CI.gate.parsers.trufflehog import _build_finding
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


if __name__ == "__main__":
    unittest.main()
