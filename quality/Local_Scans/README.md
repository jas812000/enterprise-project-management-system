# Local Quality Gate

## 1. Overview

The Local Quality Gate is the advisory static-analysis and security-scanning component of the Quality Gates framework.

It supports local execution and optional Git pre-commit integration. It collects scanner results, normalizes findings, generates an HTML report, and packages selected reporting artifacts into a timestamped ZIP archive.

The Local Quality Gate does not enforce quality thresholds or block Git commits. The CI Quality Gate is responsible for policy-driven enforcement.

For instructions on integrating Quality Gates into another repository, see [Project Configuration and Portability](../PORTABILITY.md).

## 2. Supported Scanners

Nine scanners are registered.

| Scanner    | Purpose                                  |
|------------|------------------------------------------|
| Checkstyle | Java coding standards and style          |
| PMD        | Java static analysis and maintainability |
| SpotBugs   | Java bytecode defect analysis            |
| Pylint     | Python code quality                      |
| Bandit     | Python security analysis                 |
| Gitleaks   | Secret detection                         |
| TruffleHog | Secret detection through Docker          |
| HTMLHint   | HTML validation                          |
| Stylelint  | CSS and related stylesheet analysis      |

Scanner applicability depends on the host project's source configuration and the scanner's execution requirements.

A registered scanner is not necessarily executed successfully. Results distinguish completed scans, disabled scanners, findings, and execution errors.

### 2.1 Scanner Outcomes

| Outcome  | Meaning                                       |
|----------|-----------------------------------------------|
| CLEAN    | Scanner executed without reported findings    |
| FINDINGS | Scanner executed and reported findings        |
| DISABLED | Scanner was not applicable or was disabled    |
| ERROR    | Scanner execution or result processing failed |

A disabled scanner must not be interpreted as a successful scan.

The Local Quality Gate reports findings and errors without applying CI enforcement policy.

## 3. Prerequisites

### 3.1 Core Requirements

- Git
- Python 3.10 or newer
- A supported shell environment for the shell launcher
- Required scanner dependencies for the source languages being analyzed

The launcher searches for a compatible Python interpreter before loading project configuration.

### 3.2 Java Requirements

The current Java scanner wrappers are designed for Maven-based Java projects.

They require:

- A compatible Java Development Kit
- Maven or an executable Maven Wrapper
- Java source paths configured for the host repository
- Network access for initial scanner downloads when distributions are missing

Checkstyle, PMD, and SpotBugs use scanner distributions under:

`quality/Local_Scans/tools/`

The wrappers can download their required distributions when needed.

SpotBugs compiles the configured Maven project before performing bytecode analysis.

Non-Maven Java projects and repositories without Java source may require additional wrapper changes or scanner-disable support.

### 3.3 Additional Scanner Requirements

Depending on the configured sources and enabled scanners:

- Pylint and Bandit require their Python dependencies.
- Stylelint uses Node.js/npm for its scanner dependencies.
- TruffleHog requires an available Docker executable and functioning Docker engine.
- Other scanners may require additional runtime dependencies or network access.

Python scanner dependencies are listed in:

`quality/Local_Scans/requirements-scanners.txt`

Install dependencies in an appropriate Python environment. Avoid installing project-specific dependencies globally unless that is your intended development setup.

### 3.4 Operating Systems

The Local Quality Gate has been exercised on macOS.

The shell launcher is intended for compatible Unix-like environments.

A Windows batch launcher is provided and uses Git Bash, but Windows execution remains unverified.

Cross-project and fresh-environment portability testing is also outstanding.

## 4. Project Configuration

The framework supports project-specific configuration through:

`quality/project.json`

A reusable template is provided at:

`quality/project.example.json`

Create the host project's configuration from the example and adjust it to match the actual repository structure.

Example:

```json
{
  "project_name": "Example Project",
  "java_version": "25",
  "scan_root": ".",
  "java_source": "backend/src/main/java",
  "maven_project": "backend",
  "python_source": "",
  "javascript_source": "",
  "safe_jdbc_urls": []
}
```

### 4.1 Configuration Fields

| Field               | Purpose                                                                             |
|---------------------|-------------------------------------------------------------------------------------|
| `project_name`      | Host-project name used by framework reporting and configuration                     |
| `java_version`      | Configured Java version                                                             |
| `scan_root`         | Repository-relative scan scope                                                      |
| `java_source`       | Repository-relative Java source directory                                           |
| `maven_project`     | Repository-relative Maven project directory                                         |
| `python_source`     | Repository-relative Python analysis directory                                       |
| `javascript_source` | Repository-relative JavaScript/HTML/CSS discovery root                              |
| `safe_jdbc_urls`    | Narrowly defined exceptions for supported unverified JDBC secret-detection findings |

