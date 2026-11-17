"""
EPMS Local Quality Gate report parsers.

Parses raw scanner artifacts and returns normalized findings
and severity counts.

Functions:
  parse_checkstyle(path, repo_root) -> findings, sev_counts
  parse_pmd(path, repo_root)        -> findings, sev_counts
  parse_spotbugs(path)              -> findings, sev_counts

Each finding dictionary contains:
  severity — critical | high | medium | low | info
  file     — relative file path
  line     — line number as string
  rule     — rule or bug type name
  message  — human-readable description
"""

from pathlib import Path
from typing import Any as XmlElement

from defusedxml import ElementTree as ET
from defusedxml.common import DefusedXmlException


def _strip_root(path: str, repo_root: str) -> str:
    """Return a repository-relative path when the file is inside the repository."""
    source = Path(path)
    root = Path(repo_root)

    try:
        return str(source.relative_to(root))
    except ValueError:
        return path


def _empty_sev() -> dict:
    """Return an empty severity-count map."""
    return {"critical": 0, "high": 0, "medium": 0, "low": 0, "info": 0}


def _strip_namespace(root: XmlElement) -> str:
    """Extract namespace prefix from root tag if present."""
    if root.tag.startswith("{"):
        return root.tag.split("}")[0] + "}"
    return ""


# ----------------------------------------------------------
# Checkstyle
# ----------------------------------------------------------
def parse_checkstyle(path: Path, repo_root: str) -> tuple[list, dict]:
    """
    Parse checkstyle.xml.

    Native severity mapping:
      error   → high
      warning → medium
      info    → low
    """
    findings: list = []
    sev_counts: dict = _empty_sev()

    try:
        tree = ET.parse(str(path))
        root = tree.getroot()
        namespace = _strip_namespace(root)

        if root.tag != f"{namespace}checkstyle":
            raise RuntimeError(
                f"Invalid Checkstyle report '{path}': "
                f"expected <checkstyle> root, found {root.tag!r}"
            )

        for file_el in root.findall(f"{namespace}file"):
            fname = _strip_root(file_el.get("name", "unknown"), repo_root)
            for err in file_el.findall(f"{namespace}error"):
                native = (err.get("severity") or "info").lower()
                sev = {"error": "high", "warning": "medium", "info": "low"}.get(
                    native, "low"
                )
                sev_counts[sev] += 1
                findings.append(
                    {
                        "severity": sev,
                        "file": fname,
                        "line": err.get("line", "0"),
                        "rule": (err.get("source") or "").split(".")[-1],
                        "message": err.get("message", ""),
                    }
                )
    except (OSError, ET.ParseError, DefusedXmlException, ValueError) as error:
        raise RuntimeError(
            f"Unable to parse Checkstyle report '{path}': {error}"
        ) from error

    return findings, sev_counts


# ----------------------------------------------------------
# PMD
# ----------------------------------------------------------
def parse_pmd(path: Path, repo_root: str) -> tuple[list, dict]:
    """
    Parse pmd.xml.

    Native priority mapping:
      1 → critical
      2 → high
      3 → medium
      4 → low
      5 → info

    Note: PMD XML includes a namespace which must be stripped
    before findall() will locate child elements correctly.
    """
    findings: list = []
    sev_counts: dict = _empty_sev()

    priority_map = {1: "critical", 2: "high", 3: "medium", 4: "low", 5: "info"}

    try:
        tree = ET.parse(str(path))
        root = tree.getroot()
        namespace = _strip_namespace(root)

        if root.tag != f"{namespace}pmd":
            raise RuntimeError(
                f"Invalid PMD report '{path}': "
                f"expected <pmd> root, found {root.tag!r}"
            )

        for file_el in root.findall(f"{namespace}file"):
            fname = _strip_root(file_el.get("name", "unknown"), repo_root)
            for violation in file_el.findall(f"{namespace}violation"):
                priority = int(violation.get("priority", "3"))
                sev = priority_map.get(priority, "medium")
                sev_counts[sev] += 1
                findings.append(
                    {
                        "severity": sev,
                        "file": fname,
                        "line": violation.get("beginline", "0"),
                        "rule": violation.get("rule", "unknown"),
                        "message": (violation.text or "").strip(),
                    }
                )
    except (OSError, ET.ParseError, DefusedXmlException, ValueError) as error:
        raise RuntimeError(
            f"Unable to parse PMD report '{path}': {error}"
        ) from error

    return findings, sev_counts


# ----------------------------------------------------------
# SpotBugs
# ----------------------------------------------------------
def parse_spotbugs(path: Path) -> tuple[list, dict]:
    """
    Parse spotbugs.xml.

    Native priority mapping:
      1 → high
      2 → medium
      3 → low
    """
    findings: list = []
    sev_counts: dict = _empty_sev()

    priority_map = {1: "high", 2: "medium", 3: "low"}

    try:
        tree = ET.parse(str(path))
        root = tree.getroot()
        namespace = _strip_namespace(root)

        if root.tag != f"{namespace}BugCollection":
            raise RuntimeError(
                f"Invalid SpotBugs report '{path}': "
                f"expected <BugCollection> root, found {root.tag!r}"
            )

        for bug in root.findall(f"{namespace}BugInstance"):
            priority = int(bug.get("priority", "2"))
            sev = priority_map.get(priority, "medium")
            sev_counts[sev] += 1
            src = bug.find(f"{namespace}SourceLine")
            file_path = (
                src.get("sourcepath", "unknown") if src is not None else "unknown"
            )
            line = src.get("start", "0") if src is not None else "0"
            findings.append(
                {
                    "severity": sev,
                    "file": file_path,
                    "line": line,
                    "rule": bug.get("type", "unknown"),
                    "message": (bug.findtext(f"{namespace}ShortMessage") or "").strip(),
                }
            )
    except (OSError, ET.ParseError, DefusedXmlException, ValueError) as error:
        raise RuntimeError(
            f"Unable to parse SpotBugs report '{path}': {error}"
        ) from error

    return findings, sev_counts


def empty_severity_counts():
    """Return zero counts for each normalized severity."""
    return dict.fromkeys(("critical", "high", "medium", "low", "info"), 0)


def parse_scanner_report(status, parser, path, *args):
    """Parse a completed scan or return empty results for inactive scans."""
    if status in ("error", "skipped"):
        return [], empty_severity_counts()

    if status not in ("passed", "failed"):
        raise RuntimeError(f"Unknown scanner status: {status!r}")

    return parser(path, *args)
