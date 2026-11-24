-- EPMS Phase 11: persistence foundation only. No plaintext or raw screening digests.
CREATE TABLE password_screening_outages (
    outage_id UUID PRIMARY KEY DEFAULT uuidv7(),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failure_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recovered_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'SUSPECTED',
    failure_category VARCHAR(80) NOT NULL,
    CONSTRAINT ck_screening_outage_status
        CHECK (status IN ('SUSPECTED','CONFIRMED','RECOVERED'))
);

ALTER TABLE user_credentials
    ADD COLUMN screening_status VARCHAR(32) NOT NULL DEFAULT 'VERIFIED',
    ADD COLUMN provisional_issued_at TIMESTAMPTZ,
    ADD COLUMN provisional_expires_at TIMESTAMPTZ,
    ADD COLUMN screening_digest_ciphertext BYTEA,
    ADD COLUMN screening_digest_key_id VARCHAR(100),
    ADD COLUMN screening_outage_id UUID,
    ADD CONSTRAINT ck_credential_screening_status
        CHECK (screening_status IN ('VERIFIED','PENDING_VERIFICATION','CHANGE_REQUIRED','EXPIRED')),
    ADD CONSTRAINT ck_credential_provisional_window
        CHECK (screening_status <> 'PENDING_VERIFICATION' OR
            (provisional_issued_at IS NOT NULL AND provisional_expires_at IS NOT NULL AND
             provisional_expires_at = provisional_issued_at + INTERVAL '12 hours')),
    ADD CONSTRAINT ck_credential_screening_ciphertext
        CHECK ((screening_digest_ciphertext IS NULL) = (screening_digest_key_id IS NULL)),
    ADD CONSTRAINT fk_credential_screening_outage
        FOREIGN KEY (screening_outage_id) REFERENCES password_screening_outages(outage_id);

CREATE TABLE password_security_events (
    event_id UUID PRIMARY KEY DEFAULT uuidv7(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id UUID REFERENCES users(user_id),
    outage_id UUID REFERENCES password_screening_outages(outage_id),
    event_type VARCHAR(64) NOT NULL,
    event_details VARCHAR(500),
    CONSTRAINT ck_password_security_event_type CHECK (event_type IN
      ('SCREENING_FAILURE','SCREENING_RECOVERED','PROVISIONAL_ISSUED',
       'PROVISIONAL_EXPIRED','PASSWORD_VERIFIED','PASSWORD_COMPROMISED',
       'LOGIN_FAILED','LOGIN_RATE_LIMITED','MANAGER_NOTIFIED',
       'SECURITY_ADMIN_NOTIFIED','PASSWORD_RESET'))
);
CREATE INDEX idx_password_security_events_user_time
    ON password_security_events(user_id, occurred_at DESC);
CREATE INDEX idx_password_security_events_outage
    ON password_security_events(outage_id);
CREATE INDEX idx_screening_outages_status
    ON password_screening_outages(status);
CREATE INDEX idx_credentials_pending_expiration
    ON user_credentials(provisional_expires_at)
    WHERE screening_status = 'PENDING_VERIFICATION';
GRANT SELECT, INSERT, UPDATE ON password_screening_outages TO epms_app;
GRANT SELECT, INSERT ON password_security_events TO epms_app;
