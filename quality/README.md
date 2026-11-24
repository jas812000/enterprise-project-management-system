# EPMS Quality Gates

This directory contains the **EPMS installation and configuration** of the portable Quality Gates Framework. The framework is maintained independently in the `quality-gates-framework` repository; EPMS is one consumer. Changes to shared framework behavior should be coordinated with that upstream project rather than treated as EPMS-only architecture.

## Project configuration

`quality/project.json` defines the EPMS scanner configuration, including Java 25, the `backend` Maven project, Java sources under `backend/src/main/java`, and Python sources under `quality`. `quality/project.example.json` illustrates the configuration format for other projects. The `safe_jdbc_urls` entry identifies an expected application configuration URL; it is **not** permission to run integration tests against the primary `epms` database.

## Local Quality Gate

From the EPMS repository root, first verify the working directory with `pwd`, then run:

```sh
sh quality/Local_Scans/run-local-checks.sh
```

The launcher uses Python 3.10+, reads `quality/project.json`, runs the configured local scanners, normalizes results, produces an HTML report, and packages a ZIP report. Its documented entry points are `quality/Local_Scans/README.md` and `quality/PORTABILITY.md` where present.

Local checks include Checkstyle, PMD, SpotBugs, TruffleHog, Gitleaks, Pylint, and Bandit. HTMLHint and Stylelint may be disabled when their applicable source/configuration is absent. Consult the generated summary for the actual enabled scanners and execution results; do not assume every scanner ran.

**Local mode is advisory:** findings do not block commits. A zero-new-findings summary does not mean every historical issue has been eliminated. SpotBugs uses an approved baseline (`quality/Local_Scans/checks/spotbugs-approved-baseline.json`); review the baseline separately from new findings.

## CI quality gate

The `quality/CI/` directory contains CI normalization, policy, and reporting code. CI enforcement depends on the repository's GitHub Actions workflow and configured policy. A successful local advisory scan **does not** establish that CI blocking checks passed. Inspect the workflow run and its artifacts before declaring CI acceptance.

## Reports and file hygiene

The current local launcher writes HTML and ZIP output under `quality/Local_Scans/reports/`. These are **generated artifacts**, not project source. For local review and handoff, copy or export reports to `~/Downloads/`; do not add reports, secrets, or raw scanner output to Git. The current launcher also maintains its own working report directory, which it clears on subsequent runs; preserve any report you need before rerunning.

Do not paste credentials or unredacted secret-scanner evidence into issues or pull requests. Prefer the normalized report and validated packaged ZIP, and review all artifacts before sharing.

## Verification and acceptance

1. Verify the repository root and working-tree status before changing quality configuration.
2. Run the appropriate isolated backend tests and confirm the full Maven suite passes.
3. Run the Local Quality Gate and inspect enabled, disabled, baseline, and error counts.
4. Run the configured CI quality gate and review blocking-policy results separately.
5. Review Git changes and generated artifacts before preparing a pull request. Do not commit or push without explicit approval.

The primary `epms` PostgreSQL database must not be used as a disposable test database. Integration tests should use isolated Testcontainers databases with the required test-only PostgreSQL role provisioning.

## Troubleshooting

- If the launcher cannot find Python, install or select Python 3.10 or newer.
- If Maven or a scanner is unavailable, inspect the scanner-specific error and prerequisites rather than treating the run as clean.
- If a scanner is disabled, confirm whether that matches the project's current technology and policy.
- If SpotBugs reports approved findings, compare them against the approved baseline and investigate new or changed findings.
- If a report must be retained, copy it to `~/Downloads/` before another local scan replaces its working output.
