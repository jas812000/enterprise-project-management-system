# CI Quality Gate

## Overview

The CI Quality Gate provides automated static analysis, secrets detection,
and vulnerability scanning for repositories integrating the Quality Gates
framework.

The current implementation registers 12 scanners and uses GitHub Actions
to coordinate their execution.

The CI Quality Gate:

1. Executes configured scanners and collects native reports.
2. Records scanner execution statuses.
3. Normalizes results into a common schema.
4. Evaluates findings and execution failures against centralized policy.
5. Applies the configured enforcement mode.
6. Generates summary and diagnostic reports.
7. Publishes available results through GitHub Actions.

The framework provides two complementary components:

| Component          | Purpose                                    | Enforcement   |
|--------------------|--------------------------------------------|---------------|
| Local Quality Gate | Developer feedback before commits          | Advisory      |
| CI Quality Gate    | Centralized analysis and policy evaluation | Policy-driven |

When enforcement mode is active, blocking policy violations cause the
CI Quality Gate to return a failing exit code.

GitHub branch protection or repository rulesets must separately require
the quality-gate check to prevent merging when it fails.

---

## Installation and Reusable Project Configuration

The host repository must include the complete `quality/` directory
and `.github/workflows/build-and-analyze.yml`.

Begin by copying `quality/project.example.json` to
`quality/project.json`.

Configure the applicable repository-relative source paths, Java version,
and supported project settings.

Never store credentials or other secrets in the project configuration.

The current workflow runs on:

- Pull requests targeting `main`.
- Pushes to `main`.

Repositories using other branch names must update these triggers.

The workflow uses the job identifier `quality-gate`.

For detailed installation instructions, dependencies, configuration fields,
and known portability limitations, see `quality/PORTABILITY.md`.

Independent integration into another host repository has not yet been
verified. Full cross-repository portability should not be claimed.

### Licensing

The Quality Gates framework is distributed under the MIT License.

Copyright (c) 2026 James Stevens.

See `quality/LICENSE` for the complete license terms.

The EPMS application has its own root `LICENSE`, also using MIT.

Bundled third-party scanner distributions retain their respective
license terms and notices.

---

## Pipeline Architecture

The CI Quality Gate uses four primary stages:

1. Scanner execution.
2. Result normalization.
3. Policy evaluation and gate enforcement.
4. Reporting and publication.

### Stage 1 — Scanner Execution

GitHub Actions executes the configured scanners and collects:

- Native scanner reports.
- Scanner execution-status files.

Both are stored under `quality/analysis/raw/`.

Each registered scanner requires a status file named
`<scanner>.status.json`.

A completed scanner must also provide its expected report artifact.

### Stage 2 — Normalization

The normalization layer converts scanner-specific reports into
a common result structure.

Implementation: `quality/CI/gate/normalize.py`

Output: `quality/analysis/normalized/normalized.json`

Normalization validates scanner statuses and records runtime errors.
It does not decide whether findings violate policy.

### Stage 3 — Policy Evaluation and Enforcement

The policy engine evaluates normalized results against:

`quality/CI/gate/policy.yaml`

Implementation: `quality/CI/gate/policy_engine.py`

Output: `quality/analysis/evaluated/evaluated.json`

The policy engine calculates `overall_block`.

The gate engine, `quality/CI/gate/gate.py`, applies the configured
enforcement mode to determine its exit code.

In `enforce` mode, blocking violations cause exit code 1.

In `report_only` mode, policy violations are reported without
causing a failing gate exit code.

GitHub branch protection or rulesets determine whether a failed
required check prevents merging.

### Stage 4 — Reporting and Publication

The reporting entry point is:

`quality/CI/gate/report/report.py`

It generates three files:

| File                     | Purpose                                         |
|--------------------------|-------------------------------------------------|
| `report.md`              | Markdown summary and PR comments                |
| `report.html`            | HTML summary with findings and metadata removed |
| `diagnostic-report.html` | Detailed diagnostic HTML report                 |

The gate engine also generates human-readable analysis outputs.

GitHub Actions attempts to publish the Markdown job summary and
upload analysis artifacts even when earlier steps fail.

The artifact retention period is seven days.

Detailed diagnostic artifacts may contain sensitive information.

---

## Tool Inventory

The CI Quality Gate currently registers 12 scanners covering secrets detection, static application security testing (SAST), code quality, and software composition analysis (SCA).

| Scanner          | Category                      | Language or Scope    | Blocking | Policy Threshold  |
|------------------|-------------------------------|----------------------|----------|-------------------|
| TruffleHog       | Secrets detection             | Repository content   | Yes      | Any finding       |
| Gitleaks         | Secrets detection             | Repository content   | Yes      | Any finding       |
| Checkstyle       | Static code analysis          | Java                 | Yes      | Any violation     |
| PMD              | Static code analysis          | Java                 | Yes      | Medium and above  |
| SpotBugs         | Static code analysis          | Java                 | Yes      | Medium and above  |
| Semgrep          | Static security analysis      | Multiple languages   | Yes      | High and above    |
| Pylint           | Static code analysis          | Python               | Yes      | High and above    |
| Bandit           | Static security analysis      | Python               | Yes      | Medium and above  |
| HTMLHint         | Static code analysis          | HTML                 | Yes      | Any violation     |
| Stylelint        | Static code analysis          | CSS/SCSS             | Yes      | Any violation     |
| Dependency-Check | Software composition analysis | Dependencies         | Yes      | Any vulnerability |
| Trivy            | Vulnerability scanning        | Filesystem/container | Yes      | Medium and above  |

### Scanner Registration

The authoritative scanner artifact registry is:

`quality/CI/gate/scanner_artifacts.py`

Each registered scanner has an expected native report artifact and an execution-status record.

The registry defines the scanner identifiers used throughout normalization and policy evaluation.

### Policy Configuration

The authoritative policy configuration is:

`quality/CI/gate/policy.yaml`

All 12 registered scanners currently have `blocking: true`.

However, a blocking scanner does not automatically fail the gate merely because it reports findings. The policy engine evaluates those findings against the configured threshold.

Scanner execution failures are evaluated separately from finding thresholds.

A scanner that is intentionally disabled and has a valid disabled status is exempt from finding-based policy evaluation.

### Enforcement

The policy engine calculates whether a blocking policy violation exists.

The gate engine then applies the configured enforcement mode:

- `enforce`: Blocking policy violations produce a failing gate exit code.
- `report_only`: Policy violations are reported without causing a failing gate exit code.

Preventing a pull request from merging requires GitHub branch protection or repository rulesets that require the appropriate quality-gate check.

### Configuration Consistency

Scanner registration, execution-status files, report artifacts, normalization, and policy entries must remain consistent.

The current policy evaluator defaults a scanner without a matching policy entry to non-blocking.

Therefore, adding a scanner without adding its policy configuration can unintentionally weaken enforcement.

Changes to the scanner inventory should include corresponding updates to the registry, workflow, normalization support, policy, tests, and documentation.

The configured thresholds above reflect the current policy and should be updated whenever `policy.yaml` changes.

---

## Scanner Execution-Status Contract

The CI Quality Gate uses an explicit execution-status contract to distinguish successful scanner execution, scanner failures, and intentionally disabled scanners.

Execution status is evaluated independently of scanner findings.

Every registered scanner requires a status file in:

`quality/analysis/raw/`

The filename follows this convention:

`<scanner>.status.json`

Examples:

```text
quality/analysis/raw/trufflehog.status.json
quality/analysis/raw/gitleaks.status.json
quality/analysis/raw/checkstyle.status.json
```

### Supported Scanner Statuses

| Status      | Meaning                               | Requirements                                                                              |
|-------------|---------------------------------------|-------------------------------------------------------------------------------------------|
| `completed` | Scanner execution completed           | Expected raw artifact exists, parser executed, and normalization reports no runtime error |
| `failed`    | Scanner execution failed              | Recorded as a runtime error, even when a raw report exists                                |
| `disabled`  | Scanner intentionally did not execute | Nonempty reason required; no normal raw artifact or executed result                       |

A scanner is not considered successful merely because its process returned exit code `0`.

The normalization layer must also validate the scanner's status, expected artifact, and parsing result.

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

| Field       | Type    | Required    | Description                          |
|-------------|---------|-------------|--------------------------------------|
| `status`    | String  | Yes         | `completed`, `failed`, or `disabled` |
| `exit_code` | Integer | No          | Scanner process exit code            |
| `reason`    | String  | Conditional | Explanation for execution status     |

Validation requirements:

- `status` must contain a supported status value.
- `exit_code`, when supplied, must be an integer.
- Boolean values are not accepted as integer exit codes.
- `reason`, when supplied, must be a string.
- `disabled` requires a nonempty `reason`.
- A valid disabled scanner must not have an executed result or normal raw artifact.

The status contract is implemented in:

`quality/CI/gate/scanner_status.py`

### Execution Status vs. Scanner Findings

Scanner execution status and scanner findings represent different information.

For example:

```json
{
  "status": "completed",
  "exit_code": 0
}
```

This indicates that the scanner completed execution.

It does not indicate that the scanner found no vulnerabilities, security issues, or code-quality violations.

A successfully completed scanner may report findings that exceed the configured policy threshold.

Conversely, a scanner that produces no report cannot automatically be considered successful.

The normalization layer validates execution and processes findings.

The policy engine subsequently evaluates those findings against the configured policy.

### Fail-Closed Status Validation

The scanner-status implementation treats missing, malformed, or inconsistent execution information as a runtime error.

Conditions include:

