-- Persistent retry eligibility for deferred password screening.
ALTER TABLE user_credentials
    ADD COLUMN screening_retry_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_screening_retry_at TIMESTAMPTZ;
ALTER TABLE user_credentials
    ADD CONSTRAINT ck_screening_retry_attempts CHECK (screening_retry_attempts BETWEEN 0 AND 31);
UPDATE user_credentials SET next_screening_retry_at = COALESCE(provisional_issued_at, CURRENT_TIMESTAMP)
    WHERE screening_status = 'PENDING_VERIFICATION';
CREATE INDEX ix_credentials_pending_screening_retry
    ON user_credentials (next_screening_retry_at, provisional_issued_at, user_credential_id)
    WHERE screening_status = 'PENDING_VERIFICATION';
