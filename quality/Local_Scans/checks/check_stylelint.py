
"""Run Stylelint using the EPMS CI configuration."""

import shutil
import subprocess  # nosec B404 - required for fixed-argument scanner execution
from pathlib import Path

from quality.Local_Scans.checks.scanner_runner import (
    run_json_scanner,
    ScannerValidation,
    write_status,
)
from quality.Local_Scans.checks.scan_files import eligible_files


def run(repo_root, work_dir, tools_dir):
    """Scan CSS and SCSS files with Stylelint."""
    root = Path(repo_root).resolve()
    work = Path(work_dir).resolve()
    tools = Path(tools_dir).resolve() / "stylelint"

    work.mkdir(parents=True, exist_ok=True)

    files = eligible_files(root, {".css", ".scss"})

    if not files:
        (work / "stylelint.json").unlink(missing_ok=True)
        write_status(
            work,
            "stylelint",
            "disabled",
            reason="No eligible CSS or SCSS files were found",
        )
        return

    tools.mkdir(parents=True, exist_ok=True)
    executable = tools / "node_modules" / ".bin" / "stylelint"
    config = tools / "stylelint.config.cjs"

    if not executable.is_file():
        try:
            npm = shutil.which("npm")
            if npm is None:
                raise OSError("npm executable unavailable")
            result = subprocess.run(  # nosec B603 - fixed npm install argv
                [
                    npm, "install",
                    "--prefix", str(tools),
                    "--no-save",
                    "--no-audit",
                    "--no-fund",
                    "stylelint",
                    "stylelint-config-standard",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        except OSError:
            result = None

        if result is None or result.returncode != 0:
            write_status(
                work,
                "stylelint",
                "failed",
                reason="Stylelint installation failed",
            )
            return

    config.write_text(
        "module.exports = { "
        "extends: ['stylelint-config-standard'] "
        "};\n",
        encoding="utf-8",
    )

    def valid_report(data):
        return (
            isinstance(data, list)
            and all(
                isinstance(item, dict)
                and isinstance(item.get("warnings"), list)
                and not item.get("parseErrors")
                and not item.get("invalidOptionWarnings")
                and not item.get("deprecations")
                for item in data
            )
        )

    completed = run_json_scanner(
        "stylelint",
        [
            str(executable),
            *files,
            "--config", str(config),
            "--formatter", "json",
        ],
        work,
        "stylelint.json",
        ScannerValidation({0, 2}, valid_report),
    )

    print(
        "Stylelint execution:",
        "completed" if completed else "error",
    )


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 4:
        raise SystemExit(
            "Usage: python -m "
            "quality.Local_Scans.checks.check_stylelint "
            "REPO_ROOT WORK_DIR TOOLS_DIR"
        )

    run(sys.argv[1], sys.argv[2], sys.argv[3])