1. Missing scanner-status files.
2. Malformed scanner-status JSON.
3. Scanner-status content that is not a JSON object.
4. Unsupported execution-status values.
5. Invalid `exit_code` or `reason` field types.
6. A scanner explicitly marked `failed`.
7. A completed scanner missing its expected raw artifact.
8. A completed scanner whose parser did not execute.
9. A completed scanner whose normalization result contains a runtime error.
10. A disabled scanner without a nonempty reason.
11. A disabled scanner with an unexpected raw artifact.
12. A disabled scanner with an executed result.
13. Scanner parser exceptions.

A scanner parser exception is isolated where supported so that normalization can continue collecting results from other scanners.

A valid disabled status must not conceal an existing parser error.

Runtime errors are included in normalized results and evaluated by the policy engine.

### Runtime Errors and Enforcement

The policy engine evaluates scanner execution failures separately from finding severity thresholds.

For scanners configured with `blocking: true`, runtime errors and unsuccessful execution can produce blocking policy violations.

The gate engine then applies the configured enforcement mode.

| Enforcement Mode | Behavior                                                                  |
|------------------|---------------------------------------------------------------------------|
| `enforce`        | Blocking policy violations produce a failing gate exit code               |
| `report_only`    | Policy violations are recorded without producing a failing gate exit code |

The current policy configures all 12 registered scanners as blocking and uses `enforce`.

Therefore, a failed required scanner should not be silently treated as a successful scan.

The distinction between fail-closed status validation and final enforcement is important: `report_only` does not provide the same enforcement guarantee as `enforce`.

### Intentionally Disabled Scanners

A scanner is intentionally disabled only when its status file explicitly contains a valid disabled status.

Example:

```json
{
  "status": "disabled",
  "reason": "No applicable source files"
}
```

Requirements:

- The status must be `disabled`.
- A nonempty reason must explain why the scanner did not execute.
- The scanner must not produce its normal raw analysis artifact.
- The normalized result must not indicate that the scanner executed.
- Existing parser errors must not be hidden by the disabled status.

A valid disabled scanner is exempt from finding-based policy evaluation.

A missing status file or missing raw artifact does not automatically disable a scanner.

This distinction prevents scanner failures from being misclassified as intentional skips.

### Raw Artifact Registry

The authoritative artifact registry is:

`quality/CI/gate/scanner_artifacts.py`

The registry defines the expected native report artifact for each registered scanner.

| Scanner Identifier | Expected Raw Artifact          |
|--------------------|--------------------------------|
| `trufflehog`       | `trufflehog.jsonl`             |
| `gitleaks`         | `gitleaks.json`                |
| `checkstyle`       | `checkstyle.xml`               |
| `pmd`              | `pmd.xml`                      |
| `spotbugs`         | `spotbugs.xml`                 |
| `semgrep`          | `semgrep.json`                 |
| `pylint`           | `pylint.json`                  |
| `bandit`           | `bandit.json`                  |
| `htmlhint`         | `htmlhint.json`                |
| `stylelint`        | `stylelint.json`               |
| `dependency_check` | `dependency-check-report.json` |
| `trivy`            | `trivy.json`                   |

The scanner registry must remain consistent with:

- GitHub Actions scanner execution steps.
- Execution-status filenames.
- Scanner-specific normalization parsers.
- Policy configuration.
- Automated tests.
- Documentation.

Changes to registered scanner identifiers or artifact filenames require coordinated updates across these components.

### Workflow Integration

The GitHub Actions workflow creates scanner execution-status files and invokes the normalization and policy-evaluation components.

The CI Quality Gate is designed to preserve evidence of scanner failures rather than replace missing or failed reports with artificial clean results.

The workflow attempts to generate reports and upload diagnostic artifacts even when earlier steps fail.

These operations use `if: always()` but are not guaranteed to succeed if required inputs are unavailable or report generation encounters an error.

Workflow success does not independently prove that every scanner reported zero findings.

The analysis artifacts and evaluated policy results should be inspected when investigating scanner behavior.

---

## Viewing Results

The CI Quality Gate makes analysis results available through GitHub Actions, pull request checks, and downloadable artifacts.

Report availability depends on successful generation of the corresponding output files.

### Option 1 — GitHub Actions

To review the results of a workflow run:

1. Open the host repository on GitHub.
2. Select the **Actions** tab.
3. Open the relevant **Build and Analyze** workflow run.
4. Select the `quality-gate` job.
5. Review the job status and execution logs.
6. Open the workflow run summary to view the published Markdown report.
7. Download the analysis artifact bundle when detailed investigation is required.

The workflow attempts to publish `quality/analysis/report.md` to the GitHub Actions job summary.

If the Markdown report is unavailable, the workflow writes a warning and a fallback message to the summary.

A successful workflow does not necessarily mean every scanner reported zero findings. The configured policy determines which findings constitute violations.

### Option 2 — Pull Request Checks

The CI Quality Gate runs on pull requests targeting `main`, according to the current workflow configuration.

The `quality-gate` job reports its execution result through GitHub Actions.

When `gate.mode` is set to `enforce`, blocking policy violations cause the gate process to return a failing exit code.

The reporting implementation also attempts to post or update a Markdown report comment when:

- The workflow is running for a pull request.
- A valid pull request number is available.
- GitHub authentication and permissions permit comment publication.

The workflow supplies `GITHUB_TOKEN` and the pull request number to the reporting process.

Pull request comments are supplementary reporting features. They do not independently enforce merge restrictions.

### Branch Protection and Required Checks

The gate engine determines its exit code according to the configured enforcement mode.

GitHub branch protection or repository rulesets independently determine whether the corresponding check must pass before merging.

To require CI Quality Gate enforcement:

1. Configure the policy with `gate.mode: enforce`.
2. Ensure the relevant scanners are configured as blocking.
3. Configure GitHub branch protection or a repository ruleset for the target branch.
4. Require the appropriate `quality-gate` status check.
5. Verify that a deliberately failing policy condition prevents merging.

A failing check does not automatically prevent merging unless the repository's protection configuration requires it.

Repositories integrating Quality Gates must verify their own branch protection and token permissions.

---

## Download the Artifact Bundle

The GitHub Actions workflow attempts to upload the complete quality analysis artifact bundle after execution.

To download the bundle:

1. Open the host repository on GitHub.
2. Select the **Actions** tab.
3. Open the relevant **Build and Analyze** workflow run.
4. Locate the **Artifacts** section.
5. Select the quality analysis artifact.
6. Download and extract the ZIP archive.
7. Inspect the raw, normalized, evaluated, human-readable, and reporting outputs.

### Artifact Naming

The workflow constructs the artifact name using:

- GitHub Actions workflow run number.
- Short commit SHA.
- The suffix `quality_analysis_report`.

The resulting name follows this general pattern:

`<run-number>_<short-sha>_quality_analysis_report`

### Artifact Retention

The current workflow configures artifact retention for **seven days**.

After the retention period expires, artifacts may no longer be available for download.

The workflow uses `if-no-files-found: warn`, so missing output files generate a warning rather than automatically causing the artifact upload step to fail.

The upload step uses `if: always()` to attempt artifact publication even after earlier workflow failures.

This does not guarantee successful upload when files are unavailable or the upload operation fails.

### Artifact Security

The analysis bundle can contain sensitive information, including:

- Potential secrets detected by scanners.
- Source-code locations.
- Security vulnerability descriptions.
- Dependency information.
- Scanner execution diagnostics.

Treat downloaded diagnostic artifacts as potentially sensitive.

Access should be restricted according to the host repository's security requirements.

Avoid distributing raw scanner reports or detailed diagnostic reports publicly without reviewing their contents.

---

## Artifact Bundle Contents

The current workflow uploads the following analysis outputs when they are available:

```text
quality/analysis/
├── raw/
│   ├── <scanner-report>
│   └── <scanner>.status.json
├── normalized/
│   └── normalized.json
├── evaluated/
│   └── evaluated.json
├── human/
│   └── <human-readable-analysis-files>
├── report.md
├── report.html
└── diagnostic-report.html
```

The directories and files are produced by different stages of the pipeline.

### raw/

Contains native scanner reports and execution-status files.

Native scanner reports may use JSON, JSONL, XML, or other supported formats.

Execution-status files identify whether each scanner:

- Completed execution.
- Failed execution.
- Was intentionally disabled.

The expected raw artifact filenames are defined in:

`quality/CI/gate/scanner_artifacts.py`

Raw artifacts provide the original evidence used during normalization and policy evaluation.

### normalized/

Contains:

`quality/analysis/normalized/normalized.json`

This file contains the unified representation of scanner results.

Normalized results can include:

- Scanner identifiers.
- Execution information.
- Runtime errors.
- Findings.
- Violation counts.
- Severity counts.
- Scanner metadata.

Normalization makes scanner-specific output suitable for consistent policy evaluation.

### evaluated/

Contains:

`quality/analysis/evaluated/evaluated.json`

This file records policy evaluation results, including:

- Overall blocking decision.
- Blocking scanner results.
- Non-blocking scanner results.
- Generated evaluation timestamp.

The `overall_block` value represents the policy engine's decision.

The gate engine separately applies the configured enforcement mode when determining its exit code.

### human/

Contains human-readable analysis outputs generated by the gate engine.

These outputs support investigation and review of normalized and evaluated scanner results.

The exact generated files depend on the human-readable reporting implementation.

### report.md

Contains the Markdown CI Quality Gate summary.

Path:

`quality/analysis/report.md`

The Markdown report is used for:

- GitHub Actions job summaries.
- Pull request report comments.
- Reviewing scanner results and policy decisions.

