# EPMS developer rules — database changes

These rules supplement the existing EPMS domain rules and security contracts.

1. Keep the single-organization architecture and approved Java 25, Spring Boot
   4.1.1, PostgreSQL 18 and Flyway stack. Do not add runtime DDL access.
2. Keep Flyway separate from the web application. Runtime `spring.flyway.enabled`
   remains false and Hibernate `ddl-auto` remains validate. Integration tests
   use disposable PostgreSQL databases, not the developer database.
3. Use `tools/database/migrate.py --dry-run` before approved local migrations.
   Review the target, pending SQL, prerequisite manifest and restorable backup.
   Apply with `--apply --backup-confirmed`; validate and audit afterward.
4. For every new migration, explicitly record its reviewed SHA-256 and protected
   owner prerequisites in `tools/database/migration-prerequisites.json`.
   Do not edit already-applied SQL or use Flyway repair to conceal errors.
5. Only the migration account applies ordinary schema changes. Administrator
   credentials and temporary role grants are exceptional and explicitly
   authorized. Do not permanently elevate `epms_migrator`, `epms_app` or
   `epms_rbac_runtime` for convenience. Restore and verify temporary grants on
   success and failure; stop if cleanup cannot be verified.
6. Maintain separate migration, application, RBAC and bootstrap identities.
   Never expose privileged SQL or administrator credentials to the runtime,
   repository, command-line arguments or logs. Do not initiate bootstrap merely
   because a migration introduced its function.
7. Keep temporary backups, reports and review exports in `~/Downloads/`.
   Keep source and durable developer tooling in their established repositories.
   Preserve existing Git changes; do not commit, push, merge or delete branches
   without explicit authorization.
8. Validate focused and full tests, Maven verification and applicable quality
   gates. Identify expected skipped tests; investigate unexpected skips and
   unresolved scanner findings. A successful migration does not establish
   end-to-end application security.
9. Review interrupted-run recovery journals and audit privilege restoration before
   resuming migrations. Do not run concurrent manual migrations or provisioning.

Operational detail: [Database Migration Operations](database-migration-operations.md)
and [utility usage](../tools/database/README.md).

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
