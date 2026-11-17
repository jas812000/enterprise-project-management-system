# CI Quality Gate Engine

## Overview

This directory contains the CI Quality Gate Engine for the Enterprise Project Management System (EPMS).

The GitHub Actions workflow is configured to run on pushes to `main` and pull requests targeting `main`. The pipeline is designed to execute 12 static analysis, security, and vulnerability scanners.

The quality gate:

1. Collects raw scanner reports and execution-status files.
2. Normalizes scanner outputs into a consistent schema.
3. Evaluates findings and execution failures against a centralized policy.
4. Generates Markdown and HTML reports.
5. Determines the quality-gate exit code according to the configured enforcement mode.

Scanner execution-status integration with GitHub Actions remains in progress.

GitHub branch protection or repository rulesets must be configured separately to require successful checks before merging.

---

## Pipeline Architecture

The quality gate follows a four-stage pipeline. Each stage has a defined responsibility and produces output for the next stage.

```text
Workflow (GitHub Actions)
        |
        v
Stage 1 - Scanner Execution
  Each scanner produces:
    quality/analysis/raw/<scanner-report>
    quality/analysis/raw/<scanner>.status.json
        |
        v
Stage 2 - Normalization (normalize.py)
  Native XML, JSON, and JSONL reports are converted into:
    quality/analysis/normalized/normalized.json
        |
        v
Stage 3 - Policy Evaluation (policy_engine.py)
  Normalized results are evaluated against policy.yaml:
    quality/analysis/evaluated/evaluated.json
        |
        v
Stage 4 - Reporting (report/report.py)
  Results are rendered into:
    quality/analysis/report.md
    quality/analysis/report.html
```

### Separation of Responsibilities

**GitHub Actions**

- Triggers the pipeline.
- Executes scanners.
- Captures scanner execution statuses.
- Collects raw artifacts.
- Invokes the quality-gate components.
- Publishes reports and artifacts when configured.

**Normalization Layer**

- Parses scanner-specific formats.
- Validates execution statuses.
- Produces consistent normalized results.
- Records scanner runtime errors.
- Does not determine policy thresholds or merge decisions.

**Policy Engine**

- Evaluates normalized findings against `policy.yaml`.
- Identifies policy violations.
- Evaluates scanner execution failures.
- Produces structured policy decisions.

**Gate Engine**

- Applies the configured enforcement mode.
- Determines the final quality-gate exit code.

**Reporting Layer**

- Produces human-readable Markdown and HTML reports.
- Presents scanner findings, execution statuses, and policy decisions.

The workflow coordinates execution. The quality-gate components own normalization, policy evaluation, gate decisions, and report generation.

---

## Tool Inventory

EPMS currently registers 12 scanners.

| Tool | Category | Language | Blocking Configuration | Finding Threshold |
|---|---|---|---|---|
| trufflehog | Secrets Scan | All | Yes | Any finding |
| gitleaks | Secrets Scan | All | Yes | Any finding |
| checkstyle | SAST - Java | Java | Yes | Any violation |
| pmd | SAST - Java | Java | Yes | Medium and above |
| spotbugs | SAST - Java | Java | Yes | Medium and above |
| semgrep | SAST - Multi | Multiple | Yes | High and above |
| pylint | SAST - Python | Python | Yes | High and above |
| bandit | SAST - Python | Python | Yes | Medium and above |
| htmlhint | SAST - Web | HTML | Yes | Any violation |
| stylelint | SAST - Web | CSS/SCSS | Yes | Any violation |
| dependency_check | SCA - Multi | Multiple | Yes | Any vulnerability |
| trivy | SCA - Container/Filesystem | Multiple | Yes | Medium and above |

These thresholds describe the documented scanner policy. The authoritative configuration is `quality/CI/gate/policy.yaml`.

A blocking policy violation causes the gate to fail when enforcement mode is active. Preventing a pull request from merging additionally requires the appropriate GitHub branch protection or ruleset configuration.

Scanner execution-status integration is not yet complete in the GitHub Actions workflow.

---

## Scanner Execution-Status Contract

Each registered scanner requires an execution-status file in:

`quality/analysis/raw/`

The filename follows this convention:

`<scanner>.status.json`

For example:

