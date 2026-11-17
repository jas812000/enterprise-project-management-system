# EPMS Git Hooks — Local Quality Gate Integration

## 1. Overview

The `hooks/` directory contains Git hooks used to integrate the EPMS Local Quality Gate (BN1) into the development workflow.

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

**Important:** The CI Quality Gate is implemented separately in GitHub Actions. Bypassing BN1 skips local checks, but does not skip CI.

## 9. Relationship to the CI Quality Gate

BN1 runs advisory local checks before commits. The CI Quality Gate is implemented in GitHub Actions, with policy-driven evaluation and successful PR/main workflow runs #19 and #20. Required checks depend on GitHub branch protection/rulesets.

The pre-commit hook does not block commits, even when scanner findings or execution errors are reported.

## 10. Verified Behavior

The hook ran during actual Git commit `40760b9` on 2026-11-17: seven scanners clean, two disabled, zero findings and zero errors. A later commit proceeded despite a TruffleHog Docker execution error, consistent with advisory behavior. After starting Docker, the full BN1 run completed cleanly.

## 11. Current Status

The BN1 hook is implemented and verified in real Git commits. It is advisory, not blocking. Cross-platform and fresh-environment verification remain outstanding.

To install from the repository root:

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
chmod +x quality/Local_Scans/hooks/pre-commit
git config --show-origin --get core.hooksPath
```

Start Docker before running the gate.
