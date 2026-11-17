# EPMS Local Quality Gate (BN1)

## 1. Overview

The Enterprise Project Management System (EPMS) Local Quality Gate (BN1) performs automated static analysis of the Java backend to identify coding-standard violations, maintainability issues, potential defects, and security-related problems.

The local quality gate uses three scanners:

1. **Checkstyle** — Checks Java source code against established coding standards.
2. **PMD** — Detects potential programming errors, code-quality issues, and maintainability concerns.
3. **SpotBugs** — Analyzes compiled Java bytecode for potential defects and security-related issues.

The quality gate generates an HTML report and a timestamped ZIP archive containing the analysis results.

When configured as a Git pre-commit hook, BN1 reports scanner findings and execution errors but does not block commits.

The quality gate can also be executed manually without creating a commit.

---

## 2. Development Requirements

The current EPMS development environment uses:

| Component | Version / Requirement |
|---|---|
| Java | Java 25 LTS |
| Spring Boot | 4.1.1 |
| Build System | Maven Wrapper |
| Database | PostgreSQL 18.6 |
| Python | 3.10 or newer |
| Version Control | Git |
| Operating System | macOS (verified) |

Java and Python must be available in the development environment.

The scanner distributions must also be available in the locations expected by their corresponding execution scripts.

PostgreSQL is required for application integration tests but is not directly required by every static analysis scanner.

### 2.1 Static Analysis Tools

| Tool | Version | Purpose |
|---|---|---|
| Checkstyle | Managed by scanner wrapper | Coding standards and formatting |
| PMD | 7.29.0 | Code quality and maintainability |
| SpotBugs | 4.10.5 | Bytecode defect analysis |

### 2.2 Scanner Binaries

Scanner distributions are stored in:

`quality/Local_Scans/tools/`

This directory is excluded from Git.

Downloaded scanner binaries must not be committed to the repository.

On a new development machine, the required scanner distributions must be installed or obtained according to the requirements of the individual scanner wrappers.

Setup guidance is provided in `quality/PORTABILITY.md`; fresh-environment reproducibility testing remains outstanding.

---

## 3. Directory Structure

The local quality gate uses the following organization:

```text
quality/
├── fixtures/
│   └── negative/
│       └── SampleErrorClass.java
│
└── Local_Scans/
    ├── .gitignore
    ├── README.md
    │
    ├── checks/
    │   ├── check_checkstyle.sh
    │   ├── check_pmd.sh
    │   └── check_spotbugs.sh
    │
    ├── config/
    │   ├── checkstyle.xml
    │   └── pmd-ruleset.xml
    │
    ├── hooks/
    │   └── pre-commit
    │
    ├── reporting/
    │   ├── generate_report.py
    │   ├── report_parsers.py
    │   └── open_report.py
    │
    ├── reports/
    │   └── work/
    │       └── local-report.html
    │
    ├── tests/
    │
    ├── tools/
    │
    ├── run-local-checks.sh
    └── run-local-checks.bat
```

The `reports/` and `tools/` directories are excluded from version control.

---

## Current scanner inventory and project configuration

Configure `quality/project.json` using `quality/project.example.json`. The Java wrappers require a compatible JDK and Maven project; Python 3.10+ and Git are required. Start Docker Desktop/Engine before running TruffleHog. If Docker cannot start its container, TruffleHog can report exit code 125 as an execution error rather than a finding.

| Scanner | EPMS result (2026-11-17) |
|---|---|
| Checkstyle, PMD, SpotBugs | Clean |
| Pylint, Bandit | Clean |
| Gitleaks, TruffleHog | Clean |
| HTMLHint, Stylelint | Disabled: no applicable source |

## 4. Running the Local Quality Gate

### 4.1 macOS / Linux

From the EPMS repository root:

```bash
sh quality/Local_Scans/run-local-checks.sh
```

Alternatively, from the `backend/` directory:

```bash
sh ../quality/Local_Scans/run-local-checks.sh
```

The runner performs the following operations:

1. Verifies that Python 3.10 or newer is available.
2. Prepares the working report directory.
3. Executes Checkstyle.
4. Executes PMD.
5. Compiles the backend and executes SpotBugs.
6. Processes the scanner results.
7. Generates the consolidated HTML report.
8. Packages the report artifacts into a timestamped ZIP archive.
9. Attempts to open the HTML report in the default browser.
10. Displays the overall quality-gate result.

