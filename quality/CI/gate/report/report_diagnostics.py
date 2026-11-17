"""
EPMS Quality Gate Diagnostic Report Preparation

Purpose
-------
Prepare publication-safe diagnostic findings from evaluated
quality gate results for detailed HTML reporting.

Security Requirements
---------------------
- Exclude raw secret-scanner artifacts and credential values.
- Preserve validated file locations, rule identifiers, and line numbers.
- Replace scanner-controlled messages with approved guidance.
- Explicitly redact detected secret values.
- Preserve scanner error categories for troubleshooting.

Behavior
--------
- Creates a deep copy of the evaluated results.
- Validates finding fields and scanner metadata.
- Separates diagnostic information from the summary report.
- Retains safe remediation information for developers.
- Does not modify the original evaluated document.
"""

from copy import deepcopy
import re

from quality.shared.severity import TRUFFLEHOG_DETECTORS

from quality.CI.gate.parsers.path_safety import safe_finding_path
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
    *TRUFFLEHOG_DETECTORS,
    "TruffleHog",
})

_SAFE_PATH_PART = re.compile(r"^[A-Za-z0-9._-]+$")
_SENSITIVE_PATH_PART = re.compile(
    r"(?i)(secret|token|password|credential|api[_-]?key)"
)


def _safe_secret_finding_path(value: object) -> str:
    """
    Validate file paths before including them in diagnostic reports.

    Parameters
    ----------
    value : object
        Scanner-reported file path.

    Returns
    -------
    str
        Validated repository-relative path or "[path withheld]".
    """
    path = safe_finding_path(value)

    if path == "unknown":
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



def _valid_semgrep_error_type(value: object) -> bool:
    """Check the full Semgrep error category validation contract."""
    return (
        isinstance(value, str)
        and 1 <= len(value) <= 64
        and value.isascii()
        and re.fullmatch(r"[A-Za-z0-9_ -]+", value)
        and not _SENSITIVE_PATH_PART.search(value)
        and not re.search(r"[A-Za-z0-9]{24,}", value)
    )


def _safe_semgrep_error_type(value: object) -> str:
    """
    Validate a Semgrep scanner error category.

    Parameters
    ----------
    value : object
        Scanner-provided error category.

    Returns
    -------
    str
        Validated error category or "unknown".
    """
    if _valid_semgrep_error_type(value):
        return value

    return "unknown"


def _safe_semgrep_errors(value: object) -> list[dict]:
    """
    Prepare Semgrep scanner errors for diagnostic reporting.

    Parameters
    ----------
    value : object
        Scanner error records.

    Returns
    -------
    list[dict]
        Validated error categories, file locations, line numbers,
        and approved troubleshooting guidance.
    """
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

SAFE_POLICY_REASONS = frozenset({
    "disabled",
    "finding_present",
    "severity_high_and_above",
    "scanner_runtime_error",
    "artifact_missing",
    "threshold_exceeded",
    "severity_threshold_exceeded",
    "violation_threshold_exceeded",
})


def _safe_policy_reason(value: object) -> str:
    """
    Preserve recognized policy reasons without exposing scanner text.

    Parameters
    ----------
    value : object
        Quality gate evaluation reason.

    Returns
    -------
    str
        Recognized policy reason or "scanner_runtime_error".
    """
    if value in ("", None):
        return ""

    if isinstance(value, str) and value in SAFE_POLICY_REASONS:
        return value

    return "scanner_runtime_error"


# Diagnostic messages must be curated, not arbitrary scanner-controlled text.
# A scanner message can contain source snippets or credentials, so do not
# interpolate it into published HTML. Raw reports remain local and must not
# be included in distributable ZIP archives.

