
"""Shared source-file discovery for EPMS local scanners."""

from pathlib import Path


EXCLUDED_DIRECTORIES = {
    ".git",
    ".venv",
    "venv",
    "node_modules",
    "__pycache__",
    ".dart_tool",
    "build",
    "dist",
    "target",
}

EXCLUDED_PATH_PREFIXES = (
    ("quality", "Local_Scans", "reports"),
    ("quality", "Local_Scans", "tools"),
    ("quality", "analysis"),
)


def eligible_files(repo_root, suffixes):
    """Return source files, excluding generated artifacts."""
    root = Path(repo_root).resolve()
    extensions = {suffix.lower() for suffix in suffixes}

    files = []

    for path in root.rglob("*"):
        if not path.is_file():
            continue

        relative = path.relative_to(root)
        parts = relative.parts

        if any(part in EXCLUDED_DIRECTORIES for part in parts[:-1]):
            continue

        if any(
            parts[:len(prefix)] == prefix
            for prefix in EXCLUDED_PATH_PREFIXES
        ):
            continue

        if path.suffix.lower() in extensions:
            files.append(str(path))

    return sorted(files)
