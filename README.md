# Enterprise Project Management System

The Enterprise Project Management System (EPMS) is an organization-based project management application being developed as a portfolio-quality full-stack system using modern enterprise Java technologies.

EPMS is designed around organizations, teams, users, projects, tasks, and labels. Organizations will be able to organize teams and projects, users will work with assigned project tasks, and managers will be able to monitor project progress.

## Technology Stack

- Java
- Spring
- Spring Boot
- React
- PostgreSQL
- Docker
- Kubernetes

## Project Status

**In Development**

EPMS is currently under active development. Features and documentation will be added incrementally using a feature-based development and pull request workflow.

## Repository Structure

- `backend/` — Spring Boot backend application
- `frontend/` — React frontend application
- `database/` — Database-related scripts and resources
- `docs/` — Project documentation
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
