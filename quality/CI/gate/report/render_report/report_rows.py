"""
Row renderers for the EPMS Quality Gate HTML report.

Functions
---------
finding_row(finding) -> str
    Render a single finding row for the HTML findings table.
summary_row(result) -> str
    Render a summary table row for a tool including visibility toggle.
"""

import re
from urllib.parse import quote, urlsplit
from html import escape
from urllib.parse import urlparse
from quality.CI.gate.report.report_constants import CATEGORY_MAP
from quality.CI.gate.report.render_report.report_badges import (
    tool_status,
    tool_role,
    severity_badge,
)


def _safe_source_base(commit_url: str) -> str:
    """
    Return a validated GitHub blob URL base.

    Reject untrusted hosts, malformed repository paths,
    invalid commit SHAs, and unexpected URL components.
    """
    if not isinstance(commit_url, str):
        return ""

    parsed = urlsplit(commit_url)

    if (
        parsed.scheme != "https"
        or parsed.hostname != "github.com"
        or parsed.netloc != "github.com"
        or parsed.query
        or parsed.fragment
    ):
        return ""

    match = re.fullmatch(
        r"/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)"
        r"/commit/([0-9a-fA-F]{40})",
        parsed.path,
    )

    if not match:
        return ""

    owner, repository, sha = match.groups()

    return (
        f"https://github.com/{owner}/{repository}/"
        f"blob/{sha}"
    )


def finding_row(finding: dict, commit_url: str = "") -> str:
    """Render a finding row with HTML-safe values."""

    severity_raw = str(finding.get("severity", ""))
    message_raw = str(finding.get("message", "—"))
    file_raw = str(finding.get("file", "—"))
    line_raw = str(finding.get("line", "—"))
    rule_raw = str(finding.get("rule", "—"))
    rule_url_raw = str(finding.get("rule_url", ""))

    severity = escape(severity_raw, quote=True)
    message = escape(message_raw, quote=True)
    file_path = escape(file_raw, quote=True)
    line = escape(line_raw, quote=True)
    rule = escape(rule_raw, quote=True)

    parsed_url = urlparse(rule_url_raw)

    if parsed_url.scheme in ("https", "http") and parsed_url.netloc:
        safe_url = escape(rule_url_raw, quote=True)
        rule_cell = (
            f'<a href="{safe_url}" target="_blank" '
            f'rel="noopener noreferrer">{rule}</a>'
        )
    else:
        rule_cell = rule

    search_text = escape(
        f"{file_raw} {rule_raw} {message_raw}".lower(),
        quote=True,
    )

    # Only link repository-relative paths to a validated commit.
    safe_path = (
        isinstance(finding.get("file"), str)
        and 0 < len(file_raw) <= 512
        and not re.search(r"[\x00-\x1f\x7f\\]", file_raw)
        and not file_raw.startswith(("/", "~"))
        and all(
            part not in ("", ".", "..")
            and re.fullmatch(r"[A-Za-z0-9._-]+", part)
            for part in file_raw.split("/")
        )
    )


    safe_line = (
        type(finding.get("line")) is int
        and 1 <= finding["line"] <= 10_000_000
    )

    source_base = _safe_source_base(commit_url)

    if source_base and safe_path:
        encoded_path = quote(file_raw, safe="/")
        source_url = f"{source_base}/{encoded_path}"

        if safe_line:
            source_url += f"#L{finding['line']}"

        safe_source_url = escape(source_url, quote=True)

        file_cell = (
            f'<a href="{safe_source_url}" target="_blank" '
            f'rel="noopener noreferrer"><code>{file_path}</code></a>'
        )
    else:
        file_cell = f"<code>{file_path}</code>"

    return (
        f'<tr class="finding-row" '
        f'data-severity="{severity.lower()}" '
        f'data-text="{search_text}">'
        f"<td>{severity_badge(severity_raw)}</td>"
        f"<td>{file_cell}</td>"
        f"<td>{line}</td>"
        f"<td>{rule_cell}</td>"
        f"<td>{message}</td>"
        f"</tr>"
    )


def summary_row(result: dict) -> str:
    """Render a summary table row with HTML-safe values."""

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

    finding_count = normalized.get("violation_count", 0)

    status_cell, _ = tool_status(reason, violation)
    role_cell = tool_role(blocking)

    findings_cell = (
        escape(str(finding_count), quote=True)
        if finding_count
        else "&mdash;"
    )

    toggle = f"""
    <div class="toggle-wrap">
        <label class="toggle">
            <input type="checkbox" class="tool-toggle"
                   data-tool="{tool}" checked>
            <span class="slider"></span>
        </label>
    </div>"""

    return (
        "<tr>"
        f"<td>{toggle}</td>"
        f'<td><a class="tool-link" href="#tool-{tool}">'
        f"<code>{tool}</code></a></td>"
        f"<td>{category}</td>"
        f"<td>{status_cell}</td>"
        f"<td>{role_cell}</td>"
        f"<td>{findings_cell}</td>"
        "</tr>\n"
    )
