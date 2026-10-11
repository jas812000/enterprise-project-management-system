# EPMS — Logical Database Design

**Phase:** 5 — Logical Database Design  
**Status:** Documentation draft for review  
**ERD source:** `epms-logical-erd v2(5).drawio`  
**Scope:** Logical model only; not a physical schema, migration plan, or JPA specification.

## 1. Purpose and source of truth

This document describes the logical data model extracted from the supplied multi-page diagrams and the design rules approved during the Phase 5 review. The diagram is the authoritative inventory of table and column names. Rules explicitly described below are design requirements even when the diagram does not encode them.

The ERD contains **101 tables**, **166 relationship connectors**, and **23 diagram pages** (overview plus 11 table and 11 relationship pages).

## 2. Domain organization

### 1 - Organization & Users

**8 tables:** `organizations`, `departments`, `users`, `teams`, `team_memberships`, `project_personnel_assignments`, `supervisor_assignment_history`, `user_email_history`.

### 2 - Projects & Tasks

**12 tables:** `projects`, `project_team_participations`, `project_manager_assignment_history`, `tasks`, `task_assignments`, `task_dependencies`, `project_setup_statuses`, `project_status_history`, `task_status_history`, `project_lifecycle_statuses`, `task_lifecycle_statuses`, `task_priorities`.

### 3 - Roles & Permissions

**9 tables:** `roles`, `permissions`, `role_permissions`, `user_role_assignments`, `user_role_assignment_history`, `project_role_assignments`, `account_types`, `oauth_scopes`, `service_account_scopes`.

### 4 - Collaboration

**7 tables:** `project_comments`, `task_comments`, `project_attachments`, `task_attachments`, `labels`, `task_labels`, `attachment_types`.

### 5 - Time & Reporting

**9 tables:** `time_entries`, `timesheets`, `timesheet_entries`, `timesheet_approval_history`, `timesheet_statuses`, `time_entry_types`, `report_definitions`, `report_execution_history`, `report_schedules`.

### 6 - Notifications

**9 tables:** `notifications`, `notification_recipients`, `action_required_alerts`, `notification_types`, `notification_severities`, `notification_delivery_attempts`, `notification_escalations`, `outbox_events`, `outbox_processing_attempts`.

### 7 - Configuration

**10 tables:** `system_settings`, `notification_settings`, `integration_settings`, `configuration_change_history`, `configuration_change_requests`, `configuration_change_decisions`, `approval_delegations`, `configuration_risk_levels`, `configuration_change_statuses`, `configuration_tabs`.

### 8 - Audit & Compliance

**10 tables:** `legal_holds`, `legal_hold_records`, `legal_hold_release_requests`, `legal_hold_release_decisions`, `legal_hold_modification_requests`, `retention_policies`, `retention_execution_history`, `retention_disposition_requests`, `legal_hold_modification_decisions`, `retention_disposition_decisions`.

### 9 - Audit Logging & Security Monitoring

**7 tables:** `audit_events`, `authentication_events`, `security_incidents`, `security_incident_events`, `security_alerts`, `audit_event_types`, `security_incident_statuses`.

### 10 - Authentication, Sessions & Account Security

**9 tables:** `user_credentials`, `user_sessions`, `user_refresh_tokens`, `email_verification_tokens`, `password_reset_tokens`, `account_lockouts`, `account_security_events`, `service_account_credentials`, `credential_rotation_history`.

### 11 - Reference Tables & Supporting Entities

**11 tables:** `project_cancellation_reasons`, `task_cancellation_reasons`, `membership_end_reasons`, `assignment_end_reasons`, `notification_delivery_statuses`, `outbox_processing_statuses`, `configuration_application_statuses`, `legal_hold_statuses`, `retention_execution_statuses`, `security_alert_statuses`, `project_team_participation_end_reasons`.

## 3. Key logical rules

### 3.1 Identifiers and ownership

- Primary entity identifiers use UUID, with UUIDv7 preferred for newly generated identifiers.
- Organization-owned records must be scoped to exactly one organization, directly or through a required parent.
- User/team/project/task relationships cannot cross organization boundaries. A task inherits organization ownership from its project.
- Historical records remain intact after an entity is deactivated, archived, or reassigned.
- Validate organization eligibility transactionally in backend workflows; use relational constraints wherever feasible.

### 3.2 Current state and history

- Persist current lifecycle status explicitly and retain separate status/assignment histories.
- Keep `project_personnel_assignments` (individual personnel assignment) separate from `project_team_participations` (team participation periods).
- Number historical membership, participation, and assignment periods within their respective parent relationships.
- Retain end timestamps and reasons for completed periods; do not overwrite past periods to simulate current state.

### 3.3 Uniqueness and temporal constraints

- `UNIQUE(user_credentials.user_id)`.
- `UNIQUE(user_refresh_tokens.replaced_by_token_id)` for non-null values.
- One open `team_memberships` row per `(team_id, user_id)` where `left_at IS NULL`.
- One open `project_team_participations` row per `(project_id, team_id)` where its end timestamp (`left_at`) is null.
- One open `task_assignments` row per `task_id` where `unassigned_at IS NULL`.
- Historical period numbers must be unique within their relevant parent pair or parent entity.
- These are logical constraints. Partial unique indexes are an implementation candidate for the physical design phase.

### 3.4 FK nullability and lifecycle validation

- Required: `users.organization_id`, `teams.organization_id`, `departments.organization_id`, `projects.organization_id`, `tasks.project_id`, `team_memberships.team_id`, `team_memberships.user_id`, `project_personnel_assignments.project_id`, `project_personnel_assignments.user_id`, `task_assignments.task_id`, `task_assignments.user_id`, `time_entries.user_id`, `time_entries.project_id`, `time_entries.time_entry_type_id`, `timesheets.user_id`, `timesheets.status_id`, and credential/token owner FKs.
- Optional: `users.department_id`, `users.supervisor_user_id`, `project_personnel_assignments.team_id`, `project_personnel_assignments.released_by_user_id`, `projects.project_manager_user_id` during setup, `time_entries.task_id`, `timesheets.submitted_by_user_id` before submission, `authentication_events.user_id`, `security_alerts.security_incident_id`, and `user_refresh_tokens.replaced_by_token_id`.
- End/cancellation reason codes and ended/removed-by actor FKs may be null while active, but applicable completed/cancelled workflows must require them.
- `organizations.created_by_user_id` may be null for system bootstrap. Service-account owner requirements depend on account type; backup owner remains optional.
- `legal_hold_records.parent_hold_record_id` is nullable for root records; a child must share its parent’s legal hold.
- The ERD uses FK labels and relationship cardinalities but does **not** explicitly specify `NOT NULL` for every column. Any column not explicitly classified here needs a later schema-level nullability decision; do not infer it solely from the diagram.

### 3.5 Reference catalogs

- Reference catalog codes are unique `VARCHAR(50)` values and may be referenced directly by compatible `VARCHAR(50)` FK columns.
- `project_team_participations.removal_reason_code` references `project_team_participation_end_reasons.code` and is nullable until participation ends.
- Keep project-team participation end reasons separate from user-team membership end reasons.
- The 11 Section 11 code catalogs cover project/task cancellation, team membership end, task assignment end, notification delivery, outbox processing, configuration application, legal hold status, retention execution, security alert status, and project-team participation end.

### 3.6 Legal holds, retention, approvals and audit