The workflow publishes this file to the GitHub Actions job summary when it exists.

### report.html

Contains the HTML summary report.

Path:

`quality/analysis/report.html`

The reporting implementation creates this document from a copy of the evaluated results.

Before rendering, it removes individual `findings` and scanner `metadata` from the copied results.

This reduces the amount of detailed scanner information exposed through the summary report.

The HTML summary is distinct from the detailed diagnostic report.

### diagnostic-report.html

Contains the detailed HTML diagnostic report.

Path:

`quality/analysis/diagnostic-report.html`

The reporting implementation prepares a separate diagnostic document from the evaluated results.

This report is intended to support detailed investigation of scanner findings and execution problems.

Unlike the HTML summary, the diagnostic report is designed to retain detailed diagnostic information.

It may contain sensitive security findings and source-code references.

Review its contents before sharing or publishing it.

### Report Generation and Failure Handling

The reporting entry point is:

`quality/CI/gate/report/report.py`

The GitHub Actions workflow invokes reporting separately from the gate engine.

The reporting step uses `if: always()` so it is attempted even if earlier steps fail.

However, report generation requires a readable and valid `evaluated.json`.

If the evaluation document is missing or invalid, report generation exits unsuccessfully.

The workflow still attempts subsequent summary publication and artifact upload.

This behavior helps preserve available diagnostic evidence without representing failed or missing scanner results as successful analysis.

---

## Report Filtering

The CI Quality Gate provides client-side filtering controls for reviewing individual scanner findings in the detailed HTML diagnostic report.

Filtering operates within the generated HTML document and does not require a connection to an application server.

The filtering controls affect only the information displayed to the reviewer.

They do not modify scanner artifacts, normalized findings, policy evaluation results, or the CI Quality Gate decision.

### Report Types and Filtering

The reporting implementation generates two HTML documents with different purposes.

| Report                   | Purpose                      | Finding-Level Filtering                              |
|--------------------------|------------------------------|------------------------------------------------------|
| `report.html`            | Sanitized HTML summary       | Individual findings are removed before rendering     |
| `diagnostic-report.html` | Detailed diagnostic analysis | Supports reviewing and filtering individual findings |

Use the detailed diagnostic report when investigating specific scanner findings.

### Severity Filter

The severity dropdown limits displayed findings according to the selected threshold.

| Option           | Findings Displayed              |
|------------------|---------------------------------|
| All              | All findings                    |
| Critical only    | Critical                        |
| High and above   | High and Critical               |
| Medium and above | Medium, High, and Critical      |
| Low and above    | Low, Medium, High, and Critical |

The selected severity threshold applies to findings displayed in the report.

It does not change the severity threshold configured in `quality/CI/gate/policy.yaml`.

### Scanner Visibility

The Tool Results Summary provides controls for showing or hiding individual scanner sections.

When a scanner is hidden:

- Its findings section is hidden from the current view.
- Its findings are excluded from active searching and filtering.
- Its underlying scanner results remain unchanged.

When the scanner is shown again, the report reapplies the currently selected filters.

The **Reset** control restores manually hidden scanner sections.

### Search

The search input supports finding-level text filtering.

Searchable information includes:

- File paths.
- Rule names.
- Finding messages.

Searching is case-insensitive.

The **Clear** control removes the current search term without changing the underlying scanner results.

### Combining Filters

The filtering controls use logical AND conditions.

A finding is displayed only when:

1. Its severity meets the selected threshold.
2. Its searchable text matches the current search term.
3. Its scanner is currently visible.

A finding that fails any active condition is hidden from the current view.

This behavior allows reviewers to narrow large diagnostic reports without changing the original analysis.

### Resetting Filters

The **Reset** control restores the default filtering state:

- All scanner sections are visible.
- Severity is set to All.
- The search term is cleared.

Resetting the interface does not rerun scanners or reevaluate the policy.

### Implementation

The CI report's filtering implementation is located in:

`quality/CI/gate/report/render_report/`

Relevant modules include:

- `report_controls.py` — Defines report-specific filtering controls.
- `report_scripts.py` — Integrates shared JavaScript filtering utilities.
- `report_rows.py` — Generates finding rows with filtering attributes.
- `report_sections.py` — Generates scanner and finding sections.
- `report_builder.py` — Assembles the HTML report.

Shared filtering functionality is provided by:

`quality/shared/reporting/`

The shared implementation helps keep filtering behavior consistent across reports.

### Security Considerations

Client-side filtering is a presentation feature, not a security control.

Hidden findings remain part of the generated diagnostic document.

Therefore:

- Do not rely on filtering to remove sensitive information.
- Review diagnostic reports before sharing them.
- Restrict access to detailed analysis artifacts when necessary.
- Use the sanitized HTML summary when individual finding details are unnecessary.

---

## Policy Configuration

The CI Quality Gate uses a centralized policy file to define scanner enforcement and finding thresholds.

The authoritative configuration is:

`quality/CI/gate/policy.yaml`

This policy determines:

- Which scanners are blocking.
- Which finding conditions constitute policy violations.
- How scanner execution failures are evaluated.
- Whether blocking violations cause the gate to fail.

The policy engine evaluates scanner results against these rules.

The gate engine separately applies the configured enforcement mode.

### Gate Enforcement Mode

The global enforcement mode is configured under `gate`.

Example:

```yaml
gate:
  mode: enforce
```

Supported modes:

| Mode          | Behavior                                                                |
|---------------|-------------------------------------------------------------------------|
| `enforce`     | Blocking policy violations cause the gate to return a failing exit code |
| `report_only` | Policy violations are reported without causing a failing gate exit code |

The current configuration uses `enforce`.

When no mode is explicitly configured, the gate engine defaults to `enforce`.

The `report_only` mode is useful for evaluating policy changes without enforcing finding-based failures.

However, it must not be treated as equivalent to an enforced quality gate.

### Scanner Policy Entries

Scanner-specific rules are defined under `tools`.

Example:

```yaml
tools:
  trufflehog:
    blocking: true
    description: "TruffleHog (Secrets Scan)"
    fail_on:
      any_finding: true
```

Each scanner policy entry can contain:

| Field         | Purpose                                                                                        |
|---------------|------------------------------------------------------------------------------------------------|
| `blocking`    | Determines whether the scanner's policy violations contribute to the overall blocking decision |
| `description` | Human-readable scanner description                                                             |
| `fail_on`     | Defines the scanner's finding-based violation conditions                                       |

A scanner configured with `blocking: false` may still produce policy violations.

However, those violations do not contribute to `overall_block`.

A scanner configured with `blocking: true` contributes to `overall_block` when it violates its policy.

### Supported Finding Conditions

The policy engine implements the following finding-based conditions.

| Condition           | Example                        | Violation Trigger                                          |
|---------------------|--------------------------------|------------------------------------------------------------|
| `any_finding`       | `any_finding: true`            | Normalized violation count is greater than zero            |
| `any_vulnerability` | `any_vulnerability: true`      | Normalized violation count is greater than zero            |
| `violation_count`   | `violation_count: ">0"`        | Normalized violation count is greater than zero            |
| `severity`          | `severity: "medium_and_above"` | Maximum normalized severity meets the configured threshold |
| `quality_gate`      | `quality_gate: true`           | Normalized violation count is greater than zero            |

These conditions are implemented in:

`quality/CI/gate/policy_engine.py`

The current implementation does not support an `error_count` finding condition.

Do not add `error_count` to the policy without first implementing and testing its evaluation behavior.

### Severity Thresholds

Supported severity thresholds are:

| Threshold          | Violating Severities       |
|--------------------|----------------------------|
| `critical_only`    | Critical                   |
| `high_and_above`   | High and Critical          |
| `medium_and_above` | Medium, High, and Critical |

The policy engine evaluates the scanner's normalized maximum severity.

For example:

```yaml
tools:
  bandit:
    blocking: true
    description: "Python security analysis"
    fail_on:
      severity: "medium_and_above"
```

This configuration produces a finding-based policy violation when Bandit's normalized maximum severity is Medium, High, or Critical.

Severity-based policy evaluation is separate from the severity filter available in the detailed HTML diagnostic report.

Changing a report filter does not change the policy threshold.

### Current Scanner Enforcement

The current policy contains 12 registered scanners.

All 12 are configured as blocking.

| Scanner          | Blocking | Finding Condition                 |
|------------------|----------|-----------------------------------|
| TruffleHog       | Yes      | Any finding                       |
| Gitleaks         | Yes      | Any finding                       |
| Checkstyle       | Yes      | Violation count greater than zero |
| PMD              | Yes      | Medium severity and above         |
| SpotBugs         | Yes      | Medium severity and above         |
| Semgrep          | Yes      | High severity and above           |
| Pylint           | Yes      | High severity and above           |
| Bandit           | Yes      | Medium severity and above         |
| HTMLHint         | Yes      | Violation count greater than zero |
| Stylelint        | Yes      | Violation count greater than zero |
| Dependency-Check | Yes      | Any vulnerability                 |
| Trivy            | Yes      | Medium severity and above         |

These settings reflect the current `policy.yaml`.

The table must be updated whenever the policy changes.

### Scanner Execution Failures

Scanner execution failures are evaluated independently of finding-based thresholds.

The policy engine checks for:

1. Scanner runtime errors.
2. Scanners that did not execute.
3. Finding-based policy violations.

A scanner can report zero findings and still violate policy because its execution failed.

Examples include:

- Missing or malformed scanner-status files.
- Missing expected raw artifacts.
- Scanner execution failures.
- Parser failures.
- Normalization errors.

