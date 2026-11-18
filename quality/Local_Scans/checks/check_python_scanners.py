
"""Run Pylint and Bandit using the EPMS CI scan conventions."""

import os
import sys
from pathlib import Path

from quality.Local_Scans.checks.scanner_runner import (
    run_json_scanner,
    ScannerValidation,
    write_status,
)
from quality.Local_Scans.checks.scan_files import eligible_files


def python_files(repo_root):
    """Collect Python source files using shared exclusions."""
    return eligible_files(repo_root, {".py"})


def run_selected(repo_root, work_dir, scanner):
    """Run the selected Python scanner and record its status."""
    if scanner not in ("pylint", "bandit"):
        raise ValueError(f"Unsupported scanner: {scanner}")
    repo_root = Path(repo_root).resolve()
    work_dir = Path(work_dir)
    files = python_files(repo_root)

    if not files:
        work_dir.mkdir(parents=True, exist_ok=True)

        for name in (scanner,):
            (work_dir / f"{name}.json").unlink(missing_ok=True)
            write_status(
                work_dir,
                name,
                "disabled",
                reason="No eligible Python files were found",
            )
        return

    environment = os.environ.copy()
    environment["PYTHONPATH"] = (
        str(repo_root)
        + os.pathsep
        + environment.get("PYTHONPATH", "")
    )

    def valid_pylint(data):
        return (
            isinstance(data, list)
            and all(isinstance(item, dict) for item in data)
            and not any(
                item.get("type") == "fatal"
                for item in data
            )
        )

    def valid_bandit(data):
        return (
            isinstance(data, dict)
            and isinstance(data.get("results"), list)
            and all(
                isinstance(item, dict)
                for item in data["results"]
            )
            and isinstance(data.get("errors", []), list)
            and not data.get("errors")
        )

    if scanner == "pylint":
        run_json_scanner(
            "pylint",
            [
                sys.executable,
                "-m",
                "pylint",
                *files,
                f"--rcfile={repo_root / '.pylintrc'}",
                "--output-format=json",
            ],
            work_dir,
            "pylint.json",
            ScannerValidation(set(range(32)), valid_pylint, environment),
        )
    else:
        run_json_scanner(
            "bandit",
            [sys.executable, "-m", "bandit", "-f", "json", *files],
            work_dir, "bandit.json", ScannerValidation({0, 1}, valid_bandit, environment),
        )


def run_pylint(repo_root, work_dir):
    """Run Pylint independently."""
    run_selected(repo_root, work_dir, "pylint")


def run_bandit(repo_root, work_dir):
    """Run Bandit independently."""
    run_selected(repo_root, work_dir, "bandit")


def run(repo_root, work_dir):
    """Run both scanners for backward compatibility."""
    run_pylint(repo_root, work_dir)
    run_bandit(repo_root, work_dir)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(
            "Usage: python -m "
            "quality.Local_Scans.checks.check_python_scanners "
            "REPO_ROOT WORK_DIR"
        )

    run(sys.argv[1], sys.argv[2])
