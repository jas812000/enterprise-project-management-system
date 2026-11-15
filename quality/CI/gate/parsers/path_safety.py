"""
File path validation for secret scanner findings.

Accept repository-relative POSIX paths while rejecting
malformed, absolute, or traversal paths.
"""

import re
from pathlib import PurePosixPath


def safe_finding_path(value: object) -> str:
    """
    Return a safe repository-relative path or 'unknown'.

    This validates path structure, not whether a filename
    contains sensitive information.
    """
    if not isinstance(value, str):
        return "unknown"

    if not value or len(value) > 512:
        return "unknown"

    if re.search(r"[\x00-\x1f\x7f]", value):
        return "unknown"

    if "\\" in value:
        return "unknown"

    if value.startswith(("/", "~")):
        return "unknown"

    path = PurePosixPath(value)

    if path.is_absolute():
        return "unknown"

    if any(part == ".." for part in value.split("/")):
        return "unknown"

    if not path.parts or str(path) == ".":
        return "unknown"

    return str(path)
