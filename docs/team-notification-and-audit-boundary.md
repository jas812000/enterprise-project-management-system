# Team notifications and audit boundary — B8 (V38)

## Durable in-app delivery

Existing immutable Team operation and lifecycle events are pending work. A current SYSTEM_ADMIN or ORG_ADMIN invokes a bounded dispatch operation **after** the original transaction commits. The dispatch marker and per-recipient notifications commit together; the unique source and recipient constraints make repeated dispatch safe. Failed dispatch leaves events pending. Dispatch uses a separate advisory lock `(1832,38)`; no notification insert, trigger, provider call or scheduler is part of the account/leadership security mutation. Database row-lock contention remains a runtime acceptance consideration.

The notification record contains a Team identifier, sanitized kind, severity, event timestamp and recipient-specific receipt state. No private reasons, account event types, profile snapshots, actor/subject identities or alternate identities are exposed. ADMIN recipients are current organization administrators, including the approved project-manager fallback. PERSONAL recipients are the affected active HUMAN account with a current approved role, or the incoming emergency leader; personal notices do not confer Team profile/history access. TEAM recipients must have event-time membership and current authorized membership. Leadership changes reach affected Team members and current administrators; membership changes reach the affected member and eligible current Team leadership with the relevant relationship. Alternate designations/revocations are restricted to administrators and the affected person. Project-specific managers are not inferred from organization-wide PROJECT_MANAGER roles. Archived Teams and former membership do not grant continuing TEAM notice access. Explicit archive notices to former members, other privileged leadership domains and historical recipients no longer eligible at dispatch remain deferred.

Current account, role, organization and resource scope are checked again for every inbox and receipt request. Acknowledging a notice implies read but read alone does not imply acknowledgement. Receipts, dispatch provenance, notifications, alert acknowledgement and resolution are append-only. Individual recipients cannot change a different recipient's state.

## Critical alerts and readiness

The existing NO_ELIGIBLE_SUCCESSOR event is persisted before Team deactivation by the protected security operation; V38 does not change that operation. The administrator alert queue reads the original alerts directly, even if no dispatch has happened. Any current authorized organization administrator can see unresolved alerts. Acknowledgements are individual and do not close an alert. An explicit, justified resolution is accepted only when an eligible leader exists or the Team has been archived; it records an acknowledgement and immutable resolution. An unresolved deliberately inactive Team with no eligible leader stays in the alert queue. This conservative resolution guard needs product acceptance before broadening it. Administrator alternate readiness returns two priority slots with ELIGIBLE, MISSING or INELIGIBLE; no alternate identity is returned.

## Confidential audit history

Current SYSTEM_ADMIN and ORG_ADMIN may retrieve bounded, organization-scoped Team operation/lifecycle metadata, including archived Teams. Raw confidential reasons, account lifecycle records, subject/actor identities and profile snapshots remain protected at the database boundary. Full-detail audit access requires a separately approved authorization/field policy. No raw notification table privilege or helper EXECUTE privilege is given to runtime roles. Only the eight fixed SECURITY DEFINER commands are available to the trusted RBAC backend; actor IDs originate in the service.

## HTTP endpoints

All require an authenticated session; POST additionally requires CSRF. No caller-provided actor ID or recipient ID is accepted. Pages have limit 1–100 and offset >= 0; default limit 50. Errors use the existing sanitized Team advice.

| Method | Path | Result |
|---|---|---|
| GET | /api/team-notifications | Current recipient inbox |
| POST | /api/team-notifications/dispatch?limit=50 | Number of committed source events processed; admin only |
| POST | /api/team-notifications/{notificationId}/read | 204; own notice |
| POST | /api/team-notifications/{notificationId}/acknowledge | 204; own notice |
| GET | /api/team-notifications/alerts?includeResolved=false | Administrative critical alert queue |
| POST | /api/team-notifications/alerts/{alertId}/acknowledge | 204; admin only |
| POST | /api/team-notifications/alerts/{alertId}/resolve | 204; admin only; JSON reason required |
| GET | /api/teams/{teamId}/alternate-readiness | Administrative readiness warning |
| GET | /api/teams/{teamId}/history | Administrative sanitized event metadata |

## Acceptance limits and next batch

V38 is the durable in-app processing and confidential metadata boundary, **not** an external delivery implementation. Dispatch is an explicit admin command, not a background worker or automatic email delivery. Re-running the bounded dispatch retries pending events without duplication. No email delivery attempts, backoff/lease policy, three-attempt escalation, timed acknowledgement reminders, or automatic recipient routing for future project/organization leadership domains are claimed. These require the delivery-worker/transport implementation and verified recipient policy, plus concurrency and failure-path acceptance. No new transport dependencies, credentials or external providers have been added. The critical queue is durable and independent of dispatch, but is not a timed escalation service. The V31 intermittent invariant issue remains a release blocker. Full HTTP-to-database, live datasource, production migration and CI acceptance remain outstanding.

## B8.1A — V39 system dispatch, retry and escalation

V39 retains the V38 recipient selection and manual administrative command. System processing uses the existing
restricted RBAC JDBC datasource and two fixed actor-free commands, with no HTTP path to system execution.
System dispatch provenance is `SYSTEM` with a null human actor. Existing records retain `HUMAN_ADMIN` and their
original actor. This is not a new human role or a new database login. Only the trusted backend may invoke these
commands. The internal dispatcher is owner-only. No direct table grants are introduced.

The Spring scheduler is opt-in: set `EPMS_TEAM_NOTIFICATIONS_AUTOMATIC_ENABLED=true` **after** V39 is installed
and RBAC is enabled. Default interval and initial delay are 30 seconds. The property prefix is
`epms.team.notifications.automatic`:

