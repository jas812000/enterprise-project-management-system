#!/bin/sh
# Local Quality Gate — SpotBugs
#
# Arguments:
#   $1 - REPO_ROOT
#   $2 - WORK_DIR
#   $3 - TOOLS_DIR
#
# Output: WORK_DIR/spotbugs.xml
#
# Exit codes:
#   0 - Analysis succeeded; no findings
#   1 - Analysis succeeded; findings detected
#   2 - Execution or report validation error

set -eu

if [ "$#" -ne 3 ]; then
  echo "ERROR: Expected REPO_ROOT WORK_DIR TOOLS_DIR." >&2
  exit 2
fi

REPO_ROOT="$1"
WORK_DIR="$2"
TOOLS_DIR="$3"

BACKEND_DIR="${QUALITY_MAVEN_DIR:-${REPO_ROOT}/backend}"
CLASSES="${BACKEND_DIR}/target/classes"

VERSION="4.10.5"
SPOTBUGS_DIR="${TOOLS_DIR}/spotbugs-${VERSION}"
ARCHIVE="${TOOLS_DIR}/spotbugs-${VERSION}.tgz"
URL="https://github.com/spotbugs/spotbugs/releases/download/${VERSION}/spotbugs-${VERSION}.tgz"

# Published SpotBugs 4.10.5 release checksum.
EXPECTED_SHA256="cce3244900cc215ccd9f5a8db29ed9468fc47824faf662f4fad005343d96007e"

OUT="${WORK_DIR}/spotbugs.xml"
CLASSPATH_FILE="${WORK_DIR}/spotbugs-classpath.txt"

error() {
  echo "ERROR: $*" >&2
  exit 2
}

command -v java >/dev/null 2>&1 ||
  error "Java is not installed."

command -v python3 >/dev/null 2>&1 ||
  error "Python 3 is required for XML validation."

[ -f "${BACKEND_DIR}/pom.xml" ] ||
  error "Backend Maven project not found: ${BACKEND_DIR}"

if [ -f "${BACKEND_DIR}/mvnw" ]; then
  MAVEN="${BACKEND_DIR}/mvnw"
  [ -x "${MAVEN}" ] ||
    error "Maven wrapper is not executable."
elif command -v mvn >/dev/null 2>&1; then
  MAVEN="$(command -v mvn)"
else
  error "Maven is unavailable."
fi

mkdir -p "${WORK_DIR}" "${TOOLS_DIR}" ||
  error "Unable to create working directories."

rm -f "${OUT}" "${CLASSPATH_FILE}"

if [ ! -x "${SPOTBUGS_DIR}/bin/spotbugs" ]; then
  command -v curl >/dev/null 2>&1 ||
    error "curl is required to download SpotBugs."

  if [ ! -f "${ARCHIVE}" ]; then
    echo "Downloading SpotBugs ${VERSION}..."
    curl -fL --retry 3 --output "${ARCHIVE}" "${URL}" ||
      error "SpotBugs download failed."
  fi

  if [ -z "${EXPECTED_SHA256}" ]; then
  error "SpotBugs SHA-256 checksum is not configured."
fi

if [ -n "${EXPECTED_SHA256}" ]; then
    python3 - "${ARCHIVE}" "${EXPECTED_SHA256}" <<'PY'
import hashlib
import sys
from pathlib import Path

archive = Path(sys.argv[1])
expected = sys.argv[2]
actual = hashlib.sha256(archive.read_bytes()).hexdigest()

if actual != expected:
    raise SystemExit(
        f"ERROR: SpotBugs checksum mismatch: {actual}"
    )

print("PASSED: SpotBugs checksum verified.")
PY
  fi

  echo "Extracting SpotBugs..."
  tar -xzf "${ARCHIVE}" -C "${TOOLS_DIR}" ||
    error "SpotBugs extraction failed."

  [ -f "${SPOTBUGS_DIR}/bin/spotbugs" ] ||
    error "SpotBugs executable not found after extraction."

  chmod +x "${SPOTBUGS_DIR}/bin/spotbugs" ||
    error "Unable to make SpotBugs executable."
fi

echo "Compiling configured Maven project..."

(
  cd "${BACKEND_DIR}"
  "${MAVEN}" --batch-mode -q -DskipTests compile
) || error "Maven compilation failed."

[ -d "${CLASSES}" ] ||
  error "Compiled classes directory does not exist."

CLASS_COUNT="$(find "${CLASSES}" -type f -name '*.class' |
  wc -l | tr -d ' ')"

[ "${CLASS_COUNT}" -gt 0 ] ||
  error "No compiled Java classes were found."

echo "Building Maven dependency classpath..."

(
  cd "${BACKEND_DIR}"
  "${MAVEN}" --batch-mode -q \
    dependency:build-classpath \
    -Dmdep.outputFile="${CLASSPATH_FILE}"
) || error "Unable to build dependency classpath."

[ -s "${CLASSPATH_FILE}" ] ||
  error "Dependency classpath is missing or empty."

echo "Analyzing ${CLASS_COUNT} compiled class file(s)..."

SPOTBUGS_EXIT=0

"${SPOTBUGS_DIR}/bin/spotbugs" \
  -textui \
  -exitcode \
  -xml:withMessages \
  -effort:max \
  -auxclasspath "$(cat "${CLASSPATH_FILE}")" \
  -output "${OUT}" \
  "${CLASSES}" || SPOTBUGS_EXIT=$?

case "${SPOTBUGS_EXIT}" in
  0|1|4)
    # Continue to XML validation.
    # Exit 1 is accepted provisionally because SpotBugs has
    # returned it despite reporting zero analysis errors.
    ;;

  2|3|5|6|7)
    error "SpotBugs reported analysis errors or missing classes (exit ${SPOTBUGS_EXIT})."
    ;;

  *)
    error "SpotBugs terminated unexpectedly (exit ${SPOTBUGS_EXIT})."
    ;;
esac

[ -s "${OUT}" ] ||
  error "SpotBugs did not produce an XML report."

COUNT="$(
  python3 - "${OUT}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

path = Path(sys.argv[1])

try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as exc:
    print(f"ERROR: Invalid SpotBugs XML: {exc}", file=sys.stderr)
    sys.exit(2)

if root.tag.rsplit("}", 1)[-1] != "BugCollection":
    print("ERROR: Unexpected SpotBugs XML root.", file=sys.stderr)
    sys.exit(2)

errors = root.find("Errors")

if errors is None:
    print("ERROR: SpotBugs XML is missing the Errors element.", file=sys.stderr)
    sys.exit(2)

analysis_errors = int(errors.get("errors", "-1"))
missing_classes = int(errors.get("missingClasses", "-1"))

if analysis_errors != 0 or missing_classes != 0:
    print(
        f"ERROR: SpotBugs analysis errors={analysis_errors}, "
        f"missing classes={missing_classes}.",
        file=sys.stderr,
    )
    sys.exit(2)

count = sum(
    1 for element in root
    if element.tag.rsplit("}", 1)[-1] == "BugInstance"
)

print(count)
PY
)" || error "Unable to validate SpotBugs XML."

if [ "${COUNT}" -gt 0 ]; then
  echo "SpotBugs completed. ${COUNT} findings detected."
  exit 1
fi

echo "SpotBugs passed. No findings found."
exit 0
