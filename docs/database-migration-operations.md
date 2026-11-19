# EPMS Database Migration Operations

**Project:** Enterprise Project Management System (EPMS)
**Security Requirement:** SEC-016 — Database Privilege Separation
**Database:** PostgreSQL 18.6
**Migration Tool:** Flyway 12.4.0
**Build Tool:** Maven

## 1. Purpose

EPMS separates database schema management from normal application execution.

Flyway migrations execute independently of Spring Boot using a dedicated
PostgreSQL migration account.

The application uses a restricted database account that cannot create schema
objects or access Flyway's migration history.

EPMS is a single-organization application. Each deployed instance serves
exactly one organization.

## 2. Database Roles

EPMS uses separate database identities for administration, migration execution,
and normal application operations.

| Role                     | Responsibility                                                              |
|--------------------------|-----------------------------------------------------------------------------|
| PostgreSQL administrator | Database administration, ownership management, and privileged configuration |
| `epms_migrator`          | Executes versioned migrations and owns migration-managed schema objects     |
| `epms_app`               | Performs authorized application operations with restricted privileges       |

### 2.1 Application Account

The `epms_app` role:

- Can connect to the EPMS database.
- Has `USAGE` but not `CREATE` on the `public` schema.
- Cannot read or modify `flyway_schema_history`.
- Cannot create databases or PostgreSQL roles.
- Does not have PostgreSQL superuser privileges.
- Receives explicit table permissions based on application requirements.

Current application table permissions:

| Table                   | SELECT | INSERT | UPDATE | DELETE |
|-------------------------|--------|--------|--------|--------|
| `account_types`         | Yes    | No     | No     | No     |
| `organizations`         | Yes    | Yes    | Yes    | No     |
| `departments`           | Yes    | Yes    | Yes    | No     |
| `users`                 | Yes    | Yes    | Yes    | No     |
| `flyway_schema_history` | No     | No     | No     | No     |

The application account must not own schema objects.

### 2.2 Migration Account

The `epms_migrator` role:

- Can connect to the EPMS database.
- Has `USAGE` and `CREATE` on the `public` schema.
- Owns the existing migration-managed tables.
- Can execute approved Flyway migrations.
- Cannot create databases or PostgreSQL roles.
- Does not have PostgreSQL superuser privileges.

Migration credentials must not be supplied to the normal application runtime.

### 2.3 PostgreSQL Administrator

The PostgreSQL administrator manages:

- Database ownership and administrative configuration.
- Database role creation and maintenance.
- Privileged ownership changes.
- Backup and recovery operations.
- Administrative security controls.

Routine application execution and schema migrations should not require
administrator credentials.

## 3. Spring Boot Configuration

### 3.1 Normal Application Runtime

The main configuration is located at:

`backend/src/main/resources/application.properties`

Relevant settings:

```properties
spring.flyway.enabled=false
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true
spring.flyway.baseline-on-migrate=false

spring.jpa.hibernate.ddl-auto=validate
```

Flyway does not execute automatically during normal application startup.

Hibernate validates entity mappings against the existing database schema
without creating or modifying tables.

The application datasource uses:

```properties
spring.datasource.username=${EPMS_DB_USERNAME}
spring.datasource.password=${EPMS_DB_PASSWORD}
```

These variables must identify the restricted application database account.

### 3.2 Integration Test Configuration

Integration test configuration is located at:

`backend/src/test/resources/application.properties`

Relevant settings:

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true
spring.flyway.baseline-on-migrate=false

