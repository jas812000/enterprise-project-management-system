"""
Tool section renderers for the EPMS Quality Gate HTML report.

Functions
---------
tool_findings_html(findings, reason, normalized) -> str
    Render the findings section for a tool result.
tool_section(result) -> str
    Render a full tool detail section.
"""

from html import escape
from quality.CI.gate.report.report_constants import CATEGORY_MAP
from quality.CI.gate.report.render_report.report_badges import (
    tool_status,
    tool_role,
    tool_reason_html,
    sev_pills,
)
from quality.CI.gate.report.render_report.report_rows import finding_row


def tool_findings_html(
    findings: list[dict],
    reason: str,
    normalized: dict,
    commit_url: str = "",
) -> str:
    """Render the findings section for a tool result."""
    if findings:
        rows = "\n".join(
            finding_row(f, commit_url) for f in findings
        )
        return f"""
        <table>
            <thead><tr>
                <th>Severity</th><th>File</th><th>Line</th>
                <th>Rule</th><th>Message</th>
            </tr></thead>
            <tbody>
                {rows}
                <tr class="no-results">
                    <td colspan="5" style="color:#7f8c8d;font-style:italic;">
                        No findings match the current filters.
                    </td>
                </tr>
            </tbody>
        </table>"""

    if reason == "disabled":
        return "<p><em>Tool is disabled.</em></p>"


    if normalized.get("runtime_error", False):
        error_message = (
            (normalized.get("metadata") or {}).get(
                "error", "Unknown error"
            )
        )

        safe_error = escape(str(error_message), quote=True)

        return f"<p><em>Runtime error: {safe_error}</em></p>"

    if not normalized.get("executed", False):
        return "<p><em>Tool did not execute.</em></p>"

    if normalized.get("violation_count", 0) > 0:
        return (
            "<p><em>Individual finding details are excluded "
            "from this published report. Refer to the "
            "summary above for finding counts and severity.</em></p>"
        )

    return "<p><em>No findings detected.</em></p>"


def tool_section(
    result: dict,
    commit_url: str = "",
) -> str:
    """Render a full tool detail section."""

    tool_raw = str(result.get("tool", "unknown"))
    tool = escape(tool_raw, quote=True)

    category = escape(
        str(CATEGORY_MAP.get(tool_raw, "Analysis")),
        quote=True,
    )
    violation = result.get("policy_violation", False)
    blocking = result.get("blocking", False)
    reason = result.get("reason", "")
    normalized = result.get("normalized", {})
    findings = normalized.get("findings", [])
    severity_counts = normalized.get("severity_counts", {})

    status_html, header_color = tool_status(reason, violation)
    role_html = tool_role(blocking)
    reason_html = tool_reason_html(reason)
    findings_html = tool_findings_html(
        findings,
        reason,
        normalized,
        commit_url,
    )
    # Display structured Semgrep scanner errors separately
    # from code findings.
    scanner_errors_html = ""

    if tool_raw == "semgrep":
        metadata = normalized.get("metadata", {})

        if not isinstance(metadata, dict):
            metadata = {}

        scanner_errors = metadata.get("scanner_errors", [])

        if isinstance(scanner_errors, list) and scanner_errors:
            rows = []

            for error in scanner_errors:
                if not isinstance(error, dict):
                    continue

                error_type = escape(
                    str(error.get("type", "unknown")),
                    quote=True,
                )
                error_message = escape(
                    str(error.get("message", "")),
                    quote=True,
                )
                error_file = escape(
                    str(error.get("file", "")),
                    quote=True,
                )
                error_line = escape(
                    str(error.get("line", 0)),
                    quote=True,
                )

                rows.append(
                    f"<tr><td><code>{error_type}</code></td>"
                    f"<td>{error_file}</td>"
                    f"<td>{error_line}</td>"
                    f"<td>{error_message}</td></tr>"
                )

            if rows:
                scanner_errors_html = f"""
                <h3>Semgrep Scanner Errors ({len(rows)})</h3>
                <table>
                    <thead>
                        <tr>
                            <th>Error Type</th>
                            <th>File</th>
                            <th>Line</th>
                            <th>Recommended Action</th>
                        </tr>
                    </thead>
                    <tbody>{''.join(rows)}</tbody>
                </table>
                """

    back_link = '<a href="#tool-results-summary" style="font-size:0.8em;color:#2980b9;text-decoration:none;">↑ Back to summary</a>'

    return f"""
    <div class="tool-section" id="tool-{tool}" data-tool="{tool}">
        <div class="tool-header" style="border-left:4px solid {header_color};">
            <div class="tool-title">
                <span class="tool-name">{tool}</span>
                <span class="tool-category">{category}</span>
                <span style="margin-left:auto;">{back_link}</span>
            </div>
            <div class="tool-meta">
                {status_html}
                <span style="margin-left:12px;">Role: {role_html}</span>
                {reason_html}
            </div>
            <div class="sev-counts">{sev_pills(severity_counts)}</div>
        </div>
        <div class="tool-findings">
            {scanner_errors_html}
            {findings_html}
            <div style="text-align:right;padding:8px 0 4px;">
                {back_link}
            </div>
        </div>
    </div>"""
