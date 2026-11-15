#!/bin/sh

# ==========================================================
# EPMS Local Quality Gate (BN1) — Entry Point
# ----------------------------------------------------------
# Runs local static analysis and security checks, generates
# an HTML report, packages scan artifacts into a ZIP archive,
# and opens the report in the default browser.
#
# Usage (from the EPMS repository root):
#   sh quality/Local_Scans/run-local-checks.sh
#
# Scanner prerequisites and enforcement policies are
# documented in quality/Local_Scans/README.md.
# ==========================================================

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CHECKS_DIR="${SCRIPT_DIR}/checks"
REPORT_DIR="${SCRIPT_DIR}/reporting"
TOOLS_DIR="${SCRIPT_DIR}/tools"
OUTPUT_DIR="${SCRIPT_DIR}/reports"

# Store generated scan artifacts inside the EPMS repository.
if ! mkdir -p "${OUTPUT_DIR}"; then
  echo "ERROR: Cannot create EPMS reports directory: ${OUTPUT_DIR}" >&2
  exit 2
fi

# Ensure Python can resolve the repository package root.
export PYTHONPATH="${REPO_ROOT}${PYTHONPATH:+:${PYTHONPATH}}"

# Resolve a Python 3 interpreter across supported platforms.
PYTHON_CMD=""

for candidate in python3 python; do
  if command -v "${candidate}" >/dev/null 2>&1 &&
     "${candidate}" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
    PYTHON_CMD="${candidate}"
    break
  fi
done

if [ -z "${PYTHON_CMD}" ] &&
   command -v py >/dev/null 2>&1 &&
   py -3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
  PYTHON_CMD="py"
fi

if [ -z "${PYTHON_CMD}" ]; then
  echo "ERROR: Python 3.10 or newer is required for the EPMS local quality gate."
  exit 2
fi

run_python() {
  if [ "${PYTHON_CMD}" = "py" ]; then
    py -3 "$@"
  else
    "${PYTHON_CMD}" "$@"
  fi
}

# Share the selected Python interpreter with scanner wrappers.
export PYTHON_CMD

TIMESTAMP="$(date '+%Y-%m-%d-%H%M%S')"
COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo "no-git")"

# Keep scanner working files inside the EPMS reports directory.
WORK_DIR="${OUTPUT_DIR}/work"

if ! mkdir -p "${WORK_DIR}"; then
  echo "ERROR: Cannot create scanner working directory: ${WORK_DIR}" >&2
  exit 2
fi

# Remove generated files from the previous scan.
if ! run_python - "${WORK_DIR}" <<'PY'
from pathlib import Path
import shutil
import sys

work_dir = Path(sys.argv[1])

for item in work_dir.iterdir():
    if item.is_dir() and not item.is_symlink():
        shutil.rmtree(item)
    else:
        item.unlink()
PY
then
  echo "ERROR: Unable to clean previous scanner working files." >&2
  exit 2
fi

# Maintain one consolidated ZIP report per EPMS installation.
ZIP_PATH="${OUTPUT_DIR}/local-quality-report-${TIMESTAMP}.zip"
GENERATED_AT="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
SCAN_USER="$(whoami)"

# Track quality-policy failures and scanner execution errors separately.
FAILED=0
ERRORS=0

# Initialize scanner statuses before execution.
CS_STATUS="skipped"
PMD_STATUS="skipped"
SB_STATUS="skipped"

# ----------------------------------------------------------
# Print header
# ----------------------------------------------------------
echo ""
echo "=============================="
echo " EPMS Local Quality Gate (BN1)"
echo "=============================="

# ----------------------------------------------------------
# Run Checkstyle
# ----------------------------------------------------------
echo ""
echo "--- Checkstyle ---"
SCANNER_EXIT=0
sh "${CHECKS_DIR}/check_checkstyle.sh" "${REPO_ROOT}" "${WORK_DIR}" "${TOOLS_DIR}" || SCANNER_EXIT=$?

case "${SCANNER_EXIT}" in
  0)
    CS_STATUS="passed"
    ;;
  1)
    CS_STATUS="failed"
    FAILED=$((FAILED + 1))
    ;;
  *)
    CS_STATUS="error"
    ERRORS=$((ERRORS + 1))
    echo "ERROR: check_checkstyle.sh exited with code ${SCANNER_EXIT}." >&2
    ;;
esac

