
"""Execute the six additional BN1 scanners.

Raw artifacts are stored outside the repository and report ZIP.
"""

import argparse
import json
from pathlib import Path

from quality.Local_Scans.checks import (
    check_gitleaks,
    check_htmlhint,
    check_python_scanners,
    check_stylelint,
    check_trufflehog,
)


def run(repo_root: Path, raw_dir: Path, tools_dir: Path) -> None:
    """Run each scanner independently and preserve execution status."""
    raw_dir.mkdir(parents=True, exist_ok=True)

    groups = (
        (("pylint",), lambda: check_python_scanners.run_pylint(repo_root, raw_dir)),
        (("bandit",), lambda: check_python_scanners.run_bandit(repo_root, raw_dir)),
        (("htmlhint",), lambda: check_htmlhint.run(repo_root, raw_dir)),
        (("stylelint",), lambda: check_stylelint.run(repo_root, raw_dir, tools_dir)),
        (("gitleaks",), lambda: check_gitleaks.run(repo_root, raw_dir)),
        (("trufflehog",), lambda: check_trufflehog.run(repo_root, raw_dir)),
    )

    for names, execute in groups:
        name = names[0]
        display = {
            "htmlhint": "HTMLHint", "gitleaks": "Gitleaks",
            "trufflehog": "TruffleHog", "stylelint": "Stylelint",
            "pylint": "Pylint", "bandit": "Bandit",
        }[name]
        print(f"\n--- {display} ---", flush=True)
        try:
            execute()
        except Exception:  # pylint: disable=broad-except
            for name in names:
                (raw_dir / f"{name}.json").unlink(missing_ok=True)
                (raw_dir / f"{name}.jsonl").unlink(missing_ok=True)

                status = {
                    "status": "failed",
                    "reason": "Unexpected local scanner error",
                }
                (raw_dir / f"{name}.status.json").write_text(
                    json.dumps(status),
                    encoding="utf-8",
                )

        for name in names:
            status_file = raw_dir / f"{name}.status.json"

            if not status_file.exists():
                status_file.write_text(
                    json.dumps({
                        "status": "failed",
                        "reason": "Scanner produced no status",
                    }),
                    encoding="utf-8",
                )

            try:
                status = json.loads(
                    status_file.read_text(encoding="utf-8")
                ).get("status", "failed")
            except (OSError, ValueError):
                status = "failed"

            display_name = {
                "pylint": "Pylint",
                "bandit": "Bandit",
                "htmlhint": "HTMLHint",
                "stylelint": "Stylelint",
                "gitleaks": "Gitleaks",
                "trufflehog": "TruffleHog",
            }[name]
            print(f"{display_name}: {status}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("repo_root", type=Path)
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("tools_dir", type=Path)
    arguments = parser.parse_args()

    run(
        arguments.repo_root.resolve(),
        arguments.raw_dir.resolve(),
        arguments.tools_dir.resolve(),
    )
