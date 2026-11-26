# Team profile command boundary (B6.1)

V34 adds creation and optimistic-version profile modification to the existing restricted RBAC JDBC boundary.
Only current HUMAN SYSTEM_ADMIN or ORG_ADMIN actors with OPERATIONAL_ROLE_ASSIGN may execute these operations, matching the existing Team administration contract. The trusted authenticated application service must derive the actor in B6.2. No new runtime table privileges or owner elevations are required.

Creation starts in PENDING_SETUP. Profile updates never change lifecycle, membership or leadership and cannot modify archived Teams. Creation and updates share the existing transaction advisory lock. Reasons are mandatory. Existing append-only operation events retain before/after profile data and similar candidate IDs. Exact normalized duplicate names are always rejected, including archived names. Punctuation/spacing equivalence is a conservative first-pass similarity check; broader fuzzy/confusable detection, warning presentation and explicit review of candidates remain required before exposing creation/update through application services or API. No new permission mappings have been introduced.

V34 is a routine migration. Use the documented migration utility dry run and apply after backup confirmation. Tests use disposable PostgreSQL; installation does not migrate the developer database.
