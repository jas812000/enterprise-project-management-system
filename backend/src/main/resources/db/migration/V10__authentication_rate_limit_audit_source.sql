-- B18HI-5B-4: separate source-address digest from account lookup digest.
-- Existing audit rows and the existing source_ip column remain unchanged.
ALTER TABLE authentication_security_events
    ADD COLUMN source_ip_hmac VARCHAR(64),
    ADD CONSTRAINT ck_auth_event_source_ip_hmac_length CHECK (
        source_ip_hmac IS NULL OR source_ip_hmac ~ '^[0-9a-f]{64}$');

CREATE INDEX idx_auth_events_source_hmac_time
    ON authentication_security_events(source_ip_hmac, occurred_at DESC)
    WHERE source_ip_hmac IS NOT NULL;
