# Phase 12 release acceptance and traceability

## Verified baseline and scope

B9D Final-R2 validation `20261126_063705_531785Z`: 119 source hashes, 569 Maven tests
reported (one intentional skip), Maven verify, 63 focused Team tests, 27 RBAC tests,
3 JDBC tests, all 12 activation stress/diagnostic runs, 8 completed local scanners,
0 findings, 0 scanner errors. HTMLHint and Stylelint disabled because no eligible files;
re-evaluate on frontend introduction. Local V43 has 43 successful migrations, no pending
versions, Flyway validation and role/ownership audit PASS. Separate live Spring datasource
audit: one test passed. B9D Final Closeout validation `20261126_065951_885528Z` also passed
all executable checks, including the new positive HTTP scenarios, GitHub main protection and
live V43 datasource audit. The archived results reported 569 ordinary-suite executions and one
expected live-audit skip, zero failures/errors, eight completed scanners, zero findings/errors.
V44 comprehensive validation `20261126_072027_661338Z` passed the disposable migration,
64 focused Team tests, 27 RBAC tests, 3 JDBC tests, 12 activation runs, and the full Maven
suite and verify (570 tests reported, one expected live-audit skip, zero failures/errors).
A single Pylint line-length finding was corrected without changing Python semantics.
V44 R1 targeted validation `20261126_074005_784283Z` verified all 120 reviewed source hashes,
Python offline and disposable migration tests, eight completed local scanners with zero findings
and zero errors, Git integrity, and the approved branch protection. Local V44 has 44 successful
migrations and no pending versions; Flyway validation, role/ownership audit and the separately
executed live Spring datasource identity test passed. The combined reports establish the local
technical acceptance baseline; the two disabled scanners remain subject to applicability review.

## Requirements-to-evidence inventory

| Requirement | Implementation and evidence | Status |
| --- | --- | --- |
| Single organization, Team profile, lifecycle and membership periods | V25–V31, V34–V37; TeamPhysicalSchemaIntegrationTest, TeamLifecycleGuardIntegrationTest, TeamProfileCommandIntegrationTest | R2 pass |
| Protected Team commands and runtime least privilege | V29–V43; TeamCommandBoundaryIntegrationTest, TeamProtectedOperationsIntegrationTest, RbacDatabaseAuthorizationIntegrationTest, live datasource audit | R2 pass |
| Permanent leadership, co-leaders, planned replacement, dormant alternates and emergency succession | V27–V31, V36–V37, V41–V42; TeamActivationIntegrationTest, TeamAccountLifecycleIntegrationTest, TeamSpringServiceIntegrationTest | R2 pass; accepted historical residual below |
| No eligible successor alert, deactivation, security revocation and rollback | TeamActivationIntegrationTest, TeamNotificationAutomationIntegrationTest, TeamNotificationIntegrationTest | R2 pass |
| Reviewed name conflicts and scoped reads | V34–V37; TeamProfileCommandIntegrationTest, TeamSpringServiceIntegrationTest, TeamAuthenticatedHttpDatabaseIntegrationTest | R2 pass; bounded similarity heuristic |
| Real login, authorization, strict JSON, CSRF, session expiry/revocation | TeamAuthenticatedHttpDatabaseIntegrationTest, TeamHttpIntegrationTest | R2 pass within tested matrix |
| Durable notification dispatch, retry, exhaustion, manual recovery, escalation, receipts and clean process restart | V38–V39, V42; TeamNotificationIntegrationTest, TeamNotificationAutomationIntegrationTest, TeamNotificationAutomationSchedulingTest, TeamNotificationRestartProcess | R2 pass; abrupt kill deferred |
| Confidential single-event retrieval and access-history metadata | V40, V43; TeamNotificationIntegrationTest, TeamAuthenticatedHttpDatabaseIntegrationTest, TeamConfidentialRejectionFilter | R2 pass; approved operational limits below |
| Staged upgrade, representative synthetic data, logical backup/restore and injected privilege-cleanup failure | tools/database/tests/postgres_integration.py, upgrade_recovery.py | R2 pass; production recovery deferred |
| Emergency missing, ended and cross-Team leadership assignment classification | V44; TeamCommandBoundaryIntegrationTest.emergencyRelationshipClassificationPreservesRestrictedBoundary; TeamAuthenticatedHttpDatabaseIntegrationTest.verifyEmergencyRejections | V44 comprehensive regression PASS |
| GitHub main protection and quality checks | Build and Analyze / quality-gate, GitHub Actions app 15368; protection PUT/GET and independent branch read | Configured and verified; actual PR enforcement pending |
| Positive HTTP membership end, planned leadership replacement, alternate designate/revoke, reactivation and direct emergency | TeamAuthenticatedHttpDatabaseIntegrationTest.verifyRemainingLeadershipCommands | Closeout PASS |

## Approved decisions (2026-11-26)

The user accepted the historical V31 failure as residual risk, without weakening invariants.
The original B5.2 failure occurred during fixture leadership assignment, not emergency removal.
The deferred predicate checks eligible membership, matching user, TEAM_LEAD role, active role,
nonrevocation and validity timestamps. No predicate snapshot identified the failing condition.
Repeated tests passed; this is not a verified root cause or fix. Preserve evidence and investigate
again if it recurs. Do not weaken constraints. The later B9A JDBC/Testcontainers startup failure
was a separate infrastructure event, not a repeat of the invariant failure.