For a blocking scanner, an execution-related policy violation contributes to the overall blocking decision.

A scanner must not be treated as successful merely because no findings were reported.

### Intentionally Disabled Scanners

A scanner may be intentionally disabled when its execution-status file satisfies the disabled-status contract.

A valid disabled scanner:

- Has `status: disabled`.
- Provides a nonempty reason.
- Does not produce its normal raw artifact.
- Does not have an executed result.
- Does not conceal an existing parser error.

The policy engine recognizes a valid disabled result and records it as non-blocking.

A missing status file or missing artifact is not sufficient to disable a scanner.

Refer to **Scanner Execution-Status Contract** for the complete validation requirements.

### Policy Evaluation Order

For an active scanner, the policy engine evaluates potential violations in this order:

1. Runtime error.
2. Scanner not executed.
3. Violation count.
4. Severity threshold.
5. Any vulnerability.
6. Any finding.
7. Quality-gate condition.

The first applicable violation reason is recorded.

This ordering means execution failures take precedence over finding-based violations when determining the recorded reason.

The policy engine still preserves the scanner's normalized findings and execution information for reporting.

### Missing Policy Entries

The current policy engine defaults a scanner without a matching policy entry to non-blocking.

This behavior is important when extending the framework.

Registering a new scanner without adding its corresponding policy configuration can unintentionally weaken enforcement.

When adding or renaming a scanner, update:

- Scanner artifact registration.
- Workflow execution and status generation.
- Normalization support.
- Policy configuration.
- Automated tests.
- Documentation.

The scanner inventory and policy configuration should remain consistent.

### Overall Blocking Decision

The policy engine produces an evaluation document at:

`quality/analysis/evaluated/evaluated.json`

The evaluated results include:

- `overall_block`
- `blocking_results`
- `non_blocking_results`

The `overall_block` value becomes `true` when at least one blocking scanner has a policy violation.

Non-blocking scanner violations are retained in the evaluation output but do not independently set `overall_block` to `true`.

The gate engine then uses the configured enforcement mode to determine its exit code.

For `enforce`, a blocking policy violation causes the gate to fail.

For `report_only`, blocking policy violations are reported without failing the gate solely because of those violations.

### Policy Governance

Treat `policy.yaml` as security and quality governance configuration.

Changes to this file can affect whether vulnerabilities, execution failures, or code-quality issues block the CI pipeline.

Recommended change-control practices include:

1. Make policy changes in a feature branch.
2. Document the reason for each threshold or blocking change.
3. Update relevant automated tests.
4. Review the effect on all registered scanners.
5. Submit changes through a pull request.
6. Verify the resulting CI behavior before merging.

GitHub branch protection or repository rulesets must separately require the appropriate quality-gate check to enforce merge restrictions.

---

## Adding a New Tool

The CI Quality Gate can be extended with additional scanners.

Integrating a scanner requires coordinated changes to execution, artifact registration, normalization, policy configuration, reporting, and automated tests.

Adding a scanner to only one component is insufficient.

### Integration Overview

A new scanner must participate in the existing analysis pipeline:

1. Execute the scanner.
2. Generate its native report artifact.
3. Generate its execution-status file.
4. Parse the native report into the normalized schema.
5. Validate the scanner's execution status.
6. Evaluate its results against the configured policy.
7. Include its results in generated reports.
8. Verify its behavior through automated tests.

The scanner identifier must remain consistent across these components.

### Step 1 — Implement the Scanner Parser

Create a parser module in:

`quality/CI/gate/parsers/`

For example:

`quality/CI/gate/parsers/example_scanner.py`

The parser is responsible for interpreting the scanner's native report and returning a normalized result.

It should handle supported report formats, malformed input, findings, severity information, and parser errors.

Parser output must follow the normalized schema defined in:

`quality/CI/gate/schemas.py`

Do not include raw secrets, authentication tokens, or other sensitive values in normalized findings.

### Step 2 — Register the Parser

Register the parser in:

`quality/CI/gate/normalize.py`

The normalizer maintains the `TOOL_PARSERS` registration list.

Example:

```python id="4lpgx5"
from quality.CI.gate.parsers.example_scanner import parse_example_scanner
```

Add the scanner to the parser registration list using the same scanner identifier that will be used throughout the framework.

Example:

```python id="9y40qv"
("example_scanner", parse_example_scanner),
```

The scanner identifier must match its artifact registry entry, execution-status filename, and policy entry.

### Step 3 — Register the Raw Artifact

Add the scanner's expected native report filename to:

`quality/CI/gate/scanner_artifacts.py`

The registry uses the `SCANNER_ARTIFACTS` dictionary.

Example:

```python id="3n7by1"
"example_scanner": "example-scanner.json",
```

Each registered scanner must have an appropriate artifact mapping.

Avoid reusing another scanner's artifact filename.

The artifact registry is used to locate the expected native report and validate execution-status consistency.

### Step 4 — Add Workflow Execution

Update the GitHub Actions workflow:

`.github/workflows/build-and-analyze.yml`

Add the scanner's execution step and any required setup.

The workflow must:

- Install or make the scanner available.
- Execute the scanner against the intended project scope.
- Write its native report to `quality/analysis/raw/`.
- Record the scanner's execution status.
- Preserve scanner failures for normalization and policy evaluation.
- Avoid replacing failed or missing reports with artificial clean results.

The scanner should use the host project's reusable configuration where applicable.

Do not hardcode EPMS-specific paths or assumptions into reusable scanner logic.

### Step 5 — Implement Execution-Status Reporting

Every registered scanner requires an execution-status file.

The expected location is:

`quality/analysis/raw/<scanner>.status.json`

For example:

`quality/analysis/raw/example_scanner.status.json`

The status must follow the contract implemented in:

`quality/CI/gate/scanner_status.py`

Supported statuses are:

| Status      | Meaning                                                         |
|-------------|-----------------------------------------------------------------|
| `completed` | Scanner completed and its report is available for normalization |
| `failed`    | Scanner execution failed                                        |
| `disabled`  | Scanner was intentionally skipped with a documented reason      |

A disabled scanner must have a nonempty reason and must not produce its normal raw artifact.

A missing artifact or missing status file must not be treated as a successful scan.

Refer to **Scanner Execution-Status Contract** for the full validation requirements.

### Step 6 — Configure the Scanner Policy

Add the scanner's policy entry to:

`quality/CI/gate/policy.yaml`

Example:

```yaml id="d2l4gr"
tools:
  example_scanner:
    blocking: true
    description: "Example static analysis scanner"
    fail_on:
      severity: "high_and_above"
```

Choose a finding condition supported by the policy engine.

Supported conditions include:

- `any_finding`
- `any_vulnerability`
- `violation_count`
- `severity`
- `quality_gate`

The current policy engine defaults scanners without a matching policy entry to non-blocking.

Therefore, a missing policy entry can unintentionally weaken enforcement.

Every newly registered scanner should have an explicit policy entry.

### Step 7 — Integrate Reporting

Review the reporting implementation under:

`quality/CI/gate/report/`

Verify that the new scanner is represented correctly in:

- Markdown summaries.
- HTML summaries.
- Detailed diagnostic reports.
- Scanner categories.
- Findings and severity information.
- Policy evaluation results.

The framework also uses shared reporting utilities under:

`quality/shared/reporting/`

Update the shared scanner category registry when the new scanner requires a category mapping.

Avoid duplicating category definitions in report-specific modules when a shared registry already provides the required functionality.

### Step 8 — Add Automated Tests

Add tests covering the new scanner's integration.

At minimum, verify:

1. The parser handles a valid native report.
2. The parser handles malformed or unsupported report content.
3. The parser returns the required normalized schema.
4. The scanner is registered in `TOOL_PARSERS`.
5. The scanner has an artifact mapping.
6. The scanner has an explicit policy entry.
7. Completed execution produces a valid normalized result.
8. Failed execution is recorded as a runtime error.
9. Missing execution-status files are not treated as successful scans.
10. Disabled execution follows the status contract.
11. Finding thresholds produce the expected policy decisions.
12. The scanner appears correctly in generated reports.

Where applicable, test the scanner with zero findings, non-blocking findings, and blocking findings.

### Normalized Parser Contract

Every scanner parser must return a dictionary consistent with the framework's normalized schema.

The following example illustrates the core fields:

```python id="3dl5a9"
{
    "tool": "example_scanner",
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

This is an illustrative example, not a replacement for the authoritative schema in `schemas.py`.

The execution-status layer validates scanner execution separately from finding interpretation.

A parser returning zero findings does not independently prove that the scanner executed successfully.

### Integration Verification Checklist

Before considering a new scanner fully integrated, verify that:

- [ ] The scanner executes in the intended environment.
- [ ] The parser is registered in `TOOL_PARSERS`.
- [ ] The scanner has a unique raw artifact mapping.
- [ ] The workflow generates its execution-status file.
- [ ] Completed scans produce parseable reports.
- [ ] Failed scans remain identifiable as runtime errors.
- [ ] Disabled scans satisfy the disabled-status contract.
- [ ] The normalized result follows the expected schema.
- [ ] The scanner has an explicit policy entry.
- [ ] Blocking policy violations affect `overall_block`.
- [ ] Reports include the scanner correctly.
- [ ] Relevant automated tests pass.
- [ ] Documentation reflects the new scanner.

### Portability Considerations

When integrating a scanner into a different host repository, distinguish between reusable framework logic and project-specific configuration.

Reusable scanner behavior belongs in the Quality Gates framework.

Host-specific settings should be provided through the supported project configuration or workflow integration.

Refer to:

- `quality/PORTABILITY.md`
- `quality/project.example.json`
- `quality/project_config.py`

The framework's scanner inventory, policy, and workflow must remain consistent after portability changes.

---

## Environment Variables and Secrets

The CI Quality Gate uses environment variables for project configuration, scanner authentication, GitHub Actions integration, and report metadata.

These variables fall into four categories:

1. Repository secrets used by scanners.
2. GitHub Actions authentication and event variables.
3. Reusable project configuration variables.
4. Workflow-specific environment values.

Not every variable is a secret.

Project configuration paths, scanner scope, and report metadata should not be treated as authentication credentials.

### Repository Secrets

The current workflow supports the following scanner-specific repository secret.

| Secret        | Purpose                                                                                          | Configuration                    |
|---------------|--------------------------------------------------------------------------------------------------|----------------------------------|
| `NVD_API_KEY` | Provides authenticated access to National Vulnerability Database data for OWASP Dependency-Check | GitHub Actions repository secret |

The workflow passes this secret to the Dependency-Check execution steps through an environment variable named `NVD_KEY`.

Example:

```yaml id="26x2ji"
env:
  NVD_KEY: ${{ secrets.NVD_API_KEY }}
