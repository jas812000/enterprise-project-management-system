"""
Constants for the EPMS Local Quality Gate HTML report.
"""

from quality.shared.reporting import report_styles


SEVERITY_COLORS = report_styles.SEVERITY_COLORS

CATEGORY_MAP = {
    "Checkstyle": "SAST — Java",
    "PMD":        "SAST — Java",
    "SpotBugs":   "SAST — Java",
}

TOOL_CHECKSTYLE = "Checkstyle"
TOOL_PMD = "PMD"
TOOL_SPOTBUGS = "SpotBugs"

TOOL_ID_CHECKSTYLE = "checkstyle"
TOOL_ID_PMD = "pmd"
TOOL_ID_SPOTBUGS = "spotbugs"
