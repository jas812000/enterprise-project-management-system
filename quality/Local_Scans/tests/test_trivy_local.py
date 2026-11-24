"""Local Trivy execution and report validation tests."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

from quality.Local_Scans.checks import check_trivy
from quality.CI.gate.parsers.trivy import parse_trivy
from quality.Local_Scans.local_normalize import normalize_local, format_scanner_outcome
from quality.Local_Scans.reporting.render_report.report_sections import build_sections


class TrivyTests(unittest.TestCase):
    """Regression coverage for trivy."""
    def test_invalid_report(self):
        """Verify invalid report."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for data in ({}, {"Results": {}}, {"Results": [{"Vulnerabilities": {}}]}):
                (root / "trivy.json").write_text(json.dumps(data), encoding="utf-8")
                self.assertTrue(parse_trivy(root)["runtime_error"])

    def test_schema_v2_empty_envelope(self):
        """Accept an identifiable completed report with omitted empty results."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = {"SchemaVersion": 2, "ArtifactName": ".",
                       "ArtifactType": "filesystem", "Metadata": {}}
            response = SimpleNamespace(returncode=0, stdout=json.dumps(payload).encode(),
                stderr=b"")
            with patch.object(check_trivy.shutil, "which",
                return_value="trivy"), patch.object(check_trivy.subprocess, "run",
                    return_value=response):
                check_trivy.run(root, root)
            self.assertEqual(json.loads((root / "trivy.status.json").read_text())["status"],
                "completed")
            self.assertEqual(parse_trivy(root)["violation_count"], 0)

    def test_missing_binary(self):
        """Verify missing binary."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(check_trivy.shutil, "which", return_value=None):
                check_trivy.run(root, root)
            status = json.loads((root / "trivy.status.json").read_text())
            self.assertEqual(status["status"], "failed")

    def test_success_with_findings(self):
        """Verify success with findings."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def execute(command, **kwargs):
                self.assertEqual(command[1:4], ["fs", "--scanners", "vuln,misconfig,secret"])
                self.assertEqual(kwargs["cwd"], root.resolve())
                payload = json.dumps({"Results": [{
                    "Target": "pom.xml", "Vulnerabilities": [{
                        "Severity": "HIGH", "VulnerabilityID": "CVE-test"}]}]}).encode()
                return SimpleNamespace(returncode=0, stdout=payload, stderr=b"")
            with patch.object(check_trivy.shutil, "which",
                return_value="trivy"), patch.object(check_trivy.subprocess, "run",
                    side_effect=execute):
                check_trivy.run(root, root)
            self.assertEqual(parse_trivy(root)["violation_count"], 1)
            self.assertEqual(json.loads((root / "trivy.status.json").read_text())["status"],
                "completed")

    def test_failed_exit_removes_artifact(self):
        """Verify failed exit removes artifact."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def execute(*_args, **_kwargs):
                (root / "trivy.json").write_text('{"Results": []}', encoding="utf-8")
                return SimpleNamespace(returncode=2, stdout=b"", stderr=b"")
            with patch.object(check_trivy.shutil, "which",
                return_value="trivy"), patch.object(check_trivy.subprocess, "run",
                    side_effect=execute):
                check_trivy.run(root, root)
            status = json.loads((root / "trivy.status.json").read_text())
            self.assertEqual(status["status"], "failed")
            self.assertIn("code 2", status["reason"])
            self.assertFalse((root / "trivy.json").exists())

    def test_missing_report_after_successful_exit(self):
        """Verify missing report after successful exit."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(check_trivy.shutil, "which",
                return_value="trivy"), patch.object(check_trivy.subprocess, "run",
                    return_value=SimpleNamespace(returncode=0, stdout=b"", stderr=b"")):
                check_trivy.run(root, root)
            status = json.loads((root / "trivy.status.json").read_text())
            self.assertEqual(status["status"], "failed")
            self.assertEqual(status["reason"], "Trivy report missing or invalid")

    def test_additional_findings_redacted(self):
        """Verify additional findings redacted."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def execute(*_args, **_kwargs):
                payload = json.dumps({"Results": [{
                    "Target": "fixture", "Secrets": [{"RuleID": "test", "Severity": "HIGH",
                        "Match": "SENSITIVE_SENTINEL", "Code": "SENSITIVE_SENTINEL"}],
                    "Misconfigurations": [{"ID": "TEST", "Severity": "MEDIUM"}]}]}).encode()
                return SimpleNamespace(returncode=0, stdout=payload, stderr=b"")
            with patch.object(check_trivy.shutil, "which",
                return_value="trivy"), patch.object(check_trivy.subprocess, "run",
                    side_effect=execute):
                check_trivy.run(root, root)
            self.assertNotIn("SENSITIVE_SENTINEL", (root / "trivy.json").read_text())
            result = parse_trivy(root)
            self.assertEqual(result["violation_count"], 2)
            self.assertNotIn("SENSITIVE_SENTINEL", json.dumps(result))

    def test_safe_failure_diagnostics(self):
        """Verify safe failure diagnostics."""
        cases = [(b'error getting credentials executable file not found SECRET',
            'credential helper'),
                 (b'failed to download vulnerability DB SECRET', 'database download')]
        for diagnostic, expected in cases:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                response = SimpleNamespace(returncode=1, stdout=b'SECRET', stderr=diagnostic)
                with patch.object(check_trivy.shutil, 'which',
                    return_value='trivy'), patch.object(check_trivy.subprocess, 'run',
                        return_value=response):
                    check_trivy.run(root, root)
                status = (root / 'trivy.status.json').read_text()
                self.assertIn(expected, status)
                self.assertNotIn('SECRET', status)
                self.assertFalse((root / 'trivy.json').exists())

    def test_interruption_is_not_clean(self):
        """Verify interruption is not clean."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(check_trivy.shutil, 'which',
                return_value='trivy'), patch.object(check_trivy.subprocess, 'run',
                    side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    check_trivy.run(root, root)
            self.assertEqual(json.loads((root / 'trivy.status.json').read_text())['status'],
                'failed')
            self.assertFalse((root / 'trivy.json').exists())

    def test_malformed_stdout(self):
        """Verify malformed stdout."""
        for payload in (b'{', b'{"Results":[{"Secrets":{}}]}', b'{"Results":[{"Target":42}]}'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                response = SimpleNamespace(returncode=0, stdout=payload, stderr=b'')
                with patch.object(check_trivy.shutil, 'which',
                    return_value='trivy'), patch.object(check_trivy.subprocess, 'run',
                        return_value=response):
                    check_trivy.run(root, root)
                self.assertEqual(json.loads((root / 'trivy.status.json').read_text())['status'],
                    'failed')
                self.assertFalse((root / 'trivy.json').exists())

    def test_empty_coverage_passes(self):
        """Verify empty coverage passes."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            response = SimpleNamespace(returncode=0, stdout=b'{"Results": []}', stderr=b'')
            with patch.object(check_trivy.shutil, 'which',
                return_value='trivy'), patch.object(check_trivy.subprocess, 'run',
                    return_value=response):
                check_trivy.run(root, root)
            doc = normalize_local(root, root / 'normalized.json')
            result = next(r for r in doc['results'] if r['tool'] == 'trivy')
            self.assertFalse(result['runtime_error'])
            self.assertIn('passed', format_scanner_outcome(result))
            self.assertIn('PASSED', build_sections({'normalized': doc}))
            self.assertEqual(result['metadata']['status'], 'completed')

    def test_coverage_positive_evidence(self):
        """Verify coverage positive evidence."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            response = SimpleNamespace(returncode=0,
                stdout=(
                    b'{"Results":[{"Class":"lang-pkgs","Target":"requi'
                    b'rements.txt"},{"Class":"config","Target":"Docker'
                    b'file"},{"Secrets":[{"RuleID":"test","Match":"SEN'
                    b'SITIVE_SENTINEL"}]}]}'
                ), stderr=b'')
            with patch.object(check_trivy.shutil, 'which',
                return_value='trivy'), patch.object(check_trivy.subprocess, 'run',
                    return_value=response):
                check_trivy.run(root, root)
            result = parse_trivy(root)
            self.assertEqual(result['metadata']['coverage'], dict.fromkeys(('vuln','misconfig',
                'secret'), 'observed'))
            self.assertNotIn('SENSITIVE_SENTINEL', json.dumps(result))
