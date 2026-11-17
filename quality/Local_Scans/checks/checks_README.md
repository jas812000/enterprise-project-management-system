# EPMS Static Analysis Checks

## 1. Overview

The `checks/` directory contains the scanner execution scripts used by the EPMS Local Quality Gate (BN1).

Each script executes one static analysis tool against the Java backend, generates machine-readable results, and returns a standardized exit code.

The main quality-gate runner coordinates all applicable scanners. This section originally described the three Java wrappers.

## Current scanner inventory

The BN1 runner registers Checkstyle, PMD, SpotBugs, Pylint, Bandit, Gitleaks, TruffleHog, HTMLHint, and Stylelint. Seven were clean for EPMS on 2026-11-17; HTMLHint and Stylelint were disabled because no applicable source was configured.

Configure repository-relative paths in `quality/project.json` and run from the repository root:

```bash
bash quality/Local_Scans/run-local-checks.sh
```

Docker must be running for containerized TruffleHog. An execution error is not a clean scan. The pre-commit hook is advisory. See `quality/PORTABILITY.md`.

## 2. Static Analysis Tools

| Script | Tool | Version | Purpose |
|---|---|---|---|
| `check_checkstyle.sh` | Checkstyle | 14.4.0 | Java coding standards and formatting |
| `check_pmd.sh` | PMD | 7.29.0 | Code quality and maintainability |
| `check_spotbugs.sh` | SpotBugs | 4.10.5 | Bytecode defect analysis |

The scanners analyze production Java code under:

`backend/src/main/java/`

SpotBugs additionally compiles the backend and builds its Maven dependency classpath before analysis.

## 3. Execution Flow

The scanner scripts are executed by:

`quality/Local_Scans/run-local-checks.sh`

Execution sequence:

1. Checkstyle
2. PMD
3. SpotBugs
4. HTML report generation
5. ZIP report packaging

All three scanners execute even when an earlier scanner reports violations.

Each scanner wrapper receives three arguments:

1. `REPO_ROOT` — EPMS repository root.
2. `WORK_DIR` — Directory for generated scanner results.
3. `TOOLS_DIR` — Directory for scanner dependencies.

The scanner scripts are intended to run through the main quality-gate runner rather than as standalone commands.

## 4. Exit Codes

| Exit Code | Meaning |
|---|---|
| `0` | Scanner completed successfully with no violations |
| `1` | Scanner completed and detected violations |
| `2` | Scanner execution or report-validation error |

The main runner collects these results and determines the overall gate status.

When configured as a Git pre-commit hook, any nonzero overall result blocks the commit.

## 5. Scanner Dependencies

Scanner distributions are stored under:

`quality/Local_Scans/tools/`

The scripts automatically download missing scanner distributions when their required utilities and network access are available.

- Checkstyle verifies its JAR using a configured SHA-256 checksum.
- PMD validates the downloaded ZIP archive.
- SpotBugs verifies its downloaded archive using a configured SHA-256 checksum during installation.

The `tools/` directory is excluded from Git.

Scanner distributions must not be committed.

## 6. Output Files

Raw scanner results are written to:

`quality/Local_Scans/reports/work/`

The primary XML artifacts are:

```text
checkstyle.xml
pmd.xml
spotbugs.xml
```

These files are processed by the Python reporting engine to generate the consolidated HTML report.

Generated reports are excluded from version control.

## 7. Design Principles

Scanner scripts should:

1. Produce machine-readable analysis results.
2. Distinguish quality violations from execution errors.
3. Validate generated reports before accepting their results.
4. Avoid modifying production Java source files.
5. Use consistent exit codes.
6. Support repeatable local execution.

The scanners may create build outputs, download dependencies, and generate reports, but must not rewrite production source code.

## 8. Negative Testing

The reusable negative-test fixture is located at:

`quality/fixtures/negative/SampleErrorClass.java`

It intentionally contains defects used to verify scanner detection.

The fixture must remain outside production source directories.

An automated isolated negative-test workflow is planned.

## 9. Current Status

The original Java wrapper and negative-test details above remain technical reference material. The current BN1 also runs Python and secret scanners. On 2026-11-17, seven scanners completed cleanly, two were disabled, and no scanner errors occurred.

Cross-platform and separate-project verification remain outstanding.
