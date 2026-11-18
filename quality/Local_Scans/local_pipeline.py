
"""Integrate the nine EPMS BN1 scanners.

Preserve raw artifacts and use the CI normalization schema.
BN1 remains advisory.
"""

import argparse
import json
import os
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory

from quality.Local_Scans.checks.run_additional_scanners import (
    run as run_additional,
)
from quality.Local_Scans.local_normalize import (
    normalize_local,
    summarize_local,
    format_scanner_outcome,
)


JAVA_SCANNERS = {
    "checkstyle": ("checkstyle.xml", "CS_STATUS"),
    "pmd": ("pmd.xml", "PMD_STATUS"),
    "spotbugs": ("spotbugs.xml", "SB_STATUS"),
}


def _stage_java_results(work_dir: Path, staging: Path, java_statuses: dict) -> None:
    """Stage Java scanner artifacts and execution status records."""
    for name, (artifact, env_name) in JAVA_SCANNERS.items():
        source = work_dir / artifact
        status = java_statuses[env_name]

        if source.is_file():
            shutil.copy2(source, staging / artifact)

        if status in ("passed", "failed") and source.is_file():
            record = {
                "status": "completed",
                "exit_code": 0 if status == "passed" else 1,
            }
        else:
            record = {
                "status": "failed",
                "reason": "Java scanner execution failed",
            }

        (staging / f"{name}.status.json").write_text(
            json.dumps(record),
            encoding="utf-8",
        )


def integrate(
    repo_root: Path,
    work_dir: Path,
    reports_dir: Path,
    tools_dir: Path,
    java_statuses: dict,
) -> dict:
    """Run six scanners and combine them with Java results."""
    repo_root = repo_root.resolve()
    work_dir = work_dir.resolve()
    reports_dir = reports_dir.resolve()
    tools_dir = tools_dir.resolve()

    raw_dir = reports_dir / "raw"
    normalized_dir = reports_dir / "normalized"
    normalized_file = normalized_dir / "normalized.json"

    # Remove old raw artifacts before starting another scan.
    if raw_dir.exists():
        shutil.rmtree(raw_dir)

    raw_dir.mkdir(parents=True)
    normalized_dir.mkdir(parents=True, exist_ok=True)

    # Stage raw scanner output outside the repository during
    # execution. This prevents scanning newly generated secrets.
    with TemporaryDirectory(prefix="quality-bn1-") as temporary:
        staging = Path(temporary)

        run_additional(repo_root, staging, tools_dir)

        _stage_java_results(work_dir, staging, java_statuses)

        # Reuse the existing CI parsers and status validation.
        document = normalize_local(staging, normalized_file)

        # Report actual normalized outcomes, not merely execution completion.
        additional = {
            "pylint", "bandit", "htmlhint", "stylelint",
            "gitleaks", "trufflehog",
        }
        for result in document["results"]:
            if result["tool"] in additional:
                display_name = {
                    "pylint": "Pylint",
                    "bandit": "Bandit",
                    "htmlhint": "HTMLHint",
                    "stylelint": "Stylelint",
                    "gitleaks": "Gitleaks",
                    "trufflehog": "TruffleHog",
                }[result["tool"]]
                print(f"\n--- {display_name} ---", flush=True)
                print(format_scanner_outcome(result), flush=True)

        # Retain the complete raw artifacts for local inspection.
        for artifact in staging.iterdir():
            if artifact.is_file():
                shutil.copy2(artifact, raw_dir / artifact.name)

    # Add normalized results to the existing ZIP input.
    shutil.copy2(normalized_file, work_dir / "normalized.json")

    summarize_local(document)
    return document


def main():
    """Run integration using the existing BN1 environment."""
    parser = argparse.ArgumentParser()
    parser.add_argument("repo_root", type=Path)
    parser.add_argument("work_dir", type=Path)
    parser.add_argument("reports_dir", type=Path)
    parser.add_argument("tools_dir", type=Path)
    args = parser.parse_args()

    integrate(
        args.repo_root,
        args.work_dir,
        args.reports_dir,
        args.tools_dir,
        {
            "CS_STATUS": os.environ["CS_STATUS"],
            "PMD_STATUS": os.environ["PMD_STATUS"],
            "SB_STATUS": os.environ["SB_STATUS"],
        },
    )


if __name__ == "__main__":
    main()