```

The repository secret is named `NVD_API_KEY`.

The environment variable made available to the scanner step is named `NVD_KEY`.

These identifiers serve different purposes and should not be confused.

### Configuring the NVD API Key

To configure the NVD API key:

1. Open the host repository on GitHub.
2. Select **Settings**.
3. Open **Secrets and variables**.
4. Select **Actions**.
5. Create a repository secret named `NVD_API_KEY`.
6. Store the API key as the secret value.

Without a valid NVD API key, Dependency-Check may encounter slower vulnerability database updates or NVD rate limits.

Do not place the API key directly in the workflow, project configuration JSON, source code, or documentation.

### GitHub Actions Authentication

GitHub Actions provides a workflow authentication token through `GITHUB_TOKEN`.

The CI reporting implementation uses this token when attempting to publish pull request comments.

| Variable            | Purpose                                                 |
|---------------------|---------------------------------------------------------|
| `GITHUB_TOKEN`      | Authenticates GitHub API requests                       |
| `GITHUB_REPOSITORY` | Identifies the repository                               |
| `PR_NUMBER`         | Identifies the pull request to receive a report comment |

The workflow currently supplies reporting variables using:

```yaml id="97pp4l"
env:
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  PR_NUMBER: ${{ github.event.pull_request.number }}
```

The reporting implementation reads these values from its environment.

Pull request comments are attempted only when the workflow is associated with a pull request and the required information is available.

Comment publication also depends on the token's effective permissions.

### Workflow Permissions

The current workflow declares:

```yaml id="1wg3x9"
permissions:
  contents: read
  pull-requests: write
```

These permissions support repository content access and pull request reporting operations.

However, declared permissions do not guarantee that every workflow execution can write pull request comments.

Repository settings, event context, and GitHub security restrictions can affect the effective token permissions.

For example, workflows triggered from untrusted forked pull requests may receive restricted write permissions.

A failed comment-publication attempt should not be interpreted as successful report publication.

### Reusable Project Configuration

The framework loads host-specific project settings from:

`quality/project.json`

A portable configuration template is provided at:

`quality/project.example.json`

The configuration loader is:

`quality/project_config.py`

The loader exports the following environment variables:

| Variable                | Configuration Source      | Purpose                                           |
|-------------------------|---------------------------|---------------------------------------------------|
| `QUALITY_JAVA_SOURCE`   | `java_source`             | Java source directory                             |
| `QUALITY_MAVEN_DIR`     | `maven_project`           | Maven project directory                           |
| `QUALITY_SCAN_ROOT`     | `scan_root`               | Root directory for scanning                       |
| `QUALITY_PYTHON_SOURCE` | `python_source`           | Python source directory                           |
| `QUALITY_JS_SOURCE`     | `javascript_source`       | JavaScript source directory                       |
| `QUALITY_PROJECT_NAME`  | `project_name`            | Host project display name                         |
| `QUALITY_JAVA_VERSION`  | `java_version`            | Java version used by the workflow                 |
| `QUALITY_CONFIG`        | Active configuration path | Absolute path to the loaded project configuration |

The workflow uses these variables to configure scanner execution and supporting tools.

The configuration values are project-specific, but the variable names are part of the reusable Quality Gates integration contract.

When adapting the framework to another repository, update the host project configuration rather than hardcoding project-specific paths into reusable scripts.

Refer to `quality/PORTABILITY.md` for integration requirements.

### GitHub Actions Metadata

The CI reporting implementation reads standard GitHub Actions environment variables to generate report metadata.

| Variable            | Purpose                                                  |
|---------------------|----------------------------------------------------------|
| `GITHUB_EVENT_NAME` | Identifies the workflow event                            |
| `GITHUB_RUN_NUMBER` | Identifies the workflow run number                       |
| `GITHUB_RUN_ID`     | Identifies the workflow run                              |
| `GITHUB_SHA`        | Identifies the associated commit                         |
| `GITHUB_SERVER_URL` | Provides the GitHub server URL                           |
| `GITHUB_REPOSITORY` | Identifies the repository                                |
| `GITHUB_ACTOR`      | Identifies the workflow actor                            |
| `GITHUB_HEAD_REF`   | Identifies the pull request source branch when available |
| `GITHUB_BASE_REF`   | Identifies the pull request target branch when available |
| `GITHUB_WORKSPACE`  | Provides the GitHub Actions workspace directory          |

The reporting implementation also reads `SCAN_ROOT` for scan-root metadata.

These variables are not substitutes for scanner authentication credentials.

GitHub Actions supplies standard `GITHUB_*` variables during workflow execution.

The `PR_NUMBER` variable is explicitly supplied by the workflow for report publication.

### Secrets Management

Follow these requirements when integrating Quality Gates into a host repository:

- Store API keys and authentication credentials in GitHub Actions secrets or another approved secret-management system.
- Do not commit credentials to source control.
- Do not include secret values in `quality/project.json`.
- Do not print authentication credentials in workflow logs.
- Avoid passing secrets to scanner steps that do not require them.
- Review workflow permissions before enabling pull request comment publication.
- Treat raw scanner outputs and diagnostic artifacts as potentially sensitive.

The framework's secrets-detection scanners provide additional protection, but they do not replace proper credential management.

### Portability Considerations

A repository adopting Quality Gates must configure any scanner-specific credentials required by its enabled tools.

The current reusable framework uses `NVD_API_KEY` for authenticated Dependency-Check database access.

Other scanners may introduce additional secrets or configuration variables in the future.

When adding a scanner, document:

1. Required secret names.
2. Environment variables passed to the scanner.
3. Required GitHub Actions permissions.
4. Behavior when credentials are unavailable.
5. Safe handling of credentials and scanner output.

Do not assume that credentials configured in one host repository are available in another.

---

## Troubleshooting

The CI Quality Gate processes scanner results through execution, normalization, policy evaluation, and reporting.

When troubleshooting a failed workflow, identify which stage encountered the problem before changing scanner configuration or policy thresholds.

A failed CI Quality Gate may indicate:

- A scanner execution failure.
- An invalid or missing execution-status file.
- A missing or malformed scanner report.
- A normalization or parser error.
- A legitimate blocking policy violation.
- A configuration or workflow integration problem.
- A reporting or artifact-publication failure.

Not every failing gate indicates a framework defect.

### Recommended Investigation Order

When investigating a failed CI workflow:

1. Open the relevant GitHub Actions workflow run.
2. Identify the first failing step in the `quality-gate` job.
3. Review scanner execution logs.
4. Inspect execution-status files under `quality/analysis/raw/`.
5. Review native scanner artifacts.
6. Inspect `quality/analysis/normalized/normalized.json`.
7. Inspect `quality/analysis/evaluated/evaluated.json`.
8. Review the generated Markdown or HTML reports when available.
9. Determine whether the failure represents a runtime error or an expected policy violation.
10. Correct the underlying problem and rerun the workflow.

Do not disable a scanner or weaken a policy threshold solely to make a failing workflow pass.

### Scanner Status File Missing

**Symptom:** The scanner is recorded as having a runtime error.

**Possible cause:** The required execution-status file was not generated.

Expected location:

`quality/analysis/raw/<scanner>.status.json`

**Investigation:**

1. Inspect the scanner's GitHub Actions execution step.
2. Confirm the workflow writes the status file to the expected directory.
3. Verify that the scanner identifier matches its registered name.
4. Confirm that the status file is created before normalization.
5. Check whether an earlier workflow failure prevented status generation.

A missing status file must not be treated as a successful or intentionally disabled scanner.

### Scanner Marked Completed but Artifact Missing

**Symptom:** Normalization records a runtime error for a scanner marked `completed`.

**Possible cause:** The expected native report artifact is missing.

**Investigation:**

1. Inspect the scanner's execution log.
2. Confirm the scanner generated its native report.
3. Check the artifact filename in `quality/CI/gate/scanner_artifacts.py`.
4. Verify that the workflow writes the report to `quality/analysis/raw/`.
5. Confirm the report exists before normalization begins.

A completed status does not override a missing required artifact.

### Scanner Marked Failed

**Symptom:** The scanner reports a runtime error even though a native report may exist.

**Cause:** The execution-status file contains `failed`.

**Investigation:**

1. Review the scanner's process exit code.
2. Inspect the recorded failure reason.
3. Review the corresponding GitHub Actions execution log.
4. Determine whether the failure was caused by scanner configuration, dependencies, permissions, or execution.
5. Correct the underlying problem.

A native report does not override an explicitly failed execution status.

### Scanner Marked Disabled

**Symptom:** The scanner is identified as intentionally disabled.

**Cause:** Its execution-status file contains a valid `disabled` status.

**Investigation:**

1. Review the documented reason for disabling the scanner.
2. Confirm the scanner was intentionally skipped.
3. Verify that no normal raw artifact was generated.
4. Confirm the normalized result does not indicate execution.
5. Determine whether the disabling condition remains appropriate.

A missing raw artifact alone does not establish that a scanner was intentionally disabled.

### Invalid Scanner Status

**Symptom:** Normalization reports a scanner-status validation error.

**Possible causes:**

- Malformed JSON.
- Unsupported status value.
- Incorrect field types.
- Missing required disabled reason.
- Inconsistent artifact or execution information.

**Investigation:**

1. Validate the status file's JSON syntax.
2. Confirm `status` is `completed`, `failed`, or `disabled`.
3. Confirm `exit_code`, when supplied, is an integer rather than a Boolean.
4. Confirm `reason`, when supplied, is a string.
5. Verify disabled scanners provide a nonempty reason.
6. Check the status against the expected artifact and normalized execution result.

The authoritative validation implementation is:

`quality/CI/gate/scanner_status.py`

### Scanner Reports Zero Findings Unexpectedly

**Symptom:** A scanner completes successfully but reports zero findings when findings were expected.

**Investigation:**

1. Inspect the native scanner report.
2. Confirm the scanner analyzed the intended source files.
3. Review scanner configuration, exclusions, and scan scope.
4. Verify host-specific paths in `quality/project.json`.
5. Inspect the normalized scanner result.
6. Check whether the parser interpreted the report correctly.
7. Confirm the scanner's execution status is valid.

A completed scanner status does not independently prove that its scan coverage was complete.

### Normalization or Parser Failure

**Symptom:** A scanner's normalized result contains a runtime error, or normalization fails to produce the expected output.

Expected output:

`quality/analysis/normalized/normalized.json`

**Investigation:**

1. Inspect the native report for malformed or unexpected content.
2. Review the scanner-specific parser under `quality/CI/gate/parsers/`.
3. Confirm the scanner is registered in `TOOL_PARSERS`.
4. Confirm its expected artifact is registered in `SCANNER_ARTIFACTS`.
5. Inspect the scanner's execution-status file.
6. Review normalization logs for parser exceptions.
7. Run the relevant parser and normalization tests.

Parser errors should remain visible as execution or normalization failures.

Do not replace malformed reports with artificial clean results.

### Blocking Policy Violation

**Symptom:** The scanner completed successfully, but the CI Quality Gate fails.

**Possible cause:** The scanner reported findings that violate a blocking policy threshold.

**Investigation:**

1. Inspect `quality/analysis/evaluated/evaluated.json`.
2. Identify the scanner with `policy_violation: true`.
3. Review its configured rule in `quality/CI/gate/policy.yaml`.
4. Inspect the normalized findings.
5. Determine whether the reported findings are valid.
6. Correct the underlying code, dependency, configuration, or security issue.
7. Rerun the quality gate.

A blocking finding-based violation is expected to fail the gate when `gate.mode` is `enforce`.

Do not classify an expected policy violation as a scanner execution failure.

### Gate Engine Cannot Complete

**Symptom:** The gate process encounters an error before completing normal evaluation.

**Investigation:**

1. Review the gate execution log.
2. Confirm `normalized.json` exists and contains valid data.
3. Verify `policy.yaml` is available and correctly formatted.
4. Inspect scanner runtime errors.
5. Review policy evaluation errors.
6. Check whether `evaluated.json` was generated.
7. Determine whether the gate produced a fail-safe evaluation document.

The gate engine coordinates normalization, policy evaluation, and human-readable output generation.

Some handled failures can produce fail-safe evaluation output.

However, the existence of an evaluation file does not automatically mean every pipeline stage completed successfully.

### Report Generation Failure

**Symptom:** One or more expected reports are missing.

Expected report files:

- `quality/analysis/report.md`
- `quality/analysis/report.html`
- `quality/analysis/diagnostic-report.html`

**Investigation:**

1. Review the report-generation step in GitHub Actions.
2. Confirm `quality/analysis/evaluated/evaluated.json` exists.
3. Verify that the evaluation document is valid.
4. Inspect the reporting process logs.
5. Check whether report generation terminated unsuccessfully.
6. Determine which report files were created.

The reporting implementation requires valid evaluated results.

The workflow attempts reporting even after earlier failures through `if: always()`.

This does not guarantee successful report generation when required inputs are unavailable.

### GitHub Actions Summary Missing

**Symptom:** The workflow run summary does not contain the expected Markdown report.

**Investigation:**

1. Confirm `quality/analysis/report.md` was generated.
2. Review the workflow's summary-publication step.
3. Inspect the `GITHUB_STEP_SUMMARY` operation.
4. Check whether the workflow published its fallback message.
5. Review earlier reporting errors.

The workflow attempts to append the Markdown report to the GitHub Actions job summary when the file exists.

If the file is unavailable, the workflow publishes a fallback message.

### Pull Request Comment Not Posted

**Symptom:** The Markdown quality-gate report is not published as a pull request comment.

**Investigation:**

1. Confirm the workflow was triggered by a pull request.
2. Verify that `PR_NUMBER` contains the expected pull request number.
3. Confirm `GITHUB_TOKEN` is available.
4. Verify the workflow declares the required permissions.
5. Review repository and fork-related token restrictions.
6. Inspect the reporting logs for GitHub API errors.

The current workflow declares:

```yaml id="bypqtx"
permissions:
  contents: read
  pull-requests: write