The user approved SYSTEM_ADMIN-only bounded access-history metadata for an existing Team and
operational rejection logging limits. Metadata excludes free-text justification, original confidential
content and credentials. Unknown-Team denial rows remain in immutable storage but are not returned
by the Team-scoped endpoint. HTTP 401/403 logging uses fixed route categories and status only,
not actor, path, headers, tokens or bodies. It does not distinguish CSRF from other 403 responses
or constitute an immutable database ledger. Keep WARN logging enabled and secure the sink;
retention and deployment operations must be addressed in the deployment/operations phase.

The user approved the targeted V44 emergency relationship correction and accepted the existing
bounded Unicode name similarity heuristic (2026-11-26). Do not introduce comprehensive Unicode
confusable matching as a Phase 12 requirement. Exact normalized duplicate protection and explicit
review of supported similarity conflicts remain unchanged. No global SQLSTATE remapping.

The user approved deferring abrupt termination and production-environment recovery verification.
Clean separate-JVM exit and scheduled recovery against the same disposable database passed.
The user approved future resource scope, succession and external delivery dependencies:

| Carry-forward obligation | Future phase area |
| --- | --- |
| PROJECT_MANAGER managed-project relationships; project manager emergency succession | Project backend |
| VIEWER explicit resource grants | Resource authorization |
| AUDITOR authorized confidential resource scope | Audit authorization |
| Organization administrator succession | Administrative lifecycle |
| System administrator succession | Security administration |
| External email/notification transport | Notification infrastructure |
| Abrupt kill, infrastructure interruption and recovery | Deployment resilience |
| Production backup, restore, role/secret recovery, RTO/RPO and secure log retention | Production deployment/operations |

Default denial remains mandatory until each resource model is implemented. Carry all entries into
Progress Tracker at closeout. No new role grants. Notification worker remains opt-in.

## HTTP coverage reconciliation

R2 real-login scenario covers list/detail/create/update/review, role scope and enumeration, strict
JSON, stale versions, membership join, leadership appointment, missing relationship errors,
activation, history, readiness, notification dispatch/failures/receipts, ownership, alert acknowledgement
and resolution, confidential event and access history, account security disable and session revocation.
Closeout adds successful planned leadership replacement, membership termination, alternate designation
and revocation, reactivation, and direct emergency activating the first alternate, with database assertions.
The business boundary has additional positive/negative tests. This inventory is not an exhaustive
Cartesian product of every role, endpoint, input, concurrent action and pagination boundary.
Any missing mandatory business-rule test must be reconciled before final release acceptance.

## Exception and name-matching disposition

TeamServiceSupport maps 42501 to denial, 22023 to invalid input, 23505/23514/40001 to
business conflict, and unexpected SQLSTATE to sanitized unavailable. V41 classifies five missing
relationships. V44 replaces only the V37 emergency succession active-assignment lookup: after
existing administration, input and active-Team checks, a missing, ended or different-Team
assignment raises 23514. Existing security invariants, advisory lock, role checks, helper ownership,
ACL, search path, events, succession and no-successor handling remain unchanged. Unrelated P0001
and P0002 remain sanitized unavailable, not blanket conflicts. The targeted SQL and HTTP scenarios
cover 23514/409, 42501/403, invalid request 400, authentication 401, CSRF 403, and rejected-operation
state preservation. The HTTP scenario checks both administrators and all five other human roles
using real sessions, including the current TEAM_LEAD and a MEMBER. Existing service/database tests
cover scoped reads and role relationships. V44 comprehensive regression passed; test coverage is representative
and risk-based, not every possible role/input Cartesian combination. Do not infer coverage from
passing tests alone. Unicode similarity remains a bounded heuristic (approved), with exact
normalized duplicate rejection and supported accent/punctuation/selected look-alike checks.

## Database recovery procedure

Before deployment: confirm target and approved hashes, take and test an appropriate current backup,
review operator dry-run, apply with backup confirmation, validate, audit and inspect history. A synthetic
logical dump/restore is not a production cluster backup. Never restore synthetic data to a developer or
production database. If temporary privilege cleanup fails, use the generated recovery journal and
operator recovery instructions. Production recovery is deferred as approved above.

## GitHub protection

Configured main: required PR, zero reviewer approvals, required `quality-gate` from GitHub Actions
app 15368, strict up-to-date branch, enforce administrators, resolve conversations, prohibit force
pushes and deletion. Traditional branch protection is verified by protection GET and independent
branch read (`protected=true`, enforcement everyone). Empty ruleset list does not negate protection.
Actual PR required-check and merge eligibility evidence remains pending until B10.

## Final acceptance gate

Closeout and V44/R1 validation are recorded above. V44 disposable and comprehensive regression,
zero findings, local migration and live least-privilege verification passed. B10 inventory review reconciled 92 changed paths (7 tracked modifications, 85 new files),
91 covered by the validated manifest and one historical physical-design document. All 92
exported hashes matched. Final staging, PR checks and merge eligibility remain pending.
Coverage is representative; this inventory does not establish exhaustive requirements coverage.
Transfer all approved carry-forward obligations to the Progress Tracker. Verify the real required
PR check and merge eligibility; merge only with explicit authorization. Phase 12 remains open until
these conditions are satisfied and the authorized PR is closed successfully.
