"""Run Trivy filesystem security scanning without a shell."""

import json
import shutil
import subprocess  # nosec B404
from pathlib import Path

from quality.Local_Scans.checks.scanner_runner import write_status


CATEGORY_FIELDS = {
    "Vulnerabilities": (
        "VulnerabilityID",
        "PkgName",
        "InstalledVersion",
        "FixedVersion",
        "Severity",
        "Title",
    ),
    "Misconfigurations": ("ID", "Severity"),
    "Secrets": ("RuleID", "Severity"),
}

COVERAGE_CATEGORIES = {
    "vuln": "Vulnerabilities",
    "misconfig": "Misconfigurations",
    "secret": "Secrets",  # nosec B105 - Trivy scanner category, not a credential
}


def _build_command(executable):
    """Build the Trivy filesystem scanning command."""
    return [
        executable,
        "fs",
        "--scanners", "vuln,misconfig,secret",
        "--format", "json",
        "--exit-code", "0",
        "--timeout", "10m",
        "--skip-dirs", ".git",
        "--skip-dirs", "quality/Local_Scans/tools",
        "--skip-dirs", "quality/Local_Scans/reports",
        "--skip-dirs", "**/__pycache__",
        "--skip-files", "**/*.pyc",
        "--skip-dirs", "quality/analysis",
        ".",
    ]


def _failure_reason(completed):
    """Return a sanitized diagnostic for unsuccessful execution."""
    diagnostic = completed.stderr or b""

    if (
        b"error getting credentials" in diagnostic
        and b"executable file not found" in diagnostic
    ):
        return "Trivy registry credential helper unavailable on PATH"

    if b"failed to download vulnerability DB" in diagnostic:
        return "Trivy vulnerability database download failed; check registry connectivity"

    return f"Trivy exited with code {completed.returncode}"


def _valid_envelope(data):
    """Determine whether the report contains a valid schema-v2 envelope."""
    return (
        data.get("SchemaVersion") == 2
        and bool(data.get("ArtifactName"))
        and bool(data.get("ArtifactType"))
        and isinstance(data.get("Metadata"), dict)
    )


def _extract_results(raw_output):
    """Decode and validate the Trivy report structure."""
    data = json.loads(raw_output or b"")

    if not isinstance(data, dict):
        raise ValueError("Invalid report")

    if "Results" not in data and not _valid_envelope(data):
        raise ValueError("Invalid report envelope")

    results = data.get("Results")

    if results is None and data.get("SchemaVersion") == 2:
        results = []

    if not isinstance(results, list):
        raise ValueError("Invalid results")

    return results


def _sanitize_findings(entries, keys):
    """Retain approved finding fields and reject invalid field types."""
    if entries is None:
        entries = []

    if not isinstance(entries, list) or any(
        not isinstance(finding, dict) for finding in entries
    ):
        raise ValueError("Invalid findings")

    sanitized = [
        {
            key: finding[key]
            for key in keys
            if key in finding
        }
        for finding in entries
    ]

    if any(
        not isinstance(value, str)
        for finding in sanitized
        for value in finding.values()
    ):
        raise ValueError("Invalid finding field")

    return sanitized


def _sanitize_target(item):
    """Validate a scan target and remove unapproved finding fields."""
    if not isinstance(item, dict):
        raise ValueError("Invalid target")

    target = item.get("Target", "unknown")

    if not isinstance(target, str):
        raise ValueError("Invalid target name")

    clean = {"Target": target}

    for category, keys in CATEGORY_FIELDS.items():
        clean[category] = _sanitize_findings(
            item.get(category),
            keys,
        )

    return clean


def _update_coverage(coverage, item, clean):
    """Record positive evidence of scanner coverage."""
    for mode, category in COVERAGE_CATEGORIES.items():
        if clean[category]:
            coverage[mode] = "observed"

    if item.get("Class") in ("lang-pkgs", "os-pkgs"):
        coverage["vuln"] = "observed"

    if item.get("Class") == "config":
        coverage["misconfig"] = "observed"


def _sanitize_report(raw_output):
    """Build a sanitized report containing findings and coverage evidence."""
    results = _extract_results(raw_output)

    coverage = {
        mode: "unknown"
        for mode in COVERAGE_CATEGORIES
    }

    sanitized = []

    for item in results:
        clean = _sanitize_target(item)
        _update_coverage(coverage, item, clean)
        sanitized.append(clean)

    return {
        "Results": sanitized,
        "LocalCoverage": coverage,
    }


def _execute_scan(executable, root, output):
    """Execute Trivy and persist a validated, sanitized report."""
    completed = subprocess.run(  # nosec B603
        _build_command(executable),
        cwd=root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=660,
    )

    if completed.returncode != 0:
        return _failure_reason(completed)

    safe = _sanitize_report(
        getattr(completed, "stdout", b"")
    )

    output.write_text(
        json.dumps(safe),
        encoding="utf-8",
    )

    return None


def run(repo_root, work_dir):
    """Record completed scans separately from execution failures."""
    root = Path(repo_root).resolve()
    work = Path(work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)

    output = work / "trivy.json"
    output.unlink(missing_ok=True)

    executable = shutil.which("trivy")

    if not executable:
        write_status(
            work,
            "trivy",
            "failed",
            reason="Trivy executable unavailable",
        )
        return

    write_status(
        work,
        "trivy",
        "failed",
        reason="Trivy scan incomplete",
    )

    try:
        reason = _execute_scan(executable, root, output)

        if reason is None:
            write_status(work, "trivy", "completed", 0)
            return

    except subprocess.TimeoutExpired:
        reason = "Trivy execution timed out"

    except (ValueError, TypeError, KeyError):
        reason = "Trivy report missing or invalid"

    except (OSError, subprocess.SubprocessError):
        reason = "Trivy process could not execute"

    output.unlink(missing_ok=True)

    write_status(
        work,
        "trivy",
        "failed",
        reason=reason,
    )
