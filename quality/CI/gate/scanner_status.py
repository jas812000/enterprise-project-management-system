"""
EPMS CI scanner execution-status handling.

Combines scanner execution status with normalized parser results.

Status files:
    quality/analysis/raw/<tool>.status.json

Supported statuses:
    completed
    failed
    disabled

Missing, malformed, or invalid status records fail closed.
"""

import json
from pathlib import Path


VALID_STATUSES = {"completed", "failed", "disabled"}


def apply_scanner_status(
    tool_name: str,
    result: dict,
    raw_dir: Path,
) -> dict:
    """
    Apply a scanner's execution status to its normalized result.

    Preserve existing findings and parser errors.

    A status record cannot override a parser failure or make
    an invalid report appear successful.
    """

    status_file = raw_dir / f"{tool_name}.status.json"

    metadata = result.setdefault("metadata", {})

    def mark_failed(reason: str) -> dict:
        result["runtime_error"] = True
        metadata["status"] = "failed"
        metadata["error"] = reason
        return result

    if not status_file.is_file():
        return mark_failed(
            f"Scanner status file missing: {status_file}"
        )

    try:
        data = json.loads(status_file.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeError) as error:
        return mark_failed(
            f"Unable to read scanner status: {error}"
        )

    if not isinstance(data, dict):
        return mark_failed("Scanner status must be a JSON object.")

    status = data.get("status")

    if status not in VALID_STATUSES:
        return mark_failed(
            f"Invalid scanner status: {status!r}"
        )

    exit_code = data.get("exit_code")
    reason = data.get("reason")

    if exit_code is not None:
        if type(exit_code) is not int:
            return mark_failed("Scanner exit_code must be an integer.")
        metadata["exit_code"] = exit_code

    if reason is not None:
        if not isinstance(reason, str):
            return mark_failed("Scanner reason must be a string.")
        metadata["reason"] = reason

    if status == "disabled":
        if result.get("runtime_error"):
            return mark_failed(
                "Disabled scanner has an existing normalization error."
            )

        if result.get("artifact_present") or result.get("executed"):
            return mark_failed(
                "Disabled scanner unexpectedly produced an executed result."
            )

        if not isinstance(reason, str) or not reason.strip():
            return mark_failed(
                "Disabled scanner must provide a reason."
            )

        result["executed"] = False
        result["runtime_error"] = False
        metadata["status"] = "disabled"
        return result

    if status == "failed":
        result["runtime_error"] = True
        metadata["status"] = "failed"
        metadata.setdefault(
            "error",
            reason or "Scanner execution failed.",
        )
        return result

    # Completed requires an actual successfully parsed artifact.
    if not result.get("artifact_present"):
        return mark_failed(
            "Scanner marked completed but its artifact is missing."
        )

    if not result.get("executed"):
        return mark_failed(
            "Scanner marked completed but parser reports not executed."
        )

    if result.get("runtime_error"):
        return mark_failed(
            "Scanner marked completed but parser reported an error."
        )

    metadata["status"] = "completed"
    return result
