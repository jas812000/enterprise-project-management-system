"""
Normalization Layer (Layer 1 of the Quality Gate Engine)

Purpose
-------
Convert heterogeneous tool outputs (XML, JSON, JSONL) into a single
consistent schema so policy evaluation can remain simple and deterministic.

Inputs
------
Raw tool artifacts read from:
    quality/analysis/raw/

Output
------
A single normalized file written to:
    quality/analysis/normalized/normalized.json

Top-Level normalized.json Structure
-----------------------------------
{
  "generated_at": "2026-02-24T12:00:00Z",
  "tool_count": 12,
  "total_violations": 42,
  "max_severity": "critical",
  "results": [
    {
      "tool": "trufflehog",
      "artifact_present": true,
      "executed": true,
      "runtime_error": false,
      "findings": [ ... ],
      "violation_count": 3,
      "severity_counts": { "critical": 1, "high": 2, ... },
      "max_severity": "critical",
      "metadata": { ... }
    }
  ]
}

Design Rules
------------
- Does not apply policy thresholds. policy_engine.py owns that.
- Does not decide pass/fail. gate.py owns that.
- Must be resilient. One parser crash must not prevent other tools
  from being collected and normalized.
- A parser crash is recorded as runtime_error=True for that tool,
  which the policy engine can treat as a governance violation.
- Each scanner requires a valid <scanner>.status.json file containing
  an execution status: completed, failed, or disabled.
- Completed scanners require a valid raw analysis artifact.
- Disabled scanners require a nonempty reason and must not have
  an existing raw analysis artifact.
- Scanner artifact filenames are defined in scanner_artifacts.py.
- Missing or invalid scanner statuses fail closed.

Execution
---------
Recommended:
    python -m quality.CI.gate.normalize

Direct:
    python quality/CI/gate/normalize.py
"""

import json
from datetime import datetime, timezone
from pathlib import Path

from quality.shared.scanner_status import scanner_is_disabled
from quality.shared.severity import aggregate_severity_counts

from quality.CI.gate.parsers.bandit           import parse_bandit
from quality.CI.gate.parsers.checkstyle       import parse_checkstyle
from quality.CI.gate.parsers.dependency_check import parse_dependency_check
from quality.CI.gate.parsers.gitleaks         import parse_gitleaks
from quality.CI.gate.parsers.htmlhint         import parse_htmlhint
from quality.CI.gate.parsers.pmd              import parse_pmd
from quality.CI.gate.parsers.pylint           import parse_pylint
from quality.CI.gate.parsers.semgrep          import parse_semgrep
from quality.CI.gate.parsers.spotbugs         import parse_spotbugs
from quality.CI.gate.parsers.stylelint        import parse_stylelint
from quality.CI.gate.parsers.trufflehog       import parse_trufflehog
from quality.CI.gate.parsers.trivy            import parse_trivy
from quality.CI.gate.scanner_artifacts import SCANNER_ARTIFACTS
from quality.CI.gate.scanner_status import apply_scanner_status
from quality.CI.gate.schemas                  import base_tool_result
from quality.CI.gate.utils                    import SEVERITY_ORDER, determine_max_severity


RAW_DIR        = Path("quality/analysis/raw")
NORMALIZED_DIR = Path("quality/analysis/normalized")
OUTPUT_FILE    = NORMALIZED_DIR / "normalized.json"


TOOL_PARSERS: list[tuple[str, callable]] = [
    ("trufflehog",       parse_trufflehog),       # Secrets scan            (JSONL)
    ("gitleaks",         parse_gitleaks),          # Secrets scan            (JSON)
    ("checkstyle",       parse_checkstyle),        # Java style              (XML)
    ("pmd",              parse_pmd),               # Java source analysis    (XML)
    ("spotbugs",         parse_spotbugs),          # Java bytecode analysis  (XML)
    ("semgrep",          parse_semgrep),           # Multi-language SAST     (JSON)
    ("pylint",           parse_pylint),            # Python static analysis  (JSON)
    ("bandit",           parse_bandit),            # Python security SAST    (JSON)
    ("htmlhint",         parse_htmlhint),          # HTML static analysis    (JSON)
    ("stylelint",        parse_stylelint),         # CSS/SCSS analysis       (JSON)
    ("dependency_check", parse_dependency_check),  # SCA — Multi             (JSON)
    ("trivy",            parse_trivy),             # SCA — Container/FS      (JSON)
]


def _normalize_tools() -> list[dict]:
    """Normalize every registered scanner without losing failure isolation."""
    results: list[dict] = []
    for tool_name, parser in TOOL_PARSERS:
        try:
            status_file = RAW_DIR / f"{tool_name}.status.json"

            # Explicitly disabled scanners do not require raw reports.
            # Only a valid status object with a nonempty reason qualifies.
            disabled = scanner_is_disabled(status_file)

            if disabled:
                result = base_tool_result(tool_name)

                # Disabled scanners must not have raw reports.
                artifact_name = SCANNER_ARTIFACTS.get(tool_name)

                if artifact_name is None:
                    result["runtime_error"] = True
                    result["metadata"]["error"] = (
                        "No artifact mapping registered for scanner."
                    )
                elif (RAW_DIR / artifact_name).exists():
                    result["artifact_present"] = True

            else:
                result = parser(RAW_DIR)

            result = apply_scanner_status(
                tool_name,
                result,
                RAW_DIR,
            )

            results.append(result)

        # Preserve scanner isolation and report unexpected failures.
        except Exception as error:  # pylint: disable=broad-except
            error_result = base_tool_result(tool_name)
            error_result["executed"] = False
            error_result["runtime_error"] = True
            error_result["metadata"]["status"] = "failed"
            error_result["metadata"]["error"] = (
                f"Scanner normalization failed: {error}"
            )
            results.append(error_result)

    return results


def normalize() -> list[dict]:
    """
    Run all registered tool parsers and write normalized.json.

    Each parser reads its raw artifact from RAW_DIR and returns a
    standardized result dictionary conforming to schemas.base_tool_result.

    A top-level summary wrapper is written around all tool results so the
    policy engine and reporting layer have a single entry point for overall
    pipeline state.

    Returns
    -------
    list[dict]
        The list of per-tool normalized result dictionaries.

    Contract
    --------
    - Always attempts to run every registered parser.
    - Never aborts due to a single tool failure.
    - Converts parser exceptions into runtime_error records.
    - Creates the output directory if it does not already exist.
    """
    NORMALIZED_DIR.mkdir(parents=True, exist_ok=True)

    results = _normalize_tools()

    total_violations = sum(result.get("violation_count", 0) for result in results)

    combined_severity_counts = aggregate_severity_counts(results, SEVERITY_ORDER)

    overall_max_severity = determine_max_severity(combined_severity_counts)
    generated_at         = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    normalized_doc = {
        "generated_at":    generated_at,
        "tool_count":      len(results),
        "total_violations": total_violations,
        "max_severity":    overall_max_severity,
        "results":         results,
    }

    with open(OUTPUT_FILE, "w", encoding="utf-8") as file_handle:
        json.dump(normalized_doc, file_handle, indent=2)

    print(f"[normalize] {len(results)} tool(s) processed.")
    print(f"[normalize] Total violations : {total_violations}")
    print(f"[normalize] Max severity     : {overall_max_severity or 'none'}")
    print(f"[normalize] Output written to: {OUTPUT_FILE}")

    return results


if __name__ == "__main__":
    normalize()
