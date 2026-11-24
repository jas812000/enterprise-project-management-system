-- EPMS authentication credentials
--
-- Password credentials are stored separately from user profiles.
-- Each user may have at most one password credential record.
--
-- PostgreSQL 18+ is required for native uuidv7().
--
-- Authentication and authorization are managed by application
-- security services. This migration establishes persistence only.

CREATE TABLE user_credentials (
    user_credential_id UUID PRIMARY KEY DEFAULT uuidv7(),

    user_id UUID NOT NULL,

    password_hash VARCHAR(500) NOT NULL,

    password_algorithm_code VARCHAR(50) NOT NULL,

    password_changed_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,

    failed_attempt_count INTEGER NOT NULL DEFAULT 0,

    last_failed_attempt_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_user_credentials_user_id
        UNIQUE (user_id),

    CONSTRAINT fk_user_credentials_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),

    CONSTRAINT ck_user_credentials_password_hash_nonblank
        CHECK (btrim(password_hash) <> ''),

    CONSTRAINT ck_user_credentials_algorithm_nonblank
        CHECK (btrim(password_algorithm_code) <> ''),

    CONSTRAINT ck_user_credentials_failed_attempt_count
        CHECK (failed_attempt_count >= 0),

    CONSTRAINT ck_user_credentials_version_nonnegative
        CHECK (version >= 0)
);

-- Authentication services require credential reads and updates.
-- Credential provisioning requires INSERT.
-- Credential records are retained rather than deleted.

GRANT SELECT, INSERT, UPDATE
    ON TABLE user_credentials
    TO epms_app;