Configured paths must remain within the host repository.

The project configuration loader validates supplied values and produces shell-safe configuration exports.

An invalid configuration can stop the launcher before scanners execute.

The example paths are illustrative. They must not be assumed to match every consuming repository.

For configuration validation rules, JDBC exception details, and cross-project integration, see [PORTABILITY.md](../PORTABILITY.md).

## 5. Directory Structure

The Local Quality Gate uses the following primary components:

```text
quality/
├── project.json
├── project.example.json
├── project_config.py
├── PORTABILITY.md
├── shared/
├── CI/
└── Local_Scans/
    ├── README.md
    ├── run-local-checks.sh
    ├── run-local-checks.bat
    ├── scanner_registry.py
    ├── local_pipeline.py
    ├── local_normalize.py
    ├── requirements-scanners.txt
    ├── checks/
    ├── config/
    ├── hooks/
    ├── reporting/
    ├── tests/
    ├── tools/
    └── reports/
```

The Local Quality Gate reuses components from `quality/CI/` and shared framework utilities.

Consequently, copying only `quality/Local_Scans/` is not a supported standalone installation method.

The `tools/` and `reports/` directories contain downloaded dependencies or generated artifacts and should not be committed.

## 6. Running the Local Quality Gate

### 6.1 macOS and Linux

From the host repository root:

```bash


```

The launcher:

1. Resolves the host repository root.
2. Finds a compatible Python interpreter.
3. Loads and validates available project configuration.
4. Prepares the local report workspace.
5. Runs Checkstyle, PMD, and SpotBugs wrappers.
6. Runs additional scanners through the Python orchestration layer.
7. Normalizes results using shared scanner parsers.
8. Generates a consolidated HTML report.
9. Creates and validates a timestamped ZIP archive.
10. Attempts to open the HTML report.
11. Prints the final advisory scanner summary.

The scanner registry defines the nine supported scanner names.

Scanner execution errors and findings are reported separately.

### 6.2 Windows

The Windows launcher is:

```bat
quality\Local_Scans\run-local-checks.bat
```

The batch launcher uses Git Bash to execute the shell-based workflow.

Windows compatibility has not yet been verified through a complete test run.

## 7. Git Pre-Commit Integration

The advisory pre-commit hook is located at:

`quality/Local_Scans/hooks/pre-commit`

### 7.1 Enable the Hook

From the host repository root:

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
chmod +x quality/Local_Scans/hooks/pre-commit
```

Verify the configuration:

```bash
git config --show-origin --get core.hooksPath
```

Expected configured path:

```text
quality/Local_Scans/hooks
```

### 7.2 Hook Behavior

When Git invokes the hook, it runs the Local Quality Gate.

The hook reports scanner findings and execution problems but does not reject the commit.

If the launcher returns a nonzero status, the hook prints a warning and still exits successfully.

If the launcher script is missing, the hook also prints a warning without blocking the commit.

This advisory behavior is intentional.

### 7.3 Disable the Custom Hook Configuration

If the repository uses this hook configuration and you want to restore Git's default hook lookup:

```bash
git config --local --unset core.hooksPath
```

Be aware that `core.hooksPath` is a repository-level setting. Changing it can affect other custom hooks.

## 8. Reports and Artifacts

### 8.1 HTML Report

The consolidated HTML report is written to:

`quality/Local_Scans/reports/work/local-report.html`

The launcher attempts to open this report in the default browser.

If automatic opening fails, open the file manually.

### 8.2 Normalized Results

The report workspace includes:

`quality/Local_Scans/reports/work/normalized.json`

The pipeline also maintains normalized scanner results under the reports directory for subsequent summary processing.

Normalized results provide a consistent representation of scanner execution status and findings.

### 8.3 ZIP Archive

The launcher generates a timestamped archive under:

`quality/Local_Scans/reports/`

The filename follows this pattern:

`local-quality-report-YYYY-MM-DD-HHMMSS.zip`

Before reporting success, the launcher verifies:

- ZIP integrity.
- Presence of the HTML report.
- Presence of normalized JSON.
- Exclusion of prohibited raw scanner artifacts.

Raw scanner outputs may be retained locally for diagnostics, but prohibited raw secret-scanner artifacts are not included in the distributable ZIP.

### 8.4 Generated Artifacts

Downloaded scanner tools and generated reports should remain outside version control.

Do not commit generated reports containing potentially sensitive findings.

## 9. Exit Codes and Advisory Behavior

The Local Quality Gate distinguishes scanner outcomes from infrastructure failures.

| Condition                                             | Launcher behavior                                 |
|-------------------------------------------------------|---------------------------------------------------|
| Completed scan with no findings                       | Returns `0`                                       |
| Completed advisory scan with findings                 | Returns `0`                                       |
| Completed advisory scan with scanner execution errors | Returns `0` when reporting completes successfully |
| Invalid project configuration                         | Can return `2`                                    |
| Missing compatible Python interpreter                 | Returns `2`                                       |
| Report generation or validation failure               | Can terminate with a nonzero status               |
| Invalid final normalized summary                      | Returns `2`                                       |

A zero exit code means the advisory workflow completed; it does not guarantee that every scanner ran successfully or that no findings exist.

Always inspect the scanner summary and generated report.

The pre-commit hook intentionally returns `0` for handled launcher failures, allowing the Git commit to proceed.

## 10. Relationship to the CI Quality Gate

Quality Gates has two execution contexts.

| Capability           | Local Quality Gate                        | CI Quality Gate                                      |
|----------------------|-------------------------------------------|------------------------------------------------------|
| Primary execution    | Developer machine                         | GitHub Actions                                       |
| Scanner inventory    | Nine registered scanners                  | Twelve registered scanners                           |
| Enforcement          | Advisory                                  | Policy-driven                                        |
| Git commit blocking  | No                                        | Not directly; repository rules can require CI checks |
| Reporting            | HTML, normalized JSON, ZIP                | CI reports and workflow artifacts                    |
| Shared functionality | Reuses CI parsers and framework utilities | Uses shared framework utilities                      |

The CI Quality Gate evaluates results using its configured policy.

See:

- `quality/CI/README.md`
- `quality/CI/gate/policy.yaml`

Local findings should be addressed before opening a pull request, even though the local hook does not block commits.

## 11. Testing and Verification

Local regression tests are located under:

`quality/Local_Scans/tests/`

From the repository root, run:

```bash
python3 -m unittest discover \
  -s quality/Local_Scans/tests \
  -p 'test_*.py' \
  -v
