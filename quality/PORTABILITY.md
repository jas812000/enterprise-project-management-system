# Portable Quality Gate — incremental refactor

This is a **first-step portability patch**, not a complete standalone quality toolkit.
It configures the Java source path and Maven project path without changing EPMS defaults.

## Host project setup

1. Copy the complete `quality/` directory from the patched EPMS repository.
2. Create `quality/project.json` from `quality/project.example.json`, setting
   `java_source` and `maven_project` to paths relative to the host repo.
3. Install the original scanner prerequisites and Python requirements.
4. Run `sh quality/Local_Scans/run-local-checks.sh` from the host repository.
5. Inspect the generated report, especially scanner execution errors.
6. Configure the Git hook with
   `git config --local core.hooksPath quality/Local_Scans/hooks`.

## Scope and limitations

- This patch does **not** add technology auto-detection or configurable enable/disable
  for the three Java scanners. Non-Java projects will still report Java scanner errors.
- The current Gitleaks, TruffleHog, CI workflows, report paths, package imports, and
  other scanner settings may still contain host-project assumptions.
- The scanner wrappers still assume a Maven build for SpotBugs.
- No external repository portability test has been performed.
- BN1 remains advisory; a zero exit status does not mean zero scanner errors.
- Never mark portability complete until the complete folder is copied into a separate
  test repository and all applicable scanners are verified.

## Exact local JDBC false-positive exceptions

The optional `safe_jdbc_urls` entries use a `file` path and `jdbc_parts`
object with `scheme`, `host`, `port`, and `database`. The loader validates
these components and constructs the exact URL at runtime. This avoids
placing a complete connection string in scanner configuration or validation
source, where TruffleHog may flag it as an unverified JDBC secret.
Only unverified JDBC findings matching the configured file and reconstructed
URL are suppressed; verified or different findings remain reportable.
Never put credentials, connection query parameters, or remote hosts in these
exceptions. This is not a general TruffleHog exclusion.

## CI configuration precedence

When `quality/project.json` exists, its validated `scan_root`, `maven_project`,
`python_source`, and `javascript_source` override legacy `.env` and discovery.
An empty Python or JavaScript source path remains disabled, not auto-discovered.
The configured `java_version` controls GitHub Actions Java setup (EPMS: 25).
Missing configuration preserves legacy discovery and Java 25. Invalid configuration
stops loading; missing configured source directories stop root resolution.
Gitleaks uses the resolved host scan root and TruffleHog uses the matching
workspace-relative path within the `/repo` container mount.

CI and reuse in a separate repository remain unverified. Java scanner wrappers
and reporting retain some existing structural assumptions.
