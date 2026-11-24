# Quality Gates — Portability and Integration Guide

## 1. Purpose

Quality Gates is a reusable static-analysis and security-scanning framework intended for integration into software repositories.

It provides two execution contexts:

- **Local Quality Gate:** Advisory scanning, local reports, and optional Git pre-commit integration.
- **CI Quality Gate:** GitHub Actions integration with policy-driven quality and security evaluation.

The framework also includes shared scanner parsers, configuration utilities, reporting components, and tests.

This guide explains how to incorporate Quality Gates into another repository, configure its source paths, execute local scans, and prepare CI integration.

### Current portability status

Quality Gates is partially reusable, but it has not yet been validated as a fully standalone framework.

The existing implementation has been tested primarily in its original host repository.

A separate-repository integration test, fresh-environment dependency installation, and independent GitHub Actions validation are still required.

The initial integration target is an existing **Maven-based Java project**.

Do not assume that Python-only, JavaScript-only, Gradle-based, or other project types are fully supported without additional verification or changes.

## 2. Framework Architecture

The expected framework layout is:

```text
host-repository/
├── .github/
│   └── workflows/
│       └── build-and-analyze.yml
├── quality/
│   ├── CI/
│   ├── Local_Scans/
│   ├── shared/
│   ├── project.json
│   ├── project.example.json
│   ├── project_config.py
│   └── PORTABILITY.md
└── [host-project source and build files]
```

The Local Quality Gate depends on components under `quality/CI/` and `quality/shared/`.

Therefore, copying only `quality/Local_Scans/` is insufficient.

Preserve the framework's internal directory structure.

The CI workflow is maintained separately under `.github/workflows/`.

## 3. Host Repository Prerequisites

### 3.1 Required for the Local Quality Gate

The host development environment requires:

- Git
- Python 3.10 or newer
- A compatible shell environment
- Appropriate filesystem permissions
- Network access for initial scanner downloads, when needed

For Maven-based Java analysis, also provide:

- A compatible JDK
- Maven or an executable Maven Wrapper
- A valid Maven project
- Java source directories matching the configured paths

### 3.2 Scanner-Specific Dependencies

Additional dependencies depend on the scanners being executed.

| Scanner    | Relevant prerequisite                                     |
|------------|-----------------------------------------------------------|
| Checkstyle | Java, download tooling                                    |
| PMD        | Java, curl, unzip                                         |
| SpotBugs   | Java, Maven or Maven Wrapper, download/extraction tooling |
| Pylint     | Python scanner dependencies                               |
| Bandit     | Python scanner dependencies                               |
| Gitleaks   | Scanner runtime dependencies                              |
| TruffleHog | Docker executable and running Docker engine               |
| HTMLHint   | Applicable HTML source and scanner dependencies           |
| Stylelint  | Node.js/npm and applicable stylesheet source              |

Some Java scanner distributions are downloaded automatically into the framework's local tools directory.

The Python dependency list is located at:

`quality/Local_Scans/requirements-scanners.txt`

The exact scanner installation and execution requirements must be verified in the consuming environment.

### 3.3 Operating Systems

The primary shell launcher is:

`quality/Local_Scans/run-local-checks.sh`

It is intended for macOS/Linux and compatible shell environments.

A Windows batch launcher is also provided:

`quality/Local_Scans/run-local-checks.bat`

The Windows launcher delegates execution to Git Bash.

Windows execution remains unverified.

## 4. Install Quality Gates in an Existing Repository

### Step 1 — Prepare the Host Repository

Open a terminal in the repository that will consume Quality Gates.

Confirm the repository root:

```bash
git rev-parse --show-toplevel
```

Confirm the working tree status:

```bash
git status -sb
```

Prefer using a dedicated integration branch.

Do not overwrite existing project files or GitHub Actions workflows without reviewing their contents.

### Step 2 — Obtain the Framework

Obtain the complete Quality Gates framework from its maintained source repository or an approved source checkout.

At the time of this guide, standalone repository extraction is not complete.

Until extraction is finished, the framework resides in the original host repository.

Copy the complete `quality/` directory into the consuming repository while preserving its internal structure.

Do not copy generated artifacts, downloaded scanner distributions, credentials, or temporary build outputs.

In particular, exclude generated content under:

- `quality/Local_Scans/reports/`
- `quality/Local_Scans/tools/`
- Python `__pycache__/` directories
- Other temporary scanner or build directories

