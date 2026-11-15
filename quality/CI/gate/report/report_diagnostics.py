
"""
Prepare publication-safe diagnostic findings for the EPMS Quality Gate.

The diagnostic report is separate from the existing summary report.

Security requirements:
- Never include raw secret-scanner artifacts.
- Preserve safe finding locations and remediation details.
- Explicitly indicate that detected secret values are redacted.
- Do not modify the original evaluated document.
"""

from copy import deepcopy
import re
from quality.CI.gate.report.report_constants import CATEGORY_MAP

SECRET_SCANNERS = frozenset({"gitleaks", "trufflehog"})
REDACTION_MARKER = "***REDACTED***"
SAFE_SECRET_RULES = frozenset({
    # Gitleaks
    "generic-api-key",
    "aws-access-token",
    "private-key",
    "gitleaks",

    # TruffleHog
    "Github",
    "Gitlab",
    "AWS",
    "Slack",
    "Stripe",
    "Postgres",
    "PrivateKey",
    "TruffleHog",
})

_SAFE_PATH_PART = re.compile(r"^[A-Za-z0-9._-]+$")
_SENSITIVE_PATH_PART = re.compile(
    r"(?i)(secret|token|password|credential|api[_-]?key)"
)


def _safe_secret_finding_path(value: object) -> str:
    """
    Retain ordinary repository paths, but suppress suspicious
    or malformed paths in secret-scanner findings.
    """
    if not isinstance(value, str) or not value:
        return "[path withheld]"

    path = value

    if (
        len(path) > 512
        or path.startswith(("/", "~"))
        or re.search(r"[\x00-\x1f\x7f\\]", path)
    ):
        return "[path withheld]"

    parts = path.split("/")

    if any(
        part in (".", "..")
        or not _SAFE_PATH_PART.fullmatch(part)
        or _SENSITIVE_PATH_PART.search(part)
        for part in parts
    ):
        return "[path withheld]"

    return path

def _safe_secret_rule(value: object) -> str:
    """
    Retain only recognized secret-scanner rule identifiers.
    """
    if isinstance(value, str) and value in SAFE_SECRET_RULES:
        return value

    return "[unrecognized rule]"


def _safe_analysis_rule(value: object) -> str:
    """
    Preserve conventional static-analysis rule identifiers.

    Reject excessively long, suspicious, or malformed values.
    """
    if not isinstance(value, str):
        return "[unrecognized rule]"

    if not 1 <= len(value) <= 128:
        return "[unrecognized rule]"

    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9._:/-]*", value):
        return "[unrecognized rule]"

    if _SENSITIVE_PATH_PART.search(value):
        return "[unrecognized rule]"

    if re.search(r"[A-Za-z0-9]{24,}", value):
        return "[unrecognized rule]"

    return value

# Curated guidance contains no scanner-controlled text.
_SEMGREP_GUIDANCE = {
    "Rule parse error": (
        "Check the referenced Semgrep rule syntax and configuration."
    ),
    "SemgrepError": (
        "Check Semgrep configuration and the scanner execution logs."
    ),
    "InvalidRuleSchemaError": (
        "Validate the Semgrep rule schema and required fields."
    ),
    "UnknownLanguageError": (
        "Check the language specified by the Semgrep rule."
    ),
    "LexicalError": "Check syntax near the reported file and line.",
    "ParseError": "Check syntax near the reported file and line.",
    "Timeout": "Check rule complexity and Semgrep timeout settings.",
    "OutOfMemory": "Check scanner memory limits and scan scope.",
    "invalid_error_entry": (
        "Semgrep returned a malformed scanner error entry."
    ),
}


def _safe_semgrep_error_type(value: object) -> str:
    if (
        isinstance(value, str)
        and 1 <= len(value) <= 64
        and value.isascii()
        and re.fullmatch(r"[A-Za-z0-9_-]+", value)
        and not _SENSITIVE_PATH_PART.search(value)
        and not re.search(r"[A-Za-z0-9]{24,}", value)
    ):
        return value

    return "unknown"


def _safe_semgrep_errors(value: object) -> list[dict]:
    if not isinstance(value, list):
        return []

    safe = []

    for entry in value[:200]:
        if not isinstance(entry, dict):
            entry = {}

        error_type = _safe_semgrep_error_type(entry.get("type"))

        safe.append({
            "type": error_type,
            "file": _safe_secret_finding_path(
                entry.get("file", "")
            ),
            "line": _safe_finding_line(
                entry.get("line", 0)
            ),
            "message": _SEMGREP_GUIDANCE.get(
                error_type,
                "Check the Semgrep execution logs for this error category.",
            ),
        })

    return safe


SAFE_SEVERITIES = frozenset({
    "critical",
    "high",
    "medium",
    "low",
    "info",
})


def _safe_finding_severity(value: object) -> str:
    """Return a recognized severity without publishing arbitrary text."""
    if isinstance(value, str) and value.lower() in SAFE_SEVERITIES:
        return value.lower()
    return "unknown"


