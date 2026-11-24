"""Build ten-scanner sections for the Local Quality Gate advisory report."""

from html import escape

from quality.shared.reporting.report_controls import summary_table_head
from quality.shared.reporting.tool_registry import (
    CATEGORY_MAP,
    LOCAL_TOOL_IDS,
    TOOL_NAMES,
)

from .report_badges import border_color, sev_pills, status_html
from .report_rows import finding_row, summary_row


def _scanner_status(result: dict) -> str:
    """Translate CI execution results into local display statuses."""
    if result.get("runtime_error"):
        return "error"

    metadata = result.get("metadata", {})
    if metadata.get("status") == "disabled":
        return "skipped"

    if not result.get("executed"):
        return "error"

    if result.get("violation_count", 0):
        return "failed"

    return "passed"


def _scanner_rows(context: dict) -> list:
    """Return scanner details from the CI-normalized document."""
    normalized = context.get("normalized")

    if not isinstance(normalized, dict):
        return []

    results = normalized.get("results", [])

    if not isinstance(results, list):
        return []

    return [
        result
        for result in results
        if isinstance(result, dict)
        and result.get("tool") in LOCAL_TOOL_IDS
    ]


def _finding_rows(findings: list, status: str) -> str:
    """Render findings or a scanner-status explanation."""
    if findings:
        return "".join(finding_row(item) for item in findings)

    messages = {
        "passed": "No findings.",
        "failed": "Findings were reported but details are unavailable.",
        "error": "Scanner execution failed. Findings are unavailable.",
        "skipped": "Scanner was disabled or not executed.",
    }

    message = messages.get(status, "Scanner status unavailable.")

    return (
        '<tr><td colspan="5" style="color:#7f8c8d;">'
        f"{escape(message)}</td></tr>"
    )


def tool_section(
    tool_id: str,
    tool_name: str,
    status: str,
    findings: list,
    severity_counts: dict,
) -> str:
    """Render a complete advisory scanner section."""
    category = escape(CATEGORY_MAP.get(tool_id, "Analysis"))
    tool_id = escape(tool_id, quote=True)
    tool_name = escape(tool_name)

    return f"""
<div class="tool-section" id="tool-{tool_id}"
     data-tool="{tool_id}"
     style="border-left:4px solid {border_color(status)};">
    <div class="tool-header">
        <div class="tool-title">
            <span class="tool-name">{tool_name}</span>
            <span class="tool-category">{category}</span>
            <a class="back-link" href="#tool-results-summary">
                ↑ Back to summary
            </a>
        </div>
        <div class="tool-meta">
            {status_html(status)}
            <span style="margin-left:12px;">
                Role: Advisory
            </span>
        </div>
        <div class="sev-counts">
            {sev_pills(severity_counts)}
        </div>
    </div>
    <div class="tool-findings">
        <div class="no-results" style="display:none;">
            No findings match the current filters.
        </div>
        <table>
            <thead>
                <tr>
                    <th>Severity</th>
                    <th>File</th>
                    <th>Line</th>
                    <th>Rule</th>
                    <th>Message</th>
                </tr>
            </thead>
            <tbody>
                {_finding_rows(findings, status)}
            </tbody>
        </table>
        <a class="back-link" href="#tool-results-summary">
            ↑ Back to summary
        </a>
    </div>
</div>"""


def build_summary_table(context: dict) -> str:
    """Render the ten-scanner summary table."""
    rows = []

    for result in _scanner_rows(context):
        tool_id = result["tool"]
        status = _scanner_status(result)
        findings = result.get("findings", [])

        rows.append(
            summary_row(
                tool_id,
                TOOL_NAMES[tool_id],
                status,
                findings,
            )
        )

    if not rows:
        rows.append(
            '<tr><td colspan="6">'
            'Normalized scanner results are unavailable.'
            '</td></tr>'
        )

    return f"""
<table>
    <thead>
{summary_table_head('<th class="print-hide">Show</th>')}
    </thead>
    <tbody>
        {''.join(rows)}
    </tbody>
</table>"""


def build_sections(context: dict) -> str:
    """Render all ten normalized scanner sections."""
    sections = []

    for result in _scanner_rows(context):
        tool_id = result["tool"]

        sections.append(
            tool_section(
                tool_id,
                TOOL_NAMES[tool_id],
                _scanner_status(result),
                result.get("findings", []),
                result.get("severity_counts", {}),
            )
        )

    return "\n".join(sections)
