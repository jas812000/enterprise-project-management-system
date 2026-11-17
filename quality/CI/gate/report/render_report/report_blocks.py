"""
Metadata block renderers for the CI Quality Gate HTML report.

Functions
---------
pr_block(env) -> str
    Render the pull request metadata block when applicable.
build_banner(overall_block) -> tuple[str, str]
    Build the banner color and text shown at the top of the report.
build_run_metadata(env) -> tuple[str, str, str]
    Build run-specific metadata used in the report header.
build_summary_rows(all_results) -> str
    Render the summary table rows for all evaluated tools.
build_tool_sections(blocking_results, non_blocking_results) -> tuple[str, str]
    Render the enforced and advisory tool sections.
"""

from html import escape
import re
from urllib.parse import urlsplit
from quality.CI.gate.report.render_report.report_rows import summary_row
from quality.CI.gate.report.render_report.report_sections import tool_section


def pr_block(env: dict) -> str:
    """Render HTML-safe pull request metadata."""

    if env.get("event_name") != "pull_request" or not env.get("pr_number"):
        return ""

    pr_number = escape(str(env.get("pr_number", "")), quote=True)
    actor = escape(str(env.get("actor", "")), quote=True)
    head_ref = escape(str(env.get("head_ref", "")), quote=True)
    base_ref = escape(str(env.get("base_ref", "")), quote=True)

    return f"""
    <div class="info-card">
        <h3>Pull Request</h3>
        <table class="info-table">
            <tr><td><strong>PR Number</strong></td><td>#{pr_number}</td></tr>
            <tr><td><strong>PR Author</strong></td><td>@{actor}</td></tr>
            <tr><td><strong>Source Branch</strong></td>
                <td><code>{head_ref}</code></td></tr>
            <tr><td><strong>Target Branch</strong></td>
                <td><code>{base_ref}</code></td></tr>
        </table>
    </div>"""


def build_banner(overall_block: bool) -> tuple[str, str]:
    """
    Build the banner color and text shown at the top of the report.

    Parameters
    ----------
    overall_block : bool
        True when one or more enforced tools failed policy.

    Returns
    -------
    tuple[str, str]
        Banner color and banner message.
    """
    banner_color = "#c0392b" if overall_block else "#27ae60"
    banner_text = (
        "BLOCKED — One or more required checks failed. "
        "Fix the issues below before merging."
        if overall_block
        else "APPROVED — All required checks passed."
    )
    return banner_color, banner_text


def build_run_metadata(env: dict) -> tuple[str, str, str]:
    """Build validated GitHub Actions report metadata."""

    sha = str(env.get("sha", ""))
    sha_short = sha[:7] if sha else "unknown"

    server_url = str(env.get("server_url", "")).rstrip("/")
    repository = str(env.get("repository", ""))
    run_id = str(env.get("run_id", ""))

    parsed = urlsplit(server_url)

    valid_server = (
        parsed.scheme == "https"
        and bool(parsed.hostname)
        and not parsed.username
        and not parsed.password
        and not parsed.query
        and not parsed.fragment
    )

    valid_repository = bool(
        re.fullmatch(
            r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",
            repository,
        )
    )

    valid_run_id = bool(re.fullmatch(r"[0-9]+", run_id))
    valid_sha = bool(re.fullmatch(r"[0-9a-fA-F]{40}", sha))

    run_url = ""
    commit_url = ""

    if valid_server and valid_repository:
        if valid_run_id:
            run_url = (
                f"{server_url}/{repository}/actions/runs/{run_id}"
            )

        if valid_sha:
            commit_url = (
                f"{server_url}/{repository}/commit/{sha}"
            )

    return sha_short, run_url, commit_url


def build_summary_rows(all_results: list[dict]) -> str:
    """
    Render the summary table rows for all evaluated tools.

    Parameters
    ----------
    all_results : list[dict]
        Combined blocking and non-blocking evaluated results.

    Returns
    -------
    str
        HTML table rows for the summary table.
    """
    return "".join(summary_row(result) for result in all_results)


def build_tool_sections(
    blocking_results: list[dict],
    non_blocking_results: list[dict],
    commit_url: str = "",
) -> tuple[str, str]:
    """
    Render the enforced and advisory tool sections.

    Parameters
    ----------
    blocking_results : list[dict]
        Evaluated results for enforced tools.
    non_blocking_results : list[dict]
        Evaluated results for advisory tools.

    Returns
    -------
    tuple[str, str]
        HTML for enforced-tool sections and advisory-tool sections.
    """
    blocking_sections = (
        "\n".join(
            tool_section(result, commit_url)
            for result in blocking_results
        )
        or "<p><em>No enforced tools configured.</em></p>"
    )

    non_blocking_sections = (
        "\n".join(
            tool_section(result, commit_url)
            for result in non_blocking_results
        )
        or "<p><em>No advisory tools configured.</em></p>"
    )

    return blocking_sections, non_blocking_sections
