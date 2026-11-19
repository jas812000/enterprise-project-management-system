#!/bin/sh

# ==========================================================
# Checkstyle — Local Quality Gate
# ----------------------------------------------------------
# Runs Checkstyle against the configured Java source directory.
# Downloads and verifies the Checkstyle JAR when needed.
#
# Arguments:
#   $1 — REPO_ROOT
#   $2 — WORK_DIR (temporary directory for raw output)
#   $3 — TOOLS_DIR (directory for scanner dependencies)
#
# Output:
#   $2/checkstyle.xml
#
# Exit codes:
#   0 — Passed: no violations
#   1 — Failed: Checkstyle violations detected
#   2 — Error: scanner could not execute successfully
# ==========================================================

set -eu

REPO_ROOT="$1"
WORK_DIR="$2"
TOOLS_DIR="$3"

JAVA_SRC="${QUALITY_JAVA_SOURCE:-${REPO_ROOT}/backend/src/main/java}"
CHECKSTYLE_CONFIG="${REPO_ROOT}/quality/Local_Scans/config/checkstyle.xml"

CHECKSTYLE_VERSION="14.4.0"
CHECKSTYLE_JAR="${TOOLS_DIR}/checkstyle-${CHECKSTYLE_VERSION}-all.jar"
CHECKSTYLE_URL="https://github.com/checkstyle/checkstyle/releases/download/checkstyle-${CHECKSTYLE_VERSION}/checkstyle-${CHECKSTYLE_VERSION}-all.jar"
CHECKSTYLE_SHA256="c0a521e54eabd1ece4ff7bd84cf231b012615745ed3aa4fb27ab0ca73097250c"

OUT="${WORK_DIR}/checkstyle.xml"

# ----------------------------------------------------------
# Use the Python interpreter selected by the orchestrator
# ----------------------------------------------------------

if [ -z "${PYTHON_CMD:-}" ]; then
  echo "ERROR: PYTHON_CMD is not set. Run Checkstyle through the Local Quality Gate orchestrator." >&2
  exit 2
fi

run_python() {
  if [ "${PYTHON_CMD}" = "py" ]; then
    py -3 "$@"
  else
    "${PYTHON_CMD}" "$@"
  fi
}

# ----------------------------------------------------------
# Calculate SHA-256 checksum
# ----------------------------------------------------------


calculate_sha256() {
  run_python - "$1" <<'PY'
import hashlib
import sys

digest = hashlib.sha256()

with open(sys.argv[1], "rb") as jar_file:
    for chunk in iter(lambda: jar_file.read(1024 * 1024), b""):
        digest.update(chunk)

print(digest.hexdigest())
PY
}


# ----------------------------------------------------------
# Prepare Checkstyle output
# ----------------------------------------------------------

if ! mkdir -p "${WORK_DIR}"; then
  echo "ERROR: Cannot create Checkstyle output directory: ${WORK_DIR}" >&2
  exit 2
fi

if ! rm -f "${OUT}"; then
  echo "ERROR: Cannot remove previous Checkstyle report: ${OUT}" >&2
  exit 2
fi


# ----------------------------------------------------------
# Verify Java availability
# ----------------------------------------------------------

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java is required to run Checkstyle but was not found on PATH." >&2
  exit 2
fi


# ----------------------------------------------------------
# Verify source directory exists
# ----------------------------------------------------------

if [ ! -d "${JAVA_SRC}" ]; then
  echo "ERROR: Checkstyle source directory not found: ${JAVA_SRC}" >&2
  exit 2
fi


# ----------------------------------------------------------
# Verify Checkstyle configuration
# ----------------------------------------------------------

if [ ! -f "${CHECKSTYLE_CONFIG}" ] || [ ! -r "${CHECKSTYLE_CONFIG}" ]; then
  echo "ERROR: Checkstyle configuration is missing or unreadable: ${CHECKSTYLE_CONFIG}" >&2
  exit 2
fi


# ----------------------------------------------------------
# Download Checkstyle if missing
# ----------------------------------------------------------

