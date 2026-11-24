"""
Trivy Parser (Container + Filesystem SCA)

Purpose:
    Parse Trivy JSON output and normalize findings into the
    standard schema defined in schemas.py.

Expected raw artifact:
    quality/analysis/raw/trivy.json

Native Trivy Severities:
    CRITICAL  → Critical vulnerability, immediate action required.
    HIGH      → High severity vulnerability.
    MEDIUM    → Medium severity vulnerability.
    LOW       → Low severity, informational.
    UNKNOWN   → Severity could not be determined.

Severity Mapping (Trivy → Normalized):
    CRITICAL → critical
    HIGH     → high
    MEDIUM   → medium
    LOW      → low
    UNKNOWN  → info

Trivy JSON Structure (filesystem scan):
    {
      "Results": [
        {
          "Target": "backend/core/pom.xml",
          "Type":   "pom",
          "Vulnerabilities": [
            {
              "VulnerabilityID": "CVE-2021-12345",
              "PkgName":         "log4j",
              "InstalledVersion":"2.14.0",
              "FixedVersion":    "2.17.1",
              "Severity":        "CRITICAL",
              "Title":           "Remote Code Execution",
              "Description":     "..."
            }
          ]
        }
      ]
    }
"""

import json
from pathlib import Path
from quality.shared.artifact_io import (
    load_json_artifact,
    prepare_artifact,
    record_findings,
)
from ..schemas import base_tool_result
from ..utils import determine_max_severity


SEVERITY_MAP: dict[str, str] = {
    "critical": "critical",
    "high":     "high",
    "medium":   "medium",
    "low":      "low",
    "unknown":  "info",
}


def _extract_scan_results(data: dict) -> list:
    """Validate and extract Trivy scan results."""
    if not isinstance(data, dict) or "Results" not in data:
        raise ValueError("Missing Trivy Results")

    scan_results = data["Results"]

    if scan_results is None and data.get("SchemaVersion") == 2:
        scan_results = []

    if not isinstance(scan_results, list):
        raise ValueError("Invalid Trivy Results")

    return scan_results


def _build_vulnerability(target: str, vuln: dict) -> dict:
    """Normalize a Trivy vulnerability finding."""
    native_sev = (vuln.get("Severity") or "UNKNOWN").lower()
    severity = SEVERITY_MAP.get(native_sev, "info")

    return {
        "file": target,
        "line": 0,
        "column": 0,
        "severity": severity,
        "native_severity": vuln.get("Severity", "UNKNOWN"),
        "rule": vuln.get("VulnerabilityID", "unknown"),
        "message": (
            f"{vuln.get('PkgName', 'unknown')} "
            f"{vuln.get('InstalledVersion', '')} — "
            f"{vuln.get('Title', vuln.get('Description', ''))}"
        ).strip(),
        "metadata": {
            "package": vuln.get("PkgName", ""),
            "installed_version": vuln.get("InstalledVersion", ""),
            "fixed_version": vuln.get("FixedVersion", ""),
            "cve": vuln.get("VulnerabilityID", ""),
        },
    }


def _parse_vulnerabilities(scan_result: dict, target: str) -> list:
    """Validate and normalize vulnerability findings."""
    vulnerabilities = scan_result.get("Vulnerabilities")

    if vulnerabilities is None:
        vulnerabilities = []

    if not isinstance(vulnerabilities, list):
        raise ValueError("Invalid Trivy vulnerabilities")

    return [
        _build_vulnerability(target, vuln)
        for vuln in vulnerabilities
    ]


def _build_security_finding(
    target: str,
    entry: dict,
    category: str,
    rule_key: str,
) -> dict:
    """Normalize a misconfiguration or redacted secret finding."""
    if not isinstance(entry, dict):
        raise ValueError("Invalid Trivy finding")

    native = entry.get("Severity") or "UNKNOWN"
    severity = SEVERITY_MAP.get(native.lower(), "info")

    message = (
        "Potential secret detected (redacted)"
        if category == "Secrets"
        else "Configuration security finding"
    )

    return {
        "file": target,
        "line": 0,
        "column": 0,
        "severity": severity,
        "native_severity": native,
        "rule": entry.get(rule_key, "unknown"),
        "message": message,
        "metadata": {
            "category": category,
        },
    }


def _parse_security_findings(scan_result: dict, target: str) -> list:
    """Validate and normalize misconfiguration and secret findings."""
    findings = []

    categories = (
        ("Misconfigurations", "ID"),
        ("Secrets", "RuleID"),
    )

    for category, rule_key in categories:
        entries = scan_result.get(category)

        if entries is None:
            continue

        if not isinstance(entries, list):
            raise ValueError("Invalid Trivy finding list")

        findings.extend(
            _build_security_finding(
                target,
                entry,
                category,
                rule_key,
            )
            for entry in entries
        )

    return findings


def _collect_findings(scan_results: list, severity_counts: dict) -> list:
    """Collect normalized findings and update severity counts."""
    findings = []

    for scan_result in scan_results:
        target = scan_result.get("Target", "unknown")

        module_findings = _parse_vulnerabilities(
            scan_result,
            target,
        )

        module_findings.extend(
            _parse_security_findings(scan_result, target)
        )

        for finding in module_findings:
            severity_counts[finding["severity"]] += 1

        findings.extend(module_findings)

    return findings


def _apply_coverage(data: dict, result: dict) -> None:
    """Validate and preserve optional local scanner coverage metadata."""
    if "LocalCoverage" not in data:
        return

    coverage = data["LocalCoverage"]

    if (
        not isinstance(coverage, dict)
        or set(coverage) != {"vuln", "misconfig", "secret"}
        or any(
            value not in ("observed", "unknown")
            for value in coverage.values()
        )
    ):
        raise ValueError("Invalid coverage")

    result["metadata"]["coverage"] = coverage


def parse_trivy(raw_dir: Path) -> dict:
    """
    Parse trivy.json and return a standardized result dictionary.

    Args:
        raw_dir: Path to the directory containing raw tool outputs.

    Returns:
        A dict conforming to the base_tool_result schema.

    Contract:
        - Always returns a base_tool_result structure.
        - Never raises exceptions outward.
        - Missing artifact: artifact_present=False, runtime_error=True.
        - Malformed JSON: runtime_error=True, error in metadata.
        - Empty Results: valid result with zero violations.
    """
    result = base_tool_result("trivy")
    artifact = raw_dir / "trivy.json"

    if not prepare_artifact(result, artifact):
        return result

    try:
        data = load_json_artifact(artifact)

        scan_results = _extract_scan_results(data)

        findings = _collect_findings(
            scan_results,
            result["severity_counts"],
        )

        _apply_coverage(data, result)

        record_findings(
            result,
            findings,
            determine_max_severity(result["severity_counts"]),
        )

    except json.JSONDecodeError as error:
        result["runtime_error"] = True
        result["metadata"]["error"] = f"JSON parse error: {error}"

    except Exception:  # pylint: disable=broad-except
        result["runtime_error"] = True
        result["metadata"]["error"] = "Invalid Trivy report structure"

    return result
