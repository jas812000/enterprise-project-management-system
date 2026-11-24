-- B5: revalidate token after session lock to close concurrent revocation race.
-- Preserve the established session-before-token lock order.
CREATE OR REPLACE FUNCTION public.epms_auth_validate_access(p_access bytea)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    v_session uuid;
    v_user uuid;
    v_now timestamptz;
    v_version timestamptz;
    v_current timestamptz;
BEGIN
    IF p_access IS NULL OR octet_length(p_access) <> 32 THEN RETURN NULL; END IF;
    v_now := clock_timestamp();
    SELECT t.session_id INTO v_session FROM public.user_access_tokens t
    WHERE t.token_hash=p_access AND t.token_type='ACCESS'
      AND t.revoked_at IS NULL AND t.consumed_at IS NULL AND t.expires_at>v_now;
    IF v_session IS NULL THEN RETURN NULL; END IF;
    SELECT s.user_id,s.credential_version INTO v_user,v_version
    FROM public.user_auth_sessions s WHERE s.session_id=v_session
      AND s.revoked_at IS NULL AND s.idle_expires_at>v_now
      AND s.absolute_expires_at>v_now FOR UPDATE;
    IF v_user IS NULL THEN RETURN NULL; END IF;
    -- The initial token lookup is only a locator. Recheck under the session lock.
    -- Revocation and rotation also lock the session before token rows.
    SELECT clock_timestamp() INTO v_now;
    PERFORM 1 FROM public.user_access_tokens t
    WHERE t.session_id=v_session AND t.token_hash=p_access AND t.token_type='ACCESS'
      AND t.revoked_at IS NULL AND t.consumed_at IS NULL AND t.expires_at>v_now
    FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT c.password_changed_at INTO v_current FROM public.users u
    JOIN public.user_credentials c ON c.user_id=u.user_id
    WHERE u.user_id=v_user AND u.is_active AND NOT u.is_archived
      AND c.password_algorithm_code='ARGON2ID' AND c.screening_status='VERIFIED'
      AND NOT c.must_change_password AND NOT c.login_admin_unlock_required
      AND (c.login_locked_until IS NULL OR c.login_locked_until<=v_now);
    IF v_current IS DISTINCT FROM v_version THEN RETURN NULL; END IF;
    UPDATE public.user_auth_sessions SET last_activity_at=v_now,
      idle_expires_at=LEAST(v_now+interval '30 minutes',absolute_expires_at)
    WHERE session_id=v_session;
    RETURN v_user;
END $$;
REVOKE ALL ON FUNCTION public.epms_auth_validate_access(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.epms_auth_validate_access(bytea) TO epms_app;
ALTER FUNCTION public.epms_auth_validate_access(bytea) OWNER TO epms_auth_function_owner;
-- V23 revoked direct table reads from epms_app; keep that boundary unchanged.