def _safe_finding_line(value: object) -> int:
    """Return a valid positive line number or zero when unavailable."""
    if type(value) is int and 1 <= value <= 10_000_000:
        return value
    return 0


def _safe_severity_counts(value: object) -> dict:
    """Retain only recognized severity levels with safe integer counts."""
    if not isinstance(value, dict):
        return {}

    return {
        level: count
        for level, count in value.items()
        if level in SAFE_SEVERITIES
        and type(count) is int
        and 0 <= count <= 10_000_000
    }


def _safe_violation_count(value: object) -> int:
    """Return a safe nonnegative finding count."""
    if type(value) is int and 0 <= value <= 10_000_000:
        return value

    return 0


def prepare_diagnostic_document(evaluated_doc: dict) -> dict:
    """
    Prepare evaluated results for detailed diagnostic rendering.

    Parameters
    ----------
    evaluated_doc : dict
        Evaluated quality gate results containing normalized findings.

    Returns
    -------
    dict
        A separate document prepared for diagnostic reporting.
    """
    diagnostic_doc = deepcopy(evaluated_doc)

    for group_name in ("blocking_results", "non_blocking_results"):
        for result in diagnostic_doc.get(group_name, []):
            tool = str(result.get("tool", "")).lower()

            if tool not in CATEGORY_MAP:
                result["tool"] = "unknown"
            else:
                result["tool"] = tool

            if tool not in SECRET_SCANNERS:
                normalized = result.get("normalized", {})


                # Validate numeric values before HTML rendering.
                normalized["violation_count"] = _safe_violation_count(
                    normalized.get("violation_count", 0)
                )

                normalized["severity_counts"] = _safe_severity_counts(
                    normalized.get("severity_counts", {})
                )

                # Do not publish arbitrary scanner failure reasons.
                original_reason = result.get("reason", "")
                if original_reason not in ("", "disabled"):
                    result["reason"] = "scanner_runtime_error"

                # Preserve validated Semgrep error categories while
                # removing arbitrary scanner-provided metadata.
                original_metadata = normalized.get("metadata", {})
                safe_metadata = {}

                if normalized.get("runtime_error", False):
                    safe_metadata["error"] = (
                        "Scanner encountered a runtime error. "
                        "Review the GitHub Actions execution logs."
                    )

                    if tool == "semgrep" and isinstance(original_metadata, dict):
                        scanner_errors = original_metadata.get(
                            "scanner_errors", []
                        )

                        safe_metadata["scanner_errors"] = (
                            _safe_semgrep_errors(scanner_errors)
                        )

                normalized["metadata"] = safe_metadata

                # Scanner messages may contain source snippets or secrets.
                for finding in normalized.get("findings", []):
                    safe_finding = {
                        "severity": _safe_finding_severity(
                            finding.get("severity", "")
                        ),
                        "line": _safe_finding_line(
                            finding.get("line", 0)
                        ),
                        "file": _safe_secret_finding_path(
                            finding.get("file", "")
                        ),
                        "rule": _safe_analysis_rule(
                             finding.get("rule", "")
                        ),
                        "message": (
                            "Scanner finding detected. "
                            "Review the rule and source location for details."
                        ),
                        "rule_url": "",
                    }

                    finding.clear()
                    finding.update(safe_finding)

                continue

            # Scanner-provided reasons may contain credentials.
            # Preserve the failure category without its raw details.
            original_reason = result.get("reason", "")

            if original_reason not in ("", "disabled"):
                result["reason"] = "scanner_runtime_error"

            normalized = result.get("normalized", {})

            normalized["violation_count"] = _safe_violation_count(
                normalized.get("violation_count", 0)
            )

            normalized["severity_counts"] = _safe_severity_counts(
                normalized.get("severity_counts", {})
            )

            # Never publish raw scanner error descriptions.
            # Keep the runtime-error state for troubleshooting.
            if normalized.get("runtime_error", False):
                 normalized["metadata"] = {
                     "error": (
                         "Secret scanner encountered a runtime error. "
                         "Review the GitHub Actions execution logs."
                     )
                 }
            else:
                normalized["metadata"] = {}

            for finding in normalized.get("findings", []):
                # The secret-scanner parsers already exclude raw
                # credential values. Keep only the known-safe fields.
                safe_finding = {
                    "severity": _safe_finding_severity(
                        finding.get("severity", "")
                    ),
                    "line": _safe_finding_line(
                        finding.get("line", 0)
                    ),
                    "file": _safe_secret_finding_path(finding.get("file", "")),
                    "rule": _safe_secret_rule(finding.get("rule", "")),
                    "message": finding.get("message", ""),
                    "rule_url": "",
                }

                # Never trust the original message for secret scanners.
                # It could contain the credential itself.
                safe_finding["message"] = (
                    f"Potential credential detected. "
                    f"Secret value: {REDACTION_MARKER}"
                )

                finding.clear()
                finding.update(safe_finding)

    return diagnostic_doc
