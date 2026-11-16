
"""
Regression tests for EPMS diagnostic report preparation.

Verify that detailed reports preserve actionable findings,
redact secret-scanner values, and do not modify the original data.
"""

import unittest
from copy import deepcopy

from quality.CI.gate.report.report_diagnostics import (
    REDACTION_MARKER,
    prepare_diagnostic_document,
)
from quality.CI.gate.report.render_report.report_builder import (
    build_html_report,
)
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from quality.CI.gate.report import report as report_module


class DiagnosticReportTests(unittest.TestCase):

    def setUp(self):
        self.secret = "_".join(
            ("EPMS", "SYNTHETIC", "SECRET", "123456789")
        )

        self.evaluated = {
            "overall_block": True,
            "blocking_results": [
                {
                    "tool": "gitleaks",
                    "blocking": True,
                    "policy_violation": True,
                    "reason": "",
                    "normalized": {
                        "executed": True,
                        "runtime_error": False,
                        "violation_count": 1,
                        "severity_counts": {"high": 1},
                        "findings": [
                            {
                                "severity": "high",
                                "file": "backend/application.properties",
                                "line": 18,
                                "rule": "generic-api-key",
                                "message": "Generic API key detected",
                                "rule_url": "",
                            }
                        ],
                    },
                },
                {
                    "tool": "pylint",
                    "blocking": True,
                    "policy_violation": True,
                    "reason": "",
                    "normalized": {
                        "executed": True,
                        "runtime_error": False,
                        "violation_count": 1,
                        "severity_counts": {"medium": 1},
                        "findings": [
                            {
                                "severity": "medium",
                                "file": "quality/CI/gate/gate.py",
                                "line": 42,
                                "rule": "unused-import",
                                "message": "Unused import detected",
                                "rule_url": "",
                            }
                        ],
                    },
                },
            ],
            "non_blocking_results": [],
        }

        self.env = {
            "event_name": "push",
            "run_number": "123",
            "run_id": "456789",
            "sha": "a" * 40,
            "server_url": "https://github.com",
            "repository": (
                "jas812000/enterprise-project-management-system"
            ),
            "scan_root": ".",
        }


    def test_secret_scanner_redaction_marker(self):
        diagnostic = prepare_diagnostic_document(self.evaluated)

        finding = diagnostic["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        self.assertIn(REDACTION_MARKER, finding["message"])
        self.assertEqual(finding["file"], "backend/application.properties")
        self.assertEqual(finding["line"], 18)


    def test_non_secret_finding_preserved(self):
        diagnostic = prepare_diagnostic_document(self.evaluated)

        finding = diagnostic["blocking_results"][1][
            "normalized"
        ]["findings"][0]

        self.assertEqual(finding["rule"], "unused-import")
        self.assertEqual(finding["line"], 42)
        self.assertEqual(
            finding["message"],
            "An imported module or name is not used. Remove the unused import or use it where intended.",
        )


    def test_original_document_not_modified(self):
        original = deepcopy(self.evaluated)

        prepare_diagnostic_document(self.evaluated)

        self.assertEqual(self.evaluated, original)


    def test_diagnostic_html_contains_findings(self):
        diagnostic = prepare_diagnostic_document(self.evaluated)

        html = build_html_report(diagnostic, self.env)

        self.assertIn("backend/application.properties", html)
        self.assertIn("quality/CI/gate/gate.py", html)
        self.assertIn("unused-import", html)
        self.assertIn(REDACTION_MARKER, html)


    def test_secret_not_in_diagnostic_html(self):
        diagnostic = prepare_diagnostic_document(self.evaluated)

        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)


    def test_redacted_finding_remains_locatable(self):
        """
        Redacting a credential must preserve the information
        needed to locate the finding in the source code.
        """
        diagnostic = prepare_diagnostic_document(self.evaluated)

        finding = diagnostic["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        self.assertEqual(
            finding["file"],
            "backend/application.properties",
        )
        self.assertEqual(finding["line"], 18)
        self.assertEqual(finding["rule"], "generic-api-key")
        self.assertEqual(finding["severity"], "high")

        self.assertIn(REDACTION_MARKER, finding["message"])


    def test_secret_in_finding_message_is_not_published(self):
        """
        A credential accidentally present in normalized findings
        must never appear in the diagnostic HTML report.
        """
        evaluated = deepcopy(self.evaluated)

        finding = evaluated["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        finding["message"] = (
            f"Generic API key detected: {self.secret}"
        )

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn(REDACTION_MARKER, html)
        self.assertIn("backend/application.properties", html)
        self.assertIn("18", html)


    def test_secret_in_file_path_is_not_published(self):
        """
        A credential accidentally included in a file path
        must not appear in the diagnostic HTML report.
        """
        evaluated = deepcopy(self.evaluated)

        finding = evaluated["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        finding["file"] = (
            f"backend/config/{self.secret}.properties"
        )

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn(REDACTION_MARKER, html)
        self.assertIn("18", html)


    def test_secret_in_rule_identifier_is_not_published(self):
        """
        A credential accidentally included in a scanner rule
        identifier must not appear in diagnostic HTML.
        """
        evaluated = deepcopy(self.evaluated)

        finding = evaluated["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        finding["rule"] = self.secret

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn(REDACTION_MARKER, html)
        self.assertIn("backend/application.properties", html)
        self.assertIn("18", html)


    def test_secret_in_scanner_metadata_is_not_published(self):
        """
        Credentials accidentally included in scanner metadata
        must not appear in diagnostic HTML.
        """
        evaluated = deepcopy(self.evaluated)

        result = evaluated["blocking_results"][0]

        result["reason"] = (
            f"scanner_runtime_error: {self.secret}"
        )

        result["normalized"]["runtime_error"] = True
        result["normalized"]["metadata"] = {
            "error": f"Scanner failed while processing {self.secret}"
        }

        # Runtime-error rendering uses metadata when no findings exist.
        result["normalized"]["findings"] = []

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)


    def test_invalid_severity_and_line_are_sanitized(self):
        """
        Arbitrary severity text and invalid line numbers
        must not enter published diagnostic findings.
        """
        evaluated = deepcopy(self.evaluated)

        finding = evaluated["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        finding["severity"] = self.secret
        finding["line"] = self.secret

        diagnostic = prepare_diagnostic_document(evaluated)

        safe_finding = diagnostic["blocking_results"][0][
            "normalized"
        ]["findings"][0]

        self.assertEqual(safe_finding["severity"], "unknown")
        self.assertEqual(safe_finding["line"], 0)

        html = build_html_report(diagnostic, self.env)
        self.assertNotIn(self.secret, html)


    def test_source_links_validate_file_locations(self):
        """
        Only valid repository-relative paths may become
        clickable GitHub source links.
        """
        from quality.CI.gate.report.render_report.report_rows import (
            finding_row,
        )

        commit_url = (
            "https://github.com/jas812000/"
            "enterprise-project-management-system/"
            "commit/" + "a" * 40
        )

        finding = {
            "severity": "medium",
            "file": "quality/CI/gate/gate.py",
            "line": 42,
            "rule": "unused-import",
            "message": "Unused import detected",
        }

        valid_html = finding_row(finding, commit_url)

        self.assertIn(
            "/blob/" + "a" * 40 + "/quality/CI/gate/gate.py#L42",
            valid_html,
        )

        invalid_paths = (
            "[path withheld]",
            "../outside.py",
            "/etc/passwd",
            "folder\\outside.py",
        )

        for path in invalid_paths:
            with self.subTest(path=path):
                finding["file"] = path
                html = finding_row(finding, commit_url)

                self.assertNotIn("/blob/", html)


    def test_source_links_reject_invalid_commit_urls(self):
        """Reject missing, untrusted, or malformed commit URLs."""
        from quality.CI.gate.report.render_report.report_rows import (
            finding_row,
        )

        finding = {
            "severity": "medium",
            "file": "quality/CI/gate/gate.py",
            "line": 42,
            "rule": "unused-import",
            "message": "Unused import detected",
        }

        sha = "a" * 40

        cases = {
            "Valid GitHub URL": (
                f"https://github.com/jas812000/"
                f"enterprise-project-management-system/commit/{sha}",
                True,
            ),
            "Missing URL": ("", False),
            "Untrusted host": (
                f"https://example.com/other/repository/commit/{sha}",
                False,
            ),
            "Malformed SHA": (
                "https://github.com/jas812000/"
                "enterprise-project-management-system/commit/not-a-sha",
                False,
            ),
        }

        for name, (commit_url, expected_link) in cases.items():
            with self.subTest(name=name):
                html = finding_row(finding, commit_url)

                self.assertEqual(
                    "/blob/" in html,
                    expected_link,
                )


    def test_secret_scanner_metadata_always_sanitized(self):
        """Remove raw secret-scanner metadata without a runtime error."""
        from quality.CI.gate.report.report_diagnostics import (
            prepare_diagnostic_document,
        )

        secret = "_".join(("SENSITIVE", "TEST", "CREDENTIAL", "12345"))

        evaluated_doc = {
            "blocking_results": [
                {
                    "tool": "gitleaks",
                    "reason": "",
                    "normalized": {
                        "runtime_error": False,
                        "metadata": {
                            "scanner_output": secret,
                            "details": {
                                "credential": secret,
                            },
                        },
                        "findings": [],
                    },
                },
                {
                    "tool": "trufflehog",
                    "reason": "",
                    "normalized": {
                        "runtime_error": False,
                        "metadata": {
                            "raw_output": secret,
                        },
                        "findings": [],
                    },
                },
            ],
            "non_blocking_results": [],
        }

        diagnostic_doc = prepare_diagnostic_document(evaluated_doc)

        for result in diagnostic_doc["blocking_results"]:
            with self.subTest(tool=result["tool"]):
                self.assertEqual(
                    result["normalized"]["metadata"],
                    {},
                )

        # The source document must remain unchanged.
        self.assertEqual(
            evaluated_doc["blocking_results"][0]
            ["normalized"]["metadata"]["scanner_output"],
            secret,
        )


    def test_secret_scanner_path_validation(self):
        """Preserve safe paths and withhold suspicious paths."""
        from quality.CI.gate.report.report_diagnostics import (
            _safe_secret_finding_path,
        )

        cases = {
            "src/config.py": "src/config.py",
            "src/./config.py": "[path withheld]",
            "src/../config.py": "[path withheld]",
            "src\\config.py": "[path withheld]",
            "/etc/config.py": "[path withheld]",
            "src/api_token.txt": "[path withheld]",
            "src//config.py": "[path withheld]",
            "src/config.py\ncredential": "[path withheld]",
            "": "[path withheld]",
        }

        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(
                    _safe_secret_finding_path(path),
                    expected,
                )


    def test_diagnostic_html_links_to_exact_source_lines(self):
        """Diagnostic HTML links findings to the analyzed commit."""
        diagnostic = prepare_diagnostic_document(self.evaluated)

        html = build_html_report(
            diagnostic,
            self.env,
            link_findings=True,
        )

        sha = self.env["sha"]
        repository = self.env["repository"]

        source_base = (
            f"https://github.com/{repository}/blob/{sha}"
        )

        expected_links = (
            f"{source_base}/backend/application.properties#L18",
            f"{source_base}/quality/CI/gate/gate.py#L42",
        )

        for expected_link in expected_links:
            with self.subTest(url=expected_link):
                self.assertIn(
                    f'href="{expected_link}"',
                    html,
                )

        self.assertIn(REDACTION_MARKER, html)
        self.assertNotIn(self.secret, html)


    def test_generate_report_creates_separate_outputs(self):
        """Generate summary and diagnostic reports in isolation."""
        with TemporaryDirectory() as temp_dir:
            analysis_dir = Path(temp_dir)
            evaluated_file = analysis_dir / "evaluated.json"
            markdown_file = analysis_dir / "report.md"
            summary_file = analysis_dir / "report.html"
            diagnostic_file = analysis_dir / "diagnostic-report.html"

            evaluated_file.write_text(
                json.dumps(self.evaluated),
                encoding="utf-8",
            )

            with (
                patch.object(report_module, "EVALUATED_FILE", evaluated_file),
                patch.object(report_module, "REPORT_MD_FILE", markdown_file),
                patch.object(report_module, "REPORT_HTML_FILE", summary_file),
                patch.object(
                    report_module,
                    "DIAGNOSTIC_HTML_FILE",
                    diagnostic_file,
                ),
                patch.object(report_module, "_get_env", return_value=self.env),
            ):
                report_module.generate_report()

            for output_file in (
                markdown_file,
                summary_file,
                diagnostic_file,
            ):
                with self.subTest(file=output_file.name):
                    self.assertTrue(output_file.is_file())

            markdown = markdown_file.read_text(encoding="utf-8")
            summary = summary_file.read_text(encoding="utf-8")
            diagnostic = diagnostic_file.read_text(encoding="utf-8")

            self.assertIn("Tool Results Summary", markdown)
            self.assertNotIn("unused-import", summary)
            self.assertIn("unused-import", diagnostic)
            self.assertIn(REDACTION_MARKER, diagnostic)

            expected_url = (
                f"https://github.com/{self.env['repository']}"
                f"/blob/{self.env['sha']}"
                "/quality/CI/gate/gate.py#L42"
            )
            self.assertIn(f'href="{expected_url}"', diagnostic)


    def test_secret_scanner_severity_counts_are_sanitized(self):
        """Secret-scanner severity counts must contain only safe integers."""
        evaluated = deepcopy(self.evaluated)

        evaluated["blocking_results"][0]["normalized"][
            "severity_counts"
        ] = {
            "critical": self.secret,
            "high": 1,
            "medium": -3,
            "low": True,
        }

        diagnostic = prepare_diagnostic_document(evaluated)

        counts = diagnostic["blocking_results"][0][
            "normalized"
        ]["severity_counts"]

        self.assertEqual(
            counts,
            {"high": 1},
        )

        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn("HIGH: 1", html)


    def test_secret_scanner_violation_count_is_sanitized(self):
        """Secret-scanner finding counts must be safe integers."""
        evaluated = deepcopy(self.evaluated)

        evaluated["blocking_results"][0]["normalized"][
            "violation_count"
        ] = self.secret

        diagnostic = prepare_diagnostic_document(evaluated)

        count = diagnostic["blocking_results"][0][
            "normalized"
        ]["violation_count"]

        self.assertEqual(count, 0)

        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)


    def test_non_secret_scanner_message_is_not_published(self):
        """Untrusted scanner messages must not expose credentials."""
        evaluated = deepcopy(self.evaluated)

        evaluated["blocking_results"][1]["normalized"][
            "findings"
        ][0]["message"] = (
            f"Possible vulnerability involving {self.secret}"
        )

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn("unused-import", html)
        self.assertIn("quality/CI/gate/gate.py", html)


    def test_non_secret_scanner_runtime_error_is_sanitized(self):
        """Scanner runtime errors must not expose sensitive text."""
        evaluated = deepcopy(self.evaluated)
        result = evaluated["blocking_results"][1]

        result["reason"] = self.secret
        result["normalized"]["runtime_error"] = True
        result["normalized"]["findings"] = []
        result["normalized"]["metadata"] = {
            "error": f"Scanner failed while processing {self.secret}"
        }

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertEqual(
            diagnostic["blocking_results"][1]["reason"],
            "scanner_runtime_error",
        )
        self.assertIn(
            "Review the GitHub Actions execution logs.",
            html,
        )


    def test_non_secret_scanner_finding_fields_are_sanitized(self):
        """Untrusted paths and rule identifiers must not expose secrets."""
        evaluated = deepcopy(self.evaluated)
        result = evaluated["blocking_results"][1]

        result["normalized"]["findings"] = [
            {
                "severity": "medium",
                "file": f"backend/{self.secret}.py",
                "line": 42,
                "rule": f"unsafe-{self.secret}",
                "message": "Unused import detected",
                "rule_url": "",
            }
        ]

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertIn(
            "An imported module or name is not used",
            html,
        )


    def test_analysis_rule_validator_preserves_valid_rules(self):
        """Preserve conventional non-secret scanner rule identifiers."""
        from quality.CI.gate.report.report_diagnostics import (
            _safe_analysis_rule,
        )

        valid_rules = (
            "unused-import",
            "B101",
            "C0114",
            "python.lang.security.audit",
            "python/security/example-rule",
        )

        for rule in valid_rules:
            with self.subTest(rule=rule):
                self.assertEqual(_safe_analysis_rule(rule), rule)


    def test_analysis_rule_validator_rejects_suspicious_rules(self):
        """Reject malformed or potentially sensitive rule identifiers."""
        from quality.CI.gate.report.report_diagnostics import (
            _safe_analysis_rule,
        )

        suspicious_rules = (
            f"unsafe-{self.secret}",
            "x" * 200,
            "rule with spaces",
            "../unsafe-rule",
            "",
            None,
        )

        for rule in suspicious_rules:
            with self.subTest(rule=rule):
                self.assertEqual(
                    _safe_analysis_rule(rule),
                    "[unrecognized rule]",
                )


    def test_analysis_rules_preserved_in_diagnostic_html(self):
        """Valid analysis rules remain visible in diagnostic HTML."""
        evaluated = deepcopy(self.evaluated)
        result = evaluated["blocking_results"][1]

        result["normalized"]["findings"] = [
            {
                "severity": "medium",
                "file": "quality/CI/gate/gate.py",
                "line": 12,
                "rule": rule,
                "message": "Scanner-provided message",
                "rule_url": "",
            }
            for rule in (
                "unused-import",
                "B101",
                "python.lang.security.audit",
            )
        ]

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        for rule in (
            "unused-import",
            "B101",
            "python.lang.security.audit",
        ):
            with self.subTest(rule=rule):
                self.assertIn(rule, html)

        self.assertNotIn("Scanner-provided message", html)


    def test_non_secret_scanner_counts_are_sanitized(self):
        """Non-secret scanner counts must not expose arbitrary text."""
        evaluated = deepcopy(self.evaluated)
        result = evaluated["blocking_results"][1]

        result["normalized"]["violation_count"] = self.secret
        result["normalized"]["severity_counts"] = {
            "high": self.secret,
            "medium": -5,
            "low": 3,
        }

        diagnostic = prepare_diagnostic_document(evaluated)
        normalized = diagnostic["blocking_results"][1]["normalized"]

        self.assertEqual(normalized["violation_count"], 0)
        self.assertEqual(normalized["severity_counts"]["low"], 3)
        self.assertNotIn(
            "high",
            normalized["severity_counts"],
        )
        self.assertNotIn(
            "medium",
            normalized["severity_counts"],
        )

        html = build_html_report(diagnostic, self.env)
        self.assertNotIn(self.secret, html)


    def test_semgrep_errors_render_alongside_findings(self):
        evaluated = deepcopy(self.evaluated)
        semgrep = deepcopy(evaluated["blocking_results"][1])
        semgrep["tool"] = "semgrep"
        semgrep["normalized"]["runtime_error"] = True
        semgrep["normalized"]["metadata"] = {
            "scanner_errors": [
                {
                    "type": "ParseError",
                    "file": "backend/src/App.java",
                    "line": 27,
                },
                {
                    "type": "Timeout",
                    "file": "backend/src/Slow.java",
                    "line": 81,
                },
            ]
        }

        evaluated["blocking_results"].append(semgrep)

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertIn("Semgrep Scanner Errors (2)", html)
        self.assertIn("ParseError", html)
        self.assertIn("backend/src/App.java", html)
        self.assertIn("Check syntax near the reported file", html)
        self.assertIn("unused-import", html)


    def test_semgrep_metadata_injection_is_removed(self):
        evaluated = deepcopy(self.evaluated)
        semgrep = deepcopy(evaluated["blocking_results"][1])
        semgrep["tool"] = "semgrep"
        semgrep["normalized"]["runtime_error"] = True
        semgrep["normalized"]["metadata"] = {
            "scanner_errors": [
                {
                    "type": self.secret,
                    "file": f"src/{self.secret}.java",
                    "line": self.secret,
                    "message": f"<script>{self.secret}</script>",
                }
            ]
        }

        evaluated["blocking_results"].append(semgrep)

        diagnostic = prepare_diagnostic_document(evaluated)
        html = build_html_report(diagnostic, self.env)

        self.assertNotIn(self.secret, html)
        self.assertNotIn(f"<script>{self.secret}</script>", html)
        self.assertIn("[path withheld]", html)
        self.assertIn("Semgrep Scanner Errors (1)", html)


    def test_semgrep_parser_does_not_copy_raw_error_messages(self):
        from quality.CI.gate.parsers.semgrep import parse_semgrep

        with TemporaryDirectory() as folder:
            raw = {
                "results": [],
                "errors": [
                    {
                        "type": "ParseError",
                        "path": "backend/src/App.java",
                        "start": {"line": 27},
                        "message": self.secret,
                    }
                ],
            }

            Path(folder, "semgrep.json").write_text(
                json.dumps(raw),
                encoding="utf-8",
            )

            parsed = parse_semgrep(Path(folder))

            self.assertTrue(parsed["runtime_error"])
            self.assertEqual(
                parsed["metadata"]["scanner_errors"][0]["type"],
                "ParseError",
            )
            self.assertNotIn(self.secret, json.dumps(parsed))


if __name__ == "__main__":
    unittest.main()