```text
quality/analysis/raw/trufflehog.status.json
quality/analysis/raw/gitleaks.status.json
quality/analysis/raw/checkstyle.status.json
```

The status file records whether the scanner completed, failed, or was intentionally disabled.

### Supported Scanner Statuses

| Status | Meaning | Requirements |
|---|---|---|
| `completed` | Scanner execution completed | Raw artifact must exist and normalization must succeed |
| `failed` | Scanner execution failed | Recorded as a runtime error, even if a report exists |
| `disabled` | Scanner intentionally did not execute | Nonempty reason required; raw artifact must not exist |

### Status File Examples

**Completed scanner**

```json
{
  "status": "completed",
  "exit_code": 0
}
```

**Failed scanner**

```json
{
  "status": "failed",
  "exit_code": 2,
  "reason": "Scanner execution failed"
}
```

**Intentionally disabled scanner**

```json
{
  "status": "disabled",
  "reason": "No applicable source files"
}
```

### Status Field Requirements

| Field | Type | Required | Description |
|---|---|---|---|
| `status` | String | Yes | Must be `completed`, `failed`, or `disabled` |
| `exit_code` | Integer | No | Scanner process exit code |
| `reason` | String | Conditional | Explanation for scanner execution status |

The `reason` field must contain a nonempty string when the status is `disabled`.

When supplied, `exit_code` must be an integer. Boolean values are not accepted as integer exit codes.

When supplied, `reason` must be a string.

### Execution Status vs. Scanner Findings

Execution status and scanner findings are separate concepts.

A scanner can complete successfully while identifying critical vulnerabilities.

For example:

```json
{
  "status": "completed",
  "exit_code": 0
}
```

This status means the scanner completed execution. It does not mean that the scanner reported zero findings.

The normalization layer processes the raw report, and the policy engine determines whether its findings violate the configured policy.

Similarly, a scanner that produces no report cannot automatically be considered successful.

### Fail-Closed Behavior

The scanner-status implementation follows a fail-closed approach.

The following conditions are recorded as runtime errors:

- Missing scanner-status files.
- Malformed scanner-status JSON.
- Scanner-status content that is not a JSON object.
- Unsupported execution statuses.
- Invalid `exit_code` or `reason` field types.
- Scanners marked `failed`.
- Scanners marked `completed` without a raw artifact.
- Scanners marked `completed` when their parser did not execute successfully.
- Scanners marked `completed` when normalization reports an error.
- Scanners marked `disabled` without a nonempty reason.
- Scanners marked `disabled` when a raw artifact exists.
- Scanner parser exceptions.

A parser exception is isolated to the affected scanner so the normalization process can continue collecting results from other scanners.

Runtime errors are passed to the policy engine for evaluation.

### Disabled Scanners

A scanner is considered disabled only when it has an explicit, valid status file containing:

```json
{
  "status": "disabled",
  "reason": "Documented reason for disabling this scanner"
}
```

A disabled scanner must not produce its normal raw analysis artifact.

The absence of a raw artifact does not, by itself, disable a scanner.

This distinction prevents missing or failed scanner executions from being mistaken for intentionally skipped scans.

### Raw Artifact Registry

Raw artifact filenames are centrally defined in:

`quality/CI/gate/scanner_artifacts.py`

The registry contains one artifact mapping for each registered scanner.

| Scanner | Expected Raw Artifact |
|---|---|
| trufflehog | `trufflehog.jsonl` |
| gitleaks | `gitleaks.json` |
| checkstyle | `checkstyle.xml` |
| pmd | `pmd.xml` |
| spotbugs | `spotbugs.xml` |
| semgrep | `semgrep.json` |
| pylint | `pylint.json` |
| bandit | `bandit.json` |
| htmlhint | `htmlhint.json` |
| stylelint | `stylelint.json` |
| dependency_check | `dependency-check-report.json` |
| trivy | `trivy.json` |

The registry must remain consistent with the scanner parser registrations and policy configuration.

### Workflow Integration Status

The normalization layer supports scanner execution-status validation.

The GitHub Actions workflow must be updated to generate status files for all registered scanners.

That integration remains in progress.

The workflow must preserve scanner failures as failures rather than substituting artificial clean reports or silently ignoring execution errors.

---

## Viewing Results

### Option 1 - GitHub Actions

The intended workflow is:

