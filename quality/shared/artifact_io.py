"""Small, scanner-neutral helpers for reading JSON artifacts."""

import json

from pathlib import Path

from defusedxml import ElementTree as ET
from defusedxml.common import DefusedXmlException


def read_json_artifact(artifact: Path) -> object:
    """Load a JSON artifact, propagating errors to its owning parser."""
    with artifact.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def prepare_artifact(result: dict, artifact: Path) -> bool:
    """Set scanner execution flags; return False for a missing artifact.

    The owning parser retains responsibility for handling parse errors.
    """
    if not artifact.exists():
        result["artifact_present"] = False
        result["runtime_error"] = True
        return False
    result["artifact_present"] = True
    result["executed"] = True
    return True


def record_findings(result: dict, findings: list[dict], max_severity: str) -> None:
    """Store a scanner's normalized findings and calculated severity.

    Scanner-specific severity mapping and maximum-severity calculation
    remain the responsibility of the caller.
    """
    result["findings"] = findings
    result["violation_count"] = len(findings)
    result["max_severity"] = max_severity


def load_json_with_error(artifact: Path, result: dict, tool: str) -> dict | None:
    """Read a scanner JSON document, retaining its own error label."""
    try:
        return read_json_artifact(artifact)
    except (OSError, TypeError, ValueError, KeyError) as error:
        result["runtime_error"] = True
        result["metadata"]["error"] = f"{tool} parse error: {error}"
        return None


def append_finding(result: dict, findings: list[dict], finding: dict, severity: str) -> None:
    """Accumulate a normalized finding without altering scanner policy."""
    result["severity_counts"][severity] += 1
    findings.append(finding)


def finish_findings(result: dict, findings: list[dict], determine_max_severity) -> None:
    """Store normalized findings and their computed maximum severity."""
    record_findings(result, findings, determine_max_severity(result["severity_counts"]))


def load_xml_with_error(artifact: Path, result: dict, tool: str):
    """Parse a defused XML scanner artifact, preserving parser failure flags."""
    try:
        return ET.parse(artifact).getroot()
    except (ET.ParseError, DefusedXmlException, OSError, TypeError, ValueError, KeyError) as error:
        result["runtime_error"] = True
        result["metadata"]["error"] = f"{tool} parse error: {error}"
        return None


def iter_xml_files(root):
    """Yield (file path, file node) pairs from Checkstyle/PMD XML."""
    for file_node in root.findall("file"):
        yield file_node.attrib.get("name", "unknown"), file_node
