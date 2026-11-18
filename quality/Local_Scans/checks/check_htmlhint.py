
"""Run HTMLHint with CI-compatible output and validation."""

from pathlib import Path

from quality.Local_Scans.checks.scanner_runner import (
    run_json_scanner,
    ScannerValidation,
    write_status,
)
from quality.Local_Scans.checks.scan_files import eligible_files


def run(repo_root, work_dir):
    """Scan eligible HTML files and record execution status."""
    repo_root = Path(repo_root).resolve()
    work_dir = Path(work_dir)

    files = eligible_files(repo_root, {".html"})

    if not files:
        work_dir.mkdir(parents=True, exist_ok=True)
        (work_dir / "htmlhint.json").unlink(missing_ok=True)
        write_status(
            work_dir,
            "htmlhint",
            "disabled",
            reason="No eligible HTML files were found",
        )
        return

    def valid_htmlhint(data):
        return (
            isinstance(data, list)
            and all(
                isinstance(item, dict)
                and isinstance(item.get("messages"), list)
                and all(
                    isinstance(message, dict)
                    for message in item["messages"]
                )
                for item in data
            )
        )

    run_json_scanner(
        "htmlhint",
        ["npx", "--yes", "htmlhint", *files, "--format", "json"],
        work_dir,
        "htmlhint.json",
        ScannerValidation({0, 1}, valid_htmlhint),
    )



if __name__ == "__main__":
    from quality.Local_Scans.checks.cli import invoke_scanner

    invoke_scanner(run, "quality.Local_Scans.checks.check_htmlhint")
