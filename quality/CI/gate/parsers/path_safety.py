
"""
Scanner File Path Validation

Purpose
-------
Validate and normalize file paths reported by CI security
and static-analysis scanners.

Behavior
--------
- Accepts valid repository-relative paths.
- Converts absolute GitHub Actions workspace paths to relative paths.
- Rejects paths outside the configured workspace.
- Rejects directory traversal and unsupported characters.
- Rejects excessively long or potentially sensitive path components.
- Returns "unknown" when validation fails.
- Does not access or modify files.
"""

import os
import re
from pathlib import PurePosixPath


def _workspace_relative_path(value: object) -> str:
    """
    Validate and normalize a scanner-reported file path.

    Parameters
    ----------
    value : object
        Scanner-provided file path.

    Returns
    -------
    str
        Normalized repository-relative path or "unknown" when invalid.
    """
    if not isinstance(value, str) or not value or len(value) > 2048:
        return "unknown"

    # Reject control characters and unsupported path separators.
    if re.search(r"[\x00-\x1f\x7f\\]", value) or value.startswith("~"):
        return "unknown"

    path = value

    # Normalize harmless scanner-generated /./ segments, including
    # GitHub Actions paths ending in /./quality/... . Never normalize
    # traversal (/../), which remains rejected below.
    if "/../" in path or path.endswith("/.."):
        return "unknown"
    # Keep relative dot segments invalid; only absolute workspace paths
    # are canonicalized by os.path.normpath below.

    # Convert absolute workspace paths to repository-relative paths.
    if path.startswith("/"):
        workspace = os.environ.get("GITHUB_WORKSPACE", "")

        if not workspace or not workspace.startswith("/"):
            return "unknown"

        root = os.path.normpath(workspace)
        candidate = os.path.normpath(path)

        if not candidate.startswith(root + "/"):
            return "unknown"

        path = candidate[len(root) + 1:]

    return path


def _validate_relative_path(path: str) -> str:
    """Validate repository-relative path components."""
    # Reject traversal, empty components, and dot components.
    if any(part in ("", ".", "..") for part in path.split("/")):
        return "unknown"

    normalized = str(PurePosixPath(path))

    if (
        normalized in ("", ".")
        or normalized.startswith("/")
        or len(normalized) > 512
    ):
        return "unknown"

    # Validate repository path characters.
    if not all(
        re.fullmatch(r"[A-Za-z0-9._-]+", part)
        for part in normalized.split("/")
    ):
        return "unknown"

    # Long snake_case filenames are normal (e.g. test_report_diagnostics.py).
    # Reject credential-like opaque components, not ordinary long names.
    if any(
        re.search(r"[A-Za-z0-9]{40,}", part)
        or re.search(r"(?:SYNTHETIC_SECRET|TEST_CREDENTIAL)", part, re.I)
        for part in normalized.split("/")
    ):
        return "unknown"

    # Reject path components that may contain credential identifiers.
    if any(
        re.search(
            r"(?:api[_-]?key|api[_-]?token|access[_-]?token|"
            r"secret[_-]?key|private[_-]?key)",
            part,
            re.IGNORECASE,
        )
        for part in normalized.split("/")
    ):
        return "unknown"

    return normalized


def safe_finding_path(value: object) -> str:
    """Return a validated repository-relative scanner path or unknown."""
    path = _workspace_relative_path(value)
    if path == "unknown":
        return path
    return _validate_relative_path(path)