All three scanners are executed even when an earlier scanner reports violations.

### 4.2 Windows

A Windows batch launcher is provided:

```bat
quality\Local_Scans\run-local-checks.bat
```

The launcher uses Git Bash to execute the shell-based quality gate.

**Windows support remains unverified.** The batch launcher requires additional testing, particularly its path handling and environment configuration.

---

## 5. Git Pre-Commit Hook

### 5.1 Purpose

The pre-commit hook automatically executes BN1 when a developer attempts a normal Git commit.

The hook is located at:

`quality/Local_Scans/hooks/pre-commit`

### 5.2 Configure the Hook

From anywhere within the EPMS Git repository:

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
```

This configures Git to use the project's custom hooks directory.

### 5.3 Configure Executable Permissions

On macOS or Linux, from the repository root:

```bash
chmod +x quality/Local_Scans/hooks/pre-commit
```

Alternatively, from `backend/`:

```bash
chmod +x ../quality/Local_Scans/hooks/pre-commit
```

Verify the permissions:

```bash
ls -l ../quality/Local_Scans/hooks/pre-commit
```

Expected executable permissions include:

```text
-rwxr-xr-x
```

### 5.4 Verify the Git Configuration

```bash
git config --show-origin --get core.hooksPath
```

Expected configured path:

```text
quality/Local_Scans/hooks
```

### 5.5 Enforcement Behavior

| Condition | Result |
|---|---|
| All scanners pass | Hook permits the commit to proceed |
| Checkstyle violations | Commit blocked |
| PMD violations | Commit blocked |
| SpotBugs findings | Commit blocked |
| Scanner execution error | Commit blocked |
| Required script missing | Commit blocked |

The pre-commit hook was successfully tested through direct execution on macOS.

Git's automatic invocation during an actual commit remains to be verified.

---

## 6. Exit Codes

The main quality-gate runner returns:

| Exit Code | Meaning |
|---|---|
| `0` | All scanners passed |
| `1` | One or more scanners reported quality violations |
| `2` | One or more scanner execution errors occurred |

The pre-commit hook is advisory and permits commits even when the scanner runner reports errors; inspect the report rather than relying on the commit exit status.

Scanner execution errors are distinguished from legitimate quality violations so that environmental problems are not incorrectly reported as code defects.

---

## 7. Reports and Results

### 7.1 HTML Report

The consolidated HTML report is generated at:

`quality/Local_Scans/reports/work/local-report.html`

The report provides an overview of the quality-gate results and individual scanner findings.

It includes:

- Overall quality-gate status.
- Checkstyle results.
- PMD results.
- SpotBugs results.
- Severity classifications.
- Finding descriptions and source locations where available.
- Interactive filtering controls.

### 7.2 ZIP Archive

A timestamped ZIP archive is generated in:

`quality/Local_Scans/reports/`

The filename follows this format:

```text
local-quality-report-YYYY-MM-DD-HHMMSS.zip
```

The ZIP archive contains the generated analysis artifacts from the working report directory.

### 7.3 Severity Classifications

The reporting system supports five severity levels:

| Severity | General Meaning |
|---|---|
| Critical | Highest-priority findings |
| High | Significant issues requiring attention |
| Medium | Moderate-priority issues |
| Low | Lower-priority findings |
| Info | Informational findings |

Each scanner's native severity or priority values are mapped to these classifications.

Not every scanner produces findings at every severity level.

### 7.4 Generated Artifacts

The following directories are excluded from Git:

```text
quality/Local_Scans/tools/
quality/Local_Scans/reports/
```

Generated reports and downloaded scanner distributions should remain local to the development environment.

---

## 8. Negative-Test Fixture

### 8.1 Purpose

The shared negative-test fixture is located at:

`quality/fixtures/negative/SampleErrorClass.java`

This class intentionally contains coding-standard violations, maintainability problems, and potential defects.

It is designed to verify that the static analysis tools detect problematic Java code.

### 8.2 Fixture Requirements

The fixture must:

1. Remain outside the production Java source directory.
2. Never be included in a production application build or deployment.
3. Remain available for repeatable negative testing.
4. Be version-controlled as a shared testing resource.
5. Be reusable by future local and CI quality-gate tests.

The production source directory is:

`backend/src/main/java/`

### 8.3 Future Automation

An isolated negative-test workflow is planned.

This workflow should verify that known violations are detected and that the quality gate returns the appropriate failure status without modifying production source files.

---

## 9. Troubleshooting

### 9.1 Java Version Problems

Verify the installed Java version:

```bash
java -version
```

EPMS currently targets Java 25 LTS.

### 9.2 Python Not Found

Verify Python:

```bash
python3 --version
```

Python 3.10 or newer is required.

The runner also checks for compatible Python commands available in the environment.

### 9.3 Scanner Distribution Missing

Verify that the required scanner distributions exist under:

`quality/Local_Scans/tools/`

Review the relevant script under `checks/` for expected paths, filenames, and installation behavior.

### 9.4 HTML Report Does Not Open

Open the following file manually in a browser:

`quality/Local_Scans/reports/work/local-report.html`

### 9.5 PMD Incremental Analysis Warning

PMD 7.29.0 may display a warning recommending Incremental Analysis.

This is an informational performance recommendation, not a quality-gate failure.

Incremental Analysis is deferred as a future improvement.

### 9.6 Git Hook Does Not Execute

Verify the hooks configuration:

```bash
git config --local --get core.hooksPath
```

Verify executable permissions on macOS/Linux:

```bash
ls -l ../quality/Local_Scans/hooks/pre-commit
```

The hook must be executable for Git to invoke it.

---

## 10. Relationship to the CI Quality Gate

BN1 is local and advisory; it does not block commits. The CI Quality Gate runs on GitHub Actions and evaluates scanner results using `quality/CI/gate/policy.yaml`.

| Capability | BN1 — Local | CI Quality Gate |
|---|---|---|
| Execution | Manual or pre-commit | PRs and pushes to `main` |
| Scanners | 9 registered; 7 applicable to EPMS | 12 registered |
| Reports | HTML and timestamped ZIP | Markdown, HTML, analysis artifact |
| Enforcement | Advisory | Policy-driven; required checks depend on GitHub rulesets |
| Verification | 2026-11-17 local run | GitHub Actions runs #19 and #20 succeeded |

See `quality/CI/gate/README.md` for technical contracts and CI reporting.

## 11. Verified Results

The following tests have been completed during BN1 development:

### 11.1 Negative Testing

A deliberately defective Java class was used to verify scanner detection.

All three Java scanners detected violations in this historical negative test; the current BN1 hook is advisory and does not reject commits.

### 11.2 Clean Static Analysis

After resolving the identified violations, all three scanners passed:

| Scanner | Result | Findings |
|---|---|---|
| Checkstyle | PASSED | 0 |
| PMD | PASSED | 0 |
| SpotBugs | PASSED | 0 |

Overall result: **APPROVED**

### 11.3 Maven Tests

The EPMS backend test suite completed successfully:

- Tests executed: 77
- Failures: 0
- Errors: 0
- Skipped: 0

### 11.4 Pre-Commit Hook Test

The pre-commit hook was executed directly on macOS.

Results:

- Git hooks path verified.
- Executable permissions verified.
- All three scanners passed.
- HTML report generated.
- ZIP report generated.
- Hook exit status: `0`.

No Git commit was created during that earlier direct-hook test; a later Git-triggered commit was verified on 2026-11-17.

---

## 12. Future Improvements

The following enhancements are deferred:

1. Implement PMD Incremental Analysis to improve scanning performance.
2. Automate isolated negative-test execution using the shared fixture.
3. Verify the Windows batch launcher on a Windows development machine.
4. Establish reproducible scanner installation for fresh development environments.
5. Maintain alignment between BN1 reporting and the implemented CI Quality Gate.
6. Verify Git's automatic pre-commit invocation during an approved commit.

---

## 13. Current Status

BN1 is implemented and advisory. On 2026-11-17 seven scanners (Checkstyle, PMD, SpotBugs, Pylint, Bandit, Gitleaks, TruffleHog) completed cleanly, HTMLHint and Stylelint were disabled, and there were zero findings and zero scanner errors.

The hook ran during Git commit `40760b9`. The earlier three-scanner and Java test results in section 11 are historical development checks, not the current complete scanner inventory. Fresh-environment and cross-project portability remain to be verified.
