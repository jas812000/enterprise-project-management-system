# Local Quality Gate — Scanner Wrappers

## 1. Overview

The `quality/Local_Scans/checks/` directory contains scanner execution components used by the Local Quality Gate.

Three shell wrappers execute Java static-analysis tools:

- Checkstyle
- PMD
- SpotBugs

Additional scanner components support Pylint, Bandit, Gitleaks, TruffleHog, HTMLHint, and Stylelint.

The Local Quality Gate registers ten scanners in total.

The main launcher coordinates execution, collects scanner outcomes, normalizes results, and generates consolidated reports.

For general execution instructions, see `quality/Local_Scans/README.md`.

For integration into another repository, see `quality/PORTABILITY.md`.

## 2. Java Scanner Inventory

| Wrapper | Scanner | Version | Purpose |
|---|---|---|---|
| `check_checkstyle.sh` | Checkstyle | 14.4.0 | Java coding standards and formatting |
| `check_pmd.sh` | PMD | 7.29.0 | Static code quality and maintainability |
| `check_spotbugs.sh` | SpotBugs | 4.10.5 | Bytecode defect analysis |

The wrappers use the following project configuration variables:

| Variable | Used by | Purpose |
|---|---|---|
| `QUALITY_JAVA_SOURCE` | Checkstyle, PMD | Java source directory |
| `QUALITY_MAVEN_DIR` | SpotBugs | Maven project directory |

These variables are normally supplied by `quality/project_config.py` through the main launcher.

Without explicit configuration, the wrappers retain legacy defaults:

- Java source: `backend/src/main/java`
- Maven project: `backend`

Those defaults are not appropriate for every consuming repository.

## 3. Wrapper Arguments

Each Java wrapper accepts three positional arguments:

1. `REPO_ROOT` — Root directory of the consuming repository.
2. `WORK_DIR` — Temporary directory for scanner results.
3. `TOOLS_DIR` — Directory containing downloaded scanner dependencies.

The wrappers are intended to be invoked by:

`quality/Local_Scans/run-local-checks.sh`

Direct execution is not the normal user workflow.

The main launcher prepares the execution environment and supplies the required arguments and configuration.

## 4. Execution Sequence

The main launcher attempts the Java scanners in this order:

1. Checkstyle
2. PMD
3. SpotBugs

Each scanner's result is recorded independently.

A finding or execution error in one Java scanner does not automatically prevent the remaining Java scanners from being attempted.

After these wrappers execute, the launcher invokes:

`quality.Local_Scans.local_pipeline`

The Python pipeline coordinates additional scanners and normalization.

The Local Quality Gate then generates the consolidated report and ZIP archive.

The complete workflow is documented in `quality/Local_Scans/README.md`.

## 5. Exit-Code Contract

The Java wrappers use the following exit-code convention:

| Exit code | Meaning |
|---|---|
| `0` | Scanner completed successfully without findings |
| `1` | Scanner completed and detected findings |
| `2` | Scanner execution or report-validation error |

The main launcher translates these outcomes into scanner statuses for reporting.

An execution error must not be treated as a clean scan.

### Advisory Enforcement

The Local Quality Gate is advisory.

Scanner findings and handled scanner execution errors are reported but do not block commits through the provided pre-commit hook.

Successful completion of the reporting workflow may return exit code `0` even when scanner findings or errors were recorded.

This differs from the CI Quality Gate, which applies its configured enforcement policy.

See `quality/CI/README.md`.

## 6. Scanner Dependencies

Downloaded Java scanner distributions are stored under:

`quality/Local_Scans/tools/`

The wrappers download missing distributions when the required utilities and network access are available.

### Checkstyle

Checkstyle requires Java.

Its wrapper downloads the configured JAR and validates it against the expected SHA-256 checksum.

It also uses the Python interpreter selected by the main launcher for checksum processing.

### PMD

PMD requires Java and utilities including:

- Python 3
- curl
- unzip

The wrapper downloads and validates the PMD distribution archive.

### SpotBugs

SpotBugs requires:

- Java
- Python 3
- Maven or an executable Maven Wrapper
- Download and extraction utilities when installation is needed

The wrapper validates the downloaded distribution against its configured SHA-256 checksum.

SpotBugs compiles the configured Maven project and builds the dependency classpath before analyzing compiled bytecode.

### Generated Dependencies

Downloaded scanner distributions must not be committed to version control.

The `tools/` directory is intended for generated local dependencies.

## 7. Raw Scanner Outputs

The Java wrappers generate machine-readable XML results:

| Scanner | Output |
|---|---|
| Checkstyle | `checkstyle.xml` |
| PMD | `pmd.xml` |
| SpotBugs | `spotbugs.xml` |

These files are written to the working directory supplied by the main launcher.

During normal execution, that directory is under:

`quality/Local_Scans/reports/work/`

The reporting pipeline processes scanner results to produce normalized findings and consolidated reports.

Raw scanner artifacts are working files and should not be committed.

## 8. Additional Scanners

The Local Quality Gate also registers:

- Pylint
- Bandit
- Gitleaks
- TruffleHog
- HTMLHint
- Stylelint

Their execution is coordinated through the Local Quality Gate's Python-based orchestration components.

Applicability depends on the configured source layout, scanner implementation, and available prerequisites.

Possible normalized outcomes include:

- CLEAN
- FINDINGS
- DISABLED
- ERROR

A disabled scanner has not performed analysis.

A scanner execution error is not equivalent to a clean result.

## 9. Design Requirements

Scanner components should:

1. Produce machine-readable results.
2. Distinguish findings from execution failures.
3. Validate reports before accepting successful outcomes.
4. Avoid modifying production source files.
5. Respect the consuming repository's configured source paths.
6. Support repeatable execution.
7. Preserve scanner-specific diagnostic information.
8. Integrate with the shared normalization and reporting workflow.

Scanner execution may create temporary files, downloaded dependencies, and build outputs.

It must not intentionally rewrite production source code.

## 10. Negative Testing

The framework includes a negative-test fixture at:

`quality/fixtures/negative/SampleErrorClass.java`

The fixture intentionally contains defects for scanner-detection testing.

It must remain outside production source directories.

An isolated, automated negative-test workflow remains planned.

## 11. Portability Considerations

The current Java wrappers assume a Maven-based Java project.

They are not yet verified for arbitrary Gradle-based, Python-only, or JavaScript-only consuming repositories.

Checkstyle and PMD use configurable Java source paths.

SpotBugs uses a configurable Maven project path.

Some wrappers currently invoke `python3` directly, while Checkstyle uses the interpreter selected by the main launcher.

This difference requires further portability review.

Windows execution and independent host-repository integration remain unverified.

Do not interpret successful configuration loading as proof that every scanner can execute successfully.

## 12. Related Documentation

- `quality/Local_Scans/README.md` — Local Quality Gate overview
- `quality/Local_Scans/hooks/hooks_README.md` — Advisory Git hook
- `quality/Local_Scans/reporting/report_README.md` — Reporting
- `quality/CI/README.md` — CI Quality Gate
- `quality/PORTABILITY.md` — Cross-project integration
- `quality/project.example.json` — Project configuration template

Trivy requires the `trivy` executable on PATH and scans filesystem vulnerabilities, misconfigurations and secrets. Missing or invalid execution is an error.
