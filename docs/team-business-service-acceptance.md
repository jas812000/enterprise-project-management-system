# B6.4 business service acceptance — conflict review and Spring integration

V36 binds a similarity override to the database-generated conflict snapshot returned by
`TeamProfileService.reviewConflicts(team, name)`. The snapshot contains the authenticated
actor, organization, proposed name, target Team and target version, and ordered conflict
identifiers, names and versions. Present the conflicts to the authorized administrator and
return the unchanged snapshot with an explicit override and mandatory reason. Recompute
under the existing Team advisory transaction lock; reject missing, stale or mismatched
confirmation. A snapshot is not a secret or proof of human interaction; the eventual REST/UI
workflow must enforce explicit review. Exact duplicates remain prohibited. Legacy boolean-only
override calls cannot bypass the new check. Store the reviewed snapshot on new immutable
audit events; historical records remain unchanged.

The Spring integration test uses the existing disposable database fixture and actual restricted
RBAC datasource, not the live development database. Its primary fixture connection is privileged
for test setup; it is not a substitute for the separate opt-in live least-privilege datasource audit.

Remaining acceptance work: explicit non-administrator Team-profile visibility policy and tests
(the existing administrative-only boundary remains fail-closed); Unicode confusable and
multilingual similarity scope; remaining SQLSTATE classification of legacy P0001 business failures (no unsafe message
matching); V36 classifies the shared admin check, account lifecycle entry point and transition; full service operation and concurrency acceptance.
Do not expose endpoints until these requirements are reconciled. The intermittent V31 failure
remains an unresolved release blocker. No existing migration may be edited.

## B6.5 — Approved scoped profile access (V37)

Current active HUMAN SYSTEM_ADMIN and ORG_ADMIN roles authorize organization Team profile reads, including archived profiles. Read authority is independent of the existing administrative mutation permission. MEMBER and TEAM_LEAD roles authorize current membership-scoped profiles; TEAM_LEAD also has current effective leadership scope. Non-admin archived profiles and confidential historical records are excluded. A dormant alternate designation is not a role or leadership grant. Accounts without a current supported role are denied. A scoped role with no accessible records receives an empty list; a specific inaccessible or nonexistent profile returns the same not-found result. Filtering precedes ordering and pagination. Roles are additive within their approved scopes.

PROJECT_MANAGER project assignment scope, VIEWER explicit resource grants and AUDITOR audit scope are denied until their corresponding relationship/grant models exist. An independently held MEMBER role still authorizes its own membership scope. No additional mutation permission or database table grant is introduced. The trusted backend projections remain internal, not API authorization surfaces.

V37 adds limited accent and common Greek/Cyrillic visual warning equivalence while preserving existing name rules. It is not exhaustive Unicode confusable detection, transliteration, or multilingual phonetic matching. Further language-specific rules and a maintained Unicode confusable data strategy remain acceptance decisions. Review snapshots are still verified under the shared transaction lock.

Selected legacy business-operation exceptions now have explicit SQLSTATE values. Unclassified trigger/invariant failures and unexpected database errors remain sanitized generic failures; the intermittent V31 deferred invariant failure is not claimed resolved.

### Verification matrix

- Profile visibility: Spring service test covers administrative, current membership, former membership, scoped absence, pagination, no-role, expired role, inactive account, and deferred project/viewer/auditor scopes.
- Operations: Spring-managed membership, permanent and planned leadership, alternate designation/revocation, emergency succession, scoped permission, activation and emergency account lifecycle plus planned archival. Rejected planned removal without replacement must leave the assignment available for valid replacement.
- Names: existing PostgreSQL command test covers accent/visual substitutions and concurrent reviewed conflicting creations; exact duplicates and stale review tests are retained.
- Regression: existing repository, PostgreSQL, RBAC, activation stress, Maven and scanner suites are retained. Remaining authorization/rollback combinations, live datasource audit, production migration behavior and V31 root cause still require acceptance evidence.

## B9A investigation and scope reconciliation

PROJECT_MANAGER project-to-Team relationships, VIEWER explicit resource grants, and AUDITOR explicit
scope remain future dependencies. The current default-denial policy must not be broadened until those
models are approved and implemented. The HTTP scenario tests both existing and nonexistent Team IDs.
Name matching uses the approved NFKC and limited visual key, bounded edit distance and English phonetic
heuristic. It is not a complete Unicode confusable or multilingual phonetic algorithm; warning matches
require administrative review and exact-name rejection remains separate. No new dependency is introduced.

The original V31 failure is unresolved. Preserve the production invariant and the existing original
and isolated diagnostic runs. V41 changes only explicit missing relationship lookups after admin
checks; it is not a correction for V31. The additional test verifies that rejected missing-target
appointments leave an active Team and its role grant intact. Successful runs do not prove root cause.

## B9C residual acceptance disposition

V41 is preserved. B9A activation stress pass 2 failed during Testcontainers PostgreSQL JDBC startup
(EOF during SSL negotiation), before business-rule execution; B9B completed all twelve stress runs.
This is distinct from the earlier V31 scoped-RBAC invariant failure, whose cause is unresolved.
Existing diagnostic runs retain the original predicates and capture eligibility and role validity.
Do not weaken the invariant or assert a clock-skew root cause without evidence.

Project manager access remains denied until project-to-Team relationships exist; viewer access remains
denied until explicit resource grants exist; auditor access remains denied until authorized audit
scope exists. Future integration must test both granted and denied scope and immediate revocation.
Name matching remains a bounded heuristic, not comprehensive Unicode confusable detection. Additional
SQLSTATE classification must be based on explicit business predicates, not blanket P0001/P0002 mapping.
The HTTP scenario adds current-version update, name review, history and missing relationship tests.

## B9D corrective and release acceptance

B9C's profile update returned 200; its subsequent name review omitted the existing Team ID and
returned the intended duplicate-name conflict. The acceptance request now supplies the Team ID.
The account lifecycle test timed out during open notification dispatch. V42 changes only the V36
account lifecycle user lock from FOR UPDATE to FOR NO KEY UPDATE; security authorization, advisory
locks, revocation, membership closure and immutable history remain unchanged. The test retains its
original timeout and captures safe wait metadata if blocking persists. Runtime acceptance is required.
The earlier V31 predicate failure remains a separate unresolved release blocker.
