"""
Shared constants for the CI Quality Gate report package.

Scanner identifiers, display names, and categories are maintained
centrally in quality.shared.reporting.tool_registry.
"""

from quality.shared.reporting import report_styles


SEVERITY_COLORS = report_styles.SEVERITY_COLORS

_QUALITY_GATE = "Quality Gate"

_MD_TABLE_HEADER = "| Field | Value |"
_MD_TABLE_SEPARATOR = "|-------|-------|"