- Active legal holds prevent disposition of all in-scope records, including covered dependent records.
- Release or scope reduction requires two distinct authorized approvers; requester cannot approve their own request.
- Delegated approval authority must be valid at decision time; denial cannot reduce protection.
- Retain auditable approval decisions and legal-hold change history.
- Configuration changes, including rollback, require approval; high-risk changes require two distinct authorized approvers.
- Audit events are append-only with a configurable baseline of seven years; legal holds override disposition schedules.
- Account security events use a baseline retention of 365 days, subject to policy and holds.

### 3.7 Authentication and notifications

- Preserve `user_refresh_tokens` for the planned short-lived access token / refresh-token architecture.
- Use secure hashed token storage and record authentication, lockout, session, and security events.
- Persist notifications and outbox events; email delivery is limited to three attempts, with progressive warnings/escalation.

## 4. ERD table and column inventory

The following inventory is transcribed programmatically from the supplied draw.io table pages. **PK/FK/UQ** labels are reproduced as shown; their presence does not assert column nullability.

### 1 - Organization & Users

#### `organizations`

| Column and logical type | ERD marker |
|---|---|
| `organization_id: UUID` | PK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `name: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `departments`

| Column and logical type | ERD marker |
|---|---|
| `department_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `name: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `users`

| Column and logical type | ERD marker |
|---|---|
| `user_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `department_id: UUID` | FK |
| `supervisor_user_id: UUID` | FK |
| `account_type_id: UUID` | FK |
| `owner_user_id: UUID` | FK |
| `backup_owner_user_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `reviewed_by_user_id: UUID` | FK |
| `employee_number: VARCHAR(50)` | — |
| `first_name: VARCHAR(100)` | — |
| `last_name: VARCHAR(100)` | — |
| `email: VARCHAR(320)` | — |
| `pending_email: VARCHAR(320)` | — |
| `is_active: BOOLEAN` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `last_used_at: TIMESTAMPTZ` | — |
| `last_reviewed_at: TIMESTAMPTZ` | — |
| `next_review_due_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `teams`

| Column and logical type | ERD marker |
|---|---|
| `team_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `name: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `team_memberships`

| Column and logical type | ERD marker |
|---|---|
| `team_membership_id: UUID` | PK |
| `team_id: UUID` | FK |
| `user_id: UUID` | FK |
| `removed_by_user_id: UUID` | FK |
| `membership_period_number: INTEGER` | — |
| `joined_at: TIMESTAMPTZ` | — |
| `left_at: TIMESTAMPTZ` | — |
| `end_type_code: VARCHAR(50)` | — |
| `removal_reason_code: VARCHAR(50)` | FK |
| `removal_comment: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `project_personnel_assignments`

| Column and logical type | ERD marker |
|---|---|
| `project_personnel_assignment_id: UUID` | PK |
| `user_id: UUID` | FK |
| `project_id: UUID` | FK |
| `team_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `released_by_user_id: UUID` | FK |
| `assignment_status_code: VARCHAR(50)` | — |
| `start_at: TIMESTAMPTZ` | — |
| `end_at: TIMESTAMPTZ` | — |
| `release_reason_code: VARCHAR(50)` | — |
| `release_comment: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `supervisor_assignment_history`

| Column and logical type | ERD marker |
|---|---|
| `supervisor_assignment_history_id: UUID` | PK |
| `user_id: UUID` | FK |
| `supervisor_user_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `assignment_period_number: INTEGER` | — |
| `effective_from: TIMESTAMPTZ` | — |
| `effective_to: TIMESTAMPTZ` | — |
| `change_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `user_email_history`

| Column and logical type | ERD marker |
|---|---|
| `user_email_history_id: UUID` | PK |
| `user_id: UUID` | FK |
| `email: VARCHAR(320)` | — |
| `verified_at: TIMESTAMPTZ` | — |
| `effective_from: TIMESTAMPTZ` | — |
| `effective_to: TIMESTAMPTZ` | — |
| `change_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

### 2 - Projects & Tasks

#### `projects`

| Column and logical type | ERD marker |
|---|---|
| `project_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `project_manager_user_id: UUID` | FK |
| `setup_status_code: VARCHAR(50)` | FK |
| `lifecycle_status_code: VARCHAR(50)` | FK |
| `resume_status_code: VARCHAR(50)` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `cancelled_by_user_id: UUID` | FK |
| `name: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `start_date: DATE` | — |
| `end_date: DATE` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `cancelled_at: TIMESTAMPTZ` | — |
| `cancellation_reason_code: VARCHAR(50)` | FK |
| `cancellation_comment: TEXT` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `project_team_participations`

| Column and logical type | ERD marker |
|---|---|
| `project_team_participation_id: UUID` | PK |
| `project_id: UUID` | FK |
| `team_id: UUID` | FK |
| `removed_by_user_id: UUID` | FK |
| `participation_period_number: INTEGER` | — |
| `joined_at: TIMESTAMPTZ` | — |
| `left_at: TIMESTAMPTZ` | — |
| `end_type_code: VARCHAR(50)` | — |
| `removal_reason_code: VARCHAR(50)` | FK |
| `removal_comment: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `project_manager_assignment_history`

| Column and logical type | ERD marker |
|---|---|
| `project_manager_assignment_history_id: UUID` | PK |
| `project_id: UUID` | FK |
| `project_manager_user_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `assignment_period_number: INTEGER` | — |
| `effective_from: TIMESTAMPTZ` | — |
| `effective_to: TIMESTAMPTZ` | — |
| `change_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `tasks`

| Column and logical type | ERD marker |
|---|---|
| `task_id: UUID` | PK |
| `project_id: UUID` | FK |
| `lifecycle_status_code: VARCHAR(50)` | FK |
| `resume_status_code: VARCHAR(50)` | FK |
| `priority_code: VARCHAR(50)` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `cancelled_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `title: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `start_date: DATE` | — |
| `due_date: DATE` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `cancelled_at: TIMESTAMPTZ` | — |
| `cancellation_reason_code: VARCHAR(50)` | FK |
| `cancellation_comment: TEXT` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `task_assignments`

| Column and logical type | ERD marker |
|---|---|
| `task_assignment_id: UUID` | PK |
| `task_id: UUID` | FK |
| `user_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `ended_by_user_id: UUID` | FK |
| `assignment_period_number: INTEGER` | — |
| `assigned_at: TIMESTAMPTZ` | — |
| `unassigned_at: TIMESTAMPTZ` | — |
| `end_type_code: VARCHAR(50)` | — |
| `end_reason_code: VARCHAR(50)` | FK |
| `end_comment: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `task_dependencies`

| Column and logical type | ERD marker |
|---|---|
| `task_dependency_id: UUID` | PK |
| `task_id: UUID` | FK |
| `depends_on_task_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `created_at: TIMESTAMPTZ` | — |

#### `project_setup_statuses`

| Column and logical type | ERD marker |
|---|---|
| `project_setup_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `project_status_history`

| Column and logical type | ERD marker |
|---|---|
| `project_status_history_id: UUID` | PK |
| `project_id: UUID` | FK |
| `previous_status_code: VARCHAR(50)` | FK |
| `new_status_code: VARCHAR(50)` | FK |
| `changed_by_user_id: UUID` | FK |
| `changed_at: TIMESTAMPTZ` | — |
| `reason_code: VARCHAR(50)` | — |
| `comment: TEXT` | — |

#### `task_status_history`

| Column and logical type | ERD marker |
|---|---|
| `task_status_history_id: UUID` | PK |
| `task_id: UUID` | FK |
| `previous_status_code: VARCHAR(50)` | FK |
| `new_status_code: VARCHAR(50)` | FK |
| `changed_by_user_id: UUID` | FK |
| `changed_at: TIMESTAMPTZ` | — |
| `reason_code: VARCHAR(50)` | — |
| `comment: TEXT` | — |

#### `project_lifecycle_statuses`

| Column and logical type | ERD marker |
|---|---|
| `project_lifecycle_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `task_lifecycle_statuses`

