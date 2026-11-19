#!/bin/sh

# ==========================================================
# Local Quality Gate — macOS/Linux Shell Launcher
# ----------------------------------------------------------
# Entry point for the advisory Local Quality Gate.
#
# Runs static analysis and security checks, generates an
# HTML report, packages validated reporting artifacts into
# a ZIP archive, and attempts to open the HTML report.
#
# Usage, prerequisites, and troubleshooting:
#   quality/Local_Scans/README.md
#
# Cross-project installation:
#   quality/PORTABILITY.md
# ==========================================================

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CHECKS_DIR="${SCRIPT_DIR}/checks"
REPORT_DIR="${SCRIPT_DIR}/reporting"
TOOLS_DIR="${SCRIPT_DIR}/tools"
OUTPUT_DIR="${SCRIPT_DIR}/reports"

# Store generated scan artifacts inside the host repository.
if ! mkdir -p "${OUTPUT_DIR}"; then
  echo "ERROR: Cannot create quality reports directory: ${OUTPUT_DIR}" >&2
  exit 2
fi

# Ensure Python can resolve the repository package root.
export PYTHONPATH="${REPO_ROOT}${PYTHONPATH:+:${PYTHONPATH}}"

# Shared CI parsers require a workspace root to safely turn absolute
# local scanner filenames into repository-relative paths. Keep the CI
# path validator unchanged; supply its expected context in the Local Quality Gate.
export GITHUB_WORKSPACE="${REPO_ROOT}"

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
  echo "ERROR: Python 3.10 or newer is required for the Local Quality Gate."
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

# Load the host project's configuration using the validated Python interpreter.
QUALITY_CONFIG="${QUALITY_CONFIG:-${REPO_ROOT}/quality/project.json}"

if [ -f "${QUALITY_CONFIG}" ]; then
  if ! CONFIG_EXPORTS="$(run_python "${SCRIPT_DIR}/../project_config.py" \
    --shell "${REPO_ROOT}" "${QUALITY_CONFIG}")"; then
    echo "ERROR: Unable to load Quality Gates project configuration." >&2
    exit 2
  fi

  eval "${CONFIG_EXPORTS}"
fi

export QUALITY_JAVA_SOURCE QUALITY_MAVEN_DIR QUALITY_SCAN_ROOT
export QUALITY_PYTHON_SOURCE QUALITY_JS_SOURCE QUALITY_PROJECT_NAME
export QUALITY_JAVA_VERSION QUALITY_CONFIG

TIMESTAMP="$(date '+%Y-%m-%d-%H%M%S')"

# Keep scanner working files inside the quality reports directory.
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

# Maintain one consolidated ZIP report per project installation.
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
echo " Local Quality Gate"
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
# Run additional scanners and normalize all nine results
# ----------------------------------------------------------
echo ""

# The Python orchestrator prints a dedicated section for each scanner.
export CS_STATUS
export PMD_STATUS
export SB_STATUS

run_python -m quality.Local_Scans.local_pipeline \
  "${REPO_ROOT}" \
  "${WORK_DIR}" \
  "${OUTPUT_DIR}" \
  "${TOOLS_DIR}"


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


# Validate the completed ZIP before reporting success.
with zipfile.ZipFile(zip_path, "r") as archive:
    corrupt_member = archive.testzip()
    if corrupt_member is not None:
        raise RuntimeError(
            "ZIP integrity check failed."
        )

    names = set(archive.namelist())

    required = {
        "local-report.html",
        "normalized.json",
    }

    missing = required - names
    if missing:
        raise RuntimeError(
            "ZIP is missing required artifacts: "
            + ", ".join(sorted(missing))
        )

    forbidden_names = {
        "trufflehog.json",
        "gitleaks.json",
        "bandit.json",
        "pylint.json",
    }

    for name in names:
        parts = Path(name).parts

        if (
            "raw" in parts
            or Path(name).name in forbidden_names
        ):
            raise RuntimeError(
                "ZIP contains a prohibited raw scanner artifact."
            )

