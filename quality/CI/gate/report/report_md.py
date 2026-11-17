"""
Markdown Report Builder

Builds the markdown report string consumed by:

- GitHub Actions job summary
- Pull request comment

Functions
---------
build_markdown_report(evaluated_doc, env) -> str
    Build the complete markdown quality gate report.
"""

from datetime import datetime, timezone

from quality.shared.reporting.result_summary import summary_fields

from quality.CI.gate.report.report_constants import (
    CATEGORY_MAP,
    _MD_TABLE_HEADER,
    _MD_TABLE_SEPARATOR,
)
from quality.CI.gate.report.render_report.report_blocks import (
    build_run_metadata,
)


PR_COMMENT_MARKER = "## EPMS — Security & Quality Analysis Report"


def _safe_md(value: object) -> str:
    """Escape dynamic text for GitHub-flavored Markdown tables."""
    text = str(value)

    # Prevent embedded HTML from being interpreted as markup.
    text = (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )

    # Prevent table-cell and Markdown formatting injection.
    text = text.replace("\\", "\\\\")
    text = text.replace("|", "&#124;")
    text = text.replace("`", "&#96;")
    text = text.replace("[", "&#91;")
    text = text.replace("]", "&#93;")

    # Keep dynamic values within a single table cell.
    text = text.replace("\r", " ").replace("\n", " ")

    return text


def _summary_row(result: dict) -> str:
    """Build one Markdown summary row for a tool result."""

    tool = str(result.get("tool", "unknown"))
    category = CATEGORY_MAP.get(tool, "Analysis")

    violation, blocking, reason, finding_count = summary_fields(result)

    findings_label = (
        f"{finding_count} finding(s)"
        if finding_count
        else "—"
    )

    if reason == "disabled":
        status = "DISABLED"
    elif violation:
        status = "FAILURE"
    else:
        status = "SUCCESS"

    role = "Enforced" if blocking else "Advisory"

    return (
        f"| {_safe_md(tool)} "
        f"| {_safe_md(category)} "
        f"| {status} "
        f"| {role} "
        f"| {_safe_md(findings_label)} |"
    )


def _pull_request_lines(env: dict) -> list[str]:
    """Build optional pull-request metadata lines."""
    lines: list[str] = []
    if (
        env.get("event_name") == "pull_request"
        and env.get("pr_number")
    ):
        pr_number = _safe_md(env.get("pr_number", ""))
        actor = _safe_md(env.get("actor", "unknown"))
        head_ref = _safe_md(env.get("head_ref", ""))
        base_ref = _safe_md(env.get("base_ref", ""))

        lines += [
            "### Pull Request",
            "",
            _MD_TABLE_HEADER,
            _MD_TABLE_SEPARATOR,
            f"| **PR Number** | #{pr_number} |",
            f"| **PR Author** | @{actor} |",
            f"| **Source Branch** | `{head_ref}` |",
            f"| **Target Branch** | `{base_ref}` |",
            "",
        ]

    return lines


def build_markdown_report(evaluated_doc: dict, env: dict) -> str:
    """
    Build the Markdown quality gate report.

    Parameters
    ----------
    evaluated_doc : dict
        Evaluated quality gate document.
    env : dict
        Environment metadata used for report rendering.

    Returns
    -------
    str
        Complete Markdown report body.
    """

    report_data = {
        "overall_block": bool(
            evaluated_doc.get("overall_block", True)
        ),
        "blocking_results": evaluated_doc.get(
            "blocking_results", []
        ),
        "non_blocking_results": evaluated_doc.get(
            "non_blocking_results", []
        ),
    }

    report_data["all_results"] = (
        report_data["blocking_results"]
        + report_data["non_blocking_results"]
    )

    # Reuse the validated GitHub URL construction.
    sha_short, run_url, commit_url = build_run_metadata(env)

    generated_at = datetime.now(timezone.utc).strftime(
        "%Y-%m-%d %H:%M:%S UTC"
    )

    approval_line = (
        "> **BLOCKED** — One or more required checks failed. "
        "Fix the issues below before merging."
        if report_data["overall_block"]
        else "> **APPROVED** — All required checks passed."
    )

    # Escape environment-derived display values.
    run_number = _safe_md(env.get("run_number", "?"))
    event_name = _safe_md(env.get("event_name", "unknown"))
    scan_root = _safe_md(env.get("scan_root", "."))
    safe_sha = _safe_md(sha_short)

    # Only validated URLs are rendered as Markdown links.
    pipeline_run = (
        f"[#{run_number}]({run_url})"
        if run_url
        else f"#{run_number}"
    )

    commit_details = (
        f"`{safe_sha}` ([full]({commit_url}))"
        if commit_url
        else f"`{safe_sha}`"
    )

    lines: list[str] = [
        "# CI Quality Gate Report",
        "",
        approval_line,
        "",
        PR_COMMENT_MARKER,
        "",
        "### Report Header",
        "",
        _MD_TABLE_HEADER,
        _MD_TABLE_SEPARATOR,
        f"| **Generated (UTC)** | {generated_at} |",
        f"| **Pipeline Run** | {pipeline_run} |",
        f"| **Trigger** | `{event_name}` |",
        f"| **Scan Root** | `{scan_root}` |",
        "",
        "_All timestamps are reported in Coordinated Universal Time (UTC)._",
        "",
    ]

    lines.extend(_pull_request_lines(env))

    lines += [
        "### Commit Details",
        "",
        _MD_TABLE_HEADER,
        _MD_TABLE_SEPARATOR,
        f"| **Commit SHA** | {commit_details} |",
        "",
        "### Legend",
        "",
        "| Status | Meaning |",
        "|--------|---------|",
        "| SUCCESS | Tool ran and found no violations |",
        "| FAILURE | Tool found one or more violations |",
        "| DISABLED | Tool is not yet configured |",
        "| Enforced | Violations from this tool will block the merge |",
        "| Advisory | Violations are reported but will not block the merge |",
        "",
        "### Tool Results Summary",
        "",
        "| Tool | Category | Status | Role | Findings |",
        "|------|----------|--------|------|----------|",
    ]

    lines.extend(
        _summary_row(result)
        for result in report_data["all_results"]
    )

    lines += [
        "",
        "---",
        "_Summary reports are available in the workflow run artifacts._",
        "",
    ]

    return "\n".join(lines)
