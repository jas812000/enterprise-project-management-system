-- B2D-4C: mandatory password-change security event, no modifications to applied migrations.
ALTER TABLE public.authentication_security_events
    DROP CONSTRAINT ck_auth_event_type;
ALTER TABLE public.authentication_security_events
    ADD CONSTRAINT ck_auth_event_type CHECK (event_type IN
      ('LOGIN_FAILED','LOGIN_SUCCEEDED','LOGIN_LOCKED','LOGIN_REJECTED_LOCKED',
       'ACCOUNT_UNLOCK','ACCOUNT_UNLOCK_REJECTED','RATE_LIMITED',
       'TOKEN_ISSUED','TOKEN_RENEWED','TOKEN_REVOKED','TOKEN_REPLAY_DETECTED',
       'SESSION_REVOKED','SESSION_EXPIRED','PASSWORD_CHANGED'));