| Column and logical type | ERD marker |
|---|---|
| `task_lifecycle_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `task_priorities`

| Column and logical type | ERD marker |
|---|---|
| `task_priority_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

### 3 - Roles & Permissions

#### `roles`

| Column and logical type | ERD marker |
|---|---|
| `role_id: UUID` | PK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_system_defined: BOOLEAN` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `permissions`

| Column and logical type | ERD marker |
|---|---|
| `permission_id: UUID` | PK |
| `code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `resource: VARCHAR(100)` | — |
| `action: VARCHAR(50)` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `role_permissions`

| Column and logical type | ERD marker |
|---|---|
| `role_permission_id: UUID` | PK |
| `role_id: UUID` | FK |
| `permission_id: UUID` | FK |
| `granted_by_user_id: UUID` | FK |
| `created_at: TIMESTAMPTZ` | — |

#### `user_role_assignments`

| Column and logical type | ERD marker |
|---|---|
| `user_role_assignment_id: UUID` | PK |
| `user_id: UUID` | FK |
| `role_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `assigned_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revoked_by_user_id: UUID` | FK |
| `revocation_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `user_role_assignment_history`

| Column and logical type | ERD marker |
|---|---|
| `user_role_assignment_history_id: UUID` | PK |
| `user_role_assignment_id: UUID` | FK |
| `changed_by_user_id: UUID` | FK |
| `action_code: VARCHAR(50)` | — |
| `changed_at: TIMESTAMPTZ` | — |
| `reason: TEXT` | — |

#### `project_role_assignments`

| Column and logical type | ERD marker |
|---|---|
| `project_role_assignment_id: UUID` | PK |
| `project_id: UUID` | FK |
| `user_id: UUID` | FK |
| `role_id: UUID` | FK |
| `assigned_by_user_id: UUID` | FK |
| `revoked_by_user_id: UUID` | FK |
| `assigned_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `account_types`

| Column and logical type | ERD marker |
|---|---|
| `account_type_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `oauth_scopes`

| Column and logical type | ERD marker |
|---|---|
| `oauth_scope_id: UUID` | PK |
| `code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `service_account_scopes`

| Column and logical type | ERD marker |
|---|---|
| `service_account_scope_id: UUID` | PK |
| `service_account_user_id: UUID` | FK |
| `oauth_scope_id: UUID` | FK |
| `granted_by_user_id: UUID` | FK |
| `revoked_by_user_id: UUID` | FK |
| `granted_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

### 4 - Collaboration

#### `project_comments`

| Column and logical type | ERD marker |
|---|---|
| `project_comment_id: UUID` | PK |
| `project_id: UUID` | FK |
| `author_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `deleted_by_user_id: UUID` | FK |
| `content: TEXT` | — |
| `is_edited: BOOLEAN` | — |
| `is_deleted: BOOLEAN` | — |
| `deleted_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `task_comments`

| Column and logical type | ERD marker |
|---|---|
| `task_comment_id: UUID` | PK |
| `task_id: UUID` | FK |
| `author_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `deleted_by_user_id: UUID` | FK |
| `content: TEXT` | — |
| `is_edited: BOOLEAN` | — |
| `is_deleted: BOOLEAN` | — |
| `deleted_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `project_attachments`

| Column and logical type | ERD marker |
|---|---|
| `project_attachment_id: UUID` | PK |
| `project_id: UUID` | FK |
| `attachment_type_id: UUID` | FK |
| `uploaded_by_user_id: UUID` | FK |
| `deleted_by_user_id: UUID` | FK |
| `original_filename: VARCHAR(255)` | — |
| `storage_key: VARCHAR(500)` | — |
| `mime_type: VARCHAR(255)` | — |
| `file_size_bytes: BIGINT` | — |
| `checksum_sha256: VARCHAR(64)` | — |
| `description: TEXT` | — |
| `is_deleted: BOOLEAN` | — |
| `deleted_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `task_attachments`

| Column and logical type | ERD marker |
|---|---|
| `task_attachment_id: UUID` | PK |
| `task_id: UUID` | FK |
| `attachment_type_id: UUID` | FK |
| `uploaded_by_user_id: UUID` | FK |
| `deleted_by_user_id: UUID` | FK |
| `original_filename: VARCHAR(255)` | — |
| `storage_key: VARCHAR(500)` | — |
| `mime_type: VARCHAR(255)` | — |
| `file_size_bytes: BIGINT` | — |
| `checksum_sha256: VARCHAR(64)` | — |
| `description: TEXT` | — |
| `is_deleted: BOOLEAN` | — |
| `deleted_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `labels`

| Column and logical type | ERD marker |
|---|---|
| `label_id: UUID` | PK |
| `project_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `archived_by_user_id: UUID` | FK |
| `name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `color_hex: VARCHAR(7)` | — |
| `is_archived: BOOLEAN` | — |
| `archived_at: TIMESTAMPTZ` | — |
| `archive_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `task_labels`

| Column and logical type | ERD marker |
|---|---|
| `task_label_id: UUID` | PK |
| `task_id: UUID` | FK |
| `label_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `created_at: TIMESTAMPTZ` | — |

#### `attachment_types`

| Column and logical type | ERD marker |
|---|---|
| `attachment_type_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

### 5 - Time & Reporting

#### `time_entries`

| Column and logical type | ERD marker |
|---|---|
| `time_entry_id: UUID` | PK |
| `user_id: UUID` | FK |
| `project_id: UUID` | FK |
| `task_id: UUID` | FK |
| `time_entry_type_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `work_date: DATE` | — |
| `duration_minutes: INTEGER` | — |
| `description: TEXT` | — |
| `is_billable: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `timesheets`

| Column and logical type | ERD marker |
|---|---|
| `timesheet_id: UUID` | PK |
| `user_id: UUID` | FK |
| `status_id: UUID` | FK |
| `submitted_by_user_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `period_start_date: DATE` | — |
| `period_end_date: DATE` | — |
| `total_minutes: INTEGER` | — |
| `submitted_at: TIMESTAMPTZ` | — |
| `approved_at: TIMESTAMPTZ` | — |
| `rejected_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `timesheet_entries`

| Column and logical type | ERD marker |
|---|---|
| `timesheet_entry_id: UUID` | PK |
| `timesheet_id: UUID` | FK |
| `time_entry_id: UUID` | FK |
| `created_at: TIMESTAMPTZ` | — |

#### `timesheet_approval_history`

| Column and logical type | ERD marker |
|---|---|
| `timesheet_approval_history_id: UUID` | PK |
| `timesheet_id: UUID` | FK |
| `reviewed_by_user_id: UUID` | FK |
| `previous_status_id: UUID` | FK |
| `new_status_id: UUID` | FK |
| `action_code: VARCHAR(50)` | — |
| `reviewed_at: TIMESTAMPTZ` | — |
| `reason: TEXT` | — |
| `comment: TEXT` | — |

#### `timesheet_statuses`

| Column and logical type | ERD marker |
|---|---|
| `timesheet_status_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `time_entry_types`