### Step 3 — Verify the Copied Structure

From the consuming repository root, verify:

```bash
test -f quality/project_config.py
test -f quality/project.example.json
test -f quality/Local_Scans/run-local-checks.sh
test -f quality/Local_Scans/local_pipeline.py
test -f quality/Local_Scans/scanner_registry.py
test -d quality/CI
test -d quality/shared
```

If any command fails, the copied framework may be incomplete.

### Step 4 — Review Existing Configuration

If the consuming repository already contains a `quality/` directory, inspect it before copying or merging files.

Do not overwrite unrelated quality tooling.

Review existing Git hooks, scanner configurations, CI workflows, and repository policies before integrating Quality Gates.

## 5. Configure the Host Project

### 5.1 Create the Configuration File

From the consuming repository root:

```bash
cp quality/project.example.json quality/project.json
```

Edit `quality/project.json` to reflect the consuming repository's actual source and build directories.

The example file is a template, not a universal configuration.

### 5.2 Example Maven-Based Java Project

Suppose the consuming repository has this structure:

```text
example-service/
├── pom.xml
├── src/
│   └── main/
│       └── java/
├── quality/
└── .github/
```

A possible configuration is:

```json
{
  "project_name": "Example Service",
  "java_version": "25",
  "scan_root": ".",
  "java_source": "src/main/java",
  "maven_project": ".",
  "python_source": "",
  "javascript_source": "",
  "safe_jdbc_urls": []
}
```

This configuration illustrates a Maven project located at the repository root.

It must still be tested against the scanner wrappers before being considered a verified integration.

### 5.3 Example Maven Subproject

For a repository with a Maven project under `service/`:

```text
example-repository/
├── service/
│   ├── pom.xml
│   └── src/main/java/
├── quality/
└── .github/
```

Use:

```json
{
  "project_name": "Example Repository",
  "java_version": "25",
  "scan_root": ".",
  "java_source": "service/src/main/java",
  "maven_project": "service",
  "python_source": "",
  "javascript_source": "",
  "safe_jdbc_urls": []
}
```

These examples demonstrate configuration syntax and path mapping; they do not establish successful cross-project execution.

## 6. Configuration Reference

The loader accepts these fields:

| Field               | Default                 | Description                              |
|---------------------|-------------------------|------------------------------------------|
| `project_name`      | `Quality Gate`          | Name identifying the host project        |
| `java_version`      | `25`                    | Configured Java version                  |
| `scan_root`         | `.`                     | Repository-relative scan root            |
| `java_source`       | `backend/src/main/java` | Repository-relative Java source          |
| `maven_project`     | `backend`               | Repository-relative Maven project        |
| `python_source`     | Empty                   | Optional Python source root              |
| `javascript_source` | Empty                   | Optional JavaScript/HTML/CSS source root |
| `safe_jdbc_urls`    | Empty list              | Narrow JDBC exception rules              |

All fields are optional at the configuration-loader level because defaults are supplied.

However, omitted fields may result in inappropriate paths for the consuming project.

Explicit configuration is recommended.

### 6.1 Supported Java Versions

The loader accepts:

- Java 8
- Java 11
- Java 17
- Java 21
- Java 25

Acceptance by the configuration loader does not guarantee that every scanner distribution supports every listed Java version.

Verify runtime compatibility separately.

### 6.2 Path Validation

The loader requires configured nonempty paths to be repository-relative.

Absolute paths are rejected.

Paths resolving outside the repository are rejected.

The loader also resolves paths to absolute locations for scanner execution.

Importantly, path validation does not itself verify that every configured directory exists.

A configuration can pass loader validation and still fail during scanner execution.

### 6.3 Optional Language Paths

`python_source` and `javascript_source` may be empty strings.

An empty string indicates that no source directory has been explicitly configured for that language.

When a project configuration is supplied, CI does not automatically substitute its legacy source discovery for explicitly empty Python or JavaScript paths.

Do not assume that empty paths automatically disable every scanner associated with that language.

Scanner-specific applicability must be verified.

### 6.4 Unknown Configuration Keys

Unknown keys are rejected.

This prevents spelling mistakes from being silently ignored.

### 6.5 Project Name

`project_name` must be a nonempty string, after trimming whitespace, and may not exceed 100 characters.

### 6.6 JDBC Exceptions

The `safe_jdbc_urls` field supports narrowly defined exceptions for local PostgreSQL JDBC URLs.