```

The effective token permissions may still be restricted by GitHub repository settings or the workflow event.

Pull request comment publication is supplementary reporting behavior.

It does not determine whether the gate passes or fails.

### Analysis Artifact Bundle Missing

**Symptom:** The expected analysis artifact is unavailable in the GitHub Actions workflow run.

**Investigation:**

1. Open the workflow run's **Artifacts** section.
2. Confirm the artifact-upload step executed.
3. Review the upload step's logs.
4. Verify that the expected analysis files exist.
5. Check for upload warnings or errors.
6. Confirm the artifact has not expired.

The workflow uses `if: always()` to attempt artifact upload after earlier failures.

It also uses `if-no-files-found: warn`.

Therefore, a workflow may produce an upload warning when no matching files are available.

The current artifact retention period is seven days.

### Dependency-Check Is Slow or Failing

**Symptom:** OWASP Dependency-Check encounters slow vulnerability database updates or execution failures.

**Possible causes:**

- Missing or invalid NVD API key.
- NVD rate limiting.
- Network or service availability problems.
- Scanner configuration errors.

**Investigation:**

1. Confirm the repository secret `NVD_API_KEY` is configured.
2. Verify the workflow passes the secret through `NVD_KEY`.
3. Review Dependency-Check execution logs.
4. Check for NVD rate-limit or connectivity errors.
5. Confirm the expected raw report was generated.
6. Inspect the scanner execution-status file.

Do not expose the API key while troubleshooting.

### Project Configuration Failure

**Symptom:** Scanner setup fails, or scanners analyze unexpected paths.

**Investigation:**

1. Confirm `quality/project.json` exists.
2. Validate its structure against the supported project configuration contract.
3. Review `quality/project.example.json`.
4. Confirm configured source paths and scan roots are correct.
5. Inspect the exported `QUALITY_*` environment variables.
6. Check the workflow's configuration-loading logs.
7. Verify that required tools and language versions are available.

The project configuration loader is:

`quality/project_config.py`

The reusable framework should obtain host-specific paths from configuration rather than hardcoded project assumptions.

Refer to `quality/PORTABILITY.md` for additional integration guidance.

### Workflow Triggered on Unexpected Branches

**Symptom:** The workflow runs for an unexpected branch or does not run for an intended event.

**Investigation:**

1. Open `.github/workflows/build-and-analyze.yml`.
2. Inspect the workflow's `on:` configuration.
3. Review the triggering event.
4. Confirm the target branch matches the configured filters.
5. Check whether repository rulesets or other workflow configuration affect execution.

The current workflow runs on:

- Pull requests targeting `main`.
- Pushes to `main`.

Repositories adopting Quality Gates should adjust these triggers to match their branch strategy.

### Tests for Troubleshooting

Automated tests are located under:

`quality/CI/tests/`

Relevant test modules include:

- `test_scanner_status.py`
- `test_normalization_contract.py`
- `test_normalization_integration.py`
- `test_report_diagnostics.py`
- `test_report_security.py`
- `test_diagnostic_reporting_improvements.py`
- `test_secret_scanner_security.py`
- `test_xml_parser_security.py`

Run the relevant tests after correcting parser, normalization, execution-status, or reporting behavior.

A successful test run does not replace verification of the actual GitHub Actions workflow.

---

## Testing

Quality Gates includes automated tests for its reusable scanning, normalization, execution-status, and reporting components.

The framework's Python tests use the standard-library `unittest` framework.

Tests are organized into three primary suites:

1. Local Quality Gate tests.
2. CI Quality Gate tests.
3. Shared Quality Utilities tests.

These suites should be executed independently to ensure reliable test discovery.

All commands in this section assume execution from the host repository root.

### Local Quality Gate Tests

The Local Quality Gate test suite is located in:

`quality/Local_Scans/tests/`

Run the suite using:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/Local_Scans/tests \
  -p 'test_*.py' -v
```

These tests validate behavior implemented by the local scanning framework.