| Column and logical type | ERD marker |
|---|---|
| `time_entry_type_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `report_definitions`

| Column and logical type | ERD marker |
|---|---|
| `report_definition_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `owner_user_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `name: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `report_type_code: VARCHAR(50)` | — |
| `filter_configuration: JSONB` | — |
| `column_configuration: JSONB` | — |
| `is_shared: BOOLEAN` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `report_execution_history`

| Column and logical type | ERD marker |
|---|---|
| `report_execution_history_id: UUID` | PK |
| `report_definition_id: UUID` | FK |
| `executed_by_user_id: UUID` | FK |
| `started_at: TIMESTAMPTZ` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `execution_status_code: VARCHAR(50)` | — |
| `parameters_snapshot: JSONB` | — |
| `row_count: BIGINT` | — |
| `error_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `report_schedules`

| Column and logical type | ERD marker |
|---|---|
| `report_schedule_id: UUID` | PK |
| `report_definition_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `schedule_expression: VARCHAR(200)` | — |
| `timezone_id: VARCHAR(100)` | — |
| `next_run_at: TIMESTAMPTZ` | — |
| `last_run_at: TIMESTAMPTZ` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

### 6 - Notifications

#### `notifications`

| Column and logical type | ERD marker |
|---|---|
| `notification_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `notification_type_id: UUID` | FK |
| `severity_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `title: VARCHAR(200)` | — |
| `message: TEXT` | — |
| `entity_type: VARCHAR(100)` | — |
| `entity_id: UUID` | — |
| `created_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |

#### `notification_recipients`

| Column and logical type | ERD marker |
|---|---|
| `notification_recipient_id: UUID` | PK |
| `notification_id: UUID` | FK |
| `user_id: UUID` | FK |
| `is_read: BOOLEAN` | — |
| `read_at: TIMESTAMPTZ` | — |
| `is_acknowledged: BOOLEAN` | — |
| `acknowledged_at: TIMESTAMPTZ` | — |
| `in_app_delivered_at: TIMESTAMPTZ` | — |
| `email_delivery_status_code: VARCHAR(50)` | — |
| `email_attempt_count: INTEGER` | — |
| `email_delivered_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `action_required_alerts`

| Column and logical type | ERD marker |
|---|---|
| `action_required_alert_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `notification_id: UUID` | FK |
| `assigned_to_user_id: UUID` | FK |
| `resolved_by_user_id: UUID` | FK |
| `alert_code: VARCHAR(100)` | — |
| `severity_code: VARCHAR(50)` | — |
| `entity_type: VARCHAR(100)` | — |
| `entity_id: UUID` | — |
| `status_code: VARCHAR(50)` | — |
| `is_persistent: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `resolved_at: TIMESTAMPTZ` | — |
| `resolution_comment: TEXT` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `notification_types`

| Column and logical type | ERD marker |
|---|---|
| `notification_type_id: UUID` | PK |
| `code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `notification_severities`

| Column and logical type | ERD marker |
|---|---|
| `notification_severity_id: UUID` | PK |
| `code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `notification_delivery_attempts`

| Column and logical type | ERD marker |
|---|---|
| `notification_delivery_attempt_id: UUID` | PK |
| `notification_recipient_id: UUID` | FK |
| `delivery_channel_code: VARCHAR(50)` | — |
| `attempt_number: INTEGER` | — |
| `attempted_at: TIMESTAMPTZ` | — |
| `delivery_status_code: VARCHAR(50)` | FK |
| `provider_message_id: VARCHAR(255)` | — |
| `failure_code: VARCHAR(100)` | — |
| `failure_summary: TEXT` | — |
| `next_retry_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `notification_escalations`

| Column and logical type | ERD marker |
|---|---|
| `notification_escalation_id: UUID` | PK |
| `notification_recipient_id: UUID` | FK |
| `escalated_to_user_id: UUID` | FK |
| `escalation_level: INTEGER` | — |
| `escalation_reason_code: VARCHAR(100)` | — |
| `escalated_at: TIMESTAMPTZ` | — |
| `is_resolved: BOOLEAN` | — |
| `resolved_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `outbox_events`

| Column and logical type | ERD marker |
|---|---|
| `outbox_event_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `event_type: VARCHAR(150)` | — |
| `aggregate_type: VARCHAR(100)` | — |
| `aggregate_id: UUID` | — |
| `payload: JSONB` | — |
| `correlation_id: UUID` | — |
| `processing_status_code: VARCHAR(50)` | FK |
| `attempt_count: INTEGER` | — |
| `next_attempt_at: TIMESTAMPTZ` | — |
| `processed_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `outbox_processing_attempts`

| Column and logical type | ERD marker |
|---|---|
| `outbox_processing_attempt_id: UUID` | PK |
| `outbox_event_id: UUID` | FK |
| `attempt_number: INTEGER` | — |
| `started_at: TIMESTAMPTZ` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `processing_status_code: VARCHAR(50)` | — |
| `error_code: VARCHAR(100)` | — |
| `error_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

### 7 - Configuration

#### `system_settings`

| Column and logical type | ERD marker |
|---|---|
| `system_setting_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `configuration_tab_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `setting_key: VARCHAR(150)` | — |
| `setting_value: JSONB` | — |
| `data_type_code: VARCHAR(50)` | — |
| `description: TEXT` | — |
| `is_editable: BOOLEAN` | — |
| `requires_restart: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `notification_settings`

| Column and logical type | ERD marker |
|---|---|
| `notification_setting_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `notification_type_code: VARCHAR(100)` | — |
| `in_app_enabled: BOOLEAN` | — |
| `email_enabled: BOOLEAN` | — |
| `supervisor_escalation_enabled: BOOLEAN` | — |
| `reminder_interval_hours: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `integration_settings`

| Column and logical type | ERD marker |
|---|---|
| `integration_setting_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `integration_code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `configuration: JSONB` | — |
| `secret_reference: VARCHAR(500)` | — |
| `is_enabled: BOOLEAN` | — |
| `last_tested_at: TIMESTAMPTZ` | — |
| `last_test_status_code: VARCHAR(50)` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `configuration_change_history`

| Column and logical type | ERD marker |
|---|---|
| `configuration_change_history_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `change_request_id: UUID` | FK |
| `changed_by_user_id: UUID` | FK |
| `configuration_entity_type: VARCHAR(100)` | — |
| `configuration_entity_id: UUID` | — |
| `setting_key: VARCHAR(150)` | — |
| `previous_value: JSONB` | — |
| `new_value: JSONB` | — |
| `change_reason: TEXT` | — |
| `changed_at: TIMESTAMPTZ` | — |
| `is_rollback: BOOLEAN` | — |
| `rolled_back_from_history_id: UUID` | FK |

#### `configuration_change_requests`

| Column and logical type | ERD marker |
|---|---|
| `configuration_change_request_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `requested_by_user_id: UUID` | FK |
| `risk_level_id: UUID` | FK |
| `status_id: UUID` | FK |
| `applied_by_user_id: UUID` | FK |
| `cancelled_by_user_id: UUID` | FK |
| `configuration_entity_type: VARCHAR(100)` | — |
| `configuration_entity_id: UUID` | — |
| `setting_key: VARCHAR(150)` | — |
| `proposed_value: JSONB` | — |
| `justification: TEXT` | — |
| `requested_at: TIMESTAMPTZ` | — |
| `effective_at: TIMESTAMPTZ` | — |
| `applied_at: TIMESTAMPTZ` | — |
| `cancelled_at: TIMESTAMPTZ` | — |
| `application_status_code: VARCHAR(50)` | FK |
| `failure_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `configuration_change_decisions`

| Column and logical type | ERD marker |
|---|---|
| `configuration_change_decision_id: UUID` | PK |
| `change_request_id: UUID` | FK |
| `decided_by_user_id: UUID` | FK |
| `decision_code: VARCHAR(50)` | — |
| `decision_reason: TEXT` | — |
| `decided_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `approval_delegations`