Each rule must contain exactly:

- `file`
- `jdbc_parts`

The `jdbc_parts` object must contain exactly:

- `scheme`
- `host`
- `port`
- `database`

The loader permits only:

- Scheme: `postgresql`
- Host: `localhost`
- Integer port from 1 through 65535
- Database name containing letters, numbers, or underscores

The exception file must be repository-relative and remain inside the repository.

A maximum of 20 exception rules is accepted.

Example:

```json
{
  "safe_jdbc_urls": [
    {
      "file": "service/src/main/resources/application.properties",
      "jdbc_parts": {
        "scheme": "postgresql",
        "host": "localhost",
        "port": 5432,
        "database": "example"
      }
    }
  ]
}
```

The loader reconstructs the JDBC URL from these components.

Do not use these exceptions to suppress real credentials, remote database endpoints, or verified secrets.

The exception mechanism is intended for supported, unverified false-positive findings only.

## 7. Validate Project Configuration

From the consuming repository root:

```bash
python3 quality/project_config.py \
  --shell \
  "$(pwd)" \
  "$(pwd)/quality/project.json"
```

Successful output contains shell-safe assignments such as:

```text
QUALITY_JAVA_SOURCE=...
QUALITY_MAVEN_DIR=...
QUALITY_SCAN_ROOT=...
QUALITY_PYTHON_SOURCE=...
QUALITY_JS_SOURCE=...
QUALITY_PROJECT_NAME=...
QUALITY_JAVA_VERSION=...
QUALITY_CONFIG=...
```

The values should resolve to the consuming repository's intended directories.

If validation fails, correct the configuration before attempting scans.

Do not execute arbitrary shell output from an untrusted configuration or modified loader.

## 8. Install Scanner Dependencies

### 8.1 Python Dependencies

Use a Python environment appropriate for the consuming project.

For example:

```bash
python3 -m venv .venv-quality
. .venv-quality/bin/activate

python -m pip install \
  -r quality/Local_Scans/requirements-scanners.txt
```

The requirements file includes dependencies used by local scanners and framework components.

Verify dependency compatibility with the selected Python interpreter.

### 8.2 Java Dependencies

Confirm Java:

```bash
java -version
```

If using system Maven:

```bash
mvn -version
```

If using a Maven Wrapper, verify that the wrapper exists and is executable.

The scanner wrappers may download Checkstyle, PMD, and SpotBugs distributions on first use.

### 8.3 Docker

For TruffleHog:

```bash
docker version
```

Verify that the Docker engine is running.

A missing or unavailable Docker engine can cause scanner execution errors.

### 8.4 Node.js/npm

For applicable frontend scanning:

```bash
node --version
npm --version
```

Frontend scanner applicability and installation remain subject to the configured source layout and scanner-specific behavior.

## 9. Execute the Local Quality Gate

### 9.1 macOS/Linux

From the consuming repository root:

```bash
sh quality/Local_Scans/run-local-checks.sh
```

The launcher performs configuration loading, scanner execution, normalization, reporting, packaging, and final summary generation.

### 9.2 Windows

The Windows entry point is:

```bat
quality\Local_Scans\run-local-checks.bat
```

Git for Windows/Git Bash is required.

Windows execution remains unverified.

### 9.3 Review Scanner Results

The Local Quality Gate reports ten scanner records.

Possible outcomes include:

- CLEAN
- FINDINGS
- DISABLED
- ERROR

A disabled scanner has not completed analysis.

A scanner execution error is not equivalent to a clean result.

The Local Quality Gate is advisory, so successful workflow completion can return exit code `0` even when findings or scanner errors are present.

Always review the final summary and HTML report.

## 10. Review Generated Reports

The HTML report is generated at:

`quality/Local_Scans/reports/work/local-report.html`

The normalized JSON is available at:

`quality/Local_Scans/reports/work/normalized.json`

A timestamped ZIP archive is created under:

`quality/Local_Scans/reports/`

The ZIP filename follows:

`local-quality-report-YYYY-MM-DD-HHMMSS.zip`

The launcher validates ZIP integrity and checks for required reporting artifacts.

It also rejects prohibited raw scanner artifacts from the ZIP.

Generated reports and downloaded scanner tools should remain excluded from version control.

## 11. Configure the Advisory Git Hook

