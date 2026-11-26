# EPMS Phase 12 — Team Physical Database Foundation (B3.2)

**Historical status (B3.2):** Schema foundation only; the following describes the original design milestone, not the current release. Subsequent Phase 12 work through V44 implemented and validated Team operations, emergency succession, in-app notifications and restricted runtime boundaries. See [Phase 12 release acceptance](team-phase12-release-acceptance.md) for current evidence, accepted limitations and deferred obligations.

## Existing decisions

- Single-organization EPMS, PostgreSQL 18 UUIDv7, Java 25 / Spring Boot 4.1.1.
- Team statuses: `PENDING_SETUP`, `ACTIVE`, `INACTIVE`, `ARCHIVED`; flag consistency enforced by V25.
- Exact normalized duplicate names rejected. Confusable/similar-name flagging and authorized override are later service/security work.
- Active teams require eligible active membership and at least one eligible active lead; B3 creates no authorization path to activate a team; cross-table/role invariants must be database-protected before enabling lifecycle writes.
- Memberships are temporal, with immutable closed periods, a standardized reason catalog and mandatory manual removal comments.
- At most one simultaneous active leadership assignment per user across all teams; multiple leaders per team are allowed. Approved full concurrency enforcement and complete account/role-trigger integration remain to implement.
- Up to two priority-ranked alternates per team. Alternates are dormant, with no effective leadership permission merely from their designation. One team designation per user. Alternate designation requires an open, eligible HUMAN membership period and retains its membership-period reference.
- **Only emergencies** may activate dormant alternates; planned removals require ordinary authorized replacement, never automatic succession.
- A remaining eligible active co-leader takes precedence over alternates. Emergency activation is temporary acting authority, not permanent appointment.
- Preserve immediate security revocation, pre-deactivation high-alert persistence, subsequent notifications, bidirectional leadership awareness and confidentiality.

## New migrations

- `V25__team_lifecycle_and_membership_reasons.sql`: membership reason catalog, team lifecycle table, name uniqueness, terminal archival guard.
- `V26__team_membership_periods.sql`: temporal memberships, organization-safe composite foreign keys, open-period uniqueness, closure validation, no history rewriting.
- `V27__team_leadership_succession_foundation.sql`: leadership periods, two dormant alternate slots, append-only succession events, history guards.

### Security boundary

These are **closed-by-default** tables. V25–V27 make no runtime DML or permission grants and do not create emergency elevation functions. The existing V12/V13 RBAC role-assignment catalog, 35 seed mappings, last-active-SYSTEM_ADMIN protections, protected functions, and advisory lock `(1832,12)` are unchanged.

The Phase 11 global `TEAM_LEAD` role currently carries effective permissions. Therefore dormant alternates **must not** be assigned that role just because they are designated. Implement either a protected audited activation of a properly scoped effective role plus an assignment, or a separately reviewed scoped authority-resolution path; do not add an unsecured bypass. Integrate account deactivation, role revocation and concurrent operations before enabling writes.

### Obligations outstanding at the historical B3.2 milestone

1. Database cross-table invariants: active membership and leadership eligibility; automatic deactivation on security revocation; non-overlapping historical periods beyond simple open-row uniqueness; live role and account validations; legal retention controls.
2. Actor-authenticated protected team operations, least-privileged runtime database identity, RBAC grant migration and negative authorization tests.
3. Emergency-only trusted activation with no self-activation; database locking order compatible with `(1832,12)`; durable high-alert event and assignment transition in one transaction.
4. Team-name similarity detection/override; full audit history and project dependency tracking.
5. REST, DTO and services; application-level security tests; notification recipients, privacy, acknowledgement, delivery retries and escalation.
6. Full suite, portable Quality Gates and GitHub required checks / branch protection before any push.

**No migration applied on a live or production database by this batch script.** PostgreSQL integration tests run using an isolated PostgreSQL 18 Testcontainers instance.
