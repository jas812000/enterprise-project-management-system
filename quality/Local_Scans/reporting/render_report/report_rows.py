"""
Row renderers for the Local Quality Gate HTML report.
"""

from html import escape
from .report_constants import CATEGORY_MAP
from .report_badges import severity_badge, status_html


def finding_row(finding: dict) -> str:
    """Render a single finding row for the HTML findings table."""
    severity = escape(str(finding.get("severity") or "info"))
    file_path = escape(str(finding.get("file") or ""))
    display_path = file_path.replace("/", "/<wbr>")
    line = escape(str(finding.get("line") or ""))
    rule = escape(str(finding.get("rule") or ""))
    message = escape(str(finding.get("message") or ""))
    search_text = f"{file_path} {rule} {message}".lower()

    return (
        f'<tr class="finding-row" '
        f'data-severity="{severity.lower()}" '
        f'data-text="{search_text}">'
        f"<td>{severity_badge(severity)}</td>"
        f"<td><code>{display_path}</code></td>"
        f"<td>{line}</td>"
        f"<td>{rule}</td>"
        f"<td>{message}</td>"
        "</tr>"
    )


def summary_row(tool_id: str, tool_name: str, status: str, findings: list) -> str:
    """Render a summary table row with visibility toggle."""
    category = CATEGORY_MAP.get(tool_name, "Analysis")
    count = "&mdash;" if status in ("error", "skipped") and not findings else str(len(findings))
    toggle = (
        f'<div class="toggle-wrap">'
        f'<label class="toggle">'
        f'<input type="checkbox" class="tool-toggle" data-tool="{tool_id}" checked>'
        f'<span class="slider"></span>'
        f'</label></div>'
    )
    return (
        "<tr>"
        f'<td class="print-hide">{toggle}</td>'
        f'<td><a class="tool-link" href="#tool-{tool_id}">{tool_name}</a></td>'
        f"<td>{category}</td>"
        f"<td>{status_html(status)}</td>"
        f"<td>Advisory</td>"
        f"<td>{count}</td>"
        "</tr>"
    )
