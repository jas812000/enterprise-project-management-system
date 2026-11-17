
"""Run the EPMS TruffleHog filesystem scan."""

import json
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory

from quality.Local_Scans.checks.scanner_runner import run_scanner_to_file, write_status


IMAGE = "trufflesecurity/trufflehog:3.82.6"


def _stage_source(root: Path, temporary: str) -> tuple[Path, Path]:
    """Create a filtered scan snapshot and exclusion rules."""
    staging = Path(temporary) / "source"
    excluded_dirs = {
        ".git", ".venv", "venv", "node_modules", "__pycache__",
        "target", "build", "dist", ".pytest_cache",
    }

    def ignore_generated(directory, names):
        relative = Path(directory).relative_to(root)
        excluded = {name for name in names if name in excluded_dirs}
        if relative.parts[:2] == ("quality", "Local_Scans"):
            excluded.update(name for name in names if name in {"reports", "tools"})
        if relative.parts == ("quality",):
            excluded.update(name for name in names if name == "analysis")
        return excluded

    def ignore_unsafe(directory, names):
        excluded = ignore_generated(directory, names)
        excluded.update(
            name for name in names
            if (Path(directory) / name).is_symlink()
        )
        return excluded

    shutil.copytree(root, staging, ignore=ignore_unsafe)
    exclude = Path(temporary) / "exclude.txt"
    exclude.write_text(
        r"(^|/)\.git(/|$)\n"
        r"(^|/)quality/analysis(/|$)\n"
        r"(^|/)quality/Local_Scans/reports(/|$)\n"
        r"(^|/)quality/Local_Scans/tools(/|$)" "\n"
        r"(^|/)target(/|$)" "\n"
        r"(^|/)node_modules(/|$)" "\n"
        r"(^|/)\.venv(/|$)" "\n"
        r"(^|/)__pycache__(/|$)" "\n"
        r"(^|/)dist(/|$)" "\n"
        r"(^|/)build(/|$)" "\n",
        encoding="utf-8",
    )

    return staging, exclude


def run(repo_root, work_dir):
    """Scan the repository using the CI TruffleHog image."""
    root = Path(repo_root).resolve()
    work = Path(work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)

    output = work / "trufflehog.jsonl"
    output.unlink(missing_ok=True)

    try:
        with TemporaryDirectory(prefix="epms-trufflehog-") as temporary:
            # Docker filesystem scanning must never traverse Git object storage.
            # The scanner's exclude-paths option is not a reliable way to
            # exclude .git from a filesystem walk, so stage a filtered copy.
            staging, exclude = _stage_source(root, temporary)

            docker = shutil.which("docker")
            if docker is None:
                raise OSError("Docker executable unavailable")

            command = [
                docker, "run", "--rm",
                "-v", f"{staging}:/repo:ro",
                "-v", f"{exclude}:/run/epms-exclude.txt:ro",
                IMAGE,
                "filesystem", "/repo",
                "--exclude-paths", "/run/epms-exclude.txt",
                "--json",
                "--no-verification",
            ]

            completed = run_scanner_to_file(command, output)

        valid = completed.returncode == 0

        if valid:
            try:
                with output.open(encoding="utf-8") as stream:
                    for line in stream:
                        if line.strip():
                            if not isinstance(json.loads(line), dict):
                                valid = False
                                break
            except (OSError, ValueError, UnicodeError):
                valid = False

        if valid:
            write_status(work, "trufflehog", "completed", 0)
            print("TruffleHog execution: completed")
            return

        output.unlink(missing_ok=True)
        write_status(
            work,
            "trufflehog",
            "failed",
            completed.returncode,
            "TruffleHog execution failed or JSONL was invalid",
        )

    except OSError:
        output.unlink(missing_ok=True)
        write_status(
            work,
            "trufflehog",
            "failed",
            reason="Docker or TruffleHog execution unavailable",
        )

    print("TruffleHog execution: error")


if __name__ == "__main__":
    from quality.Local_Scans.checks.cli import invoke_scanner

    invoke_scanner(run, "quality.Local_Scans.checks.check_trufflehog")
