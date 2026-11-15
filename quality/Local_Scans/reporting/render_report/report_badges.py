"""
Badge and status helper renderers for the EPMS Local Quality Gate report.
"""

from .report_constants import SEVERITY_COLORS


def severity_badge(severity: str) -> str:
    """Render a coloured severity badge."""
    severity = (severity or "info").lower()
    color = SEVERITY_COLORS.get(severity, "#95a5a6")
    return (
        f'<span style="background:{color};color:#fff;padding:2px 8px;'
        f'border-radius:4px;font-size:0.85em;font-weight:bold;">'
        f"{severity.upper()}</span>"
    )


def status_html(status: str) -> str:
    """Render scanner execution and quality gate status."""
    if status == "passed":
        return '<span style="color:#27ae60;">PASSED</span>'
    if status == "failed":
        return '<span style="color:#c0392b;">FAILED</span>'
    if status == "error":
        return '<span style="color:#8e44ad;">ERROR</span>'
    if status == "skipped":
        return '<span style="color:#7f8c8d;">SKIPPED</span>'
    return '<span style="color:#8e44ad;">UNKNOWN STATUS</span>'


def border_color(status: str) -> str:
    """Return the left-border color for a scanner section."""
    if status == "passed":
        return "#27ae60"
    if status == "failed":
        return "#c0392b"
    if status == "error":
        return "#8e44ad"
    if status == "skipped":
        return "#7f8c8d"
    return "#8e44ad"


def sev_pills(counts: dict) -> str:
    """Render severity summary pills."""
    pills = ""
    for level in ["critical", "high", "medium", "low", "info"]:
        count = counts.get(level, 0)
        if count:
            color = SEVERITY_COLORS.get(level, "#95a5a6")
            pills += (
                f'<span style="background:{color};color:#fff;'
                f'padding:2px 8px;border-radius:4px;'
                f'font-size:0.8em;margin-right:4px;">'
                f"{level.upper()}: {count}</span>"
            )
    return pills or '<span style="color:#7f8c8d;">No findings</span>'
