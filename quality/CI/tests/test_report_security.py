"""
Regression tests for EPMS Quality Gate report security.

Verifies HTML and Markdown rendering protections against
unsafe URLs and injected environment metadata.
"""

import unittest
from copy import deepcopy
from quality.CI.gate.report.report_md import (
    PR_COMMENT_MARKER,
    build_markdown_report,
)
from quality.CI.gate.report.render_report.report_builder import (
    build_html_report,
)
from quality.CI.gate.report.render_report.report_blocks import (
    build_run_metadata,
)


class ReportSecurityTests(unittest.TestCase):
    """Verify security-sensitive report rendering behavior."""


    def setUp(self):
        self.evaluated = {
            "overall_block": False,
            "blocking_results": [],
            "non_blocking_results": [],
        }

        self.env = {
            "event_name": "pull_request",
            "run_number": "123",
            "run_id": "456789",
            "sha": "a" * 40,
            "server_url": "https://github.com",
            "repository": (
                "jas812000/enterprise-project-management-system"
            ),
            "scan_root": "backend",
            "actor": "developer",
            "head_ref": "feature/test",
            "base_ref": "main",
            "pr_number": "42",
        }


    def test_valid_github_urls(self):
        """Valid GitHub metadata produces expected URLs."""
        sha, run_url, commit_url = build_run_metadata(self.env)

        self.assertEqual(sha, "aaaaaaa")
        self.assertTrue(run_url.endswith("/actions/runs/456789"))
        self.assertTrue(commit_url.endswith("/commit/" + "a" * 40))


    def test_unsafe_url_scheme_rejected(self):
        """Unsafe server URL schemes must not produce links."""
        env = dict(self.env, server_url="javascript:alert(1)")

        _, run_url, commit_url = build_run_metadata(env)

        self.assertEqual(run_url, "")
        self.assertEqual(commit_url, "")


    def test_invalid_repository_rejected(self):
        """Malformed repository identifiers must be rejected."""
        env = dict(
            self.env,
            repository='owner/repo" onclick="alert(1)',
        )

        _, run_url, commit_url = build_run_metadata(env)

        self.assertEqual(run_url, "")
        self.assertEqual(commit_url, "")


    def test_invalid_run_and_commit_identifiers(self):
        """Invalid run IDs and commit SHAs must not produce links."""
        env = dict(
            self.env,
            run_id="123?x=1",
            sha="invalid-sha",
        )

        _, run_url, commit_url = build_run_metadata(env)

        self.assertEqual(run_url, "")
        self.assertEqual(commit_url, "")


    def test_html_escapes_environment_metadata(self):
        """HTML report must escape untrusted metadata."""
        env = dict(
            self.env,
            event_name='<img src=x onerror=alert(1)>',
            scan_root='<script>alert("test")</script>',
            run_number='123" onclick="alert(1)',
        )

        report = build_html_report(self.evaluated, env)

        self.assertNotIn("<img src=x", report)
        self.assertNotIn('<script>alert("test")</script>', report)
        self.assertIn("&lt;img", report)
        self.assertIn("&lt;script&gt;", report)
        self.assertIn("&quot;", report)


    def test_html_invalid_urls_use_plain_text(self):
        """Invalid URLs must produce non-clickable metadata."""
        env = dict(
            self.env,
            server_url="javascript:alert(1)",
        )

        report = build_html_report(self.evaluated, env)

        self.assertNotIn('href="javascript:', report)
        self.assertNotIn('href=""', report)
        self.assertIn("#123", report)
        self.assertIn("<code>aaaaaaa</code>", report)


    def test_html_report_structure_preserved(self):
        """Security changes must preserve main report sections."""
        report = build_html_report(self.evaluated, self.env)

        self.assertIn("Tool Results Summary", report)
        self.assertIn("Enforced Tools", report)
        self.assertIn("Advisory Tools", report)


    def test_markdown_valid_links_and_marker(self):
        """Markdown report must preserve valid links and marker."""
        report = build_markdown_report(self.evaluated, self.env)

        self.assertIn("[#123](https://github.com/", report)
        self.assertIn("/actions/runs/456789)", report)
        self.assertIn("/commit/" + "a" * 40, report)
        self.assertIn(PR_COMMENT_MARKER, report)


    def test_markdown_rejects_unsafe_urls(self):
        """Markdown must not render unsafe URL schemes."""
        env = dict(
            self.env,
            server_url="javascript:alert(1)",
        )

        report = build_markdown_report(self.evaluated, env)

        self.assertNotIn("javascript:alert(1)", report)
        self.assertIn("| **Pipeline Run** | #123 |", report)
        self.assertIn("| **Commit SHA** | `aaaaaaa` |", report)


    def test_markdown_escapes_table_content(self):
        """Markdown metadata must not create additional table cells."""
        env = dict(
            self.env,
            run_number="123|injected",
            actor="developer|admin",
            head_ref="feature/test\n| Injected | row |",
        )

        report = build_markdown_report(self.evaluated, env)

        self.assertIn("#123&#124;injected", report)
        self.assertIn("@developer&#124;admin", report)
        self.assertIn(
            "feature/test &#124; Injected &#124; row &#124;",
            report,
        )


    def test_markdown_escapes_html_markup(self):
        """HTML markup in Markdown metadata must be escaped."""
        env = dict(
            self.env,
            scan_root="<script>alert(1)</script>",
        )

        report = build_markdown_report(self.evaluated, env)

        self.assertNotIn("<script>alert(1)</script>", report)
        self.assertIn("&lt;script&gt;", report)


    def test_markdown_report_structure_preserved(self):
        """Markdown security changes must preserve report sections."""
        report = build_markdown_report(self.evaluated, self.env)

        self.assertIn("### Report Header", report)
        self.assertIn("### Pull Request", report)
        self.assertIn("### Commit Details", report)
        self.assertIn("### Tool Results Summary", report)


    def test_published_html_excludes_sensitive_findings(self):
        """Published HTML must retain counts without exposing findings."""
        synthetic_secret = "_".join(
            ("EPMS", "SYNTHETIC", "SECRET", "987654321")
        )

        evaluated = deepcopy(self.evaluated)
        evaluated["overall_block"] = True
        evaluated["blocking_results"] = [
            {
                "tool": "gitleaks",
                "blocking": True,
                "policy_violation": True,
                "reason": "",
                "normalized": {
                    "executed": True,
                    "runtime_error": False,
                    "violation_count": 1,
                    "severity_counts": {"critical": 1},
                    "findings": [
                        {
                            "severity": "critical",
                            "file": f"config/{synthetic_secret}.txt",
                            "line": 12,
                            "rule": "generic-api-key",
                            "message": synthetic_secret,
                            "rule_url": "",
                        }
                    ],
                },
            }
        ]

        # Simulate the publication boundary in report.py.
        published = deepcopy(evaluated)
        for group_name in ("blocking_results", "non_blocking_results"):
            for result in published.get(group_name, []):
                result["normalized"]["findings"] = []

        html = build_html_report(published, self.env)
        markdown = build_markdown_report(published, self.env)

        self.assertNotIn(synthetic_secret, html)
        self.assertNotIn(synthetic_secret, markdown)
        self.assertIn("Individual finding details are excluded", html)
        self.assertIn("1 finding(s)", markdown)
        self.assertEqual(
            evaluated["blocking_results"][0]["normalized"]["violation_count"],
            1,
        )
        self.assertEqual(
            len(evaluated["blocking_results"][0]["normalized"]["findings"]),
            1,
        )


if __name__ == "__main__":
    unittest.main()
