# EPMS Git Hooks — Local Quality Gate Integration

## 1. Overview

The `hooks/` directory contains Git hooks used to integrate the Local Quality Gate (BN1) into the development workflow.

The pre-commit hook automatically executes the local quality checks before Git creates a commit.

Its purpose is to detect coding-standard violations, maintainability issues, and potential defects before changes enter the repository history.

## 2. Available Hooks

| Hook | Purpose | Status |
|---|---|---|
| `pre-commit` | Executes the BN1 Local Quality Gate before committing | Implemented |

The hook invokes:

`quality/Local_Scans/run-local-checks.sh`

The quality gate runs Checkstyle, PMD, and SpotBugs against the Java backend.

## 3. Installation

Git does not automatically activate custom repository hooks after cloning.

From the EPMS repository root, configure the local hook directory:

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
```

Ensure the hook is executable on macOS or Linux:

```bash
chmod +x quality/Local_Scans/hooks/pre-commit
```

Verify the configuration:

```bash
git config --local --get core.hooksPath
```

Expected output:

```text
quality/Local_Scans/hooks
```

This configuration applies only to the current local Git repository.

Each developer must configure the hook independently.

## 4. Pre-Commit Execution Flow

When a developer runs:

```bash
git commit
```

Git invokes the configured pre-commit hook before creating the commit.

Execution flow:

```text
git commit
    |
    v
pre-commit hook
    |
    v
run-local-checks.sh
    |
    +-- Checkstyle
    |
    +-- PMD
    |
    +-- SpotBugs
    |
    v
Consolidated quality report
    |
    v
Quality gate result
    |
    +-- Exit 0: Commit may proceed
    |
    +-- Exit 1: Commit blocked (violations)
    |
    +-- Exit 2: Commit blocked (execution error)
```

The scanners execute even if an earlier scanner detects violations.

## 5. Exit Codes

| Exit Code | Meaning | Commit Behavior |
|---|---|---|
| `0` | All quality checks passed | Commit may proceed |
| `1` | One or more quality violations detected | Commit blocked |
| `2` | Scanner execution or reporting error | Commit blocked |

The hook uses the overall exit status returned by the main quality-gate runner.

A successful quality-gate result does not guarantee that Git will create the commit. Other Git checks or errors may still prevent it.

## 6. Reports

The quality gate generates an HTML report at:

`quality/Local_Scans/reports/work/local-report.html`

It also creates a timestamped ZIP archive under:

`quality/Local_Scans/reports/`

Generated reports and downloaded scanner distributions are excluded from Git.

## 7. Manual Execution

Developers can run the quality gate without creating a commit.

From the `backend/` directory:

```bash
sh ../quality/Local_Scans/run-local-checks.sh
```

This is useful when checking changes before staging or committing.

## 8. Emergency Bypass

Git provides a mechanism to bypass pre-commit hooks:

```bash
git commit --no-verify
```

This skips the configured local pre-commit hook.

It should be reserved for exceptional circumstances and should not be part of the normal development workflow.

**Important:** The BN2 CI Quality Gate is not yet implemented. Therefore, bypassing BN1 currently bypasses the project's implemented automated commit-time quality enforcement.

## 9. Relationship to BN2

BN1 and BN2 serve different purposes.

| Quality Gate | Purpose | Status |
|---|---|---|
| BN1 — Local Quality Gate | Runs static analysis locally and before commits | Implemented |
| BN2 — CI Quality Gate | Runs automated checks during GitHub pull-request validation | Planned |

BN2 is intended to provide repository-level validation independent of each developer's local Git hook configuration.

Until BN2 is implemented, the local pre-commit hook remains the primary automated quality gate.

## 10. Verified Behavior

The pre-commit hook has been configured and directly executed successfully on macOS.

The direct hook test confirmed that:

- Checkstyle passed.
- PMD passed.
- SpotBugs passed.
- The overall quality gate returned exit code `0`.

An actual Git-triggered commit test remains pending until the BN1 changes are approved for committing.

## 11. Current Status

The BN1 pre-commit hook is implemented and locally verified.

Remaining work includes:

1. Final repository-content review.
2. Verification during an approved Git commit.
3. Implementation of BN2 GitHub Actions integration.
4. Additional cross-platform verification.
