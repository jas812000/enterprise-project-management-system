# EPMS database migration utility

`migrate.py` is the local-development operator for independent Flyway execution.
It does not start Spring Boot, modify migration files, run bootstrap, or repair
migration history. Python 3.10+, PostgreSQL `psql`, Java 25 and the repository's
Maven wrapper are required. Docker is required for the isolated integration test.

## Routine use

From the repository root, after sourcing the established EPMS environment:

```sh
python3 tools/database/migrate.py --info
python3 tools/database/migrate.py --dry-run
python3 tools/database/migrate.py --apply --backup-confirmed
python3 tools/database/migrate.py --validate
python3 tools/database/migrate.py --audit
```

With no flags the utility performs a read-only dry run. Confirm the local target,
review pending SQL and verify a restorable backup before using
`--backup-confirmed`. Only explicitly configured local `epms` databases on
`localhost` or `127.0.0.1` are accepted in this initial implementation. Use the
existing `EPMS_FLYWAY_URL`, `EPMS_FLYWAY_USERNAME` (`epms_migrator`) and
`EPMS_FLYWAY_PASSWORD` variables. The utility does not echo credentials.
Use a trusted Maven environment and user settings; do not use alternate
Flyway or Maven configuration overrides. Do not change source or database role
configuration concurrently with a migration. Stop the backend first.

## Security migrations

`migration-prerequisites.json` explicitly declares the reviewed SQL SHA-256
and temporary protected-owner requirements for V1–V44. V13 requires the RBAC
function owner; V15 requires the bootstrap owner; V17–V20, V22 and V24 require
the authentication function owner. An empty owner list denotes no special
ownership elevation, not an absence of all database prerequisites. V25–V44 have empty temporary owner lists. Review and
add a declaration for every new migration, including routine migrations.
For a changed Maven POM or wrapper, review execution configuration and update
the corresponding `_maven` fingerprints. Never modify an applied migration
just to match a fingerprint.

All eight EPMS security/runtime roles must already be provisioned with their
approved attributes. Protected owners and runtime identities must not have
schema CREATE; the migration account must not already be a member of the
protected owner roles. Missing or unexpected privileges fail closed. Role
creation and permanent USAGE grants are separate administrator provisioning
operations; the utility does not create accounts or silently alter existing
security policy. For a clean installation use the reviewed provisioning runbook.

When dry run identifies protected owner prerequisites, the operator may use:

```sh
python3 tools/database/migrate.py --apply --backup-confirmed --allow-elevation --admin-user james_stevens
```

Use the authorized database administrator login for your local installation.
The utility asks for an explicit confirmation and prompts for the administrator
password without echo. It grants only the declared owners to the migrator with
INHERIT TRUE and SET TRUE, plus schema CREATE to those owners. The grant and
revoke batches run transactionally as the administrator. Permanent USAGE and
function-specific grants are preserved. Unexpected pre-existing role membership
or CREATE rights are rejected instead of overwritten. Do not run concurrent
manual Flyway, provisioning, or another migration utility session.

## Recovery and reporting

Reports are written to `~/Downloads/epms_migration_<UTC timestamp>/`.
The utility records an owner-grant recovery SQL file before elevation. Normal
failure, interrupt and success paths attempt cleanup. The journal is removed
only after the role audit succeeds. If the process is forcibly killed, the
computer loses power, or cleanup fails, temporary grants may remain. Stop
migration activity, review `RECOVERY.sql` against the recorded target and
original grant baseline, execute the recovery SQL as the administrator on that
same target, then run `--audit`. Archive/remove the recovery journal manually
only after confirming cleanup. The utility blocks further write operations while
a recovery journal exists. It does not automatically apply recovery SQL or
repair history. A local file lock protects concurrent utility invocations on
the same account; it does not lock other hosts or direct Maven executions.

Reports contain status, target, version/prerequisite decisions and controlled
failure messages, not raw SQL tool output or credentials. For detailed Flyway
errors use a separately authorized, carefully reviewed local diagnostic run.

## Tests and limitations

Offline tests:

```sh
python3 -B -m unittest discover -s tools/database/tests -v
```

The Phase 12 validation harness additionally creates an isolated Docker PostgreSQL
18 database on a randomly assigned loopback port, provisions restricted roles,
executes the actual V1–V44 migrations with the restricted migrator and verifies
cleanup following an injected failure. It also exercises synthetic V41 logical backup
and restoration, staged upgrades through V44 and protected function security metadata. It does not migrate the developer database.
The actual live audit is separate and read-only. This utility's audit is scoped
to role attributes, membership, schema CREATE/USAGE, selected function owners
and Flyway history access; it is not a full application-security audit.
Production deployment, distributed locking, unattended credential handling and
bootstrap CLI execution are outside this initial local tool's scope.

See [Database Migration Operations](../../docs/database-migration-operations.md)
and [Developer Rules](../../docs/developer-rules.md).

### Routine migrations

From the EPMS repository root, after reviewing the pending migration declarations:

```zsh
python3 tools/database/migrate.py --dry-run

python3 tools/database/migrate.py --apply --backup-confirmed
```

Confirm a restorable backup before using `--backup-confirmed`. The apply operation
performs post-migration validation and privilege auditing.

### Specialized security migrations

When a reviewed migration requires protected function ownership privileges:

```zsh
python3 tools/database/migrate.py --apply --backup-confirmed --allow-elevation --admin-user james_stevens
```

The utility requires explicit authorization and prompts for the administrator
password without displaying it when temporary elevation is necessary. Verify
cleanup; if cleanup fails, follow the recovery journal and audit before continuing.
Never permanently elevate application or migration roles for convenience.

### Consolidated migration preflight

The dry run reports all detected role/schema and membership boundary violations together before blocking migration. Missing roles are reported separately. Review and reconcile each finding using the authorized provisioning procedure; the utility never automatically revokes pre-existing privileges.
