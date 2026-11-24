-- Explicit, individually assigned account-unlock permission; no account-type defaults.
CREATE TABLE account_unlock_grants (
    user_id UUID PRIMARY KEY REFERENCES users(user_id),
    granted_by UUID REFERENCES users(user_id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_unlock_grant_revocation CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE INDEX idx_unlock_grants_active ON account_unlock_grants(user_id) WHERE revoked_at IS NULL;
GRANT SELECT ON account_unlock_grants TO epms_app;
-- Grants must be provisioned through a separately authorized database-administration procedure.
-- Never grant ACCOUNT_UNLOCK automatically based on HUMAN account type.
