"""Common command-line entry point for local scanner modules."""

import sys


def invoke_scanner(run, module_name: str) -> None:
    """Validate the two required paths before invoking a scanner."""

    if len(sys.argv) != 3:
        raise SystemExit(f"Usage: python -m {module_name} REPO_ROOT WORK_DIR")
    run(sys.argv[1], sys.argv[2])
