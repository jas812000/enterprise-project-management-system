# EPMS — Database Naming Conventions

**Phase:** 5 — Logical Database Design  
**Status:** Documentation draft for review  
**Basis:** Approved EPMS decisions and the uploaded ERD.

## 1. General conventions

- Use lowercase `snake_case` for tables and columns.
- Use descriptive plural nouns for tables: `organizations`, `project_team_participations`, `user_refresh_tokens`.
- Use singular entity-based primary keys: `organization_id`, `project_team_participation_id`.
- Use consistent semantic names rather than generic `id`, `status`, `type`, or `date` columns.
- Avoid SQL reserved words and unclear abbreviations.
- Use UUID for entity identifiers, with UUIDv7 preferred when creating new IDs.

## 2. Foreign keys

- Name FK columns after the referenced role and identifier, e.g. `organization_id`, `created_by_user_id`, `project_manager_user_id`.
- For distinct actor roles, retain explicit prefixes: `created_by_user_id`, `updated_by_user_id`, `removed_by_user_id`, `released_by_user_id`.
- For catalog-code FKs, use `<meaning>_code` and reference a unique catalog `code`, e.g. `removal_reason_code` → `project_team_participation_end_reasons.code`.
- Mark FK columns as `FK` on table diagrams and connect them on relationship diagrams.
- FK labels do not imply `NOT NULL`; document requiredness separately.

## 3. Reference tables

- Use plural catalog names describing the values, e.g. `membership_end_reasons`, `project_team_participation_end_reasons`.
- Use an entity-specific UUID primary key, unique `code VARCHAR(50)`, `display_name`, optional `description`, `is_active`, `sort_order`, and creation/update timestamps where standardized.
- Never reuse a catalog merely because two concepts have similar labels; distinguish team membership end from project-team participation end.
- Preserve codes referenced by historical rows; deactivate rather than destructively deleting referenced catalog values.

## 4. Status and temporal columns

- Current statuses: explicit `<meaning>_status_code` or an ERD-established `<meaning>_status_id`.
- Historical status changes: dedicated `*_status_history` tables.
- Time instants: `*_at` (`created_at`, `updated_at`, `joined_at`, `left_at`, `unassigned_at`), using `TIMESTAMPTZ` for instants.
- Boolean flags: `is_*` or other unambiguous boolean phrases (`is_active`, `is_voided`).
- Period sequence: `*_period_number`, scoped to the applicable entity pair or parent.
- Free-form explanatory text: `*_comment`, `*_notes`, or `description`, as appropriate.

## 5. Constraint naming — proposed physical implementation convention

The logical ERD does not encode names for every database constraint. When implementing the physical schema, use:

| Object | Convention | Example |
|---|---|---|
| Primary key | `pk_<table>` | `pk_projects` |
| Foreign key | `fk_<child_table>_<column>` | `fk_tasks_project_id` |
| Unique constraint | `uq_<table>_<columns>` | `uq_user_credentials_user_id` |
| Check constraint | `ck_<table>_<rule>` | `ck_time_entries_positive_duration` |
| Index | `ix_<table>_<columns>` | `ix_tasks_project_id` |

These examples are **proposals for the future physical database phase**, not claims that such objects already exist.

## 6. Consistency and change control

- The ERD is the authoritative source for existing table/column spellings.
- Avoid renaming a column solely for stylistic preference without an approved design decision.
- Record exceptions and approved changes in `database-decision-register.md`.
- Enforce consistent naming across ERD, design documents, future DDL, backend mappings, and API contracts.
