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


def _validate_status_data(data: object) -> str | None:
    """Return a validation diagnostic for malformed scanner status data."""
    if not isinstance(data, dict):
        return "Scanner status must be a JSON object."
    if data.get("status") not in VALID_STATUSES:
        return f"Invalid scanner status: {data.get('status')!r}"
    exit_code = data.get("exit_code")
    if exit_code is not None and (
        not isinstance(exit_code, int) or isinstance(exit_code, bool)
    ):
        return "Scanner exit_code must be an integer."
    reason = data.get("reason")
    if reason is not None and not isinstance(reason, str):
        return "Scanner reason must be a string."
    return None


def _read_scanner_status(status_file: Path) -> tuple[dict | None, str | None]:
    """Load scanner status, returning a diagnostic instead of raising."""
    data = None
    error = None
    if not status_file.is_file():
        error = f"Scanner status file missing: {status_file}"
    else:
        try:
            data = json.loads(status_file.read_text(encoding="utf-8"))
        except (OSError, ValueError, UnicodeError) as exception:
            error = f"Unable to read scanner status: {exception}"
        if error is None:
            error = _validate_status_data(data)
    return (data if error is None else None), error


def _apply_disabled_status(result: dict, metadata: dict, reason: object) -> str | None:
    """Validate a disabled scanner without discarding parser errors."""
    if result.get("runtime_error"):
        return "Disabled scanner has an existing normalization error."
    if result.get("artifact_present") or result.get("executed"):
        return "Disabled scanner unexpectedly produced an executed result."
    if not isinstance(reason, str) or not reason.strip():
        return "Disabled scanner must provide a reason."
    result["executed"] = False
    result["runtime_error"] = False
    metadata["status"] = "disabled"
    return None


def _completed_status_error(result: dict) -> str | None:
    """Check that a completed scanner has a successfully parsed artifact."""
    if not result.get("artifact_present"):
        return "Scanner marked completed but its artifact is missing."
    if not result.get("executed"):
        return "Scanner marked completed but parser reports not executed."
    if result.get("runtime_error"):
        return "Scanner marked completed but parser reported an error."
    return None


def apply_scanner_status(tool_name: str, result: dict, raw_dir: Path) -> dict:
    """Apply scanner status without masking normalization failures."""
    metadata = result.setdefault("metadata", {})
    data, error = _read_scanner_status(raw_dir / f"{tool_name}.status.json")
    if error is None:
        status = data["status"]
        exit_code = data.get("exit_code")
        reason = data.get("reason")
        if exit_code is not None:
            metadata["exit_code"] = exit_code
        if reason is not None:
            metadata["reason"] = reason

        if status == "disabled":
            error = _apply_disabled_status(result, metadata, reason)
        elif status == "failed":
            result["runtime_error"] = True
            metadata["status"] = "failed"
            metadata.setdefault("error", reason or "Scanner execution failed.")
        else:
            error = _completed_status_error(result)
            if error is None:
                metadata["status"] = "completed"

    if error is not None:
        result["runtime_error"] = True
        metadata["status"] = "failed"
        metadata["error"] = error
    return result