Local Quality Gate tests do not replace CI Quality Gate tests or GitHub Actions workflow verification.

### CI Quality Gate Tests

The CI Quality Gate test suite is located in:

`quality/CI/tests/`

Run the suite using:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/CI/tests \
  -p 'test_*.py' -v
```

The current CI test inventory includes eight modules:

| Test Module                                 | Primary Coverage                                                       |
|---------------------------------------------|------------------------------------------------------------------------|
| `test_normalization_contract.py`            | Scanner registration and normalized result schema                      |
| `test_normalization_integration.py`         | Scanner execution-status integration and normalization behavior        |
| `test_scanner_status.py`                    | Completed, failed, disabled, missing, and invalid scanner statuses     |
| `test_report_diagnostics.py`                | Diagnostic reporting, redaction, and source-link validation            |
| `test_report_security.py`                   | Safe report generation, metadata handling, and output sanitization     |
| `test_diagnostic_reporting_improvements.py` | Diagnostic report formatting and finding presentation                  |
| `test_secret_scanner_security.py`           | Secret-scanner redaction, path validation, and sensitive-data handling |
| `test_xml_parser_security.py`               | XML parser security protections                                        |

The CI test suite checks framework behavior using controlled test inputs.

It does not execute every external scanner against a complete host project.

### Shared Quality Utilities Tests

Shared Quality Utilities are located under:

`quality/shared/`

Run the shared test suite using:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/shared/tests \
  -p 'test_*.py' -v
```

These tests validate reusable functionality shared between the Local and CI Quality Gates.

Changes to shared utilities should be followed by execution of the shared tests and any affected Local or CI tests.

### Running All Three Python Test Suites

To run the framework test suites sequentially from the repository root:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/Local_Scans/tests \
  -p 'test_*.py' -v

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/CI/tests \
  -p 'test_*.py' -v

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s quality/shared/tests \
  -p 'test_*.py' -v
```

Review the result of each suite.

The commands above execute sequentially, but a failure in one command does not automatically prevent a later command from running in an interactive shell.

For fail-fast execution in a shell script, use explicit error handling or `set -e` in an appropriate script context.

### Python Test Execution Requirements

The test commands use:

`PYTHONDONTWRITEBYTECODE=1`

This prevents Python from writing new `.pyc` bytecode cache files during test execution.

It does not disable Python imports, change test behavior, or guarantee that tests make no other filesystem changes.

The commands also use:

- `python3 -m unittest discover` for standard-library test discovery.
- `-s` to select the test directory.
- `-p 'test_*.py'` to select matching test files.
- `-v` for verbose test output.

The framework's supported Python runtime requirements must still be satisfied.

### Test Discovery

Use explicit test discovery for each suite.

Broad discovery from the top-level `quality/` directory may not identify every test module reliably because of package and directory structure.

The documented commands provide a consistent starting point for running the framework's tests.

When adding new test modules, follow the existing `test_*.py` naming convention.

### Host Application Tests

Quality Gates may be integrated into repositories containing Java, Python, JavaScript, or other application components.

Framework tests and host application tests serve different purposes.

The reusable framework's tests validate Quality Gates functionality.

Host application tests validate the application being analyzed.

For a Maven-based Java application, the host project may use:

```bash
./mvnw clean verify
```

Run this command from the directory containing the host application's Maven wrapper.

The exact command, working directory, and available test lifecycle depend on the host repository.

Do not assume that every repository using Quality Gates contains a `backend/` directory or a Maven project.

### Workflow Verification

Passing Python unit tests does not prove that the complete GitHub Actions workflow is functioning correctly.

After changes to scanner execution, workflow configuration, policy enforcement, or report publication, verify the actual workflow.

Check that:

1. The workflow starts for its configured events.
2. Required project configuration loads successfully.
3. Enabled scanners execute against the intended paths.
4. Each registered scanner produces a valid execution-status file.
5. Expected native artifacts are available.
6. Normalization produces the expected results.
7. Policy evaluation applies the configured thresholds.
8. Blocking violations affect the gate outcome.
9. Reports are generated when valid inputs are available.
10. GitHub Actions summaries and artifacts are published as expected.
11. Pull request comments are attempted when the event and permissions permit them.

Workflow verification should include both successful execution and appropriate failure handling.

### Test Result Reporting

Do not assume a fixed number of tests will always be present.

Test counts change as modules and test cases are added, modified, or removed.

When documenting verification results, record:

- The command executed.
- The execution date.
- The test suite.
- The number of tests run.
- The number of failures and errors.
- Whether the command completed successfully.

Historical test results should be identified as historical rather than presented as evidence of the current repository state.

### Recommended Verification After Changes

Use the following guidance when modifying the framework:

| Changed Component                 | Recommended Verification                               |
|-----------------------------------|--------------------------------------------------------|
| Local scanning implementation     | Local Quality Gate tests                               |
| CI scanner parser                 | CI normalization and parser-related tests              |
| Scanner execution-status handling | CI scanner-status and normalization tests              |
| Policy evaluation                 | Relevant policy tests and workflow verification        |
| Reporting implementation          | CI reporting and security tests                        |
| Shared utilities                  | Shared tests and affected Local/CI tests               |
| GitHub Actions workflow           | Relevant framework tests and actual workflow execution |
| Host application code             | Host application tests and applicable quality scans    |

For changes affecting multiple components, run all three Python suites.

A passing test suite confirms the tested behavior under the executed conditions; it does not guarantee that every scanner, external dependency, or GitHub Actions integration will behave correctly in all environments.

---

## Directory Structure

The Quality Gates framework is organized into Local Quality Gate, CI Quality Gate, and Shared Quality Utilities components.

The `quality/` directory also contains reusable project configuration, portability documentation, and licensing information.

### Framework Directory Overview

```text
quality/
├── CI/
│   ├── README.md
│   ├── __init__.py
│   ├── gate/
│   └── tests/
├── Local_Scans/
│   ├── README.md
│   ├── checks/
│   ├── config/
│   ├── hooks/
│   ├── reporting/
│   ├── reports/
│   ├── tests/
│   ├── tools/
│   ├── local_normalize.py
│   ├── local_pipeline.py
│   ├── requirements-scanners.txt
│   ├── run-local-checks.bat
│   ├── run-local-checks.sh
│   └── scanner_registry.py
├── shared/
│   ├── reporting/
│   ├── tests/
│   ├── __init__.py
│   ├── artifact_io.py
│   ├── scanner_status.py
│   └── severity.py
├── LICENSE
├── PORTABILITY.md
├── project.example.json
├── project.json
└── project_config.py
```

The tree shows the framework's principal directories and files rather than every nested implementation module.

`project.json` contains configuration for the current host repository.

Repositories adopting Quality Gates should create their own project configuration using `project.example.json` as a starting point.

### CI Quality Gate Structure

The CI Quality Gate implementation is located under:

`quality/CI/`

```text
quality/CI/
├── README.md
├── __init__.py
├── gate/
│   ├── __init__.py
│   ├── gate.py
│   ├── humanize.py
│   ├── normalize.py
│   ├── policy_engine.py
│   ├── policy.yaml
│   ├── scanner_artifacts.py
│   ├── scanner_status.py
│   ├── schemas.py
│   ├── utils.py
│   ├── parsers/
│   │   ├── __init__.py
│   │   ├── bandit.py
│   │   ├── checkstyle.py
│   │   ├── dependency_check.py
│   │   ├── gitleaks.py
│   │   ├── htmlhint.py
│   │   ├── path_safety.py
│   │   ├── pmd.py
│   │   ├── pylint.py
│   │   ├── semgrep.py
│   │   ├── spotbugs.py
│   │   ├── stylelint.py
│   │   ├── trivy.py
│   │   └── trufflehog.py
│   └── report/
│       ├── __init__.py
│       ├── report.py
│       ├── report_constants.py
│       ├── report_diagnostics.py
│       ├── report_github.py
│       ├── report_html.py
│       ├── report_md.py
│       └── render_report/
│           ├── __init__.py
│           ├── report_badges.py
│           ├── report_blocks.py
│           ├── report_builder.py
│           ├── report_controls.py
│           ├── report_rows.py
│           ├── report_scripts.py
│           ├── report_sections.py
│           └── report_styles.py
└── tests/
    ├── report_fixtures.py
    ├── test_diagnostic_reporting_improvements.py
    ├── test_normalization_contract.py
    ├── test_normalization_integration.py
    ├── test_report_diagnostics.py
    ├── test_report_security.py
    ├── test_scanner_status.py
    ├── test_secret_scanner_security.py
    └── test_xml_parser_security.py
```

The CI README is located directly under `quality/CI/`.

The `gate/` directory contains the implementation, scanner parsers, policy configuration, and reporting modules.

The `tests/` directory contains automated tests and supporting fixtures.

### Shared Quality Utilities Structure

Shared utilities are located under:

`quality/shared/`

```text
quality/shared/
├── __init__.py
├── artifact_io.py
├── scanner_status.py
├── severity.py
├── reporting/
│   ├── __init__.py
│   ├── report_controls.py
│   ├── report_scripts.py
│   ├── report_styles.py
│   ├── result_summary.py
│   └── tool_registry.py
└── tests/
    ├── __init__.py
    ├── test_project_config.py
    └── test_shared_utils.py
