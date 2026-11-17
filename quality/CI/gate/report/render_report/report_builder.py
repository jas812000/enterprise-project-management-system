"""
Main report builder for the EPMS Quality Gate HTML report.

Functions
---------
build_html_report(evaluated_doc, env) -> str
    Build the full HTML quality gate report document.
"""

from datetime import datetime, timezone
from html import escape
from quality.shared.reporting.report_controls import summary_table_head

from quality.CI.gate.report.render_report.report_styles import CSS
from quality.CI.gate.report.render_report.report_scripts import FILTER_JS
from quality.CI.gate.report.render_report.report_controls import (
    FILTER_BAR,
    LEGEND_BLOCK,
)
from quality.CI.gate.report.render_report.report_blocks import (
    pr_block,
    build_banner,
    build_run_metadata,
    build_summary_rows,
    build_tool_sections,
)


def _header_metadata(env: dict, sha_short: str, run_url: str, commit_url: str):
    """Escape and link untrusted workflow metadata for HTML display."""
    # ----------------------------------------------------------
    # Escape environment-derived metadata
    # ----------------------------------------------------------
    safe_run_number = escape(
        str(env.get("run_number", "?")),
        quote=True,
    )

    safe_event_name = escape(
        str(env.get("event_name", "unknown")),
        quote=True,
    )

    safe_scan_root = escape(
        str(env.get("scan_root", ".")),
        quote=True,
    )

    safe_sha_short = escape(
        str(sha_short),
        quote=True,
    )

    # ----------------------------------------------------------
    # Build validated GitHub Actions links
    # ----------------------------------------------------------
    if run_url:
        safe_run_url = escape(run_url, quote=True)

        run_cell = (
            f'<a href="{safe_run_url}" target="_blank" '
            f'rel="noopener noreferrer">'
            f"#{safe_run_number}</a>"
        )
    else:
        run_cell = f"#{safe_run_number}"

    if commit_url:
        safe_commit_url = escape(commit_url, quote=True)

        commit_cell = (
            f'<a href="{safe_commit_url}" target="_blank" '
            f'rel="noopener noreferrer">'
            f"<code>{safe_sha_short}</code></a>"
        )
    else:
        commit_cell = f"<code>{safe_sha_short}</code>"

    return (run_cell, commit_cell, safe_event_name, safe_scan_root)


def _report_results(evaluated_doc: dict) -> tuple[bool, list, list, list]:
    """Return blocking, advisory, and combined scanner results."""
    # ----------------------------------------------------------
    # Extract evaluated result groups
    # ----------------------------------------------------------
    overall_block = bool(evaluated_doc.get("overall_block", True))
    blocking_results = evaluated_doc.get("blocking_results", [])
    non_blocking_results = evaluated_doc.get("non_blocking_results", [])
    all_results = blocking_results + non_blocking_results

    return overall_block, blocking_results, non_blocking_results, all_results


def build_html_report(
    evaluated_doc: dict,
    env: dict,
    *,
    link_findings: bool = False,
) -> str:
    """
    Build the complete HTML report from evaluated results.

    Parameters
    ----------
    evaluated_doc : dict
        Evaluated quality gate document.
    env : dict
        Environment metadata for report rendering.

    Returns
    -------
    str
        Full HTML report string.
    """

    overall_block, blocking_results, non_blocking_results, all_results = (
        _report_results(evaluated_doc)
    )
    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    banner_color, banner_text = build_banner(overall_block)
    sha_short, run_url, commit_url = build_run_metadata(env)
    header = _header_metadata(env, sha_short, run_url, commit_url)
    sections = build_tool_sections(
        blocking_results,
        non_blocking_results,
        commit_url if link_findings else "",
    )

    # ----------------------------------------------------------
    # Render final HTML document
    # ----------------------------------------------------------
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>EPMS Quality Gate Report</title>
    <style>{CSS}</style>
</head>
<body>

<h1>EPMS Quality Gate Report</h1>
<div class="banner" style="background:{banner_color};">{banner_text}</div>

<div class="info-card">
    <h3>Report Header</h3>
    <table class="info-table">
        <tr>
            <td><strong>Generated (UTC)</strong></td>
            <td>{generated_at}</td>
        </tr>
        <tr>
            <td><strong>Pipeline Run</strong></td>
            <td>{header[0]}</td>
        </tr>
        <tr>
            <td><strong>Trigger</strong></td>
            <td><code>{header[2]}</code></td>
        </tr>
        <tr>
            <td><strong>Scan Root</strong></td>
            <td><code>{header[3]}</code></td>
        </tr>
        <tr>
            <td><strong>Commit SHA</strong></td>
            <td>{header[1]}</td>
        </tr>
    </table>
</div>

{pr_block(env)}

{LEGEND_BLOCK}

{FILTER_BAR}

<h2 id="tool-results-summary">Tool Results Summary</h2>
<table>
    <thead>
{summary_table_head('<th>Show</th>')}
    </thead>
    <tbody>{build_summary_rows(all_results)}</tbody>
</table>

<div class="section-header">Enforced Tools</div>
{sections[0]}

<div class="section-header advisory">Advisory Tools</div>
{sections[1]}

<footer>
    Generated by EPMS Quality Gate Engine &mdash; {generated_at}
</footer>

{FILTER_JS}
</body>
</html>"""