| Column and logical type | ERD marker |
|---|---|
| `approval_delegation_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `delegator_user_id: UUID` | FK |
| `delegate_user_id: UUID` | FK |
| `authorized_by_user_id: UUID` | FK |
| `revoked_by_user_id: UUID` | FK |
| `approval_scope_code: VARCHAR(100)` | — |
| `effective_from: TIMESTAMPTZ` | — |
| `effective_to: TIMESTAMPTZ` | — |
| `delegation_reason: TEXT` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `configuration_risk_levels`

| Column and logical type | ERD marker |
|---|---|
| `configuration_risk_level_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `requires_independent_approval: BOOLEAN` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `configuration_change_statuses`

| Column and logical type | ERD marker |
|---|---|
| `configuration_change_status_id: UUID` | PK |
| `code: VARCHAR(50)` | — |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `configuration_tabs`

| Column and logical type | ERD marker |
|---|---|
| `configuration_tab_id: UUID` | PK |
| `code: VARCHAR(100)` | — |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `sort_order: INTEGER` | — |
| `is_active: BOOLEAN` | — |

### 8 - Audit & Compliance

#### `legal_holds`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `created_by_user_id: UUID` | FK |
| `responsible_user_id: UUID` | FK |
| `released_by_user_id: UUID` | FK |
| `hold_number: VARCHAR(50)` | — |
| `title: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `legal_basis: TEXT` | — |
| `status_code: VARCHAR(30)` | FK |
| `effective_at: TIMESTAMPTZ` | — |
| `released_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `legal_hold_records`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_record_id: UUID` | PK |
| `legal_hold_id: UUID` | FK |
| `parent_hold_record_id: UUID` | FK |
| `added_by_user_id: UUID` | FK |
| `removed_by_user_id: UUID` | FK |
| `record_entity_type: VARCHAR(100)` | — |
| `record_entity_id: UUID` | — |
| `protection_source_code: VARCHAR(30)` | — |
| `added_at: TIMESTAMPTZ` | — |
| `removed_at: TIMESTAMPTZ` | — |
| `removal_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `legal_hold_release_requests`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_release_request_id: UUID` | PK |
| `legal_hold_id: UUID` | FK |
| `requested_by_user_id: UUID` | FK |
| `status_code: VARCHAR(30)` | — |
| `justification: TEXT` | — |
| `requested_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `cancelled_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `legal_hold_release_decisions`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_release_decision_id: UUID` | PK |
| `legal_hold_release_request_id: UUID` | FK |
| `decided_by_user_id: UUID` | FK |
| `decision_code: VARCHAR(30)` | — |
| `decision_reason: TEXT` | — |
| `decided_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `legal_hold_modification_requests`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_modification_request_id: UUID` | PK |
| `legal_hold_id: UUID` | FK |
| `requested_by_user_id: UUID` | FK |
| `modification_type_code: VARCHAR(30)` | — |
| `proposed_changes: JSONB` | — |
| `justification: TEXT` | — |
| `status_code: VARCHAR(30)` | — |
| `requested_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `cancelled_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `retention_policies`

| Column and logical type | ERD marker |
|---|---|
| `retention_policy_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `updated_by_user_id: UUID` | FK |
| `policy_code: VARCHAR(100)` | — |
| `policy_name: VARCHAR(200)` | — |
| `record_category_code: VARCHAR(100)` | — |
| `retention_period_days: INTEGER` | — |
| `retention_start_event_code: VARCHAR(50)` | — |
| `disposition_action_code: VARCHAR(30)` | — |
| `requires_disposition_approval: BOOLEAN` | — |
| `is_enabled: BOOLEAN` | — |
| `description: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `retention_execution_history`

| Column and logical type | ERD marker |
|---|---|
| `retention_execution_history_id: UUID` | PK |
| `retention_policy_id: UUID` | FK |
| `executed_by_user_id: UUID` | FK |
| `execution_type_code: VARCHAR(30)` | — |
| `execution_status_code: VARCHAR(30)` | FK |
| `records_evaluated: INTEGER` | — |
| `records_eligible: INTEGER` | — |
| `records_processed: INTEGER` | — |
| `records_skipped_legal_hold: INTEGER` | — |
| `records_failed: INTEGER` | — |
| `started_at: TIMESTAMPTZ` | — |
| `completed_at: TIMESTAMPTZ` | — |
| `failure_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `retention_disposition_requests`

| Column and logical type | ERD marker |
|---|---|
| `retention_disposition_request_id: UUID` | PK |
| `retention_policy_id: UUID` | FK |
| `requested_by_user_id: UUID` | FK |
| `status_code: VARCHAR(30)` | — |
| `record_selection_criteria: JSONB` | — |
| `estimated_record_count: INTEGER` | — |
| `disposition_action_code: VARCHAR(30)` | — |
| `justification: TEXT` | — |
| `requested_at: TIMESTAMPTZ` | — |
| `approved_at: TIMESTAMPTZ` | — |
| `executed_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `legal_hold_modification_decisions`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_modification_decision_id: UUID` | PK |
| `legal_hold_modification_request_id: UUID` | FK |
| `decided_by_user_id: UUID` | FK |
| `decision_code: VARCHAR(30)` | — |
| `decision_reason: TEXT` | — |
| `decided_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `retention_disposition_decisions`

| Column and logical type | ERD marker |
|---|---|
| `retention_disposition_decision_id: UUID` | PK |
| `retention_disposition_request_id: UUID` | FK |
| `decided_by_user_id: UUID` | FK |
| `decision_code: VARCHAR(30)` | — |
| `decision_reason: TEXT` | — |
| `decided_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

### 9 - Audit Logging & Security Monitoring

#### `audit_events`

| Column and logical type | ERD marker |
|---|---|
| `audit_event_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `actor_user_id: UUID` | FK |
| `audit_event_type_id: UUID` | FK |
| `entity_type: VARCHAR(100)` | — |
| `entity_id: UUID` | — |
| `action_code: VARCHAR(100)` | — |
| `previous_values: JSONB` | — |
| `new_values: JSONB` | — |
| `change_reason: TEXT` | — |
| `correlation_id: UUID` | — |
| `request_id: UUID` | — |
| `ip_address: INET` | — |
| `user_agent: TEXT` | — |
| `occurred_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `authentication_events`

| Column and logical type | ERD marker |
|---|---|
| `authentication_event_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `user_id: UUID` | FK |
| `event_type_code: VARCHAR(100)` | — |
| `authentication_method_code: VARCHAR(50)` | — |
| `result_code: VARCHAR(50)` | — |
| `failure_reason_code: VARCHAR(100)` | — |
| `attempted_identifier: VARCHAR(320)` | — |
| `ip_address: INET` | — |
| `user_agent: TEXT` | — |
| `correlation_id: UUID` | — |
| `occurred_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `security_incidents`

| Column and logical type | ERD marker |
|---|---|
| `security_incident_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `status_id: UUID` | FK |
| `reported_by_user_id: UUID` | FK |
| `assigned_to_user_id: UUID` | FK |
| `resolved_by_user_id: UUID` | FK |
| `closed_by_user_id: UUID` | FK |
| `incident_number: VARCHAR(50)` | — |
| `title: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `severity_code: VARCHAR(30)` | — |
| `incident_category_code: VARCHAR(50)` | — |
| `detected_at: TIMESTAMPTZ` | — |
| `reported_at: TIMESTAMPTZ` | — |
| `resolved_at: TIMESTAMPTZ` | — |
| `closed_at: TIMESTAMPTZ` | — |
| `resolution_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `security_incident_events`

