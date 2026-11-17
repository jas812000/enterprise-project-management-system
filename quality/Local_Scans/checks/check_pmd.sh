#!/bin/sh

# ==========================================================
# PMD - Local Check
# ----------------------------------------------------------
# Runs PMD against backend/src/main/java.
# Downloads PMD to the supplied tools directory on first run.
#
# Arguments:
#   $1 - REPO_ROOT
#   $2 - WORK_DIR
#   $3 - TOOLS_DIR
#
# Output:
#   $2/pmd.xml
#
# Exit codes:
#   0 - passed (no violations)
#   1 - failed (violations detected)
#   2 - error (scanner or prerequisite failure)
# ==========================================================

set -eu

REPO_ROOT="$1"
WORK_DIR="$2"
TOOLS_DIR="$3"

JAVA_SRC="${QUALITY_JAVA_SOURCE:-${REPO_ROOT}/backend/src/main/java}"

PMD_VERSION="7.29.0"
PMD_DIR="${TOOLS_DIR}/pmd-bin-${PMD_VERSION}"
PMD_ZIP="${TOOLS_DIR}/pmd-dist-${PMD_VERSION}-bin.zip"
PMD_URL="https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip"

OUT="${WORK_DIR}/pmd.xml"

# ----------------------------------------------------------
# Prepare output directory
# ----------------------------------------------------------

mkdir -p "${WORK_DIR}"
rm -f "${OUT}"

# ----------------------------------------------------------
# Verify prerequisites
# ----------------------------------------------------------

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java is required to run PMD." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: Python 3 is required to validate PMD XML reports." >&2
  exit 2
fi

if [ ! -d "${JAVA_SRC}" ]; then
  echo "ERROR: Java source directory not found: ${JAVA_SRC}" >&2
  exit 2
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required to download PMD." >&2
  exit 2
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "ERROR: unzip is required to install PMD." >&2
  exit 2
fi

# ----------------------------------------------------------
# Download PMD
# ----------------------------------------------------------

mkdir -p "${TOOLS_DIR}"

if [ ! -f "${PMD_ZIP}" ]; then
  echo "Downloading PMD ${PMD_VERSION}..."

  if ! curl -fL --retry 3 \
      -o "${PMD_ZIP}.tmp" "${PMD_URL}"; then
    rm -f "${PMD_ZIP}.tmp"
    echo "ERROR: PMD download failed." >&2
    exit 2
  fi

  if ! mv "${PMD_ZIP}.tmp" "${PMD_ZIP}"; then
    echo "ERROR: Unable to save PMD archive." >&2
    exit 2
  fi

  echo "PMD downloaded."
fi

# ----------------------------------------------------------
# Validate archive
# ----------------------------------------------------------

if ! unzip -tq "${PMD_ZIP}" >/dev/null 2>&1; then
  echo "ERROR: PMD archive validation failed." >&2
  exit 2
fi

# ----------------------------------------------------------
# Extract PMD
# ----------------------------------------------------------

if [ ! -d "${PMD_DIR}" ]; then
  echo "Extracting PMD ${PMD_VERSION}..."

  if ! unzip -q "${PMD_ZIP}" -d "${TOOLS_DIR}"; then
    echo "ERROR: PMD extraction failed." >&2
    exit 2
  fi

  echo "PMD extracted."
fi

# ----------------------------------------------------------
# Verify PMD executable
# ----------------------------------------------------------

if [ ! -f "${PMD_DIR}/bin/pmd" ]; then
  echo "ERROR: PMD executable not found." >&2
  exit 2
fi

if [ ! -x "${PMD_DIR}/bin/pmd" ]; then
  echo "ERROR: PMD executable is not executable." >&2
  exit 2
fi

# ----------------------------------------------------------
# Execute PMD
# ----------------------------------------------------------

echo "Running PMD ${PMD_VERSION}..."

PMD_EXIT=0

"${PMD_DIR}/bin/pmd" check \
  --dir "${JAVA_SRC}" \
  --rulesets "${REPO_ROOT}/quality/Local_Scans/config/pmd-ruleset.xml" \
  --format xml \
  --report-file "${OUT}" \
  || PMD_EXIT=$?

# ----------------------------------------------------------
# Reject scanner execution errors
# ----------------------------------------------------------

# PMD exit codes:
#   0 - no violations
#   4 - violations detected
#   Other - scanner execution or analysis error

case "${PMD_EXIT}" in
  0|4)
    ;;
  *)
    echo "ERROR: PMD execution failed (exit code ${PMD_EXIT})." >&2
    exit 2
    ;;
esac

# ----------------------------------------------------------
# Validate XML report
# ----------------------------------------------------------

if [ ! -s "${OUT}" ]; then
  echo "ERROR: PMD did not produce an XML report." >&2
  exit 2
fi

if ! python3 - "${OUT}" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()

    if root.tag.rsplit("}", 1)[-1] != "pmd":
        raise ValueError("Unexpected XML root element")

except (ET.ParseError, OSError, ValueError) as exc:
    print(
        f"ERROR: Invalid PMD XML report: {exc}",
        file=sys.stderr,
    )
    sys.exit(1)
PY
then
  exit 2
fi

# ----------------------------------------------------------
# Count violations
# ----------------------------------------------------------

VIOLATION_COUNT="$(python3 - "${OUT}" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()

count = sum(
    1
    for element in root.iter()
    if element.tag.rsplit("}", 1)[-1] == "violation"
)

print(count)
PY
)"

# ----------------------------------------------------------
# Determine final scanner status
# ----------------------------------------------------------

case "${PMD_EXIT}" in
  0)
    if [ "${VIOLATION_COUNT}" -ne 0 ]; then
      echo "ERROR: PMD reported success but XML contains violations." >&2
      exit 2
    fi

    echo "PMD passed. No violations found."
    exit 0
    ;;

  4)
    if [ "${VIOLATION_COUNT}" -eq 0 ]; then
      echo "ERROR: PMD reported violations but XML contains none." >&2
      exit 2
    fi

    echo "PMD failed. ${VIOLATION_COUNT} violation(s) detected."
    exit 1
    ;;
esac
