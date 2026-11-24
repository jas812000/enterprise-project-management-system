-- B4: the application accesses protected token/session data only through restricted functions.
-- Function owner retains explicit table privileges from V17; do not revoke those grants.
REVOKE SELECT ON TABLE public.user_access_tokens, public.user_auth_sessions FROM epms_app;