# ----------------------------------------------------------
# Run PMD
# ----------------------------------------------------------
echo ""
echo "--- PMD ---"
SCANNER_EXIT=0
sh "${CHECKS_DIR}/check_pmd.sh" "${REPO_ROOT}" "${WORK_DIR}" "${TOOLS_DIR}" || SCANNER_EXIT=$?

case "${SCANNER_EXIT}" in
  0)
    PMD_STATUS="passed"
    ;;
  1)
    PMD_STATUS="failed"
    FAILED=$((FAILED + 1))
    ;;
  *)
    PMD_STATUS="error"
    ERRORS=$((ERRORS + 1))
    echo "ERROR: check_pmd.sh exited with code ${SCANNER_EXIT}." >&2
    ;;
esac

# ----------------------------------------------------------
# Run SpotBugs
# ----------------------------------------------------------
echo ""
echo "--- SpotBugs ---"
SCANNER_EXIT=0
sh "${CHECKS_DIR}/check_spotbugs.sh" "${REPO_ROOT}" "${WORK_DIR}" "${TOOLS_DIR}" || SCANNER_EXIT=$?

case "${SCANNER_EXIT}" in
  0)
    SB_STATUS="passed"
    ;;
  1)
    SB_STATUS="failed"
    FAILED=$((FAILED + 1))
    ;;
  *)
    SB_STATUS="error"
    ERRORS=$((ERRORS + 1))
    echo "ERROR: check_spotbugs.sh exited with code ${SCANNER_EXIT}." >&2
    ;;
esac

# ----------------------------------------------------------
# Export environment for Python scripts
# ----------------------------------------------------------
export WORK_DIR
export ZIP_PATH
export REPO_ROOT
export GENERATED_AT
export SCAN_USER
export CS_STATUS
export PMD_STATUS
export SB_STATUS
export FAILED
export ERRORS

# ----------------------------------------------------------
# Generate HTML report
# ----------------------------------------------------------
echo ""
echo "Generating HTML report..."
run_python -m quality.Local_Scans.reporting.generate_report

# ----------------------------------------------------------
# Package zip
# ----------------------------------------------------------
echo ""
echo "Packaging report..."
run_python - <<'PY'
import os
import zipfile
from pathlib import Path

work_dir = Path(os.environ["WORK_DIR"])
zip_path = Path(os.environ["ZIP_PATH"])

zip_path.parent.mkdir(parents=True, exist_ok=True)

with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zip_file:
    for file_path in sorted(work_dir.rglob("*")):
        if file_path.is_file():
            zip_file.write(file_path, arcname=file_path.relative_to(work_dir))

print(f"[package] ZIP written to: {zip_path}")
PY

# ----------------------------------------------------------
# Open in browser
# ----------------------------------------------------------
echo ""
echo "Opening report in browser..."
run_python "${REPORT_DIR}/open_report.py"

# ----------------------------------------------------------
# Summary
# ----------------------------------------------------------
echo ""
echo "=============================="
echo " Results"
echo "=============================="

printf " Checkstyle    "
if [ "${CS_STATUS}" = "passed" ]; then
  printf "PASSED\n"
elif [ "${CS_STATUS}" = "failed" ]; then
  printf "FAILED\n"
elif [ "${CS_STATUS}" = "error" ]; then
  printf "ERROR\n"
else
  printf "SKIPPED\n"
fi

printf " PMD           "
if [ "${PMD_STATUS}" = "passed" ]; then
  printf "PASSED\n"
elif [ "${PMD_STATUS}" = "failed" ]; then
  printf "FAILED\n"
elif [ "${PMD_STATUS}" = "error" ]; then
  printf "ERROR\n"
else
  printf "SKIPPED\n"
fi

printf " SpotBugs      "
if [ "${SB_STATUS}" = "passed" ]; then
  printf "PASSED\n"
elif [ "${SB_STATUS}" = "failed" ]; then
  printf "FAILED\n"
elif [ "${SB_STATUS}" = "error" ]; then
  printf "ERROR\n"
else
  printf "SKIPPED\n"
fi

echo "------------------------------"
if [ "${ERRORS}" -gt 0 ]; then
  echo " Result: ${ERRORS} scanner execution error(s); ${FAILED} check(s) failed"
  GATE_EXIT=2
elif [ "${FAILED}" -gt 0 ]; then
  echo " Result: ${FAILED} check(s) failed"
  GATE_EXIT=1
else
  echo " Result: All checks passed"
  GATE_EXIT=0
fi
echo "=============================="
echo ""
echo " Report saved to: ${ZIP_PATH}"
echo ""

exit "${GATE_EXIT}"
