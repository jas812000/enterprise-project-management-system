
"""Run the local Gitleaks filesystem scan."""

import json
import shutil
import subprocess  # nosec B404 - required for fixed-argument scanner execution
from pathlib import Path

from quality.Local_Scans.checks.scanner_runner import write_status


def run(repo_root, work_dir, binary="gitleaks"):
    """Scan for secrets using CI-compatible settings."""
    root = Path(repo_root).resolve()
    work = Path(work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)

    output = work / "gitleaks.json"
    output.unlink(missing_ok=True)

    # Local configuration extends the default Gitleaks rules
    # and excludes generated quality-gate artifacts.
    config = (
        root
        / "quality"
        / "Local_Scans"
        / "config"
        / "gitleaks.toml"
    )

    # A missing configuration is an execution error.
    # Do not silently scan with different settings.
    if not config.is_file():
        write_status(
            work,
            "gitleaks",
            "failed",
            reason="Gitleaks configuration file unavailable",
        )
        return

    # Resolve executable explicitly; never invoke a shell.
    executable = shutil.which(binary)
    if executable is None:
        write_status(work, "gitleaks", "failed", reason="Gitleaks executable unavailable")
        return

    command = [
        executable,
        "detect",
        "--source", str(root),
        "--config", str(config),
        "--report-format", "json",
        "--report-path", str(output),
        "--no-git",
        "--exit-code", "10",
        "--redact",
    ]

    try:
        completed = subprocess.run(  # nosec B603 - fixed argv; shell=False
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )

        exit_code = completed.returncode

        valid = False

        if exit_code in (0, 10) and output.is_file():
            try:
                data = json.loads(
                    output.read_text(encoding="utf-8")
                )

                # Gitleaks may produce null when no secrets
                # are detected. Normalize that to an empty
                # list for compatibility with the CI parser.
                if data is None:
                    data = []

                valid = (
                    isinstance(data, list)
                    and all(
                        isinstance(item, dict)
                        for item in data
                    )
                )

                if valid:
                    # Preserve a valid JSON array artifact.
                    output.write_text(
                        json.dumps(data, indent=2),
                        encoding="utf-8",
                    )

            except (OSError, ValueError, UnicodeError):
                valid = False

        if valid:
            write_status(
                work,
                "gitleaks",
                "completed",
                exit_code,
            )
            return

        output.unlink(missing_ok=True)

        write_status(
            work,
            "gitleaks",
            "failed",
            exit_code,
            "Gitleaks execution failed or report was invalid",
        )

    except OSError:
        output.unlink(missing_ok=True)

        write_status(
            work,
            "gitleaks",
            "failed",
            reason="Gitleaks executable unavailable",
        )



if __name__ == "__main__":
    import sys

    if len(sys.argv) not in (3, 4):
        raise SystemExit(
            "Usage: python -m "
            "quality.Local_Scans.checks.check_gitleaks "
            "REPO_ROOT WORK_DIR [GITLEAKS_BINARY]"
        )

    if len(sys.argv) == 3:
        run(sys.argv[1], sys.argv[2])
    else:
        run(sys.argv[1], sys.argv[2], sys.argv[3])