| Column and logical type | ERD marker |
|---|---|
| `security_incident_event_id: UUID` | PK |
| `security_incident_id: UUID` | FK |
| `performed_by_user_id: UUID` | FK |
| `previous_status_id: UUID` | FK |
| `new_status_id: UUID` | FK |
| `event_type_code: VARCHAR(50)` | — |
| `event_description: TEXT` | — |
| `event_details: JSONB` | — |
| `occurred_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `security_alerts`

| Column and logical type | ERD marker |
|---|---|
| `security_alert_id: UUID` | PK |
| `organization_id: UUID` | FK |
| `security_incident_id: UUID` | FK |
| `assigned_to_user_id: UUID` | FK |
| `acknowledged_by_user_id: UUID` | FK |
| `resolved_by_user_id: UUID` | FK |
| `alert_type_code: VARCHAR(100)` | — |
| `severity_code: VARCHAR(30)` | — |
| `status_code: VARCHAR(30)` | FK |
| `title: VARCHAR(200)` | — |
| `description: TEXT` | — |
| `source_entity_type: VARCHAR(100)` | — |
| `source_entity_id: UUID` | — |
| `detected_at: TIMESTAMPTZ` | — |
| `acknowledged_at: TIMESTAMPTZ` | — |
| `resolved_at: TIMESTAMPTZ` | — |
| `resolution_summary: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `audit_event_types`

| Column and logical type | ERD marker |
|---|---|
| `audit_event_type_id: UUID` | PK |
| `code: VARCHAR(100)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

#### `security_incident_statuses`

| Column and logical type | ERD marker |
|---|---|
| `security_incident_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(100)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |

### 10 - Authentication, Sessions & Account Security

#### `user_credentials`

| Column and logical type | ERD marker |
|---|---|
| `user_credential_id: UUID` | PK |
| `user_id: UUID` | FK |
| `password_hash: VARCHAR(500)` | — |
| `password_algorithm_code: VARCHAR(50)` | — |
| `password_changed_at: TIMESTAMPTZ` | — |
| `must_change_password: BOOLEAN` | — |
| `failed_attempt_count: INTEGER` | — |
| `last_failed_attempt_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `user_sessions`

| Column and logical type | ERD marker |
|---|---|
| `user_session_id: UUID` | PK |
| `user_id: UUID` | FK |
| `session_identifier_hash: VARCHAR(500)` | — |
| `ip_address: INET` | — |
| `user_agent: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |
| `last_activity_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason_code: VARCHAR(50)` | — |
| `is_active: BOOLEAN` | — |
| `version: BIGINT` | — |

#### `user_refresh_tokens`

| Column and logical type | ERD marker |
|---|---|
| `user_refresh_token_id: UUID` | PK |
| `user_id: UUID` | FK |
| `user_session_id: UUID` | FK |
| `replaced_by_token_id: UUID` | FK |
| `token_hash: VARCHAR(500)` | — |
| `issued_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `used_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason_code: VARCHAR(50)` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `email_verification_tokens`

| Column and logical type | ERD marker |
|---|---|
| `email_verification_token_id: UUID` | PK |
| `user_id: UUID` | FK |
| `email_address: VARCHAR(320)` | — |
| `token_hash: VARCHAR(500)` | — |
| `purpose_code: VARCHAR(50)` | — |
| `issued_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `used_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `password_reset_tokens`

| Column and logical type | ERD marker |
|---|---|
| `password_reset_token_id: UUID` | PK |
| `user_id: UUID` | FK |
| `token_hash: VARCHAR(500)` | — |
| `issued_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `used_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `account_lockouts`

| Column and logical type | ERD marker |
|---|---|
| `account_lockout_id: UUID` | PK |
| `user_id: UUID` | FK |
| `unlocked_by_user_id: UUID` | FK |
| `lockout_reason_code: VARCHAR(50)` | — |
| `locked_at: TIMESTAMPTZ` | — |
| `scheduled_unlock_at: TIMESTAMPTZ` | — |
| `unlocked_at: TIMESTAMPTZ` | — |
| `unlock_reason: TEXT` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `account_security_events`

| Column and logical type | ERD marker |
|---|---|
| `account_security_event_id: UUID` | PK |
| `user_id: UUID` | FK |
| `performed_by_user_id: UUID` | FK |
| `event_type_code: VARCHAR(100)` | — |
| `event_description: TEXT` | — |
| `event_details: JSONB` | — |
| `ip_address: INET` | — |
| `correlation_id: UUID` | — |
| `occurred_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

#### `service_account_credentials`

| Column and logical type | ERD marker |
|---|---|
| `service_account_credential_id: UUID` | PK |
| `user_id: UUID` | FK |
| `credential_identifier: VARCHAR(150)` | — |
| `credential_hash: VARCHAR(500)` | — |
| `credential_type_code: VARCHAR(50)` | — |
| `issued_at: TIMESTAMPTZ` | — |
| `expires_at: TIMESTAMPTZ` | — |
| `last_used_at: TIMESTAMPTZ` | — |
| `revoked_at: TIMESTAMPTZ` | — |
| `revocation_reason_code: VARCHAR(50)` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `version: BIGINT` | — |

#### `credential_rotation_history`

| Column and logical type | ERD marker |
|---|---|
| `credential_rotation_history_id: UUID` | PK |
| `service_account_credential_id: UUID` | FK |
| `rotated_by_user_id: UUID` | FK |
| `rotation_reason_code: VARCHAR(50)` | — |
| `previous_credential_identifier: VARCHAR(150)` | — |
| `new_credential_identifier: VARCHAR(150)` | — |
| `rotated_at: TIMESTAMPTZ` | — |
| `created_at: TIMESTAMPTZ` | — |

### 11 - Reference Tables & Supporting Entities

#### `project_cancellation_reasons`

| Column and logical type | ERD marker |
|---|---|
| `project_cancellation_reason_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `task_cancellation_reasons`

| Column and logical type | ERD marker |
|---|---|
| `task_cancellation_reason_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `membership_end_reasons`

| Column and logical type | ERD marker |
|---|---|
| `membership_end_reason_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `assignment_end_reasons`

| Column and logical type | ERD marker |
|---|---|
| `assignment_end_reason_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `notification_delivery_statuses`

| Column and logical type | ERD marker |
|---|---|
| `notification_delivery_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `outbox_processing_statuses`

| Column and logical type | ERD marker |
|---|---|
| `outbox_processing_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `configuration_application_statuses`

| Column and logical type | ERD marker |
|---|---|
| `configuration_application_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `legal_hold_statuses`

| Column and logical type | ERD marker |
|---|---|
| `legal_hold_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `retention_execution_statuses`