1. Open the EPMS repository on GitHub.
2. Select the **Actions** tab.
3. Open a **Build and Analyze** workflow run.
4. Review the workflow jobs and quality-gate results.
5. Open the generated report artifacts when available.

The Markdown report is intended for the GitHub Actions job summary.

Publication of the report to the job summary must be verified against the completed workflow configuration.

### Option 2 - Pull Request Checks

The quality gate is intended to appear in pull request checks.

When the workflow and repository protection rules are fully configured, a failed required check will prevent merging.

Pull request comment publication is supported by the reporting design but must be verified against the workflow integration.

### Branch Protection

GitHub branch protection and rulesets operate independently of the gate engine.

The gate engine produces an exit code. GitHub determines whether that check is required for merging.

Branch protection enforcement is not yet established as complete for EPMS.

---

## Download the Artifact Bundle

When the workflow is configured to upload analysis artifacts:

1. Open the relevant GitHub Actions workflow run.
2. Scroll to **Artifacts**.
3. Select the analysis artifact bundle.
4. Download and extract the ZIP file.
5. Review the raw, normalized, evaluated, and reporting outputs.

Artifact upload behavior must be verified during GitHub Actions integration.

---

## Artifact Bundle Contents

The intended analysis output structure is:

```text
quality/analysis/
├── raw/
│   ├── <scanner-report>
│   └── <scanner>.status.json
├── normalized/
│   └── normalized.json
├── evaluated/
│   └── evaluated.json
├── report.md
└── report.html
```

### raw/

Contains native scanner reports and execution-status files.

Native reports provide the evidence used to identify findings.

Execution-status files establish whether each scanner completed, failed, or was intentionally disabled.

### normalized/

Contains `normalized.json`, the unified representation produced by the normalization layer.

Scanner-specific XML, JSON, and JSONL reports are converted into a consistent structure.

Normalized results include execution information, runtime errors, findings, violation counts, severity counts, and metadata.

### evaluated/

Contains `evaluated.json`, the policy evaluation output.

This records the decisions produced when normalized scanner results are evaluated against the configured policy.

### report.md

The Markdown quality-gate report.

It provides a summary of scanner results, findings, policy decisions, and the overall gate outcome.

### report.html

The HTML quality-gate report.

The reporting implementation is designed to present results by scanner category, with finding details such as file path, line number, severity, rule, and message.

It also includes client-side filtering controls.

---

## Report Filtering

The HTML report includes filtering controls for reviewing scanner findings.

Filtering is performed client-side without requiring an additional server connection.

### Severity Filter

The severity dropdown limits displayed findings according to the selected threshold.

| Option | Shows |
|---|---|
| All | Every finding |
| Critical only | Critical findings |
| High and above | High and Critical |
| Medium and above | Medium, High, and Critical |
| Low and above | Low, Medium, High, and Critical |

### Tool Visibility Toggle

Each scanner in the Tool Results Summary table has a visibility toggle.

Turning a scanner off hides its findings section and excludes those findings from active filtering and searching.

Turning it back on restores the section and reapplies the current filters.

Manually hidden scanners are restored using **Reset**.

### Search Bar

The search bar filters visible findings by:

- File path.
- Rule name.
- Finding message.

Search is case-insensitive.

The **Clear** control removes the current search term.

### Combining Filters

Filters use AND logic.

A finding is displayed only when it:

1. Meets the selected severity threshold.
2. Matches the search term.
3. Belongs to a scanner whose visibility toggle is enabled.

### Reset

The **Reset** control restores the default filtering state:

- All scanner sections visible.
- Severity set to All.
- Search term cleared.

---

## Policy Configuration

The centralized policy configuration is:

`quality/CI/gate/policy.yaml`

This file defines scanner-specific enforcement settings and finding thresholds.

### Gate Mode

Example:

```yaml
gate:
  mode: enforce
```

| Mode | Behavior |
|---|---|
| `enforce` | Blocking policy violations cause the quality gate to exit with failure |
| `report_only` | Policy violations are reported without failing the gate solely because of those violations |

The gate engine applies the configured mode.

The policy engine evaluates findings and execution errors but does not independently apply the final gate mode.

### Tool Entry Structure

Example:

