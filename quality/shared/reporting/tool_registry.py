"""Shared scanner identifiers, display names, and report categories."""

TOOL_ID_TRUFFLEHOG = "trufflehog"
TOOL_ID_GITLEAKS = "gitleaks"
TOOL_ID_CHECKSTYLE = "checkstyle"
TOOL_ID_PMD = "pmd"
TOOL_ID_SPOTBUGS = "spotbugs"
TOOL_ID_PYLINT = "pylint"
TOOL_ID_BANDIT = "bandit"
TOOL_ID_HTMLHINT = "htmlhint"
TOOL_ID_STYLELINT = "stylelint"
TOOL_ID_SEMGREP = "semgrep"
TOOL_ID_DEPENDENCY_CHECK = "dependency_check"
TOOL_ID_TRIVY = "trivy"

TOOL_TRUFFLEHOG = "TruffleHog"
TOOL_GITLEAKS = "Gitleaks"
TOOL_CHECKSTYLE = "Checkstyle"
TOOL_PMD = "PMD"
TOOL_SPOTBUGS = "SpotBugs"
TOOL_PYLINT = "Pylint"
TOOL_BANDIT = "Bandit"
TOOL_HTMLHINT = "HTMLHint"
TOOL_STYLELINT = "Stylelint"
TOOL_SEMGREP = "Semgrep"
TOOL_DEPENDENCY_CHECK = "OWASP Dependency-Check"
TOOL_TRIVY = "Trivy"

TOOL_NAMES = {
    TOOL_ID_TRUFFLEHOG: TOOL_TRUFFLEHOG,
    TOOL_ID_GITLEAKS: TOOL_GITLEAKS,
    TOOL_ID_CHECKSTYLE: TOOL_CHECKSTYLE,
    TOOL_ID_PMD: TOOL_PMD,
    TOOL_ID_SPOTBUGS: TOOL_SPOTBUGS,
    TOOL_ID_PYLINT: TOOL_PYLINT,
    TOOL_ID_BANDIT: TOOL_BANDIT,
    TOOL_ID_HTMLHINT: TOOL_HTMLHINT,
    TOOL_ID_STYLELINT: TOOL_STYLELINT,
    TOOL_ID_SEMGREP: TOOL_SEMGREP,
    TOOL_ID_DEPENDENCY_CHECK: TOOL_DEPENDENCY_CHECK,
    TOOL_ID_TRIVY: TOOL_TRIVY,
}

CATEGORY_MAP = {
    TOOL_ID_TRUFFLEHOG: "Secret Detection",
    TOOL_ID_GITLEAKS: "Secret Detection",
    TOOL_ID_CHECKSTYLE: "Java Style / Linting",
    TOOL_ID_PMD: "Java Static Code Analysis",
    TOOL_ID_SPOTBUGS: "Java Bug Detection / SAST",
    TOOL_ID_PYLINT: "Python Static Code Analysis",
    TOOL_ID_BANDIT: "Python Security / SAST",
    TOOL_ID_HTMLHINT: "HTML Linting",
    TOOL_ID_STYLELINT: "CSS Linting",
    TOOL_ID_SEMGREP: "Multi-Language SAST",
    TOOL_ID_DEPENDENCY_CHECK: "Software Composition Analysis",
    TOOL_ID_TRIVY: "Container / Dependency Security",
}

LOCAL_TOOL_IDS = frozenset({
    TOOL_ID_TRUFFLEHOG,
    TOOL_ID_GITLEAKS,
    TOOL_ID_CHECKSTYLE,
    TOOL_ID_PMD,
    TOOL_ID_SPOTBUGS,
    TOOL_ID_PYLINT,
    TOOL_ID_BANDIT,
    TOOL_ID_HTMLHINT,
    TOOL_ID_STYLELINT,
})