```

Project configuration tests can be run with:

```bash
python3 -m unittest quality.shared.tests.test_project_config -v
```

These tests validate selected framework behaviors. They do not replace full scanner execution or testing in a separate host repository.

Before declaring a new installation successful, verify configuration loading, scanner applicability, report generation, and actual scanner execution.

## 12. Troubleshooting

### 12.1 Python Is Missing or Unsupported

Check:

```bash
python3 --version
```

Python 3.10 or newer is required.

### 12.2 Java or Maven Is Unavailable

Check:

```bash
java -version
mvn -version
```

If using a Maven Wrapper, verify that it exists and is executable.

### 12.3 Scanner Downloads Fail

Check network access and the availability of the required download tools.

Review the corresponding wrapper under `quality/Local_Scans/checks/`.

### 12.4 TruffleHog Reports a Docker Error

Verify that Docker is installed and the Docker engine is running.

A Docker execution failure must not be interpreted as a clean secret scan.

### 12.5 Scanner Is Disabled

Review the configured source directories and scanner prerequisites.

A disabled scanner has not completed an analysis.

### 12.6 HTML Report Does Not Open

Open:

`quality/Local_Scans/reports/work/local-report.html`

### 12.7 Git Hook Does Not Execute

Check:

```bash
git config --local --get core.hooksPath
ls -l quality/Local_Scans/hooks/pre-commit
```

Ensure that the configured hooks directory is correct and the hook is executable.

### 12.8 Project Configuration Is Rejected

Review `quality/project.json` for unsupported values, invalid paths, or missing configured directories.

Use `quality/project.example.json` as the starting template.

## 13. Portability and Known Limitations

The Quality Gates framework is being prepared for use outside its original host repository.

Current limitations include:

- Java wrappers designed for Maven-based projects.
- Incomplete verification of non-Java-only repositories.
- Unverified Windows execution.
- Unverified fresh-environment scanner installation.
- Incomplete validation in a separate consuming repository.
- CI workflow integration that still requires cross-project verification.

The framework should not yet be described as universally compatible or fully standalone.

For the integration procedure and portability validation plan, see [PORTABILITY.md](../PORTABILITY.md).

## 14. Documentation

Additional technical documentation:

- [Scanner wrappers](checks/checks_README.md)
- [Git hooks](hooks/hooks_README.md)
- [Reporting](reporting/report_README.md)
- [Portability and integration](../PORTABILITY.md)
- [CI Quality Gate](../CI/README.md)

These documents should be maintained together whenever scanner behavior, configuration, or reporting contracts change.
