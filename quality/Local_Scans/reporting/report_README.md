# Local Quality Gate — Reporting Engine

## 1. Overview

The `quality/Local_Scans/reporting/` directory contains the Python reporting components for the Local Quality Gate.

The reporting workflow consolidates results from ten registered scanners into a normalized JSON document and an HTML report.

The Local Quality Gate is advisory.

Scanner findings, disabled scanners, and handled scanner execution errors are reported without automatically blocking Git commits.

The reporting engine is invoked by the main Local Quality Gate launcher.

## 2. Reporting Architecture

The Local Quality Gate coordinates ten scanners:

| Scanner | Category |
|---|---|
| Checkstyle | Java style analysis |
| PMD | Java static analysis |
| SpotBugs | Java bytecode analysis |
| Pylint | Python static analysis |
| Bandit | Python security analysis |
| Gitleaks | Secret detection |
| TruffleHog | Secret detection |
| HTMLHint | HTML analysis |
| Stylelint | Stylesheet analysis |

The Java scanners produce reports through their scanner wrappers.

The additional scanners are coordinated through the local Python pipeline.

The pipeline combines the scanner results using shared normalization and parsing utilities from the CI Quality Gate.

## 3. Directory Structure

```text
reporting/
├── generate_report.py
├── open_report.py
├── report_html.py
├── report_parsers.py
├── report_README.md
└── render_report/
    ├── __init__.py
    ├── report_badges.py
    ├── report_builder.py
    ├── report_constants.py
    ├── report_controls.py
    ├── report_rows.py
    ├── report_scripts.py
    ├── report_sections.py
    └── report_styles.py
```

Generated Python bytecode caches are not part of the maintained source structure.

## 4. Reporting Components

| Component | Responsibility |
|---|---|
| `generate_report.py` | Reads scanner artifacts, validates normalized input structure, prepares report context, and writes HTML |
| `report_parsers.py` | Parses Checkstyle, PMD, and SpotBugs reports |
| `report_html.py` | Provides the `build_html(context)` interface |
| `render_report/` | Implements modular HTML report rendering |
| `open_report.py` | Attempts to open the generated HTML report |
| `local_pipeline.py` | Coordinates additional scanners and produces normalized results |
| `run-local-checks.sh` | Coordinates execution, reporting, ZIP packaging, validation, and final summary |

The last two components reside outside the `reporting/` directory.

The HTML entry point delegates rendering to the `render_report` package.

## 5. Report Generation Workflow

The reporting process follows this sequence:

```text
Local Quality Gate launcher
          |
          v
Java scanner wrappers
          |
          v
Additional scanner pipeline
          |
          v
Shared normalization utilities
          |
          v
Normalized JSON results
          |
          v
HTML report generator
          |
          v
HTML rendering components
          |
          v
local-report.html
          |
          v
ZIP packaging and validation
          |
          v
Final advisory summary
```

The HTML report generator also reads the original Java scanner XML reports.

The reporting process does not replace scanner-specific diagnostics with an assumption that all tools completed successfully.

## 6. Normalized Scanner Results

The local pipeline writes normalized results to:

`quality/Local_Scans/reports/normalized/normalized.json`

It copies the normalized document to:

`quality/Local_Scans/reports/work/normalized.json`

The working-directory copy is consumed by the HTML report generator and included in the ZIP archive.

The normalized document contains results for ten registered scanners.

The HTML generator validates that:

1. The document is a JSON object.
2. `tool_count` equals `9`.
3. `results` is a list.
4. The results list contains ten entries.

If the normalized document is missing or structurally invalid, the HTML generator prints a warning and may render without normalized data.

The main launcher separately validates the normalized results when producing its final summary.

Missing required scanner records cause that final summary validation to fail.

## 7. Scanner Outcome Classification

The final Local Quality Gate summary uses four outcome categories:

| Outcome | Meaning |
|---|---|
| CLEAN | Scanner executed without reported findings |
| FINDINGS | Scanner executed and reported findings |
| DISABLED | Scanner did not execute because it was disabled |
| ERROR | Scanner execution failed or could not be reliably confirmed |

A disabled scanner is not equivalent to a clean scanner.

A scanner execution error is not equivalent to a successful scan.

The summary also reports completed scanners, disabled scanners, scanner errors, and total findings.

Findings and scanner execution errors are advisory outcomes rather than automatic commit-blocking decisions.

## 8. Generated Artifacts

The main reporting locations are:

```text
quality/Local_Scans/reports/
├── normalized/
│   └── normalized.json
├── raw/
│   └── scanner-specific artifacts
├── work/
│   ├── checkstyle.xml
│   ├── pmd.xml
│   ├── spotbugs.xml
│   ├── normalized.json
│   └── local-report.html
└── local-quality-report-YYYY-MM-DD-HHMMSS.zip
```

The working directory may contain additional generated files.

The raw directory retains scanner-specific artifacts for local investigation.

The timestamped ZIP archive is generated from files in the working directory.

The directory structure above illustrates the principal artifacts, not an exhaustive inventory of every possible generated file.

## 9. HTML Report

The HTML report is written to:

`quality/Local_Scans/reports/work/local-report.html`

The report combines:

- Scanner execution status.
- Normalized findings.
- Java scanner findings from raw XML.
- Finding counts.
- Severity information.
- Available file and location details.
- Interactive report presentation components.