| Column and logical type | ERD marker |
|---|---|
| `retention_execution_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `security_alert_statuses`

| Column and logical type | ERD marker |
|---|---|
| `security_alert_status_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: TEXT` | — |
| `is_active: BOOLEAN` | — |
| `sort_order: INTEGER` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |

#### `project_team_participation_end_reasons`

| Column and logical type | ERD marker |
|---|---|
| `project_team_participation_end_reason_id: UUID` | PK |
| `code: VARCHAR(50)` | UQ |
| `display_name: VARCHAR(150)` | — |
| `description: VARCHAR(500)` | — |
| `is_active: BOOLEAN` | — |
| `created_at: TIMESTAMPTZ` | — |
| `updated_at: TIMESTAMPTZ` | — |
| `sort_order: INTEGER` | — |

## 5. Relationship-diagram register

The list below captures the connector endpoints as diagrammed. It is an inventory, **not** a verified FK-column mapping; refer to the ERD and the rules above for FK semantics.

### 1 - Organization & Users - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `organizations` | `users` | `ERone` / `ERzeroToMany` |
| `organizations` | `teams` | `ERone` / `ERzeroToMany` |
| `organizations` | `departments` | `ERone` / `ERzeroToMany` |
| `departments` | `users` | `ERone` / `ERzeroToMany` |
| `users` | `email_verification_tokens` | `ERone` / `ERzeroToMany` |
| `users` | `user_email_history` | `ERone` / `ERzeroToMany` |
| `users` | `supervisor_assignment_history` | `ERone` / `ERzeroToMany` |
| `team_memberships` | `teams` | `ERzeroToMany` / `ERone` |
| `project_personnel_assignments` | `teams` | `ERzeroToMany` / `ERone` |
| `users` | `users` | `ERone` / `ERzeroToMany` |
| `team_memberships` | `users` | `ERzeroToMany` / `ERone` |
| `project_personnel_assignments` | `users` | `ERzeroToMany` / `ERone` |

### 2 - Projects & Tasks - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `organizations` | `projects` | `ERone` / `ERzeroToMany` |
| `projects` | `project_team_participations` | `ERone` / `ERzeroToMany` |
| `projects` | `project_manager_assignment_history` | `ERone` / `ERzeroToMany` |
| `projects` | `tasks` | `ERone` / `ERzeroToMany` |
| `projects` | `project_status_history` | `ERone` / `ERzeroToMany` |
| `projects` | `project_personnel_assignments` | `ERone` / `ERzeroToMany` |
| `projects` | `project_setup_statuses` | `ERzeroToMany` / `ERone` |
| `projects` | `project_lifecycle_statuses` | `ERzeroToMany` / `ERone` |
| `users` | `projects` | `ERone` / `ERzeroToMany` |
| `users` | `project_manager_assignment_history` | `ERone` / `ERzeroToMany` |
| `users` | `task_assignments` | `ERone` / `ERzeroToMany` |
| `teams` | `project_team_participations` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_assignments` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_dependencies` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_status_history` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_priorities` | `ERzeroToMany` / `ERone` |
| `tasks` | `task_lifecycle_statuses` | `ERzeroToMany` / `ERone` |
| `tasks` | `task_dependencies` | `ERone` / `ERzeroToMany` |

### 3 - Roles & Permissions - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `roles` | `role_permissions` | `ERone` / `ERzeroToMany` |
| `roles` | `user_role_assignments` | `ERone` / `ERzeroToMany` |
| `permissions` | `role_permissions` | `ERone` / `ERzeroToMany` |
| `users` | `account_types` | `ERzeroToMany` / `ERone` |
| `users` | `service_account_scopes` | `ERone` / `ERzeroToMany` |
| `users` | `user_role_assignments` | `ERone` / `ERzeroToMany` |
| `users` | `project_role_assignments` | `ERone` / `ERzeroToMany` |
| `user_role_assignments` | `user_role_assignment_history` | `ERone` / `ERzeroToMany` |
| `project_role_assignments` | `roles` | `ERzeroToMany` / `ERone` |
| `projects` | `project_role_assignments` | `ERone` / `ERzeroToMany` |
| `oauth_scopes` | `service_account_scopes` | `ERone` / `ERzeroToMany` |

### 4 - Collaboration - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `projects` | `project_comments` | `ERone` / `ERzeroToMany` |
| `projects` | `project_attachments` | `ERone` / `ERzeroToMany` |
| `projects` | `labels` | `ERone` / `ERzeroToMany` |
| `task_attachments` | `attachment_types` | `ERzeroToMany` / `ERone` |
| `users` | `project_comments` | `ERone` / `ERzeroToMany` |
| `users` | `task_comments` | `ERone` / `ERzeroToMany` |
| `users` | `project_attachments` | `ERone` / `ERzeroToMany` |
| `users` | `task_attachments` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_comments` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_attachments` | `ERone` / `ERzeroToMany` |
| `tasks` | `task_labels` | `ERone` / `ERzeroToMany` |
| `attachment_types` | `project_attachments` | `ERone` / `ERzeroToMany` |
| `labels` | `task_labels` | `ERone` / `ERzeroToMany` |

### 5 - Time & Reporting - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `timesheets` | `users` | `ERzeroToMany` / `ERone` |
| `timesheets` | `timesheet_entries` | `ERone` / `ERzeroToMany` |
| `timesheets` | `timesheet_approval_history` | `ERone` / `ERzeroToMany` |
| `timesheet_approval_history` | `timesheet_statuses` | `ERzeroToMany` / `ERone` |
| `timesheet_statuses` | `timesheets` | `ERone` / `ERzeroToMany` |
| `timesheet_statuses` | `timesheet_approval_history` | `ERone` / `ERzeroToMany` |
| `report_definitions` | `report_execution_history` | `ERone` / `ERzeroToMany` |
| `report_definitions` | `report_schedules` | `ERone` / `ERzeroToMany` |
| `users` | `timesheet_approval_history` | `ERone` / `ERzeroToMany` |
| `users` | `report_definitions` | `ERone` / `ERzeroToMany` |
| `users` | `report_execution_history` | `ERone` / `ERzeroToMany` |
| `users` | `time_entries` | `ERone` / `ERzeroToMany` |
| `projects` | `time_entries` | `ERone` / `ERzeroToMany` |
| `tasks` | `time_entries` | `ERzeroToOne` / `ERzeroToMany` |
| `time_entry_types` | `time_entries` | `ERone` / `ERzeroToMany` |
| `time_entries` | `timesheet_entries` | `ERone` / `ERzeroToOne` |
| `organizations` | `report_definitions` | `ERone` / `ERzeroToMany` |

### 6 - Notifications - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `organizations` | `notifications` | `ERone` / `ERzeroToMany` |
| `organizations` | `action_required_alerts` | `ERone` / `ERzeroToMany` |
| `organizations` | `outbox_events` | `ERone` / `ERzeroToMany` |
| `?` | `notification_recipients` | `ERone` / `ERzeroToMany` |
| `notification_types` | `notifications` | `ERone` / `ERzeroToMany` |
| `notification_severities` | `notifications` | `ERone` / `ERzeroToMany` |
| `users` | `notifications` | `ERone` / `ERzeroToMany` |
| `users` | `notification_recipients` | `ERone` / `ERzeroToMany` |
| `users` | `action_required_alerts` | `ERone` / `ERzeroToMany` |
| `users` | `notification_escalations` | `ERone` / `ERzeroToMany` |
| `notification_recipients` | `notification_delivery_attempts` | `ERone` / `ERzeroToMany` |
| `notification_recipients` | `notification_escalations` | `ERone` / `ERzeroToMany` |
| `action_required_alerts` | `users` | `ERzeroToMany` / `ERone` |
| `outbox_events` | `outbox_processing_attempts` | `ERone` / `ERzeroToMany` |
| `notifications` | `action_required_alerts` | `ERone` / `ERzeroToMany` |

### 7 - Configuration - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `organizations` | `system_settings` | `ERone` / `ERzeroToMany` |
| `organizations` | `notification_settings` | `ERone` / `ERzeroToMany` |
| `organizations` | `integration_settings` | `ERone` / `ERzeroToMany` |
| `organizations` | `configuration_change_history` | `ERone` / `ERzeroToMany` |
| `organizations` | `configuration_change_requests` | `ERone` / `ERzeroToMany` |
| `organizations` | `approval_delegations` | `ERone` / `ERzeroToMany` |
| `configuration_tabs` | `system_settings` | `ERone` / `ERzeroToMany` |
| `users` | `system_settings` | `ERone` / `ERzeroToMany` |
| `users` | `notification_settings` | `ERone` / `ERzeroToMany` |
| `users` | `integration_settings` | `ERone` / `ERzeroToMany` |
| `?` | `configuration_change_history` | `ERone` / `ERzeroToMany` |
| `users` | `configuration_change_requests` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `configuration_change_requests` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `configuration_change_requests` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `configuration_change_decisions` | `ERone` / `ERzeroToMany` |
| `users` | `approval_delegations` | `ERone` / `ERzeroToMany` |
| `configuration_change_requests` | `configuration_change_history` | `ERone` / `ERzeroToMany` |
| `configuration_change_requests` | `configuration_change_decisions` | `ERone` / `ERzeroToMany` |
| `approval_delegations` | `users` | `ERzeroToMany` / `ERone` |
| `approval_delegations` | `users` | `ERzeroToMany` / `ERone` |
| `approval_delegations` | `users` | `ERzeroToMany` / `ERone` |
| `configuration_change_statuses` | `configuration_change_requests` | `ERone` / `ERzeroToMany` |
| `configuration_risk_levels` | `configuration_change_requests` | `ERone` / `ERzeroToMany` |
| `configuration_change_history` | `configuration_change_history` | `ERzeroToOne` / `ERzeroToMany` |

### 8 - Audit & Compliance - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `legal_holds` | `legal_hold_records` | `ERone` / `ERzeroToMany` |
| `legal_holds` | `legal_hold_modification_requests` | `ERone` / `ERzeroToMany` |
| `legal_holds` | `legal_hold_release_requests` | `ERone` / `ERzeroToMany` |
| `legal_hold_release_requests` | `legal_hold_release_decisions` | `ERone` / `ERzeroToMany` |
| `legal_hold_modification_requests` | `legal_hold_modification_decisions` | `ERone` / `ERzeroToMany` |
| `retention_policies` | `retention_execution_history` | `ERone` / `ERzeroToMany` |
| `retention_policies` | `retention_disposition_requests` | `ERone` / `ERzeroToMany` |
| `retention_disposition_decisions` | `retention_disposition_requests` | `ERzeroToMany` / `ERone` |
| `organizations` | `legal_holds` | `ERone` / `ERzeroToMany` |
| `organizations` | `retention_policies` | `ERone` / `ERzeroToMany` |
| `legal_hold_records` | `legal_hold_records` | `ERzeroToOne` / `ERzeroToMany` |

### 9 - Audit Logging & Security Monitoring - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `security_incidents` | `security_incident_events` | `ERzeroToOne` / `ERzeroToMany` |
| `security_incidents` | `security_alerts` | `ERzeroToOne` / `ERzeroToMany` |
| `audit_event_types` | `audit_events` | `ERone` / `ERzeroToMany` |
| `security_incident_statuses` | `security_incidents` | `ERone` / `ERzeroToMany` |
| `security_incident_statuses` | `security_incident_events` | `ERzeroToOne` / `ERzeroToMany` |
| `security_incident_statuses` | `security_incident_events` | `ERzeroToOne` / `ERzeroToMany` |
| `organizations` | `audit_events` | `ERone` / `ERzeroToMany` |
| `organizations` | `authentication_events` | `ERone` / `ERzeroToMany` |
| `organizations` | `security_incidents` | `ERone` / `ERzeroToMany` |
| `organizations` | `security_alerts` | `ERone` / `ERzeroToMany` |
| `users` | `audit_events` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `authentication_events` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_incidents` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_incident_events` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_incidents` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_incidents` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_incidents` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_alerts` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_alerts` | `ERzeroToOne` / `ERzeroToMany` |
| `users` | `security_alerts` | `ERzeroToOne` / `ERzeroToMany` |

