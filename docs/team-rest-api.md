# Team REST API — B7 initial boundary

All endpoints require the existing authenticated session; all mutations and conflict review POST require CSRF. Controllers are enabled only with epms.rbac.enabled=true. Actor identity is derived by the existing service, never from a request DTO. No new role or database grants. Scope checks remain in V37. Permission evaluation stays internal rather than exposing a generic authorization oracle. No migration is required.

| Method | Path | Request | Success |
|---|---|---|---|
| GET | /api/teams | limit=50, offset=0 | 200 scoped list |
| GET | /api/teams/{teamId} | none | 200 profile |
| POST | /api/teams | name, description, override, reason, reviewedConflicts | 201 teamId and Location |
| PUT | /api/teams/{teamId} | version, profile (same fields as create) | 200 version |
| POST | /api/teams/name-conflict-review | optional teamId, name | 200 reviewedConflicts |
| POST | /api/teams/{teamId}/memberships | userId, reason | 200 resultId |
| POST | /api/teams/{teamId}/memberships/{membershipId}/end | endType, reasonCode, comment | 204 |
| POST | /api/teams/{teamId}/leadership | membershipId, reason | 200 resultId |
| POST | /api/teams/{teamId}/leadership/{assignmentId}/end | optional replacementMembershipId, reason | 204 |
| POST | /api/teams/{teamId}/alternates | membershipId, priority, reason | 200 resultId |
| POST | /api/teams/{teamId}/alternates/{alternateId}/revoke | reason | 204 |
| POST | /api/teams/{teamId}/lifecycle | type, reason | 200 resultId |
| POST | /api/teams/{teamId}/leadership/{assignmentId}/emergency | type, reason | 200 resultId |
| POST | /api/security/users/{userId}/lifecycle | type, reason | 200 resultId |

Conflict review returns the existing database snapshot as a JSON string; display its contents and return it unchanged with override=true and justification. No separate conflict-list request is necessary. Exact duplicates remain forbidden. Service validation remains authoritative for operation codes and page limits. A null replacement means no replacement, subject to business constraints. No hard-delete endpoint. ProblemDetail: 400 invalid request, 401 unauthenticated, 403 denied or missing CSRF, 404 unavailable profile, 409 conflict, 503 database unavailable. Global MVC validation and unexpected error handling are retained.

Initial HTTP tests exercise the real filter chain with mocked session eligibility and service boundaries, not actual login or end-to-end HTTP-to-database command execution. Existing Spring service integration continues to verify database behavior. Full HTTP operation coverage, strict unknown-field policy review, snapshot display contract, live security and end-to-end rollback tests remain acceptance work before release. Deferred project/viewer/auditor scopes remain denied. No notification or history endpoint is introduced here.

## B8 notification boundary

See [Team notifications and audit boundary](team-notification-and-audit-boundary.md) for the V38 inbox, critical alert, receipt, dispatch, readiness and sanitized history endpoints.

## B8.1A notification automation diagnostics

`GET /api/team-notifications/dispatch-failures?limit=50&offset=0` returns pending failed dispatch metadata
for current organization administrators only. The existing administrative alert response adds
`escalationCount`, `lastEscalatedAt`, and `lastEscalationStage`. Manual dispatch remains authorized and
can recover exhausted work; its result counts successful events. Automatic system commands have no HTTP route.

### Confidential single-event review

`POST /api/teams/{teamId}/history/{eventId}/confidential` accepts `sourceKind` (`OPERATION` or
`LIFECYCLE`) and `justification`. Requires authentication and CSRF; returns JSON with no-store
on success, 403 on scope denial and 400 on invalid input. No caller actor or bulk export is accepted.

## B9A acceptance additions

The real-login HTTP acceptance scenario uses disposable PostgreSQL and the restricted Team datasource.
External password screening is mocked; session validation and Team services are not mocked. The first
scenario covers profile creation, CSRF, malformed JSON, stale update, membership and leadership,
activation, deferred-role reads and mutation denial, and confidential audit retrieval. It is not a
complete endpoint matrix. Missing or mismatched administrative relationship targets now return a
sanitized conflict via V41; internal errors are not globally reclassified. Unknown JSON field policy,
revoked/expired sessions, all remaining endpoint cases and full audit-filter acceptance remain open.

## B9B additional HTTP acceptance

Team command request records explicitly reject undeclared JSON properties (including nested profile
properties), independently of global Jackson defaults. Unreadable Team JSON returns a fixed HTTP 400
without echoing the request. No unrelated REST DTO is modified. The real-login scenario adds inbox,
manual dispatch, failure diagnostics, receipt ownership and idempotence, confidential denial history,
expired/stale session handling, account security deactivation, and alert acknowledgement and resolution.
These cases require local regression verification. Full endpoint coverage is not yet established.

### Confidential access metadata (V43)

`GET /api/teams/{teamId}/confidential-access-history?limit=50&offset=0` is SYSTEM_ADMIN only,
restricted to an existing Team in the current organization. Returns bounded metadata, not original
confidential records or justifications. Limit 1–100; offset nonnegative. Unauthorized and unknown Team
requests are denied; successful responses include `Cache-Control: no-store`.

## B9D Final V44 emergency relationship classification

For a valid emergency request from a current authorized administrator on an active Team, an
unknown, ended or different-Team leadership assignment returns the same sanitized HTTP 409.
Authorization precedes the assignment lookup. Missing authority remains HTTP 403; invalid
input remains 400; unauthenticated requests remain 401. Unexpected database errors remain 503.
V44 does not change API routes, security grants or succession behavior. The bounded Unicode
name-warning heuristic is accepted; exact normalized duplicate protection remains mandatory.
The V44 regression and local deployment evidence must be recorded before release.
