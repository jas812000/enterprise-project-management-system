"""Raw report filenames for CI scanners."""

SCANNER_ARTIFACTS = {
    "trufflehog": "trufflehog.jsonl",
    "gitleaks": "gitleaks.json",
    "checkstyle": "checkstyle.xml",
    "pmd": "pmd.xml",
    "spotbugs": "spotbugs.xml",
    "semgrep": "semgrep.json",
    "pylint": "pylint.json",
    "bandit": "bandit.json",
    "htmlhint": "htmlhint.json",
    "stylelint": "stylelint.json",
    "dependency_check": "dependency-check-report.json",
    "trivy": "trivy.json",
}
