
"""Scanner registry for the EPMS advisory Local Quality Gate."""

from quality.CI.gate.normalize import TOOL_PARSERS
from quality.CI.gate.scanner_artifacts import SCANNER_ARTIFACTS

# BN1 uses nine of the twelve scanners configured in BN2.
LOCAL_SCANNER_NAMES = (
    "trufflehog",
    "gitleaks",
    "checkstyle",
    "pmd",
    "spotbugs",
    "pylint",
    "bandit",
    "htmlhint",
    "stylelint",
)

# Reuse the CI parsers rather than maintaining local copies.
_CI_PARSERS = dict(TOOL_PARSERS)

LOCAL_SCANNERS = tuple(
    {
        "name": name,
        "parser": _CI_PARSERS[name],
        "artifact": SCANNER_ARTIFACTS[name],
    }
    for name in LOCAL_SCANNER_NAMES
)
