"""Regression tests for EPMS diagnostic clarity and source-location safety."""
import os
import unittest
from copy import deepcopy
from unittest.mock import patch

from quality.CI.gate.parsers.path_safety import safe_finding_path
from quality.CI.gate.report.report_diagnostics import (
    _safe_policy_reason,
    prepare_diagnostic_document,
)
from quality.CI.gate.report.render_report.report_rows import finding_row


class DiagnosticReportingImprovementsTests(unittest.TestCase):
    """Verify actionable messages and safe, clickable source paths."""

    def _finding(self, tool, rule, path, message):
        return {
            "blocking_results": [{
                "tool": tool, "blocking": True, "policy_violation": True,
                "reason": "", "normalized": {
                    "executed": True, "runtime_error": False,
                    "violation_count": 1, "severity_counts": {"low": 1},
                    "findings": [{"severity": "low", "file": path,
                                  "line": 52, "rule": rule,
                                  "message": message}],
                },
            }],
            "non_blocking_results": [],
        }

    def test_long_python_test_filenames_remain_visible(self):
        for path in (
            "quality/CI/tests/test_normalization_integration.py",
            "quality/CI/tests/test_secret_scanner_security.py",
            "quality/CI/tests/test_report_diagnostics.py",
        ):
            with self.subTest(path=path):
                self.assertEqual(safe_finding_path(path), path)

    def test_absolute_workspace_dot_segment_normalizes(self):
        workspace = "/home/runner/work/epms/epms"
        path = workspace + "/./quality/CI/gate/parsers/checkstyle.py"
        with patch.dict(os.environ, {"GITHUB_WORKSPACE": workspace}):
            self.assertEqual(safe_finding_path(path),
                             "quality/CI/gate/parsers/checkstyle.py")

    def test_unsafe_paths_still_withheld(self):
        for path in ("../outside.py", "src/../outside.py",
                     "src/api_token.txt", "src/EPMS_SYNTHETIC_SECRET_123456789.py"):
            with self.subTest(path=path):
                self.assertEqual(safe_finding_path(path), "unknown")

    def test_pylint_message_is_specific_and_source_is_clickable(self):
        path = "quality/CI/tests/test_normalization_integration.py"
        doc = self._finding("pylint", "import-error", path,
                            "Unable to import 'quality.CI.gate'")
        finding = prepare_diagnostic_document(doc)["blocking_results"][0]["normalized"]["findings"][0]
        self.assertEqual(finding["file"], path)
        self.assertIn("PYTHONPATH", finding["message"])
        html = finding_row(finding, "https://github.com/owner/repo/commit/" + "a" * 40)
        self.assertIn("/blob/" + "a" * 40 + "/" + path + "#L52", html)

    def test_bandit_guidance_does_not_publish_raw_secret(self):
        secret = "_".join(("SYNTHETIC", "TEST", "CREDENTIAL", "123456789"))
        doc = self._finding("bandit", "B105", "quality/CI/tests/test_report_security.py",
                            "Possible hardcoded password: " + secret)
        original = deepcopy(doc)
        finding = prepare_diagnostic_document(doc)["blocking_results"][0]["normalized"]["findings"][0]
        self.assertIn("hardcoded credential", finding["message"])
        self.assertNotIn(secret, str(finding))
        self.assertEqual(doc, original)

    def test_quality_gate_reasons_are_preserved(self):
        for reason in ("finding_present", "severity_high_and_above"):
            with self.subTest(reason=reason):
                self.assertEqual(_safe_policy_reason(reason), reason)

    def test_b405_guidance_explains_xml_risk(self):
        doc = self._finding("bandit", "B405", "quality/CI/gate/parsers/pmd.py",
                            "Untrusted XML")
        finding = prepare_diagnostic_document(doc)["blocking_results"][0]["normalized"]["findings"][0]
        self.assertIn("defusedxml", finding["message"])


if __name__ == "__main__":
    unittest.main()
