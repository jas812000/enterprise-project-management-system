# EPMS Role-Based Access Control (RBAC) Specification

**Status:** Approved role catalog and policy decisions; detailed permission assignments remain proposed until reviewed.
**Scope:** One organization per EPMS instance.
**Implementation status:** V12 database RBAC foundation is implemented and has passed isolated database acceptance and backend regression tests; authenticated Java RBAC administration and authorization enforcement remain pending. Detailed individual grants are not yet approved as policy.
**Decision date:** 2026-11-21.

## 1. Approved role catalog

| Role              | Purpose                                                                                                                                               |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SYSTEM_ADMIN`    | Privileged security configuration, role administration and account-unlock authorization. Does not implicitly grant unrestricted business-data access. |
| `ORG_ADMIN`       | Manage ordinary users, departments, organizational settings and approved operational role assignments.                                                |
| `PROJECT_MANAGER` | Plan and manage authorized projects, schedules, resources, delivery and explicitly permitted budgets.                                                 |
| `TEAM_LEAD`       | Coordinate assigned teams, assign authorized tasks, monitor workload, and approve/reject assigned team members' timesheets.                           |
| `MEMBER`          | Perform assigned work, update authorized tasks and submit own timesheets.                                                                             |
| `VIEWER`          | Read-only access to explicitly authorized business projects, dashboards and reports.                                                                  |
| `AUDITOR`         | Read-only security, administrative and authorized compliance audit access.                                                                            |

All seven roles are system-defined. Only HUMAN accounts may receive these roles; service/API clients use separately controlled scopes. Multiple human roles may be assigned subject to separation-of-duties restrictions. Roles do not automatically inherit other roles. No role bypasses record-level authorization.

## 2. Approved authorization policies

1. `SYSTEM_ADMIN` alone controls privileged role assignments (`SYSTEM_ADMIN`, `AUDITOR`) and account-unlock grants. Last-active-SYSTEM_ADMIN protection is mandatory.
2. `ORG_ADMIN` may assign only approved operational roles and cannot elevate itself to privileged roles or modify the security permission matrix.
3. `AUDITOR` can inspect authorized audit records but cannot change business or security records or alter audit history.
4. `VIEWER` is business read-only, not a security auditor. Viewer access does not imply access to confidential financial, personnel or security data.
5. `TEAM_LEAD` may approve or reject timesheets **only for members of teams the lead is authorized to supervise**. A lead may not approve their own timesheet. Every decision must be audited.
6. All administrative actor identities must derive from the authenticated principal, never from a client-supplied actor ID.
7. Permission checks must be server-side and include resource ownership, team membership, project scope, current role assignments and account status. Privilege revocations must take effect for existing sessions.
8. Database administrative functions are callable only through a separate least-privileged backend connection. Ordinary `epms_app` credentials must not inherit `epms_security_admin`.
9. Audit and security history must be append-only where required; no application role may erase or rewrite it.

## 3. Proposed security and organization permission matrix

**This section is a proposed implementation contract, not yet approved at the individual-permission level.** `Scoped` means the permission also requires a resource-level authorization check. `No` means no role grant; combined-role users are evaluated against all assigned roles subject to explicit prohibitions.

| Permission                    | SYSTEM_ADMIN      | ORG_ADMIN | PROJECT_MANAGER | TEAM_LEAD | MEMBER | VIEWER | AUDITOR |
|-------------------------------|-------------------|-----------|-----------------|-----------|--------|--------|---------|
| `SECURITY_CONFIG_MANAGE`      | Yes               | No        | No              | No        | No     | No     | No      |
| `PRIVILEGED_ROLE_ASSIGN`      | Yes               | No        | No              | No        | No     | No     | No      |
| `OPERATIONAL_ROLE_ASSIGN`     | Yes               | Yes       | No              | No        | No     | No     | No      |
| `USER_MANAGE`                 | Scoped            | Scoped    | No              | No        | No     | No     | No      |
| `DEPARTMENT_MANAGE`           | By explicit grant | Yes       | No              | No        | No     | No     | No      |
| `ACCOUNT_UNLOCK_GRANT_MANAGE` | Yes               | No        | No              | No        | No     | No     | No      |
| `SECURITY_AUDIT_READ`         | Yes               | No        | No              | No        | No     | No     | Yes     |
| `AUDIT_HISTORY_MODIFY`        | No                | No        | No              | No        | No     | No     | No      |

## 4. Proposed project and timesheet permission matrix

| Permission                        | SYSTEM_ADMIN        | ORG_ADMIN           | PROJECT_MANAGER   | TEAM_LEAD     | MEMBER             | VIEWER | AUDITOR                |
|-----------------------------------|---------------------|---------------------|-------------------|---------------|--------------------|--------|------------------------|
| `PROJECT_READ`                    | By explicit grant   | Scoped              | Scoped            | Scoped        | Scoped             | Scoped | Authorized audit scope |
| `PROJECT_CREATE`                  | By explicit grant   | By explicit grant   | Scoped            | No            | No                 | No     | No                     |
| `PROJECT_UPDATE`                  | By explicit grant   | By explicit grant   | Scoped            | No            | No                 | No     | No                     |
| `TASK_ASSIGN`                     | No implicit grant   | No implicit grant   | Scoped            | Assigned team | No                 | No     | No                     |
| `TASK_UPDATE_ASSIGNED`            | No implicit grant   | No implicit grant   | Scoped            | Scoped        | Own assigned tasks | No     | No                     |
| `TIMESHEET_SUBMIT_OWN`            | By operational role | By operational role | Yes               | Yes           | Yes                | No     | No                     |
| `TIMESHEET_EDIT_OWN_DRAFT`        | By operational role | By operational role | Yes               | Yes           | Yes                | No     | No                     |
| `TIMESHEET_READ_TEAM`             | No implicit grant   | By explicit grant   | Scoped            | Assigned team | No                 | No     | Authorized audit scope |
| `TIMESHEET_APPROVE_TEAM`          | No implicit grant   | No implicit grant   | By explicit grant | Assigned team | No                 | No     | No                     |
| `TIMESHEET_REJECT_TEAM`           | No implicit grant   | No implicit grant   | By explicit grant | Assigned team | No                 | No     | No                     |
| `TIMESHEET_APPROVE_SELF`          | No                  | No                  | No                | No            | No                 | No     | No                     |
| `TIMESHEET_APPROVAL_HISTORY_READ` | By explicit grant   | By explicit grant   | Scoped            | Assigned team | Own records        | No     | Authorized audit scope |

### Timesheet workflow rules

- The approver must have the relevant current permission and be an active authorized lead of the timesheet owner's team at decision time.
- The approver's user ID must differ from the timesheet owner's user ID, regardless of other assigned roles.
- Approvals/rejections require a valid timesheet state and transactional, auditable actor/time/outcome details. Rejection reason requirements and any escalation/delegation process remain to be finalized.
- Team assignment history and effective dates must be considered if a timesheet covers periods spanning team changes; exact effective-date policy remains an open design decision.
- Timesheet approval must not automatically grant payroll authorization or budget approval.

## 5. Required technical enforcement

- Store system-defined roles, permission definitions, role-permission mappings, user-role assignments and protected administrative history through a new versioned migration (proposed V12), without editing V1–V11.
- Resolve authorities from verified server-side assignments; enforce current permissions at administrative request time, not solely cached login authorities.
- Reject self-elevation, last-active-SYSTEM_ADMIN removal, cross-team timesheet approval, self-approval, cross-project access, inactive accounts, and unauthorized AUDITOR administration.
- Add unit, integration, negative-authorization, concurrent-update and audit-integrity tests.
- Preserve staged, unstaged and untracked Git changes; use feature branch and PR workflow. No production migration until application authorization and regression checks pass.

## 6. Open decisions before authenticated RBAC enforcement

1. Final permission identifiers and role-permission mapping, including whether project managers receive timesheet approval by explicit assignment.
2. Whether SYSTEM_ADMIN and AUDITOR may hold operational roles simultaneously, and any stricter separation-of-duties policy.
3. Role-assignment validity periods, delegation, and last-admin race-condition locking strategy.
4. Timesheet submission, approval/rejection, correction, resubmission, delegation and escalation states.
5. Team membership effective-date rule for timesheet approval when membership changes.
6. Precise financial, reporting and sensitive-data permissions as corresponding EPMS modules are implemented.

## 7. Decision record

| Date       | Decision                                                                                                        | Status                   |
|------------|-----------------------------------------------------------------------------------------------------------------|--------------------------|
| 2026-11-21 | Retain `SYSTEM_ADMIN`, `ORG_ADMIN`, `PROJECT_MANAGER`, `TEAM_LEAD`, `MEMBER`, `VIEWER`, `AUDITOR` (seven roles) | Approved                 |
| 2026-11-21 | `TEAM_LEAD` approves/rejects assigned team's timesheets; never self-approves                                    | Approved                 |
| 2026-11-21 | Store the authoritative RBAC specification at `docs/security/rbac-permission-matrix.md`                         | Approved                 |
| 2026-11-21 | Detailed individual permission grants and implementation schema                                                 | Proposed; review pending |


## 8. V12 seed reconciliation (review record; not policy approval)

The existing `V12__rbac_foundation.sql` creates seven roles, 18 permission definitions, and 35 role-permission seed mappings. The seed is a provisional technical baseline, **not** approval of every grant in sections 3–4. No migration or database permissions are changed by this document update.

### 8.1 Seeded grants by role

| Role | Permissions seeded in V12 |
|---|---|
| `SYSTEM_ADMIN` | `SECURITY_CONFIG_MANAGE`, `PRIVILEGED_ROLE_ASSIGN`, `OPERATIONAL_ROLE_ASSIGN`, `USER_MANAGE`, `ACCOUNT_UNLOCK_GRANT_MANAGE`, `SECURITY_AUDIT_READ` |
| `ORG_ADMIN` | `OPERATIONAL_ROLE_ASSIGN`, `USER_MANAGE`, `DEPARTMENT_MANAGE`, `PROJECT_READ` |
| `PROJECT_MANAGER` | `PROJECT_READ`, `PROJECT_CREATE`, `PROJECT_UPDATE`, `TASK_ASSIGN`, `TASK_UPDATE_ASSIGNED`, `TIMESHEET_SUBMIT_OWN`, `TIMESHEET_EDIT_OWN_DRAFT`, `TIMESHEET_READ_TEAM`, `TIMESHEET_APPROVAL_HISTORY_READ` |
| `TEAM_LEAD` | `PROJECT_READ`, `TASK_ASSIGN`, `TASK_UPDATE_ASSIGNED`, `TIMESHEET_SUBMIT_OWN`, `TIMESHEET_EDIT_OWN_DRAFT`, `TIMESHEET_READ_TEAM`, `TIMESHEET_APPROVE_TEAM`, `TIMESHEET_REJECT_TEAM`, `TIMESHEET_APPROVAL_HISTORY_READ` |
| `MEMBER` | `PROJECT_READ`, `TASK_UPDATE_ASSIGNED`, `TIMESHEET_SUBMIT_OWN`, `TIMESHEET_EDIT_OWN_DRAFT`, `TIMESHEET_APPROVAL_HISTORY_READ` |
| `VIEWER` | `PROJECT_READ` |
| `AUDITOR` | `SECURITY_AUDIT_READ` |

### 8.2 Decisions requiring explicit approval

1. Confirm the V12 grant set, particularly `SYSTEM_ADMIN` → `USER_MANAGE`, `ORG_ADMIN` → `PROJECT_READ`, `PROJECT_MANAGER` → `TIMESHEET_READ_TEAM`, and `MEMBER` → `TIMESHEET_APPROVAL_HISTORY_READ`. All must remain scoped where specified.
2. Decide whether the permission catalog needs an explicit `TIMESHEET_APPROVE_SELF` prohibition represented only as a business invariant (recommended) rather than a grantable permission.
3. Confirm that `AUDITOR` is mutually exclusive with **every** other role, as V12 enforces; this is stricter than the original open question.
4. Confirm `SYSTEM_ADMIN` may combine with operational roles but has no implicit business access from its privileged role.
5. Finalize team-membership effective dates, timesheet workflow states, and financial/sensitive-data permissions before corresponding endpoints are enabled.
6. Specify authenticated principal-to-database actor provenance, least-privileged administration connection, revocation freshness, and concurrency acceptance tests before implementing administrative APIs.

Changes to already-applied V12 seed policy should be delivered through a later versioned migration, not by rewriting V12.

## 9. Approved B18.32h-5 authorization decisions

**Approval date:** 2026-11-21. **Status:** Approved policy constraints; application enforcement pending.

The following ten constraints were explicitly approved. This approval does **not** constitute blanket approval of all 35 V12 seeded role-permission mappings. Section 8 remains a seed reconciliation, and any individual grant not resolved below still requires review before enabling the corresponding endpoint.

1. **SYSTEM_ADMIN business access:** `SYSTEM_ADMIN` has no implicit business-data permissions. Business access requires a separately assigned authorized operational role and resource-level checks.
2. **USER_MANAGE scope:** `SYSTEM_ADMIN`'s `USER_MANAGE` is limited to account administration, not unrestricted business-record access.
3. **ORG_ADMIN project reads:** `ORG_ADMIN`'s `PROJECT_READ` is restricted to authorized projects; no organization-wide bypass is implied.
4. **PROJECT_MANAGER team timesheets:** `TIMESHEET_READ_TEAM` applies only to authorized projects and team members within the manager's scope.
5. **MEMBER approval history:** `TIMESHEET_APPROVAL_HISTORY_READ` applies only to the member's own timesheets.
6. **TEAM_LEAD approvals:** Approval/rejection is limited to authorized assigned team members. Self-approval is forbidden regardless of other roles; this is a business invariant, not a grantable `TIMESHEET_APPROVE_SELF` permission.
7. **AUDITOR separation:** `AUDITOR` is mutually exclusive with every other human role.
8. **Multiple operational roles:** Multiple operational roles are allowed, subject to separation of duties; effective permissions derive from current active assignments and remain constrained by resource-level authorization.
9. **Revocation freshness:** Changes to authorization must take effect on subsequent protected requests, including existing sessions. Do not rely on stale permissions embedded in access tokens.
10. **Administrative history:** Capture authenticated actor identity, target, action, timestamp, and outcome; protect history against unauthorized modification. Actor identity must come from the authenticated principal, not a client-supplied identifier.

**Implementation boundary:** These rules require authenticated Java enforcement, least-privileged administrative database access, and negative/concurrency tests. The V12 migration is not modified by this record. Any necessary change to an already-applied migration's seed policy must use a subsequent versioned migration.
