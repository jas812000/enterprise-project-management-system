"""
Generate the EPMS Local Quality Gate HTML report.

Reads scanner results from report_parsers.py and builds
the HTML report through report_html.py.

Writes the completed report to WORK_DIR/local-report.html.

Environment variables (set by run-local-checks.sh):
  WORK_DIR       — managed directory containing scanner artifacts
  REPO_ROOT      — repository root path
  GENERATED_AT   — UTC timestamp string
  SCAN_USER      — local username
  CS_STATUS      — passed | failed | error | skipped
  PMD_STATUS     — passed | failed | error | skipped
  SB_STATUS      — passed | failed | error | skipped
  FAILED         — number of failed quality checks
"""

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
WORK_DIR = os.environ["WORK_DIR"]
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
RAW_CS = Path(WORK_DIR) / "checkstyle.xml"
RAW_PMD = Path(WORK_DIR) / "pmd.xml"
RAW_SB = Path(WORK_DIR) / "spotbugs.xml"

OUT = Path(WORK_DIR) / "local-report.html"

# ----------------------------------------------------------
# Parse raw artifacts
# ----------------------------------------------------------
cs_findings, cs_sev = parse_scanner_report(
    CS_STATUS, parse_checkstyle, RAW_CS, REPO_ROOT
)

pmd_findings, pmd_sev = parse_scanner_report(
    PMD_STATUS, parse_pmd, RAW_PMD, REPO_ROOT
)

sb_findings, sb_sev = parse_scanner_report(
    SB_STATUS, parse_spotbugs, RAW_SB
)

# ----------------------------------------------------------
# Build HTML
# ----------------------------------------------------------
html = build_html(
    {
        "generated_at": GENERATED_AT,
        "scan_user": SCAN_USER,
        "repo_root": REPO_ROOT,
        "failed": FAILED,
        "cs_status": CS_STATUS,
        "pmd_status": PMD_STATUS,
        "sb_status": SB_STATUS,
        "cs_findings": cs_findings,
        "pmd_findings": pmd_findings,
        "sb_findings": sb_findings,
        "cs_sev": cs_sev,
        "pmd_sev": pmd_sev,
        "sb_sev": sb_sev,
    }
)

# ----------------------------------------------------------
# Write output
# ----------------------------------------------------------
OUT.write_text(html, encoding="utf-8")
print(f"[generate-report] HTML written to: {OUT}")
