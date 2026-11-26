# Initial SYSTEM_ADMIN bootstrap — operator safety contract

**Status:** Implementation and isolated database tests exist; do not run against the live EPMS database until CLI process acceptance and the production-role provisioning procedure are approved and verified.

- Entry point: `com.jamesstevens.epms.security.bootstrap.InitialAdminBootstrapCli`. It must not launch the EPMS web server.
- The CLI accepts **no command-line arguments**. It requires an interactive terminal and the environment variables `EPMS_BOOTSTRAP_JDBC_URL` (PostgreSQL JDBC URL) and `EPMS_BOOTSTRAP_USERNAME` (`epms_bootstrap_runtime`).
- The bootstrap database password is prompted without terminal echo; **do not** put it in an environment variable, shell command, arguments, or logs. The administrator password is separately prompted without terminal echo.
- The administrator password must pass verified password screening and be encoded with Argon2id before the restricted V15 function is invoked.
- The database role must be preprovisioned with least privilege. The ordinary application and RBAC runtime identities must not be used for bootstrap.
- The V15 function enforces one-time bootstrap, transactional state/audit changes, and replay rejection.
- Unexpected console-input failures produce a generic error and clear the mutable database-password buffer before exit.
- The CLI uses Java `String` for password policy/screening and JDBC compatibility. Java cannot reliably erase immutable Strings; restrict process lifetime, access, crash dumps, and diagnostics. Do not claim zeroization of Strings.
- Password screening needs an available provider; outages must fail closed. Never override this requirement to get the initial administrator created.
- Process acceptance should run only against isolated disposable databases; the production invocation requires a separate operator runbook and explicit authorization.
- Do not print database credentials, administrator passwords, password hashes, SQL parameter values, or exception stack traces to operator output or test reports.

## Related database migration procedure

Before V15, provision the restricted `epms_bootstrap_owner` and
`epms_bootstrap_runtime` identities using an authorized administrator. Use
[the migration utility](../../tools/database/README.md) for reviewed temporary
owner grants and verification of cleanup. The utility never runs this bootstrap
CLI or sets a bootstrap password. Bootstrap process acceptance and explicit
operator authorization remain separate requirements.