spring.jpa.hibernate.ddl-auto=validate
```

Integration tests use isolated PostgreSQL Testcontainers databases.

Flyway initializes these temporary databases before Hibernate validates
the entity mappings.

Production migration credentials are not required for these tests.

The test configuration is separate from normal runtime configuration.

## 4. Independent Flyway Execution

### 4.1 Maven Plugin

The Flyway Maven plugin is configured in:

`backend/pom.xml`

The plugin executes independently of Spring Boot.

Flyway is not bound to the normal Maven build lifecycle. Migration commands
must be invoked explicitly.

### 4.2 Migration Connection Configuration

The Maven Flyway plugin reads three environment variables:

| Environment Variable | Purpose |
|---|---|
| `EPMS_FLYWAY_URL` | JDBC URL identifying the target PostgreSQL database |
| `EPMS_FLYWAY_USERNAME` | PostgreSQL migration account username |
| `EPMS_FLYWAY_PASSWORD` | PostgreSQL migration account password |

For local development, `EPMS_FLYWAY_URL` identifies the local EPMS PostgreSQL database.

The connection URL is supplied through the developer's environment configuration rather than hardcoded in the Maven plugin.

The migration username must identify `epms_migrator`.

These variables are configured locally through:

```text
~/.config/epms/env
```

The shell loads this configuration before executing Maven commands.

Migration credentials must not be hardcoded in:

- `pom.xml`
- `application.properties`
- Migration scripts
- Git-tracked configuration files

The migration process must use the intended database URL and credentials.

Normal Spring Boot runtime processes must not receive migration credentials.

For production deployments, provide these variables securely to the dedicated migration process rather than relying on a developer's local configuration file.

### 4.3 Working Directory

Run Flyway Maven commands from the backend directory:

```bash
cd /Volumes/DevDrive/code/web/fullstack/epms/backend
```

The configured migration directory is:

```text
src/main/resources/db/migration
```

### 4.4 Inspect Migration History

```bash
./mvnw flyway:info
```

This command:

- Connects to the configured PostgreSQL database.
- Reads the existing Flyway migration history.
- Displays applied and pending migrations.
- Does not apply migrations.

### 4.5 Validate Existing Migrations

```bash
./mvnw flyway:validate
```

This command verifies that applied migrations match the available
migration files.

Validation failures must be investigated before proceeding.

Do not automatically repair migration history to bypass validation errors.

### 4.6 Apply Approved Migrations

```bash
./mvnw flyway:migrate
```

**Warning:** This command modifies the database when pending migrations exist.

Before execution:

1. Confirm the intended database and environment.
2. Review all pending migration scripts.
3. Verify that a current, restorable database backup exists.
4. Confirm the migration account and its privileges.
5. Check application compatibility with the pending schema changes.
6. Obtain approval for migration execution.

After execution:

```bash
./mvnw flyway:info
./mvnw flyway:validate
```

Confirm that the expected migrations were applied successfully.

## 5. Application Startup

### 5.1 Required Runtime Credentials

Normal application execution requires:

- `EPMS_DB_USERNAME`
- `EPMS_DB_PASSWORD`

These credentials must belong to `epms_app`.

The application runtime must not receive:

- `EPMS_FLYWAY_URL`
- `EPMS_FLYWAY_USERNAME`
- `EPMS_FLYWAY_PASSWORD`

The migration connection settings belong exclusively to the independent Flyway process.

### 5.2 Local Startup Verification

From the backend directory:

```bash
env -u EPMS_FLYWAY_URL \
    -u EPMS_FLYWAY_USERNAME \
    -u EPMS_FLYWAY_PASSWORD \
    ./mvnw spring-boot:run