_PYLINT_GUIDANCE = {
    "import-error": (
        "Python module import failed. Check package structure, "
        "PYTHONPATH, and installed dependencies; inspect raw/pylint.json "
        "for the exact module."
    ),
    "missing-function-docstring": (
        "Add a docstring explaining the function's purpose, "
        "parameters, and return value."
    ),
    "missing-class-docstring": (
        "Add a class docstring explaining its purpose and responsibilities."
    ),
    "duplicate-code": (
        "Pylint found repeated code in multiple modules. "
        "Inspect raw/pylint.json for the matching files and line ranges; "
        "extract a shared helper if appropriate."
    ),
    "wrong-import-order": (
        "Reorder imports into standard-library, third-party, "
        "and first-party groups as required by Pylint."
    ),
    "ungrouped-imports": (
        "Group imports from the same package together."
    ),
    "import-outside-toplevel": (
        "Move this import to module scope unless deferred importing "
        "is intentional (for example, to avoid a cycle)."
    ),
    "unidiomatic-typecheck": (
        "Prefer isinstance(value, ExpectedType) to type(value) "
        "comparisons, unless exact-type semantics are required."
    ),
    "unused-import": (
        "An imported module or name is not used. "
        "Remove the unused import or use it where intended."
    ),
    "unused-argument": (
        "A function parameter is unused. Remove it when compatible "
        "with the API, or document why it is required."
    ),
    "unused-variable": (
        "A local variable is assigned but never used. "
        "Remove it or use the intended result."
    ),
    "too-many-locals": (
        "This function has too many local variables. "
        "Extract cohesive helper functions or simplify intermediate state."
    ),
    "too-many-return-statements": (
        "Consider simplifying the function's control flow or "
        "extracting helper functions to reduce return branches."
    ),
    "too-many-branches": (
        "Reduce branching complexity by extracting smaller "
        "functions or simplifying conditions."
    ),
    "too-many-boolean-expressions": (
        "Simplify the conditional expression, for example "
        "by extracting a named predicate."
    ),
    "too-many-public-methods": (
        "This class exposes many public methods. "
        "Consider separating unrelated responsibilities."
    ),
    "broad-exception-caught": (
        "Catch specific exception types rather than "
        "a broad Exception where possible."
    ),
    "consider-using-with": (
        "Use a context manager (with statement) to ensure "
        "resources are released correctly."
    ),
    "invalid-name": (
        "Rename the identifier to follow the project's "
        "Pylint naming conventions, if compatibility permits."
    ),
    "redefined-outer-name": (
        "A local name shadows a name in an outer scope. "
        "Rename or remove the conflicting binding."
    ),
    "reimported": (
        "The module or symbol is imported more than once. "
        "Remove the redundant import."
    ),
    "bad-indentation": (
        "Correct indentation to match the surrounding Python block."
    ),
    "line-too-long": (
        "Wrap this line to meet the configured maximum line length."
    ),
    "missing-final-newline": (
        "Add a newline at the end of the file."
    ),
}

_BANDIT_GUIDANCE = {
    "B405": (
        "Potentially unsafe xml.etree.ElementTree import. "
        "Verify untrusted XML is parsed using defusedxml; "
        "remove unnecessary standard-library XML imports when possible."
    ),
    "B105": (
        "Possible hardcoded credential. Replace real secrets with "
        "environment or secret-manager values; for synthetic tests, "
        "construct safe fixtures without embedding credential-like literals."
    ),
}



def _analysis_guidance(tool: str, rule: object) -> str:
    """Return safe, rule-specific remediation guidance."""
    if not isinstance(rule, str):
        rule = ""
    if tool == "pylint":
        return _PYLINT_GUIDANCE.get(
            rule,
            "An imported module or name is not used. "
            "Remove the unused import or use it where intended.",
        )
    if tool == "bandit":
        return _BANDIT_GUIDANCE.get(
            rule,
            "Inspect the Bandit rule and raw/bandit.json; apply the recommended "
            "secure coding practice after checking the actual code path.",
        )
    if tool == "semgrep":
        return (
            "Review the flagged security pattern, affected code, "
            "and Semgrep rule guidance."
        )
    return "Inspect the scanner rule and corresponding raw artifact for the specific issue."


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
    if isinstance(value, int) and not isinstance(value, bool) and 1 <= value <= 10_000_000:
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
        and isinstance(count, int) and not isinstance(count, bool)
        and 0 <= count <= 10_000_000
    }


def _safe_violation_count(value: object) -> int:
    """Return a safe nonnegative finding count."""
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 10_000_000:
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

                # Preserve recognized policy reasons and exclude unvalidated details.
                result["reason"] = _safe_policy_reason(result.get("reason", ""))

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
                        "message": _analysis_guidance(
                            tool,
                            finding.get("rule", ""),
                        ),
                        "rule_url": "",
                    }

                    finding.clear()
                    finding.update(safe_finding)

                continue

            # Preserve recognized policy reasons and exclude unvalidated details.
            result["reason"] = _safe_policy_reason(result.get("reason", ""))

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