```yaml
tools:
  trufflehog:
    blocking: true
    description: "TruffleHog (Secrets Scan)"
    fail_on:
      any_finding: true
```

| Field | Purpose |
|---|---|
| `blocking` | Indicates whether the scanner's policy violations are blocking |
| `description` | Human-readable scanner description |
| `fail_on` | Defines the conditions that constitute a policy violation |


### Supported Fail Condition Types

The following conditions are implemented in `policy_engine.py`.

| Condition | Example | Behavior |
|---|---|---|
| `any_finding` | `any_finding: true` | Violation when the normalized finding count is greater than zero |
| `any_vulnerability` | `any_vulnerability: true` | Violation when the normalized finding count is greater than zero |
| `violation_count` | `violation_count: ">0"` | Violation when the normalized violation count is greater than zero |
| `severity` | `severity: "medium_and_above"` | Violation when the maximum finding severity meets the configured threshold |
| `quality_gate` | `quality_gate: true` | Violation when the normalized violation count is greater than zero |

Supported severity thresholds are:

- `critical_only`
- `high_and_above`
- `medium_and_above`

The policy engine evaluates these conditions using the normalized scanner results.

The `error_count` condition is not implemented and must not be used in `policy.yaml`.

Runtime errors and unsuccessful scanner execution are evaluated independently of the configured finding thresholds.

When multiple conditions apply, the policy engine records the first violation reason encountered.


### Scanner Execution Failures

Scanner execution failures are distinct from finding thresholds.

A scanner can have zero findings and still fail execution validation.

Missing or invalid scanner-status files must not be interpreted as successful scans.

---

## Adding a New Tool

Adding a scanner requires updating its parser, artifact registration, policy configuration, and workflow execution.

### Registration Procedure

1. Implement a parser in `quality/CI/gate/parsers/`.
2. Register the parser in `quality/CI/gate/normalize.py`.
3. Add the scanner's raw artifact filename to `quality/CI/gate/scanner_artifacts.py`.
4. Add the scanner's policy configuration to `quality/CI/gate/policy.yaml`.
5. Add the appropriate reporting category to `quality/CI/gate/report/report_constants.py`, when required.
6. Add scanner execution to `.github/workflows/build-and-analyze.yml`.
7. Ensure the workflow produces `<scanner>.status.json`.
8. Add parser, normalization, and status-contract tests.
9. Verify that the scanner produces the expected raw artifact and normalized result.
10. Verify its policy evaluation and reporting behavior.

The parser registration, artifact registry, and policy configuration must remain aligned.

### Parser Contract

Every parser must return a normalized dictionary consistent with the quality-gate schema.

Example:

```python
{
    "tool": "tool_name",
    "executed": True,
    "artifact_present": True,
    "runtime_error": False,
    "violation_count": 0,
    "findings": [],
    "severity_counts": {
        "critical": 0,
        "high": 0,
        "medium": 0,
        "low": 0,
        "info": 0
    },
    "metadata": {}
}
```

The scanner-status layer validates execution status separately from the parser's findings.

### Scanner Registration Verification

Verify that:

- Every registered parser has an artifact mapping.
- Every registered scanner has a policy entry.
- Artifact filenames are unique.
- Every scanner produces an execution-status file.
- Disabled scanners have valid reasons and no raw report.
- Failed scanners are recorded as runtime errors.
- Completed scanners produce parseable reports.
- Normalization tests pass.
- Policy evaluation tests pass.

---

## Environment Variables and Secrets

| Secret | Purpose | Configuration |
|---|---|---|
| `NVD_API_KEY` | Provides authenticated access to NVD data for OWASP Dependency-Check | GitHub Actions repository secret |
| `GITHUB_TOKEN` | Supports GitHub API operations such as PR comments, subject to workflow permissions | Provided automatically by GitHub Actions |

### NVD API Key

To configure `NVD_API_KEY`:

1. Open the GitHub repository.
2. Select **Settings**.
3. Open **Secrets and variables**.
4. Select **Actions**.
5. Create a new repository secret named `NVD_API_KEY`.

Without a valid API key, Dependency-Check may encounter slower downloads or NVD rate limits.

### GitHub Token

GitHub Actions provides `GITHUB_TOKEN` automatically.

Operations such as posting pull request comments require appropriate workflow permissions.

