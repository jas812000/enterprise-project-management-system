"""
Badge and pill renderers for the CI Quality Gate HTML report.

Functions
---------
severity_badge(severity) -> str
    Render an HTML severity badge.
sev_pills(sev_counts) -> str
    Render severity count pills for a tool section.
tool_status(reason, violation) -> tuple[str, str]
    Return status HTML and header color for a tool result.
tool_role(blocking) -> str
    Return role HTML for a tool result.
tool_reason_html(reason) -> str
    Return optional reason HTML for a tool result.
"""

from html import escape
from quality.CI.gate.report.report_constants import SEVERITY_COLORS



def severity_badge(severity: str | None) -> str:
    """Render an HTML-safe severity badge."""

    if not severity:
        return "<span>&mdash;</span>"

    severity_raw = str(severity)

    color = SEVERITY_COLORS.get(
        severity_raw.lower(),
        "#95a5a6",
    )

    safe_severity = escape(
        severity_raw.upper(),
        quote=True,
    )

    return (
        f'<span style="background:{color};color:#fff;padding:2px 8px;'
        f'border-radius:4px;font-size:0.85em;font-weight:bold;">'
        f"{safe_severity}</span>"
    )



def sev_pills(sev_counts: dict) -> str:
    """Render HTML-safe severity count pills."""

    pills = ""

    for level in ["critical", "high", "medium", "low", "info"]:
        count = sev_counts.get(level, 0)

        if count:
            color = SEVERITY_COLORS.get(level, "#95a5a6")

            safe_count = escape(str(count), quote=True)

            pills += (
                f'<span style="background:{color};color:#fff;padding:2px 8px;'
                f'border-radius:4px;font-size:0.8em;margin-right:4px;">'
                f"{level.upper()}: {safe_count}</span>"
            )

    return pills or '<span style="color:#7f8c8d;">No findings</span>'


def tool_status(reason: str, violation: bool) -> tuple[str, str]:
    """Return status HTML and header color for a tool result."""
    if reason == "disabled":
        return '<span style="color:#7f8c8d;">DISABLED</span>', "#7f8c8d"
    if violation:
        return '<span style="color:#c0392b;">FAILURE</span>', "#c0392b"
    return '<span style="color:#27ae60;">SUCCESS</span>', "#27ae60"


def tool_role(blocking: bool) -> str:
    """Return role HTML for a tool result."""
    return (
        '<span style="color:#c0392b;">Enforced</span>'
        if blocking
        else '<span style="color:#e67e22;">Advisory</span>'
    )



def tool_reason_html(reason: str) -> str:
    """Render an HTML-safe tool reason."""

    if not reason or reason == "disabled":
        return ""

    safe_reason = escape(str(reason), quote=True)

    return (
        f'<span style="margin-left:12px;color:#7f8c8d;font-size:0.85em;">'
        f"Reason: <code>{safe_reason}</code></span>"
    )
