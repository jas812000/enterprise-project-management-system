
"""
Generate the EPMS Local Quality Gate HTML report.

Reads the existing Java scanner reports and the normalized
nine-scanner results produced by the local pipeline.

Builds the HTML report through report_html.py.

Writes the completed report to WORK_DIR/local-report.html.

Environment variables (set by run-local-checks.sh):
    WORK_DIR       - managed report working directory
    REPO_ROOT      - repository root path
    GENERATED_AT   - UTC timestamp string
    SCAN_USER      - local username
    CS_STATUS      - passed | failed | error | skipped
    PMD_STATUS     - passed | failed | error | skipped
    SB_STATUS      - passed | failed | error | skipped
    FAILED         - number of failed Java quality checks

BN1 operates in advisory mode. Scanner findings and
execution errors do not prevent Git commits.

The normalized results use the existing BN2 CI parsers
and severity classifications.
"""

import json
import os
from pathlib import Path

from quality.Local_Scans.reporting.report_html import build_html
from quality.Local_Scans.reporting.report_parsers import (
    parse_checkstyle,
    parse_pmd,
    parse_spotbugs,
    parse_scanner_report,
)


# ----------------------------------------------------------
# Environment
# ----------------------------------------------------------
WORK_DIR = Path(os.environ["WORK_DIR"])
REPO_ROOT = os.environ["REPO_ROOT"]
GENERATED_AT = os.environ["GENERATED_AT"]
SCAN_USER = os.environ["SCAN_USER"]

CS_STATUS = os.environ["CS_STATUS"]
PMD_STATUS = os.environ["PMD_STATUS"]
SB_STATUS = os.environ["SB_STATUS"]

FAILED = int(os.environ["FAILED"])


# ----------------------------------------------------------
# Input artifacts
# ----------------------------------------------------------
RAW_CS = WORK_DIR / "checkstyle.xml"
RAW_PMD = WORK_DIR / "pmd.xml"
RAW_SB = WORK_DIR / "spotbugs.xml"

NORMALIZED_PATH = WORK_DIR / "normalized.json"

OUT = WORK_DIR / "local-report.html"


def main() -> None:
    """Parse scanner artifacts and generate the local HTML report."""
    # ----------------------------------------------------------
    # Parse existing Java scanner artifacts
    # ----------------------------------------------------------
    cs_findings, cs_sev = parse_scanner_report(
        CS_STATUS,
        parse_checkstyle,
        RAW_CS,
        REPO_ROOT,
    )

    pmd_findings, pmd_sev = parse_scanner_report(
        PMD_STATUS,
        parse_pmd,
        RAW_PMD,
        REPO_ROOT,
    )

    sb_findings, sb_sev = parse_scanner_report(
        SB_STATUS,
        parse_spotbugs,
        RAW_SB,
    )


    # ----------------------------------------------------------
    # Load normalized nine-scanner results
    # ----------------------------------------------------------
    normalized = None

    if NORMALIZED_PATH.is_file():
        try:
            with NORMALIZED_PATH.open(encoding="utf-8") as report:
                normalized = json.load(report)

            if not isinstance(normalized, dict):
                raise ValueError(
                    "Normalized report must contain a JSON object."
                )

            if normalized.get("tool_count") != 9:
                raise ValueError(
                    "Normalized report must contain nine scanners."
                )

            if not isinstance(normalized.get("results"), list):
                raise ValueError(
                    "Normalized report is missing scanner results."
                )

            if len(normalized["results"]) != 9:
                raise ValueError(
                    "Normalized report has an incorrect result count."
                )

        except (OSError, ValueError, TypeError):
            print(
                "[generate-report] WARNING: "
                "Normalized scanner results are unavailable or invalid."
            )
            normalized = None

    else:
        print(
            "[generate-report] WARNING: "
            "Normalized scanner report was not found."
        )


    # ----------------------------------------------------------
    # Build HTML report
    # ----------------------------------------------------------
    context = {
        # Report metadata
        "generated_at": GENERATED_AT,
        "scan_user": SCAN_USER,
        "repo_root": REPO_ROOT,

        # Existing Java scanner statuses
        "failed": FAILED,
        "cs_status": CS_STATUS,
        "pmd_status": PMD_STATUS,
        "sb_status": SB_STATUS,

        # Existing Java scanner findings
        "cs_findings": cs_findings,
        "pmd_findings": pmd_findings,
        "sb_findings": sb_findings,

        # Existing Java severity summaries
        "cs_sev": cs_sev,
        "pmd_sev": pmd_sev,
        "sb_sev": sb_sev,

        # All nine scanners, normalized using BN2 parsers
        "normalized": normalized,

        # BN1 does not enforce quality thresholds
        "gate_mode": "advisory",
    }

    html = build_html(context)


    # ----------------------------------------------------------
    # Write output
    # ----------------------------------------------------------
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")

    print(f"[generate-report] HTML written to: {OUT}")


if __name__ == "__main__":
    main()