| Property suffix | Default |
|---|---:|
| `fixed-delay-ms` | 30000 |
| `initial-delay-ms` | 30000 |
| `batch-size` | 10 |
| `max-attempts` | 5 |
| `base-retry-seconds` | 30 |
| `max-retry-seconds` | 900 |
| `acknowledgement-seconds` | 300 |
| `repeat-escalation-seconds` | 900 |

Each system dispatch call processes at most one committed source event and commits separately. Advisory lock
(1832,38) is shared by manual and automatic dispatch; a competing system worker returns without waiting.
The scheduler stops its current batch on zero successes and retries on its next tick. Notification inserts and
marker roll back atomically on event failure. The attempt records SQLSTATE only, outcome, ordinal, provenance,
and next retry timestamp; failures use capped exponential backoff. Automatic exhaustion leaves the original
source pending. An authorized administrator can explicitly retry pending/exhausted events through the existing
manual dispatch endpoint, which bypasses the automatic delay. Manual dispatch returns successful event count;
failures remain visible through `GET /api/team-notifications/dispatch-failures`, filtered before pagination.
`automaticExhausted` reflects the limit stored with the last attempt; a configuration change can alter eligibility.
Database outages, canceled statements and transaction-level failures may prevent attempt persistence; the
source remains pending and scheduler logs are sanitized. Failed dispatch is never a prerequisite for revocation.
Foreign-key lock contention is still possible; automatic dispatch uses one event per transaction and a short
lock timeout. Concurrency with emergency revocation must be verified under representative workloads.

Escalation independently observes unresolved critical alerts using lock (1832,39). The first unacknowledged
escalation is due after the acknowledgement deadline; if already acknowledged, the first unresolved escalation
is due after the repeat interval from acknowledgement. Subsequent observations repeat until resolution. Alert
responses expose count, latest escalation timestamp and stage. Escalation is an append-only observation, not a
new alert or a resolution; a concurrent acknowledgement/resolution can supersede an observation. Existing
resolution checks remain authoritative. This is durable in-app escalation metadata, not a guaranteed push or
email delivery channel. An authorized client must poll/display the critical queue. No external provider or
worker process is introduced. Actual application restart with a persistent database, production
lock contention and full HTTP-to-database verification remain acceptance requirements.

Full confidential event fields remain denied pending an explicitly approved field/role/scope policy, mandatory
access justification and access audit. This batch does not grant SYSTEM_ADMIN, ORG_ADMIN or AUDITOR access
to raw private reasons, snapshots or account lifecycle records. External email delivery remains deferred.

## Confidential Team audit (V40)

A CSRF-protected POST retrieves a single operation or lifecycle event by Team and event UUID.
SYSTEM_ADMIN may inspect the explicit Team event fields and linked account lifecycle record.
ORG_ADMIN may inspect operational events, excluding account-linked or emergency events; lifecycle
metadata omits reasons. Current TEAM_LEAD scope permits metadata history only. Other roles cannot
retrieve confidential details; AUDITOR remains denied until explicit audit scope exists.
Access requires a nonblank justification (maximum 500 characters). Successful and denied database
requests append an immutable access record. Denied requests do not record the justification. The
protected command returns SQL NULL for denial so its standalone JDBC transaction commits before
Java returns 403. Anonymous, malformed, inactive and rejected session requests may be stopped
before this command; security-filter denial audit remains an acceptance item. No credential tables
are queried. Free-text reasons and profile descriptions must never contain secrets; this is not an
arbitrary secret-detection or redaction guarantee. No list or bulk detail endpoint is introduced.
The response uses Cache-Control: no-store. Access-log retrieval and broader security audit review
remain restricted to future explicitly authorized operations.

External delivery, real application restart recovery and row-lock contention acceptance remain open.

## B9B additional transaction acceptance

The automation integration scenario holds a real uncommitted restricted dispatch transaction during
emergency leadership removal. It checks that emergency revocation completes, that the competing
worker cannot duplicate dispatch, that a rollback leaves no committed marker or attempt, and that
subsequent processing recovers pending work. This is a database transaction interruption test, not a
full application process restart. Account deactivation can acquire additional user-row locks; its
contention behavior remains a separate acceptance question. Security-filter denial auditing and
authorized access-history retrieval also remain outstanding. No applied migration is changed.

## B9C acceptance additions

The automated integration scenario closes and recreates an isolated Spring notification worker context
against the same disposable database, checks the persisted retry deadline, then invokes the recreated
worker to recover the event. This is a worker-context restart, not a full JVM or application restart.
It also tests account-security deactivation during a real uncommitted restricted dispatch and checks
revocation, rollback and subsequent delivery. These tests require local execution. Confidential
security-filter rejection logging and authorized access-history retrieval remain outstanding; existing
single-event database audit denial records are not a substitute. External transport remains deferred.

## B9D account lifecycle concurrency correction

Notification recipient FK checks can hold KEY SHARE on users. Account lifecycle updates non-key
fields only; V42 uses NO KEY UPDATE, preserving writer exclusion while permitting key-share locks.
The existing test must prove account deactivation commits before dispatch rollback. No timeout increase,
assertion removal or notification FK removal is permitted. Failed lock tests must retain diagnostic data.

## B9D final: access metadata and full-process restart acceptance

V43 adds protected, paginated SYSTEM_ADMIN-only access-history metadata. It never reads credential
records and omits free-text justification. The original immutable V40 records remain unchanged.
Confidential HTTP rejection logs contain fixed route and numeric status only. Secure operational log
retention is required separately. The integration test starts and stops two separate JVMs running the
real application against disposable PostgreSQL. The first persists a retry using the actual worker; the
second relies on automatic scheduling to recover the due event. Abrupt process death is not tested.
