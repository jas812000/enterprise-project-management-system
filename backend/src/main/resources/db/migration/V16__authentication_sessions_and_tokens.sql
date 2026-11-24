-- Session-B2A: additive PostgreSQL-backed authentication session foundation.
-- Application token issuance, rotation, and HTTP cutover are later batches.
CREATE TABLE user_auth_sessions (
    session_id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    credential_version TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(64),
    CONSTRAINT ck_auth_session_deadlines CHECK (
        last_activity_at >= created_at AND
        idle_expires_at > last_activity_at AND
        absolute_expires_at > created_at AND
        absolute_expires_at <= created_at + INTERVAL '7 days'),
    CONSTRAINT ck_auth_session_revoke CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL) OR
        (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL))
);
CREATE INDEX idx_auth_sessions_user ON user_auth_sessions(user_id, created_at DESC);
CREATE INDEX idx_auth_sessions_active ON user_auth_sessions(user_id, absolute_expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE user_access_tokens (
    token_id UUID PRIMARY KEY DEFAULT uuidv7(),
    session_id UUID NOT NULL REFERENCES user_auth_sessions(session_id),
    token_type VARCHAR(12) NOT NULL,
    token_hash BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by_token_id UUID REFERENCES user_access_tokens(token_id),
    CONSTRAINT ck_auth_token_type CHECK (token_type IN ('ACCESS','RENEWAL')),
    CONSTRAINT ck_auth_token_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT uq_auth_token_hash UNIQUE(token_hash),
    CONSTRAINT ck_auth_token_deadlines CHECK (expires_at > issued_at),
    CONSTRAINT ck_auth_token_consumption CHECK (
        token_type = 'RENEWAL' OR (consumed_at IS NULL AND replaced_by_token_id IS NULL)),
    CONSTRAINT ck_auth_token_replacement CHECK (
        replaced_by_token_id IS NULL OR consumed_at IS NOT NULL)
);
CREATE INDEX idx_auth_tokens_session ON user_access_tokens(session_id, issued_at DESC);
CREATE UNIQUE INDEX uq_auth_active_renewal_per_session
    ON user_access_tokens(session_id)
    WHERE token_type = 'RENEWAL' AND consumed_at IS NULL AND revoked_at IS NULL;

ALTER TABLE authentication_security_events
    DROP CONSTRAINT ck_auth_event_type;
ALTER TABLE authentication_security_events
    ADD CONSTRAINT ck_auth_event_type CHECK (event_type IN
      ('LOGIN_FAILED','LOGIN_SUCCEEDED','LOGIN_LOCKED','LOGIN_REJECTED_LOCKED',
       'ACCOUNT_UNLOCK','ACCOUNT_UNLOCK_REJECTED','RATE_LIMITED',
       'TOKEN_ISSUED','TOKEN_RENEWED','TOKEN_REVOKED','TOKEN_REPLAY_DETECTED',
       'SESSION_REVOKED','SESSION_EXPIRED'));

-- Direct DML is provisionally limited to epms_app. Revisit with atomic
-- security-definer routines in B2B before enabling token issuance in HTTP.
REVOKE ALL ON user_auth_sessions, user_access_tokens FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON user_auth_sessions, user_access_tokens TO epms_app;
