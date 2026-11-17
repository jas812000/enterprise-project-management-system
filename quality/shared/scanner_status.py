"""Shared, fail-closed status probe for CI and local normalization."""

import json
from pathlib import Path


def scanner_is_disabled(status_file: Path) -> bool:
    """Return True only for a readable status object marked disabled.

    Full validation of disabled reasons and artifact invariants remains
    the responsibility of CI's apply_scanner_status function.
    """
    if not status_file.is_file():
        return False
    try:
        status_data = json.loads(status_file.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeError):
        return False
    return isinstance(status_data, dict) and status_data.get("status") == "disabled"