print("[package] ZIP integrity and contents verified.")
print(f"[package] ZIP written to: {zip_path}")

PY


# ----------------------------------------------------------
# Open in browser
# ----------------------------------------------------------
echo ""
echo "Opening report in browser..."
run_python "${REPORT_DIR}/open_report.py"


# ----------------------------------------------------------
# Final Local Quality Gate summary — all nine scanners
# ----------------------------------------------------------
echo ""
echo "=========================================="
echo " Local Quality Gate — Final Results"
echo "=========================================="

if run_python - "${OUTPUT_DIR}/normalized/normalized.json" <<'PY'
import json
import sys
from pathlib import Path

report_path = Path(sys.argv[1])

scanner_names = (
    "trufflehog",
    "gitleaks",
    "checkstyle",
    "pmd",
    "spotbugs",
    "pylint",
    "bandit",
    "htmlhint",
    "stylelint",
)

try:
    report = json.loads(report_path.read_text(encoding="utf-8"))
except (OSError, ValueError) as error:
    print(f"ERROR: Unable to read normalized results: {error}")
    print("Result: INFRASTRUCTURE ERROR — normalized report unavailable")
    sys.exit(2)
else:
    if not isinstance(report, dict):
        print("ERROR: Normalized report must be a JSON object.")
        sys.exit(2)

    records = report.get("results")

    if not isinstance(records, list):
        print("ERROR: Normalized report 'results' must be a list.")
        sys.exit(2)

    if not records:
        print("ERROR: Normalized report contains no scanner results.")
        sys.exit(2)

    results_by_tool = {
        result["tool"]: result
        for result in records
        if isinstance(result, dict)
        and isinstance(result.get("tool"), str)
    }

    missing_scanners = [
        name for name in scanner_names
        if name not in results_by_tool
    ]

    if missing_scanners:
        print(
            "ERROR: Missing scanner records: "
            + ", ".join(missing_scanners)
        )
        sys.exit(2)

    total_findings = 0
    scanner_errors = 0
    disabled = 0
    completed = 0

    for name in scanner_names:
        result = results_by_tool.get(name)

        if result is None:
            status = "ERROR"
            count = 0
            scanner_errors += 1
        else:
            metadata = result.get("metadata", {})
            if not isinstance(metadata, dict):
                metadata = {}

            scanner_status = metadata.get("status")

            count = result.get("violation_count", 0)
            if type(count) is not int or count < 0:
                count = 0

            total_findings += count

            if (
                result.get("runtime_error")
                or scanner_status == "failed"
            ):
                status = "ERROR"
                scanner_errors += 1
            elif scanner_status == "disabled":
                status = "DISABLED"
                disabled += 1
            elif not result.get("executed"):
                status = "ERROR"
                scanner_errors += 1
            elif count > 0:
                status = "FINDINGS"
                completed += 1
            else:
                status = "CLEAN"
                completed += 1

        print(
            f" {name:<14} {status:<10} "
            f"{count:>4} finding(s)"
        )

    print("------------------------------------------")
    print(f" Completed scanners: {completed}")
    print(f" Disabled scanners:  {disabled}")
    print(f" Scanner errors:     {scanner_errors}")
    print(f" Total findings:     {total_findings}")

    if scanner_errors:
        print(" Result: ADVISORY — scanner errors detected")
    elif total_findings:
        print(" Result: ADVISORY — findings detected")
    elif disabled:
        print(" Result: ADVISORY — some scanners disabled")
    else:
        print(" Result: ADVISORY — all scanners clean")

    print(" Commits are not blocked by the Local Quality Gate.")

PY
then
  SUMMARY_EXIT=0
else
  SUMMARY_EXIT=$?
fi

echo "=========================================="
echo ""
if [ "${SUMMARY_EXIT}" -ne 0 ]; then
  echo "ERROR: Local Quality Gate could not produce a valid final summary." >&2
  exit 2
fi

echo " Report saved to: ${ZIP_PATH}"
echo ""

# The Local Quality Gate remains advisory:
# Scanner findings and scanner execution errors are reported,
# but do not block commits.
exit 0
