# EPMS — Database Decision Register

**Phase:** 5 — Logical Database Design  
**Status:** Documentation draft for review  
**ERD baseline:** `epms-logical-erd v2(5).drawio`

## Approved decisions

| ID | Topic | Question | Decision | Status |
|---|---|---|---|---|
| D-001 | Phase boundary | What belongs in Phase 5, and what must wait until physical implementation? | Phase 5 defines the logical model only; defer SQL/JPA/physical schema. | Approved |
| D-002 | Identity | Which identifier type and generation approach should EPMS use? | UUID entity identifiers; UUIDv7 preferred for generation. | Approved |
| D-003 | Organization boundary | How should EPMS prevent relationships across organization boundaries? | Single organization per deployment; prohibit cross-organization assignment and enforce eligibility transactionally. | Approved |
| D-004 | Status and history | Should EPMS store current statuses, status history, or both? | Persist current statuses and separate status histories. | Approved |
| D-005 | Assignment modeling | Should individual personnel assignments and team participation use separate tables? | Retain both individual `project_personnel_assignments` and team `project_team_participations`. | Approved |
| D-006 | Temporal membership | How should repeated memberships and assignments be preserved over time? | Keep historical periods with period numbers and end metadata. | Approved |
| D-007 | Uniqueness | Which uniqueness rules prevent duplicate current records and conflicting histories? | Unique credential owner; unique refresh-token replacement; at most one open membership, participation, or task assignment; unique period numbers within scope. | Approved |
| D-008 | FK optionality | Which foreign keys are required, optional, or dependent on lifecycle state? | Apply required/optional FK and lifecycle-dependent reason rules as described in logical design document. | Approved |
| D-009 | Bootstrap actor | How is an organization created before a user actor exists? | Allow nullable `organizations.created_by_user_id` for system bootstrap. | Approved |
| D-010 | Service-account ownership | Which ownership fields are required for service accounts? | Account-type-dependent owner requirement; optional backup owner. | Approved |
| D-011 | Reason catalogs | How should ending and cancellation reasons be validated and referenced? | Use dedicated unique-code catalogs, including `project_team_participation_end_reasons`; its `code` is referenced by `project_team_participations.removal_reason_code`. | Approved |
| D-012 | Reason nullability | When must a cancellation or ending reason be supplied? | End/cancellation reasons nullable before completion; enforce appropriate reason at transition. | Approved |
| D-013 | Legal holds | How should legal holds protect records and dependent records from disposition? | Active holds block covered disposition including dependents; root `legal_hold_records.parent_hold_record_id` nullable, child stays in same hold. | Approved |
| D-014 | Legal hold approvals | Who must approve releasing a legal hold or reducing its scope? | Two distinct authorized approvers for release or scope reduction; no self-approval; validate delegation. | Approved |
| D-015 | Configuration governance | What approval controls apply to configuration changes and rollbacks? | All configuration changes, including rollback, require approval; high-risk changes require two distinct approvers. | Approved |
| D-016 | Audit retention | How long should append-only audit events be retained? | Append-only audit events; seven-year configurable baseline with legal hold override. | Approved |
| D-017 | Security event retention | How long should account security events be retained? | 365-day baseline for account security events, subject to retention and legal holds. | Approved |
| D-018 | Authentication | How should the frontend authenticate while supporting token renewal? | Retain refresh-token model for short-lived access-token architecture. | Approved |
| D-019 | Notifications | How should notification delivery, retries, and escalation be handled? | Persist notifications/outbox; max three email delivery attempts with escalation. | Approved |
| D-020 | ERD cardinality | Can a parent record exist without any child records? | Use zero-or-many child cardinality where a parent can exist without dependents. | Approved / diagram updated |
| D-021 | FK label corrections | Which user and rollback references must be explicitly marked as foreign keys? | Mark `action_required_alerts.assigned_to_user_id`, `resolved_by_user_id`, and `configuration_change_history.rolled_back_from_history_id` as FK. | Approved / diagram updated |
| D-022 | Reference table format | Which standard columns belong in the new participation-end-reason catalog? | Use `display_name VARCHAR(150)`, `sort_order INTEGER`, `updated_at TIMESTAMPTZ` in the new participation-end-reason catalog. | Approved / diagram updated |

## Implementation notes and unresolved validation

- Approved logical decisions are **not** proof of implemented database constraints.
- The ERD does not label every column as nullable or non-nullable. The future physical schema must complete that mapping.
- Before Phase 5 sign-off, review the generated inventory against the diagram and confirm FK target columns, organization boundaries, and conditional lifecycle rules.
- Constraint names, index designs, and concrete enforcement mechanisms belong to the physical design phase.
- The reference-catalog decision is resolved: a dedicated project-team participation end-reason catalog is included.
