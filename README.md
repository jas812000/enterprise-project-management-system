# Enterprise Project Management System

The Enterprise Project Management System (EPMS) is an organization-based project management application being developed as a portfolio-quality full-stack system using modern enterprise Java technologies.

EPMS is a single-organization application: each deployed instance serves one organization. Teams and users are implemented; projects, tasks, labels and broader project management capabilities remain part of the roadmap.

## Technology Stack

- Java 25, Spring Boot 4.1.1, Spring Security, Spring Data JPA and Maven
- PostgreSQL 18 and Flyway
- JUnit, Mockito and Testcontainers
- Docker for isolated database integration testing
- React and Kubernetes: planned frontend and deployment technologies

## Project Status

**In Development**

EPMS is currently under active development. Phase 12 (Team backend) has passed local
technical acceptance and is undergoing pull request integration. Implemented backend
capabilities include organization and user persistence, authentication and scoped RBAC,
Team lifecycle, temporal membership, permanent and acting leadership, emergency
succession, durable in-app notifications, critical alerts and confidential audit access.
Automatic notification processing is opt-in; external email delivery is not implemented.

The Phase 12 release candidate uses Flyway V1–V44. The comprehensive V44 Maven
run reported 570 tests including one expected live-audit skip, with zero failures or
errors. A separate live datasource audit passed; the final local Quality Gates reported
zero findings and scanner errors. See the release acceptance record for approved
limitations and deferred capabilities. The protected `main` branch requires the GitHub
Actions `quality-gate` check and a pull request. Local success does not replace PR CI.

## Repository Structure

- `backend/` — Spring Boot backend application
- `frontend/` — React frontend application
- `database/` — Database-related scripts and resources
- `docs/` — Project documentation
- `tools/database/` — Independent Flyway operator, migration prerequisites and tests
- `quality/` — EPMS installation of the portable Quality Gates Framework
- `diagrams/` — Editable diagrams and exported images
- `docker/` — Docker-related configuration
- `kubernetes/` — Kubernetes configuration
- `postman/` — Postman API collections
- `.github/` — GitHub configuration and workflows

## Documentation

EPMS technical documentation is maintained in the `docs/` directory.

### Database Design

- [Logical Database Design](docs/logical-database-design.md) — Logical data model, entity relationships, and database constraints.
- [Database Naming Conventions](docs/database-naming-conventions.md) — Naming standards for database objects.
- [Database Decision Register](docs/database-decision-register.md) — Approved database architecture and design decisions.

### Database Operations

- [Database Migration Operations](docs/database-migration-operations.md) — Flyway migration procedures, PostgreSQL role separation, least-privilege access, and application startup requirements.

### Business Requirements

- [Business Requirements](docs/business-requirements.md) — EPMS functional and business requirements.
- [Domain Rules](docs/domain-rules.md) — Business rules, entity relationships, and lifecycle constraints.

### Team backend

- [Phase 12 release acceptance and traceability](docs/team-phase12-release-acceptance.md)
- [Team REST API](docs/team-rest-api.md)
- [Team notification and audit boundary](docs/team-notification-and-audit-boundary.md)
- [Historical Team physical database design](docs/team-phase12-physical-database-design.md)

### Developer migration tooling

- [Migration utility](tools/database/README.md) — read-only preflight, approved
  migrations, temporary privilege cleanup and audit reporting.
- [Developer Rules](docs/developer-rules.md) — mandatory migration and security
  procedures.

From the repository root, use `python3 tools/database/migrate.py --dry-run`,
then `--apply --backup-confirmed` after reviewing the target, pending SQL and
restorable backup. The utility uses the existing independent Flyway configuration.

### Backend verification

From the repository root, use the Java 25 Maven wrapper:

```sh
cd backend
./mvnw verify
```

Integration tests require Docker and use disposable PostgreSQL databases. The
live datasource audit is separately opt-in and must target only the reviewed local
configuration. See [Quality Gates](quality/README.md) for scanner procedures.

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
