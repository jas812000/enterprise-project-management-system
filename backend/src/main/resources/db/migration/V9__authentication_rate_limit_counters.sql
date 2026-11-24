-- B18HI-5B-2: PostgreSQL-backed source/policy counters. No HTTP enforcement in this migration.
-- Source identifiers must be HMAC-SHA256 digests; never store raw IP addresses here.
CREATE TABLE authentication_rate_limit_counters (
    source_key VARCHAR(64) NOT NULL,
    policy_code VARCHAR(48) NOT NULL,
    minute_window_start TIMESTAMPTZ NOT NULL,
    minute_count INTEGER NOT NULL DEFAULT 0,
    hour_window_start TIMESTAMPTZ NOT NULL,
    hour_count INTEGER NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_auth_rate_limit_counters PRIMARY KEY (source_key, policy_code),
    CONSTRAINT ck_auth_rate_limit_source_key CHECK (source_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_rate_limit_policy CHECK (policy_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    CONSTRAINT ck_auth_rate_limit_counts CHECK (minute_count >= 0 AND hour_count >= 0)
);
CREATE INDEX idx_auth_rate_limit_updated_at ON authentication_rate_limit_counters(updated_at);
GRANT SELECT, INSERT, UPDATE ON authentication_rate_limit_counters TO epms_app;
-- Retention/cleanup requires a separately approved privileged maintenance procedure.
