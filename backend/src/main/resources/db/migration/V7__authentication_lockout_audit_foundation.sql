-- B18H/B18I: progressive lockout state and append-only authentication audit foundation.
-- Existing user account active/archived flags remain separate from authentication lockouts.
ALTER TABLE user_credentials
    ADD COLUMN login_lockout_stage SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN login_stage_failures SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN login_locked_until TIMESTAMPTZ,
    ADD COLUMN login_admin_unlock_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_login_lockout_stage CHECK (login_lockout_stage BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_login_stage_failures CHECK (login_stage_failures BETWEEN 0 AND 5);

CREATE TABLE authentication_security_events (
    event_id UUID PRIMARY KEY DEFAULT uuidv7(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(48) NOT NULL,
    target_user_id UUID REFERENCES users(user_id),
    actor_user_id UUID REFERENCES users(user_id),
    account_lookup_hmac VARCHAR(128),
    source_ip INET,
    user_agent VARCHAR(300),
    correlation_id UUID,
    lockout_stage SMALLINT,
    stage_failure_count SMALLINT,
    reason VARCHAR(500),
    outcome VARCHAR(32) NOT NULL,
    CONSTRAINT ck_auth_event_type CHECK (event_type IN
      ('LOGIN_FAILED','LOGIN_SUCCEEDED','LOGIN_LOCKED','LOGIN_REJECTED_LOCKED',
       'ACCOUNT_UNLOCK','ACCOUNT_UNLOCK_REJECTED','RATE_LIMITED')),
    CONSTRAINT ck_auth_event_unlock_actor CHECK (
      event_type <> 'ACCOUNT_UNLOCK' OR actor_user_id IS NOT NULL),
    CONSTRAINT ck_auth_event_hmac_length CHECK (
      account_lookup_hmac IS NULL OR length(account_lookup_hmac) = 64)
);
CREATE INDEX idx_auth_events_user_time ON authentication_security_events(target_user_id, occurred_at DESC);
CREATE INDEX idx_auth_events_source_time ON authentication_security_events(source_ip, occurred_at DESC);
CREATE INDEX idx_auth_events_lookup_time ON authentication_security_events(account_lookup_hmac, occurred_at DESC);
CREATE INDEX idx_auth_events_actor_time ON authentication_security_events(actor_user_id, occurred_at DESC);
GRANT SELECT, INSERT ON authentication_security_events TO epms_app;