```

This excludes migration connection settings and credentials from the application process.

Expected startup behavior:

- HikariCP connects to PostgreSQL.
- Hibernate validates entity mappings.
- Spring Boot initializes the application context.
- Embedded Tomcat starts.
- Flyway does not execute migrations.

### 5.3 Deployment Sequence

The required deployment sequence is:

1. Back up the database when appropriate.
2. Review pending migration scripts.
3. Execute approved migrations using `epms_migrator`.
4. Validate the resulting migration history.
5. Start or deploy the application using `epms_app`.
6. Verify application health and database connectivity.

The application must not require migration credentials to start.

## 6. Future Database Migrations

### 6.1 Migration Directory

All versioned database migrations belong in:

```text
backend/src/main/resources/db/migration/
```

Migration naming convention:

```text
V3__description.sql
V4__description.sql
V5__description.sql
```

Migration version numbers must remain unique and follow the approved
schema evolution sequence.

### 6.2 Applied Migration Integrity

Once a migration has been applied to a shared or persistent database:

- Do not edit the migration.
- Do not rename it.
- Do not delete it.
- Do not alter its checksum to bypass validation.

Corrections should normally be implemented through a new migration.

### 6.3 Privileges for New Tables

New database objects require explicit privilege review.

The `epms_app` account must receive only the permissions needed for
approved application operations.

Example:

```sql
GRANT SELECT ON TABLE public.example_table TO epms_app;
```

This example is illustrative and must not be applied without confirming
the table's actual access requirements.

A table supporting application writes may require additional privileges,
such as `INSERT` or `UPDATE`.

Security-sensitive tables require separate review.

Examples include:

- Authentication credentials.
- Session records.
- Refresh tokens.
- Role and permission catalogs.
- User-role assignments.
- Security monitoring records.
- Audit and compliance records.

Do not automatically grant unrestricted privileges to every table.

### 6.4 Schema Ownership

Migration-managed schema objects should remain owned by the migration
account or another explicitly approved privileged owner.

The application account must not receive:

- Schema ownership.
- General schema creation privileges.
- Unrestricted database administration privileges.
- Permissions that bypass approved security controls.

### 6.5 Database Triggers and Constraints

Security-sensitive constraints and database triggers must not be
modifiable or removable by the application account.

Database-level controls should be maintained through reviewed migrations.

## 7. Security Requirements

### 7.1 Credential Protection

- Never commit database passwords to Git.
- Never include passwords in documentation examples.
- Avoid displaying passwords in screenshots.
- Avoid verbose Maven output that could expose credentials.
- Keep migration credentials separate from application credentials.
- Use secure environment-specific credential management.

### 7.2 Least Privilege

Database permissions must follow the principle of least privilege.

The application receives only the access required for its approved
business operations.

Migration permissions must be limited to approved schema management
responsibilities.

### 7.3 Prohibited or Restricted Operations

Do not execute the following commands without explicit authorization
and a documented recovery plan:

```bash
./mvnw flyway:clean
./mvnw flyway:repair
```

`flyway:clean` can remove database objects and data.

`flyway:repair` modifies migration history metadata and must not be used
to conceal unresolved migration problems.

### 7.4 Backup and Recovery

Before significant schema changes:

1. Create an appropriate database backup.
2. Verify the backup is readable.
3. Confirm recovery procedures.
4. Review the impact of the proposed migration.
5. Identify recovery options if migration execution fails.

A successful backup command alone does not guarantee recoverability.
Restoration procedures should also be tested.

## 8. Troubleshooting

### 8.1 Migration Authentication Failure

Verify:

- The migration username is correct.
- The migration password is available to Maven.
- PostgreSQL is running.
- The target database is correct.
- The migration role has permission to connect.

### 8.2 Permission Denied

Determine which database role executed the failing statement.

Review:

- Schema permissions.
- Table ownership.
- Table privileges.
- Database privileges.
- Role memberships.

Do not grant unrestricted privileges merely to eliminate an error.

### 8.3 Flyway Validation Failure

Check for:

- Modified migration files.
- Missing migration files.
- Checksum mismatches.
- Failed migrations.
- Unexpected migration history changes.

Investigate before using corrective commands.

### 8.4 Application Startup Failure

Verify:

- The database is running.
- `epms_app` credentials are valid.
- Required migrations have completed.
- Hibernate mappings match the schema.
- Application table privileges are correct.
- Runtime Flyway execution remains disabled.

### 8.5 Integration Test Failure

Verify:

- Docker is running.
- Testcontainers can start PostgreSQL.
- Test-specific configuration is loaded.
- Flyway initializes the test schema.
- Hibernate validation succeeds.

## 9. SEC-016 Verification Results

**Verification date:** November 18, 2026

### 9.1 Independent Flyway Execution

- Flyway Maven Plugin 12.4.0 resolved successfully.
- PostgreSQL 18.6 connection succeeded.
- Flyway reported schema version 2.
- V1 initial schema migration was successful.
- V2 account type seed migration was successful.
- Flyway validation passed for both migrations.

### 9.2 Automated Tests

Maven test results:

| Metric         | Result  |
|----------------|---------|
| Tests executed | 130     |
| Failures       | 0       |
| Errors         | 0       |
| Skipped        | 0       |
| Build          | SUCCESS |

### 9.3 Application Startup

Spring Boot started successfully without Flyway migration credentials.

Verified components:

- HikariCP connection pool.
- JPA EntityManagerFactory.
- Embedded Tomcat on port 8080.
- Spring Boot application startup.

### 9.4 PostgreSQL Privilege Verification

Verified:

- `epms_app` is not a PostgreSQL superuser.
- `epms_app` cannot create databases or roles.
- `epms_app` has schema `USAGE`.
- `epms_app` does not have schema `CREATE`.
- `epms_app` cannot read Flyway migration history.
- `epms_migrator` has schema `CREATE`.
- `epms_migrator` is not a PostgreSQL superuser.
- `epms_migrator` cannot create databases or roles.

### 9.5 Verification Scope

These results verify the tested local development configuration.

Future migrations, production deployment, credential management,
and security-sensitive database objects require their own validation.

## 10. Related Project Files

- `backend/pom.xml`
- `backend/src/main/resources/application.properties`
- `backend/src/test/resources/application.properties`
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`
- `backend/src/main/resources/db/migration/V2__seed_account_types.sql`
- `docs/logical-database-design.md`
- `docs/database-decision-register.md`
- `docs/database-naming-conventions.md`
