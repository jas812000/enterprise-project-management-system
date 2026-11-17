# Quality Gate — Project Configuration and Portability

## Scope

This is a **partially reusable toolkit**, not a fully tested standalone product. The
local gate (BN1) is advisory; the GitHub Actions CI gate uses its own policy.
EPMS is the reference host. A separate-repository portability test and live
GitHub Actions validation are still required.

## Install in a host repository

1. Copy the complete `quality/` directory into the host repository, retaining
   its relative subdirectories. Also copy/adapt `.github/workflows/build-and-analyze.yml`
   if CI integration is wanted.
2. Copy `quality/project.example.json` to `quality/project.json` and edit paths.
3. Install Python 3.10+, Git, a compatible JDK, Maven or the Maven wrapper,
   and any scanner-specific prerequisites. Docker Desktop/Engine must be running
   for the local TruffleHog container scan.
4. From the repository root, run `bash quality/Local_Scans/run-local-checks.sh`.
5. Review the HTML report and timestamped ZIP, including scanner **errors**
   and disabled tools, not only finding counts.
6. Optionally enable the advisory Git hook:

   ```bash
   git config --local core.hooksPath quality/Local_Scans/hooks
   chmod +x quality/Local_Scans/hooks/pre-commit
   ```

Do not copy generated `reports/`, scanner `tools/`, credentials, or build outputs.

## Configuration (`quality/project.json`)

Paths are relative to the repository root and must remain inside it.
The loader validates the supplied configuration and exports shell-safe values.

| Field | Purpose | EPMS value |
|---|---|---|
| `project_name` | Name in CI scanner reports | `EPMS` |
| `java_version` | Java version for CI setup | `25` |
| `scan_root` | Repository-wide CI scanner scope | `.` |
| `java_source` | Java source directory | `backend/src/main/java` |
| `maven_project` | Maven project directory | `backend` |
| `python_source` | Python analysis directory | `quality` |
| `javascript_source` | JS/CSS/HTML discovery root; empty means none | `""` |
| `safe_jdbc_urls` | Narrow, unverified JDBC false-positive exceptions | See below |

Use `quality/project.example.json` as the template. A host project must
adjust paths to its actual layout; copying EPMS defaults is not sufficient.

When `project.json` exists, configured CI paths override legacy discovery.
An explicitly empty Python/JavaScript path does not trigger discovery.
Without the file, the CI workflow retains legacy discovery and Java 25.
Invalid configuration or nonexistent configured source directories should
stop setup rather than silently scan another directory. The CI workflow
uses the host scan root for Gitleaks and a workspace-relative `/repo` path
for containerized TruffleHog.

### JDBC exception format

An entry has `file` and `jdbc_parts` (`scheme`, `host`, `port`, `database`).
The loader reconstructs the exact JDBC URL at runtime. Only **unverified**
TruffleHog JDBC findings matching that file and URL are suppressed; verified
or different findings remain reportable. Never add credentials, query
parameters, or remote hosts as exceptions.

## Local vs CI

- **BN1**: seven scanners currently complete for EPMS (Checkstyle, PMD,
  SpotBugs, Pylint, Bandit, Gitleaks, TruffleHog). HTMLHint and Stylelint
  are disabled with the current source configuration. BN1 is advisory,
  including its pre-commit hook; zero exit code does not prove zero findings
  or scanner errors. See `Local_Scans/README.md`.
- **CI**: 12 registered scanners, policy-driven enforcement, and reports.
  See `CI/gate/README.md` and `CI/gate/policy.yaml`.

## Known limitations and verification

The Java scanner wrappers require a Maven-based Java project. Non-Java
repositories may need wrapper changes or explicit scanner disable support.
Some report paths, package imports, and scanner assumptions remain tied to
the existing directory structure. Windows and cross-project operation have
not been fully verified.

For EPMS, the most recent local verification (2026-11-17) was 104/104
Python tests, seven clean scanners, two disabled scanners, zero findings,
and zero scanner errors. This does **not** validate a fresh installation,
a non-EPMS repository, or a live GitHub Actions run.