The completed workflow must be reviewed to confirm that its token permissions match the actions it performs.

---

## Troubleshooting

### Scanner Status File Missing

**Symptom:** The scanner is recorded as having a runtime error.

**Cause:** The required `<scanner>.status.json` file was not generated.

**Action:**

1. Inspect the scanner's GitHub Actions execution step.
2. Confirm that the status file is written to `quality/analysis/raw/`.
3. Verify the scanner name matches the registered name.
4. Confirm that the status file contains valid JSON.

A missing status file must not be treated as a successful or disabled scanner.

### Scanner Marked Completed but Artifact Missing

**Symptom:** Normalization records a runtime error.

**Cause:** The scanner reports `completed`, but its expected raw artifact is absent.

**Action:**

1. Check the artifact filename in `scanner_artifacts.py`.
2. Verify the workflow writes the artifact to the correct directory.
3. Inspect the scanner command and output handling.
4. Confirm the artifact exists before normalization begins.

### Scanner Marked Failed

**Symptom:** The scanner has a runtime error even when a raw report exists.

**Cause:** Its status file contains `failed`.

**Action:**

1. Review the scanner process exit code.
2. Inspect the recorded reason.
3. Check the workflow execution log.
4. Correct the underlying execution problem.

A raw report does not override a failed execution status.

### Scanner Marked Disabled

**Symptom:** The report identifies the scanner as disabled.

**Cause:** A valid status file explicitly marks the scanner `disabled`.

**Action:**

1. Review the documented reason.
2. Confirm the scanner was intentionally skipped.
3. Verify that no raw artifact exists for the disabled scanner.
4. Determine whether the disabling condition is still appropriate.

A missing raw artifact alone does not mean the scanner is disabled.

### Invalid Scanner Status

**Symptom:** Normalization records a scanner-status validation error.

**Cause:** The status JSON is malformed or contains unsupported values.

**Action:**

1. Verify the JSON syntax.
2. Confirm `status` is `completed`, `failed`, or `disabled`.
3. Confirm `exit_code`, when present, is an integer.
4. Confirm `reason`, when present, is a string.
5. Confirm disabled scanners provide a nonempty reason.

### Scanner Shows Zero Findings Unexpectedly

**Symptom:** A scanner completed but reported no findings when findings were expected.

**Action:**

1. Inspect the native raw report.
2. Confirm the scanner analyzed the intended files.
3. Review scanner configuration and exclusions.
4. Check the parser's normalized output.
5. Verify the status file indicates completed execution.

A completed status confirms execution validation, not the correctness or completeness of the scanner's analysis.

### Gate Engine Exits With a Runtime Error

**Symptom:** The quality gate cannot complete normally.

**Action:**

1. Review the workflow log.
2. Inspect the normalized results.
3. Identify scanners with `runtime_error: true`.
4. Check the corresponding status files and raw artifacts.
5. Investigate parser or policy evaluation errors.

### Pull Request Comment Not Posted

**Symptom:** The quality-gate report is not posted as a pull request comment.

**Action:**

1. Verify the workflow includes the PR comment step.
2. Confirm `GITHUB_TOKEN` permissions allow `pull-requests: write`.
3. Inspect the workflow logs.
4. Check whether the workflow event permits the requested operation.

PR comment integration must be verified during workflow completion.

### Dependency-Check Is Slow or Failing

**Possible cause:** The NVD API key is missing or invalid.

**Action:**

1. Verify the `NVD_API_KEY` repository secret.
2. Review Dependency-Check logs.
3. Check for NVD rate limiting.
4. Confirm the scanner generates its expected artifact and status file.

### Workflow Triggered on Unexpected Branches

Inspect the `on:` configuration in:

`.github/workflows/build-and-analyze.yml`

The intended configuration runs on pushes to `main` and pull requests targeting `main`.

---

## Testing

### BN1 - Local Scans

Run the local scanning test suite from the repository root:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/Local_Scans/tests \
  -p 'test_*.py' -v
```

### BN2 - CI Quality Gate

Run the CI quality-gate test suite:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/CI/tests \
  -p 'test_*.py' -v
```

### Java Backend

Run the Maven verification lifecycle:

```bash
cd backend
./mvnw clean verify
```

### Last Confirmed Local Test Results

