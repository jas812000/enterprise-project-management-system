
"""Shared execution helpers for advisory local scanners."""

import json
import subprocess  # nosec B404 - required for fixed-argument scanner execution
from pathlib import Path
from typing import NamedTuple, Callable, Any


def run_scanner_to_file(command, output: Path, *, env=None):
    """Execute a fixed scanner argv, streaming stdout to an artifact."""
    with output.open("w", encoding="utf-8") as stream:
        return subprocess.run(  # nosec B603 - scanner argv; shell=False
            command, stdout=stream, stderr=subprocess.DEVNULL,
            env=env, check=False,
        )


def write_status(work_dir, name, status, exit_code=None, reason=None):
    """Write a CI-compatible scanner execution status."""
    data = {"status": status}
    if exit_code is not None:
        data["exit_code"] = exit_code
    if reason is not None:
        data["reason"] = reason

    path = Path(work_dir) / f"{name}.status.json"
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


class ScannerValidation(NamedTuple):
    """Scanner-specific accepted exit codes, validator and environment."""

    valid_exit_codes: set[int]
    validator: Callable[[Any], bool]
    env: dict[str, str] | None = None


def run_json_scanner(
    name,
    command,
    work_dir,
    artifact,
    validation: ScannerValidation,
):
    """Execute scanner and validate its JSON output."""
    work_dir = Path(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    output = work_dir / artifact

    output.unlink(missing_ok=True)
    (work_dir / f"{name}.status.json").unlink(missing_ok=True)

    try:
        completed = run_scanner_to_file(command, output, env=validation.env)

        exit_code = completed.returncode

        valid = False
        if exit_code in validation.valid_exit_codes and output.stat().st_size:
            try:
                data = json.loads(output.read_text(encoding="utf-8"))
                valid = validation.validator(data)
            except (OSError, ValueError, TypeError):
                pass

        if valid:
            write_status(work_dir, name, "completed", exit_code)
            return True

        output.unlink(missing_ok=True)
        write_status(
            work_dir,
            name,
            "failed",
            exit_code,
            "Scanner execution failed or its JSON report is invalid",
        )
        return False

    except (OSError, subprocess.SubprocessError):
        output.unlink(missing_ok=True)
        write_status(
            work_dir,
            name,
            "failed",
            reason="Scanner executable unavailable or execution failed",
        )
        return False