```

Shared utilities provide reusable behavior used by the Local and CI Quality Gates.

Changes to shared modules may affect multiple framework components.

Review the relevant Local, CI, and Shared tests when modifying shared functionality.

### Key CI Components

| Component                           | Responsibility                                                       |
|-------------------------------------|----------------------------------------------------------------------|
| `gate/gate.py`                      | Coordinates normalization, policy evaluation, and gate exit behavior |
| `gate/normalize.py`                 | Runs registered scanner parsers and produces normalized results      |
| `gate/policy_engine.py`             | Evaluates normalized scanner results against policy                  |
| `gate/policy.yaml`                  | Configures scanner enforcement rules and gate mode                   |
| `gate/scanner_artifacts.py`         | Registers expected native scanner report filenames                   |
| `gate/scanner_status.py`            | Validates scanner execution-status information                       |
| `gate/schemas.py`                   | Defines the normalized scanner result structure                      |
| `gate/humanize.py`                  | Provides human-readable presentation helpers                         |
| `gate/utils.py`                     | Provides supporting gate utilities                                   |
| `gate/parsers/`                     | Contains scanner-specific native report parsers                      |
| `gate/parsers/path_safety.py`       | Provides path-safety handling for scanner findings                   |
| `gate/report/report.py`             | Coordinates report generation and publication attempts               |
| `gate/report/report_diagnostics.py` | Supports detailed diagnostic reporting                               |
| `gate/report/report_github.py`      | Handles GitHub pull request comment integration                      |
| `gate/report/report_html.py`        | Supports HTML report generation                                      |
| `gate/report/report_md.py`          | Supports Markdown report generation                                  |
| `gate/report/render_report/`        | Contains HTML report rendering components                            |
| `tests/`                            | Contains CI framework automated tests                                |

Paths in this table are relative to `quality/CI/`.

### GitHub Actions Integration

The CI Quality Gate is executed through the host repository's GitHub Actions workflow.

The current workflow is:

`.github/workflows/build-and-analyze.yml`

The workflow is outside the `quality/` directory.

It coordinates scanner setup, execution, artifact creation, gate evaluation, reporting, and artifact upload.

When adopting the framework in another repository, review the workflow configuration alongside the project's source layout and required scanners.

### Generated Analysis Outputs

CI analysis outputs are generated during workflow execution.

The expected analysis structure is:

```text
quality/analysis/
├── raw/
│   ├── <scanner-native-reports>
│   └── <scanner>.status.json
├── normalized/
│   └── normalized.json
├── evaluated/
│   └── evaluated.json
├── human/
├── report.md
├── report.html
└── diagnostic-report.html
```

This tree describes expected output locations, not necessarily files that exist before a workflow runs.

The `raw/` directory contains scanner-native reports and execution-status files.

The `normalized/` directory contains normalized scanner results.

The `evaluated/` directory contains policy evaluation results.

The `human/` directory contains human-readable analysis outputs.

The three top-level report files provide Markdown, sanitized HTML, and detailed diagnostic reporting.

Generated analysis files are runtime artifacts rather than reusable framework source code.

They should not be confused with committed implementation files.

### Local Quality Gate Outputs

The Local Quality Gate maintains its own reporting and output structure under:

`quality/Local_Scans/`

Local reporting behavior is documented in:

`quality/Local_Scans/README.md`

Local scanning outputs and CI analysis outputs are separate execution products.

The two frameworks may share utilities without sharing identical execution workflows or report locations.

### Modification Guidance

Use the following references when changing the framework:

| Change                              | Primary Location                                         |
|-------------------------------------|----------------------------------------------------------|
| Scanner policy thresholds           | `quality/CI/gate/policy.yaml`                            |
| Scanner artifact filenames          | `quality/CI/gate/scanner_artifacts.py`                   |
| Scanner execution-status validation | `quality/CI/gate/scanner_status.py`                      |
| Scanner parser implementation       | `quality/CI/gate/parsers/`                               |
| Parser registration                 | `quality/CI/gate/normalize.py`                           |
| Normalized result structure         | `quality/CI/gate/schemas.py`                             |
| HTML report layout                  | `quality/CI/gate/report/render_report/report_builder.py` |
| HTML report styling                 | `quality/CI/gate/report/render_report/report_styles.py`  |
| Client-side report filtering        | `quality/CI/gate/report/render_report/report_scripts.py` |
| Shared report controls              | `quality/shared/reporting/`                              |
| Project-specific scan paths         | `quality/project.json`                                   |
| Reusable configuration example      | `quality/project.example.json`                           |
| Configuration loading               | `quality/project_config.py`                              |
| CI scanner execution                | `.github/workflows/build-and-analyze.yml`                |
| CI automated tests                  | `quality/CI/tests/`                                      |
| Shared automated tests              | `quality/shared/tests/`                                  |

Changes to scanner registration, artifact mappings, execution-status handling, normalized schemas, or shared utilities should be accompanied by appropriate tests.

### Portability and Source Control

The reusable framework includes:

- Implementation modules.
- Scanner configuration and policy definitions.
- Automated tests.
- Documentation.
- A project configuration example.
- Licensing information.

Host-specific configuration must be reviewed when copying the framework into another repository.

Generated reports, scanner-native artifacts, local caches, and temporary execution files should not be treated as reusable framework source files.

Refer to `quality/PORTABILITY.md` for additional portability requirements.

---

## Current Implementation Status

The CI Quality Gate provides scanner execution-status validation, normalized scanner results, policy-driven enforcement, and automated reporting.

The implementation currently includes twelve registered scanners, reusable project configuration, and automated tests for core framework behavior.

Implementation status and verification status are documented separately.

### Implemented Components

The current CI Quality Gate includes:

- Twelve registered scanner parsers.
- Centralized native artifact filename registration.
- Scanner execution-status validation.
- Normalized scanner result generation.
- Policy-driven evaluation and enforcement.
- Configurable gate enforcement modes.
- Markdown report generation.
- Sanitized HTML summary reporting.
- Detailed diagnostic HTML reporting.
- GitHub Actions job summary integration.
- Pull request comment integration.
- Analysis artifact upload configuration.
- Automated tests for normalization, execution-status handling, reporting, and security.
- Shared utilities used by the Local and CI Quality Gates.
- Reusable project configuration and portability documentation.

The implementation is organized under:

`quality/CI/`

Shared functionality is located under:

`quality/shared/`

Project configuration and portability documentation are located at:

- `quality/project_config.py`
- `quality/project.example.json`
- `quality/PORTABILITY.md`

### Previously Verified Results

The following results were recorded during earlier development and verification.

**Verification date: 2026-11-17**

| Verification                             | Recorded Result             |
|------------------------------------------|-----------------------------|
| Registered scanner parsers               | 12                          |
| Registered scanner artifact mappings     | 12                          |
| Scanner execution-status validation      | Implemented                 |
| GitHub Actions Build and Analyze run #19 | Successful pull request run |
| GitHub Actions Build and Analyze run #20 | Successful `main` run       |
| Run #20 analysis artifact                | Uploaded                    |
| CI Python tests                          | 74 passed                   |
| Local Python tests                       | 21 passed                   |
| Shared Python tests                      | 9 passed                    |
| Total Python tests                       | 104 passed                  |

A previous Local Quality Gate execution also recorded:

- Seven scanners completed without reported findings.
- Two scanners were intentionally disabled.
- Zero reported findings.
- Zero reported execution errors.

These are historical verification results.

They do not establish that the current working tree, subsequent changes, or a different host repository have passed the same checks.

### Current Verification Requirements

Before treating the current framework revision as verified, complete the following checks.

1. Review the final documentation and source changes for portability.
2. Confirm reusable framework files do not depend on unintended host-specific paths or identifiers.
3. Verify that project-specific settings remain in the appropriate configuration files.
4. Run the Local Quality Gate automated tests.
5. Run the CI Quality Gate automated tests.
6. Run the Shared Quality Utilities automated tests.
7. Inspect test failures or errors and resolve them as appropriate.
8. Review the final Git changeset for unintended modifications.
9. Verify the updated framework in GitHub Actions after integration.

Test results should be recorded with their execution date and actual outcome.

### Remaining Integration Verification

The following items require separate verification or review:

1. **Scanner artifacts and policy decisions**

   Review individual scanner execution statuses, normalized findings, and evaluated policy decisions from a representative CI workflow run.

2. **GitHub branch protection or rulesets**

   Confirm that the host repository requires the intended CI Quality Gate check before protected branches can be updated.

3. **Fresh-environment portability**

   Verify that the framework can be configured and executed in a clean environment without relying on undocumented local dependencies.

4. **Separate-repository portability**

   Validate integration into another repository using its own project configuration, source layout, and workflow settings.

5. **Pull request reporting**

   Verify pull request comment behavior in the intended repository and event context when that functionality is required.

6. **Scanner configuration and coverage**

   Confirm that enabled scanners analyze the intended files and that disabled scanners have documented reasons.

These checks are distinct from Python unit-test execution.

### Portability Status

The framework has been structured to support reuse across repositories.

Its reusable components are separated from host-specific project configuration, and the documentation describes the integration requirements.

However, successful execution in one repository does not independently prove portability to another repository.

Fresh-environment and separate-repository verification remain necessary before claiming that portability has been fully validated.

Refer to `quality/PORTABILITY.md` for the framework's portability requirements and integration guidance.

### Maintenance Expectations

When modifying the framework:

- Keep scanner registration, artifact mappings, and policy configuration aligned.
- Preserve fail-safe handling of scanner execution and normalization errors.
- Maintain the normalized result contract.
- Update automated tests when changing framework behavior.
- Review security implications of reporting and diagnostic output.
- Keep host-specific configuration separate from reusable implementation logic.
- Update documentation when workflow behavior or configuration requirements change.
- Record verification results from actual test and workflow executions.

The framework should be evaluated based on its current implementation and verified behavior rather than historical test counts alone.

---