| Test Suite | Tests Passed |
|---|---:|
| BN1 Local Scans | 19 |
| BN2 CI Quality Gate | 13 |
| Java Backend | 77 |
| **Total** | **109** |

These results represent the last confirmed local verification during BN2 development.

They do not establish that all 12 external scanners have executed successfully in GitHub Actions.

### Test Discovery Note

The following command should not be used as the sole verification method:

```bash
python3 -m unittest discover -s quality -p 'test_*.py' -v
```

During previous verification, this command discovered zero tests.

Use the explicit BN1 and BN2 test commands above.

---

## Directory Structure

```text
quality/CI/gate/
├── parsers/
│   ├── bandit.py
│   ├── checkstyle.py
│   ├── dependency_check.py
│   ├── gitleaks.py
│   ├── htmlhint.py
│   ├── pmd.py
│   ├── pylint.py
│   ├── semgrep.py
│   ├── spotbugs.py
│   ├── stylelint.py
│   ├── trivy.py
│   └── trufflehog.py
├── report/
│   ├── render_report/
│   │   ├── __init__.py
│   │   ├── report_builder.py
│   │   ├── report_blocks.py
│   │   ├── report_sections.py
│   │   ├── report_rows.py
│   │   ├── report_badges.py
│   │   ├── report_controls.py
│   │   ├── report_scripts.py
│   │   └── report_styles.py
│   ├── __init__.py
│   ├── report.py
│   ├── report_constants.py
│   ├── report_github.py
│   ├── report_html.py
│   └── report_md.py
├── __init__.py
├── gate.py
├── humanize.py
├── normalize.py
├── policy_engine.py
├── policy.yaml
├── README.md
├── scanner_artifacts.py
├── scanner_status.py
├── schemas.py
└── utils.py
```

### Key Components

| Component | Responsibility |
|---|---|
| `gate.py` | Quality-gate orchestration and exit-code handling |
| `normalize.py` | Scanner normalization orchestration |
| `policy_engine.py` | Policy evaluation |
| `policy.yaml` | Scanner enforcement configuration |
| `scanner_artifacts.py` | Central registry of expected scanner report filenames |
| `scanner_status.py` | Scanner execution-status validation |
| `schemas.py` | Normalized result schema |
| `utils.py` | Shared utilities |
| `humanize.py` | Human-readable labels and severity helpers |
| `report/report.py` | Reporting orchestration |
| `report/report_constants.py` | Report categories and constants |
| `report/render_report/` | HTML report generation and filtering |

### Modification Guidance

- To change scanner policy thresholds, review `policy.yaml`.
- To change artifact filenames, update `scanner_artifacts.py` and the corresponding workflow output.
- To change execution-status validation, review `scanner_status.py`.
- To add or change a parser, update `parsers/` and `normalize.py`.
- To modify HTML report styling, review `render_report/report_styles.py`.
- To modify client-side filtering, review `render_report/report_scripts.py`.
- To modify report layout, review `render_report/report_builder.py`.
- To modify scanner execution, update `.github/workflows/build-and-analyze.yml`.

Changes to shared contracts should be accompanied by corresponding tests.

---

## Current Implementation Status

### Completed or Locally Verified

- Twelve scanner parsers registered.
- Twelve raw artifact mappings registered.
- Scanner execution-status validation implemented.
- Completed, failed, and disabled status handling implemented.
- Missing or invalid status files handled as runtime errors.
- Parser exception isolation implemented.
- BN1 and BN2 local Python tests passing.
- Java backend Maven verification passing.

### Remaining BN2 Work

1. Review documentation and Python docstrings for consistency.
2. Verify documented policy operators against the current policy engine.
3. Complete scanner execution-status generation in GitHub Actions.
4. Ensure all 12 scanner steps produce valid execution-status files.
5. Remove any workflow behavior that masks scanner execution failures.
6. Verify normalization and policy evaluation in GitHub Actions.
7. Verify report generation, publication, and artifact uploads.
8. Confirm the workflow produces the intended gate exit code.
9. Run final local and CI verification.
10. Review the completed changes before staging and committing.
11. Prepare the feature branch pull request with a descriptive title and Markdown description.
12. Configure required GitHub checks when enforcement is ready.

The CI quality gate should not be considered fully integrated until the GitHub Actions workflow has been tested end to end.
