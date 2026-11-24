-- B18C: short-lived PostgreSQL claims for deferred password screening.
ALTER TABLE user_credentials
    ADD COLUMN screening_claim_token UUID,
    ADD COLUMN screening_claim_expires_at TIMESTAMPTZ;
ALTER TABLE user_credentials
    ADD CONSTRAINT ck_screening_claim_pair CHECK
        ((screening_claim_token IS NULL) = (screening_claim_expires_at IS NULL));
CREATE INDEX ix_credentials_pending_claim
    ON user_credentials (screening_claim_expires_at, next_screening_retry_at)
    WHERE screening_status = 'PENDING_VERIFICATION';