The renderer uses the `build_html(context)` interface.

The context includes scanner statuses, findings, severity summaries, normalized results, report metadata, and advisory gate mode.

The HTML report should be reviewed alongside the final console summary when investigating scanner errors or disabled tools.

## 10. ZIP Packaging and Validation

The launcher creates a timestamped archive:

`quality/Local_Scans/reports/local-quality-report-YYYY-MM-DD-HHMMSS.zip`

The archive is generated using Python's `zipfile` module.

Files under the report working directory are recursively added to the archive.

The completed archive is reopened and validated.

Validation includes:

1. Running `ZipFile.testzip()` to detect corrupt archive members.
2. Confirming that `local-report.html` exists in the archive.
3. Confirming that `normalized.json` exists in the archive.
4. Rejecting archive entries containing a `raw` path component.
5. Rejecting prohibited raw scanner JSON filenames.

The explicitly prohibited filenames include:

- `trufflehog.json`
- `gitleaks.json`
- `bandit.json`
- `pylint.json`

Raw scanner artifacts remain available locally under `reports/raw/`.

The ZIP validation establishes archive integrity and required artifact presence.

It does not guarantee that every scanner executed successfully or returned zero findings.

## 11. Advisory Enforcement and Error Handling

The Local Quality Gate distinguishes scanner findings from execution failures.

Scanner findings are recorded and reported.

Handled scanner execution errors are also recorded and reported.

When reporting, packaging, and final summary generation complete successfully, the Local Quality Gate exits `0`, including when scanner findings or scanner execution errors were recorded.

This is intentional advisory behavior.

Infrastructure or reporting failures may cause the launcher to exit with a nonzero status.

If the final normalized summary is invalid or unavailable, the launcher exits `2`.

The provided Git pre-commit hook handles launcher failures as advisory warnings and exits `0`.

The CI Quality Gate has separate policy-driven enforcement.

## 12. Running the Reporting Workflow

The supported execution method is the main Local Quality Gate launcher.

From the consuming repository root:

```bash
sh quality/Local_Scans/run-local-checks.sh
```

The launcher coordinates scanner execution, normalization, HTML generation, ZIP packaging, archive validation, and final summary output.

The reporting modules are internal components and may require environment variables or arguments established by the launcher.

Developers should normally invoke the launcher rather than calling individual reporting modules directly.

## 13. Python Requirements

The Local Quality Gate requires Python 3.10 or later.

Python supports:

- Scanner-result parsing.
- Normalization.
- HTML rendering.
- ZIP creation and validation.
- Final summary generation.
- Report-opening utilities.

The main launcher selects a compatible Python interpreter before loading project configuration.

Some scanner wrappers have separate Python invocation assumptions documented in the scanner wrapper README.

## 14. Reporting Tests

Reporting-related tests are maintained under:

`quality/Local_Scans/tests/`

Relevant test modules include:

- `test_pmd_parser.py`
- `test_report_renderer.py`
- `test_report_status.py`
- `test_local_pipeline.py`
- `test_additional_scanners.py`
- `test_console_outcomes.py`
- `test_local_launcher.py`

Run the local test suite from the repository root:

```bash
python3 -m unittest discover \
  -s quality/Local_Scans/tests \
  -p 'test_*.py' \
  -v
```

These tests cover selected parser, rendering, status, pipeline, scanner integration, and launcher behaviors.

Passing unit tests does not establish complete portability across independent host repositories.

## 15. Negative Testing

The framework includes an intentionally defective Java fixture:

`quality/fixtures/negative/SampleErrorClass.java`

The fixture supports testing whether scanners detect known defects.

It must remain outside production source directories.

Successful defect detection does not imply that the advisory Local Quality Gate blocks commits.

An isolated automated negative-test workflow remains planned.

## 16. Relationship to the CI Quality Gate

The Local Quality Gate and CI Quality Gate share normalization and parsing concepts but have different enforcement responsibilities.

| Component | Reporting | Enforcement |
|---|---|---|
| Local Quality Gate | HTML, normalized JSON, ZIP, console summary | Advisory |
| CI Quality Gate | CI reports and workflow artifacts | Policy-driven |

CI enforcement depends on the configured quality policy.

Repository branch protection or rulesets determine whether CI checks are required before merging.

See `quality/CI/README.md`.

## 17. Portability Status

The reporting workflow has been exercised in its original host repository.

Independent host-repository integration remains unverified.

Windows execution and fresh-environment verification remain outstanding.

The reporting components depend on the framework's directory structure, scanner artifacts, normalization utilities, and launcher-provided environment.

Do not treat successful report generation as proof that all registered scanners completed successfully.

## 18. Related Documentation

- `quality/Local_Scans/README.md` — Local Quality Gate overview
- `quality/Local_Scans/checks/checks_README.md` — Scanner wrappers
- `quality/Local_Scans/hooks/hooks_README.md` — Git hook integration
- `quality/CI/README.md` — CI Quality Gate
- `quality/PORTABILITY.md` — Cross-project integration
- `quality/project.example.json` — Project configuration template

Trivy requires the `trivy` executable on PATH and scans filesystem vulnerabilities, misconfigurations and secrets. Missing or invalid execution is an error.
