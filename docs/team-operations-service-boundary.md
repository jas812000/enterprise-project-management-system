# Authenticated Team operations (B6.3)

`TeamOperationsService` exposes the existing V33 membership, leadership, alternate,
transition, emergency leadership and current-actor scoped permission commands.
`TeamAccountLifecycleService` routes account lifecycle changes separately from user
profile updates. Both are conditional on `epms.rbac.enabled=true`.

Every call resolves the current authenticated active user; the database remains
responsible for HUMAN eligibility, current administrative authorization, organization
scope, temporal rules, succession, and atomic revocation. No request actor ID or
cross-datasource transaction is accepted. Planned replacement accepts a nullable
replacement membership; PostgreSQL decides whether the resulting state is valid.
Account emergencies cannot be routed through the leadership-only command.
Permission evaluation is only the existing TEAM_LEAD scope check, not a general
RBAC permission decision or authorization for profile visibility.

Reasons and operation codes are validated before execution; membership reason codes
are validated against the active database catalog. Unknown SQL states (including
legacy P0001 business rejections) fail closed with the existing sanitized generic
exception; precise typed SQL-state mapping remains for service acceptance.

Tests cover authenticated routing of all ten operations, rejection before dispatch,
and sanitized exception translation. Existing PostgreSQL integration tests cover
database eligibility, authorization, concurrency and rollback. Full Spring-managed
service-to-database integration, non-admin reads, override confirmation, exhaustive
name matching, and intermittent V31 investigation remain acceptance work.
No new migration or runtime grant is introduced by this batch.
