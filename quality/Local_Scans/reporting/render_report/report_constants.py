"""
Constants for the EPMS Local Quality Gate HTML report.
"""

SEVERITY_COLORS = {
    "critical": "#7c0000",
    "high":     "#c0392b",
    "medium":   "#e67e22",
    "low":      "#f1c40f",
    "info":     "#3498db",
}

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