### 10 - Authentication, Sessions & Account Security - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `user_credentials` | `users` | `ERzeroToOne` / `ERone` |
| `user_sessions` | `users` | `ERzeroToMany` / `ERone` |
| `user_sessions` | `user_refresh_tokens` | `ERone` / `ERzeroToMany` |
| `user_refresh_tokens` | `users` | `ERzeroToMany` / `ERone` |
| `service_account_credentials` | `credential_rotation_history` | `ERone` / `ERzeroToMany` |
| `users` | `email_verification_tokens` | `ERone` / `ERzeroToMany` |
| `users` | `password_reset_tokens` | `ERone` / `ERzeroToMany` |
| `users` | `account_lockouts` | `ERone` / `ERzeroToMany` |
| `users` | `account_lockouts` | `ERone` / `ERzeroToMany` |
| `users` | `account_security_events` | `ERone` / `ERzeroToMany` |
| `users` | `account_security_events` | `ERone` / `ERzeroToMany` |
| `users` | `service_account_credentials` | `ERone` / `ERzeroToMany` |
| `users` | `credential_rotation_history` | `ERone` / `ERzeroToMany` |
| `user_refresh_tokens` | `user_refresh_tokens` | `ERzeroToOne` / `ERzeroToOne` |

### 11 - Reference Tables & Supporting Entities - Relationships

| From | To | Endpoint markers (source / target) |
|---|---|---|
| `project_cancellation_reasons` | `projects` | `ERzeroToOne` / `ERzeroToMany` |
| `task_cancellation_reasons` | `tasks` | `ERzeroToOne` / `ERzeroToMany` |
| `membership_end_reasons` | `team_memberships` | `ERzeroToOne` / `ERzeroToMany` |
| `assignment_end_reasons` | `task_assignments` | `ERzeroToOne` / `ERzeroToMany` |
| `notification_delivery_statuses` | `notification_delivery_attempts` | `ERone` / `ERzeroToMany` |
| `outbox_processing_statuses` | `outbox_events` | `ERone` / `ERzeroToMany` |
| `configuration_application_statuses` | `configuration_change_requests` | `ERone` / `ERzeroToMany` |
| `legal_hold_statuses` | `legal_holds` | `ERone` / `ERzeroToMany` |
| `retention_execution_statuses` | `retention_execution_history` | `ERone` / `ERzeroToMany` |
| `security_alert_statuses` | `security_alerts` | `ERone` / `ERzeroToMany` |
| `project_team_participation_end_reasons` | `project_team_participations` | `ERzeroToOne` / `ERzeroToMany` |

## 6. Validation and boundaries

- The table/relationship inventories were extracted from the latest uploaded ERD.
- Cardinality markers and FK labels represent intended logical structure; they do not enforce constraints by themselves.
- No JPA entities, DDL, migrations, indexes, triggers, or deployment configurations are authorized by this Phase 5 document.
- Final review should verify FK targets, nullability, lifecycle checks, and organizational scoping before Phase 5 sign-off.