if [ ! -f "${CHECKSTYLE_JAR}" ]; then
  echo "Downloading Checkstyle ${CHECKSTYLE_VERSION}..."

  if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required to download Checkstyle." >&2
    exit 2
  fi

  if ! mkdir -p "${TOOLS_DIR}"; then
    echo "ERROR: Cannot create Checkstyle tools directory: ${TOOLS_DIR}" >&2
    exit 2
  fi

  TEMP_JAR="${CHECKSTYLE_JAR}.tmp.$$"

  if ! curl -fsSL -o "${TEMP_JAR}" "${CHECKSTYLE_URL}"; then
    rm -f "${TEMP_JAR}"
    echo "ERROR: Failed to download Checkstyle ${CHECKSTYLE_VERSION}." >&2
    exit 2
  fi

  if [ ! -s "${TEMP_JAR}" ]; then
    rm -f "${TEMP_JAR}"
    echo "ERROR: Downloaded Checkstyle JAR is empty." >&2
    exit 2
  fi

  if ! mv "${TEMP_JAR}" "${CHECKSTYLE_JAR}"; then
    rm -f "${TEMP_JAR}"
    echo "ERROR: Cannot install Checkstyle JAR." >&2
    exit 2
  fi

  echo "Checkstyle downloaded successfully."
fi


# ----------------------------------------------------------
# Verify Checkstyle JAR integrity
# ----------------------------------------------------------

if [ ! -s "${CHECKSTYLE_JAR}" ]; then
  echo "ERROR: Checkstyle JAR is missing or empty: ${CHECKSTYLE_JAR}" >&2
  exit 2
fi

if ! ACTUAL_SHA256="$(calculate_sha256 "${CHECKSTYLE_JAR}")"; then
  echo "ERROR: Unable to calculate Checkstyle JAR checksum." >&2
  exit 2
fi

if [ "${ACTUAL_SHA256}" != "${CHECKSTYLE_SHA256}" ]; then
  echo "ERROR: Checkstyle JAR checksum mismatch." >&2
  echo "Expected: ${CHECKSTYLE_SHA256}" >&2
  echo "Actual:   ${ACTUAL_SHA256}" >&2
  exit 2
fi

echo "Checkstyle JAR integrity verified."


# ----------------------------------------------------------
# Run Checkstyle
# ----------------------------------------------------------

CHECKSTYLE_EXIT=0

run_python - "${CHECKSTYLE_JAR}" "${CHECKSTYLE_CONFIG}" "${JAVA_SRC}" "${OUT}" <<'PY' || CHECKSTYLE_EXIT=$?
import pathlib
import subprocess
import sys

jar_path, config_path, source_path, output_path = sys.argv[1:]

java_files = sorted(
    str(path)
    for path in pathlib.Path(source_path).rglob("*.java")
    if path.is_file()
)

if not java_files:
    print("ERROR: No Java source files found.", file=sys.stderr)
    sys.exit(2)

command = [
    "java", "-jar", jar_path,
    "-c", config_path,
    "-f", "xml",
    "-o", output_path,
    *java_files,
]

try:
    result = subprocess.run(command, check=False)
except OSError as exc:
    print(f"ERROR: Unable to execute Checkstyle: {exc}", file=sys.stderr)
    sys.exit(2)

sys.exit(result.returncode)
PY


# ----------------------------------------------------------
# Validate Checkstyle XML output
# ----------------------------------------------------------

if [ ! -s "${OUT}" ]; then
  echo "ERROR: Checkstyle did not generate a non-empty XML report." >&2
  exit 2
fi

if ! run_python - "${OUT}" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
    if root.tag != "checkstyle":
        raise ValueError("Unexpected XML root element")
except (ET.ParseError, OSError, ValueError) as exc:
    print(f"ERROR: Invalid Checkstyle XML report: {exc}", file=sys.stderr)
    sys.exit(1)
PY
then
  exit 2
fi


# ----------------------------------------------------------
# Count Checkstyle violations from validated XML
# ----------------------------------------------------------

if ! COUNT="$(run_python - "${OUT}" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
print(len(root.findall(".//error")))
PY
)"; then
  echo "ERROR: Unable to count Checkstyle violations." >&2
  exit 2
fi


# ----------------------------------------------------------
# Verify scanner exit status matches reported violations
# ----------------------------------------------------------

if [ "${CHECKSTYLE_EXIT}" -ne 0 ] && [ "${COUNT}" -eq 0 ]; then
  echo "ERROR: Checkstyle returned exit code ${CHECKSTYLE_EXIT} without reporting violations." >&2
  exit 2
fi

if [ "${COUNT}" -eq 0 ]; then
  echo "Checkstyle passed. No findings found."
  exit 0
else
  echo "Checkstyle completed. ${COUNT} findings detected."
  exit 1
fi
