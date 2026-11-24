# Local Quality Gate — Git Hook Integration

## 1. Overview

The `quality/Local_Scans/hooks/` directory contains Git hooks for integrating the Local Quality Gate into a developer's Git workflow.

The provided `pre-commit` hook executes the Local Quality Gate before Git creates a commit.

Its purpose is to provide early feedback about static-analysis findings, security findings, scanner execution errors, and disabled scanners.

**The Local Quality Gate is advisory.**

The provided hook does not intentionally block commits because of scanner findings or handled scanner execution failures.

CI Quality Gate enforcement is configured separately through GitHub Actions.

## 2. Available Hooks

| Hook | Purpose | Enforcement |
|---|---|---|
| `pre-commit` | Runs the Local Quality Gate before a commit | Advisory |

The hook invokes:

`quality/Local_Scans/run-local-checks.sh`

The Local Quality Gate coordinates ten registered scanners:

- Checkstyle
- PMD
- SpotBugs
- Pylint
- Bandit
- Gitleaks
- TruffleHog
- HTMLHint
- Stylelint

Scanner applicability depends on the consuming project's configuration and available dependencies.

## 3. Installation

Git does not automatically activate custom repository hooks after cloning.

Each developer must configure the hook locally.

### Step 1 — Open the Repository Root

From the consuming repository:

```bash
git rev-parse --show-toplevel
```

Run the remaining commands from that repository's root directory.

### Step 2 — Configure the Hook Directory

```bash
git config --local core.hooksPath quality/Local_Scans/hooks
```

This tells Git to look for hooks in the specified directory.

The setting is stored in the local repository configuration and is not automatically shared with other clones.

### Step 3 — Make the Hook Executable

On macOS/Linux:

```bash
chmod +x quality/Local_Scans/hooks/pre-commit
```

### Step 4 — Verify Installation

```bash
git config --show-origin --get core.hooksPath
```

Expected configured value:

```text
quality/Local_Scans/hooks
```

The output also identifies the configuration source.

## 4. Existing Git Hook Considerations

Git uses one effective `core.hooksPath` setting.

If the consuming repository already uses custom hooks, changing this setting can prevent those hooks from running.

Before installation, inspect:

```bash
git config --show-origin --get core.hooksPath
```

If an existing hook directory is configured, review its contents and determine how the Local Quality Gate should be integrated.

Do not replace existing security, formatting, or validation hooks without reviewing their purpose.

The provided installation procedure assumes that using `quality/Local_Scans/hooks` is acceptable for the consuming repository.

## 5. Pre-Commit Execution Flow

When a developer runs:

```bash
git commit
```

Git invokes the configured pre-commit hook.

The hook attempts to execute the Local Quality Gate.

The Local Quality Gate runs applicable scanners, normalizes results, and generates reporting artifacts.

The hook then prints the result or an advisory warning.

Simplified flow:

```text
git commit
    |
    v
pre-commit hook
    |
    v
Local Quality Gate launcher
    |
    +-- Java scanners
    |
    +-- Additional scanners
    |
    +-- Normalization
    |
    +-- HTML and ZIP reporting
    |
    v
Advisory result or warning
    |
    v
Hook exits 0
    |
    v
Git continues commit processing
```

Git may still reject the commit for reasons unrelated to this advisory hook.

## 6. Exit Codes and Enforcement

### 6.1 Java Scanner Wrapper Exit Codes

The Java scanner wrappers use:

| Exit code | Meaning |
|---|---|
| `0` | Analysis completed without findings |
| `1` | Analysis completed with findings |
| `2` | Scanner execution or report-validation error |

These are scanner-level outcomes, not Git commit decisions.

### 6.2 Local Quality Gate Launcher

The Local Quality Gate records scanner outcomes and generates a consolidated report.

A successfully completed advisory reporting workflow may exit `0` even when findings or scanner execution errors were recorded.

Infrastructure or reporting failures may produce nonzero launcher exit codes.

Therefore, a launcher exit code of `0` does not guarantee that every scanner completed cleanly.

### 6.3 Pre-Commit Hook

The provided pre-commit hook:

1. Checks whether the Local Quality Gate launcher exists.
2. Prints a warning and exits `0` if the launcher is missing.
3. Attempts to run the launcher when present.
4. Prints a success message or warning based on the launcher result.
5. Exits `0` after handling the result.

The hook is intentionally advisory.

Scanner findings and handled launcher failures do not block commits.

## 7. Reports

The Local Quality Gate generates an HTML report at:

`quality/Local_Scans/reports/work/local-report.html`

It also generates normalized JSON and a timestamped ZIP archive under:

`quality/Local_Scans/reports/`

Reports provide more information than the hook's terminal message.

Developers should review findings, disabled scanners, and execution errors before treating a scan as successful.

Generated reports and downloaded scanner distributions should remain excluded from version control.

## 8. Manual Execution

The Local Quality Gate can be executed without creating a Git commit.

From the consuming repository root:

```bash
sh quality/Local_Scans/run-local-checks.sh
```

Manual execution is useful before staging changes, creating commits, or opening pull requests.

For Windows, the framework provides:

```bat
quality\Local_Scans\run-local-checks.bat
```

Windows execution remains unverified.

## 9. Temporarily Skipping the Hook

Git supports:

```bash
git commit --no-verify
```

This skips the pre-commit hook.

Because the provided Local Quality Gate hook is advisory, skipping it is not necessary to bypass scanner findings.

Using `--no-verify` also skips other applicable Git hooks, so it should not be used routinely.

Skipping the local hook does not disable GitHub Actions or the CI Quality Gate.

## 10. Removing the Hook Configuration

To remove the repository-local hook-path setting:

```bash
git config --local --unset core.hooksPath
```

This removes the local override.

Git then uses its normal configuration precedence to determine the effective hook directory.

If another configuration source defines `core.hooksPath`, that setting may remain effective.

Verify the result with:

```bash
git config --show-origin --get core.hooksPath
```

## 11. Relationship to the CI Quality Gate

The Local Quality Gate and CI Quality Gate have different enforcement responsibilities.

| Component | Execution context | Enforcement |
|---|---|---|
| Local Quality Gate | Developer workstation | Advisory |
| Pre-commit hook | Local Git commit | Advisory |
| CI Quality Gate | GitHub Actions | Policy-driven |

The CI Quality Gate evaluates results according to its configured policy.

Whether GitHub requires a passing CI check before merging depends on repository branch protection or ruleset configuration.

The local hook does not replace CI validation.

See `quality/CI/README.md`.

## 12. Troubleshooting

### Hook Does Not Execute

Verify that:

- `core.hooksPath` points to the expected directory.
- The `pre-commit` file exists.
- The hook is executable in the current environment.
- The commit command is being executed in the intended repository.

### Local Quality Gate Launcher Is Missing

The hook prints a warning and allows Git to continue.

Verify that the complete `quality/` framework was installed.

### Scanner Execution Fails

Review the terminal output and generated reports.

Check scanner dependencies, project configuration, Java, Python, Maven, Docker, and network availability as applicable.

### Commit Proceeds Despite Findings

This is expected behavior.

The Local Quality Gate is advisory and does not block commits because of scanner findings.

### Existing Hooks Stop Running

Inspect the effective `core.hooksPath` configuration.

Git may now be using the Quality Gates hook directory instead of the previous hook directory.

Restore or integrate existing hooks as appropriate.

## 13. Portability Status

The pre-commit hook has been exercised in its original host repository.

Independent host-repository integration remains unverified.

Windows execution and fresh-environment installation also remain unverified.

Do not assume that installing the hook guarantees every scanner can execute successfully.

## 14. Related Documentation

- `quality/Local_Scans/README.md` — Local Quality Gate overview
- `quality/Local_Scans/checks/checks_README.md` — Scanner wrappers
- `quality/Local_Scans/reporting/report_README.md` — Reporting
- `quality/CI/README.md` — CI Quality Gate
- `quality/PORTABILITY.md` — Cross-project integration

Trivy requires the `trivy` executable on PATH and scans filesystem vulnerabilities, misconfigurations and secrets. Missing or invalid execution is an error.
