
"""Scanner registry for the advisory Local Quality Gate."""

from quality.CI.gate.normalize import TOOL_PARSERS
from quality.CI.gate.scanner_artifacts import SCANNER_ARTIFACTS

# The Local Quality Gate uses nine of the twelve CI Quality Gate scanners.
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