From the consuming repository root:

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
chmod +x quality/Local_Scans/hooks/pre-commit
```

Verify:

```bash
git config --show-origin --get core.hooksPath
```

The hook invokes the Local Quality Gate before commits.

It reports findings and execution problems without blocking commits.

The hook exits successfully even when the scanner launcher reports a handled failure.

### Existing Hook Considerations

Git supports one configured `core.hooksPath` directory at a time.

If the consuming repository already uses custom Git hooks, do not replace the existing configuration without reviewing how those hooks are managed.

An integration strategy may be needed to preserve existing hooks.

To remove the custom hook-path setting:

```bash
git config --local --unset core.hooksPath
```

This restores Git's default hook-path lookup unless another configuration source defines the setting.

## 12. Integrate the CI Quality Gate

The CI Quality Gate uses GitHub Actions and policy-driven enforcement.

Its workflow currently resides at:

`.github/workflows/build-and-analyze.yml`

Its policy configuration resides at:

`quality/CI/gate/policy.yaml`

### Step 1 — Review Existing Workflows

Inspect the consuming repository's `.github/workflows/` directory.

Do not overwrite an existing workflow with the same filename without reviewing it.

### Step 2 — Copy or Adapt the CI Workflow

Copy the framework's CI workflow into the consuming repository, or integrate its relevant jobs into an existing workflow.

Preserve the required framework paths and dependencies.

The current workflow contains assumptions inherited from the original host repository.

Those assumptions must be reviewed before independent deployment.

### Step 3 — Review Project Configuration Integration

The CI workflow reads:

`quality/project.json`

When present, it invokes the shared configuration loader and exports the resulting `QUALITY_*` variables for subsequent workflow steps.

When the configuration file is absent, the workflow retains legacy scan-root discovery and Java 25 defaults.

Explicit project configuration is recommended for portability.

### Step 4 — Review CI Policy

Inspect:

`quality/CI/gate/policy.yaml`

Confirm that the policy matches the consuming project's requirements.

Do not assume the original host's enforcement policy is appropriate for another organization or repository.

### Step 5 — Review Permissions and Dependencies

Before enabling the workflow, inspect:

- GitHub Actions permissions
- Required secrets and variables
- Java and Python setup
- Maven project paths
- Docker-dependent scanning
- Scanner installation
- Report artifact publication
- Pull-request reporting
- Any original-host-specific assumptions

### Step 5A — Configure the Pull Request Comment Marker

The CI Quality Gate uses a comment marker to identify reports previously published to a pull request.

The marker is defined in:

`quality/CI/gate/report/report_md.py`

Locate the `PR_COMMENT_MARKER` constant.

The current value identifies the original host repository's quality and security report.

Before enabling CI reporting in another repository:

1. Review the existing marker.
2. Replace it with a stable, descriptive marker appropriate for the consuming repository.
3. Keep the marker consistent across subsequent workflow runs.
4. Verify that the CI workflow creates a report comment on the first run and updates that comment on later runs.

The marker is used to locate an existing report comment so the reporting integration can update it rather than create a new comment each time.

Changing the marker after reports have already been published may prevent the integration from recognizing earlier comments, resulting in additional report comments.

This setting is separate from `quality/project.json` and `quality/CI/gate/policy.yaml`.

The original host repository's marker should remain unchanged unless its reporting identity is intentionally being updated.

### Step 6 — Validate in GitHub Actions

Open a test pull request in the consuming repository.

Confirm that the workflow:

1. Starts successfully.
2. Loads the intended project configuration.
3. Executes applicable scanners.
4. Records disabled scanners accurately.
5. Normalizes results.
6. Applies CI policy.
7. Publishes the expected reports.
8. Produces an appropriate check conclusion.

A successful local run does not establish CI portability.

Do not require the CI check for branch protection until its behavior has been verified in the consuming repository.

For implementation details, see:

`quality/CI/README.md`

## 13. Verify the Integration

Use a dedicated integration branch or disposable test repository.

### 13.1 Configuration Validation

Confirm the configuration loader accepts the host configuration.

Check that exported paths match the intended source and build directories.

### 13.2 Local Execution

Run the Local Quality Gate.

Verify that all ten scanner records appear in the final normalized results.

Review the status of every scanner.

Do not treat disabled scanners as successful scans.

### 13.3 Report Validation

Verify that the HTML report exists.

Verify that normalized JSON exists.

Verify that the ZIP archive is generated and passes the launcher's integrity checks.

Confirm that prohibited raw scanner artifacts are excluded from the ZIP.

### 13.4 Git Hook Validation

Enable the advisory pre-commit hook.

Use a disposable test commit to verify that Git invokes the hook.

Confirm that findings or handled scanner failures do not block the commit.

Do not create test commits containing real credentials or sensitive data.

### 13.5 CI Validation

Run the adapted workflow in GitHub Actions.

Confirm scanner execution, policy evaluation, report generation, and check conclusions.

Document any host-specific changes needed for successful execution.

### 13.6 Record Verification Evidence

Record:

- Host repository type
- Operating system
- Python version
- Java and Maven versions, if applicable
- Scanner execution outcomes
- Local report results
- Git hook behavior
- CI workflow result
- Any modifications required for compatibility

A framework should not be declared portable solely because its files can be copied into another repository.

## 14. Troubleshooting

### Configuration Validation Fails

Check for unknown keys, unsupported Java versions, invalid values, and paths escaping the repository.

### Java Scanners Fail

Verify the Maven project directory, Java source directory, JDK, Maven installation, and executable Maven Wrapper.

### Scanner Is Disabled

Review the host source configuration and scanner applicability.

A disabled scanner has not completed analysis.

### Scanner Dependency Is Missing

Install the required runtime or dependency and rerun the Local Quality Gate.

### Docker Scanner Fails

Verify Docker installation, daemon availability, and container execution permissions.

### Report Generation Fails

Review scanner outputs, normalization errors, filesystem permissions, and the local reports directory.

### Git Hook Does Not Run

Verify `core.hooksPath`, hook permissions, and the presence of the launcher.

### CI Workflow Fails

Inspect GitHub Actions logs, configured paths, runtime versions, workflow permissions, scanner dependencies, and policy evaluation.

## 15. Licensing and Attribution

The Quality Gates framework is distributed under the MIT License.

Copyright (c) 2026 James Stevens.

The complete license text is provided in:

`quality/LICENSE`

When copying, modifying, or redistributing Quality Gates:

1. Include the framework's `quality/LICENSE` file.
2. Preserve the original copyright and MIT permission notices.
3. Review any additional licensing requirements introduced by modifications or third-party components.
4. Preserve applicable third-party scanner license terms and notices.

The framework's license is separate from the consuming repository's own license.

A consuming repository may use a different license, provided the MIT License requirements applicable to Quality Gates are satisfied.

Bundled or downloaded third-party scanner tools may have their own licensing requirements. The Quality Gates MIT License does not replace those requirements.

## 16. Framework Maintenance and Upgrades

When updating Quality Gates in a consuming repository:

1. Review the framework's changes.
2. Preserve the consuming project's `quality/project.json`.
3. Preserve deliberate host-specific scanner and policy configuration.
4. Review workflow changes before applying them.
5. Avoid copying generated reports or downloaded tools.
6. Rerun configuration validation.
7. Rerun local regression tests.
8. Rerun applicable scanners.
9. Revalidate the Git hook.
10. Revalidate GitHub Actions.

Until a standalone distribution and upgrade mechanism are established, updates require deliberate review and integration.

## 17. Known Limitations

The following limitations remain:

- Standalone repository extraction is incomplete.
- Independent host-repository validation is outstanding.
- Fresh-environment dependency installation is not fully verified.
- Java scanner wrappers assume Maven-based projects.
- Python-only and JavaScript-only host compatibility is not fully verified.
- Windows execution is not fully verified.
- CI workflow assumptions require independent host review.
- Live GitHub Actions validation in a separate repository is outstanding.

These limitations should be resolved or explicitly documented before publishing Quality Gates as a fully portable framework.

## 18. Related Documentation

- `quality/Local_Scans/README.md` — Local Quality Gate user guide
- `quality/Local_Scans/checks/checks_README.md` — Scanner wrappers
- `quality/Local_Scans/hooks/hooks_README.md` — Git hook integration
- `quality/Local_Scans/reporting/report_README.md` — Reporting
- `quality/CI/README.md` — CI Quality Gate
- `quality/CI/gate/policy.yaml` — CI enforcement policy
- `quality/project.example.json` — Configuration template
- `quality/project_config.py` — Configuration validation

Trivy requires the `trivy` executable on PATH and scans filesystem vulnerabilities, misconfigurations and secrets. Missing or invalid execution is an error.
