-- B2D: synchronize issuance with application credential mutation using transaction-scoped per-user lock.
-- All credential invalidation writers must participate in this contract.
-- Session-B2D: credential eligibility read uses SELECT-only privilege.
-- This migration deliberately avoids widening epms_auth_function_owner permissions.
-- Concurrency with credential mutation remains a separate acceptance requirement;
-- application-level issuance must not be enabled until that race is resolved.
CREATE OR REPLACE FUNCTION public.epms_auth_issue(p_user uuid, p_access bytea, p_renewal bytea)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s uuid; v_now timestamptz := clock_timestamp(); v_changed timestamptz;
BEGIN
 IF p_user IS NULL OR octet_length(p_access) <> 32 OR octet_length(p_renewal) <> 32 OR p_access=p_renewal
 THEN RAISE EXCEPTION 'invalid token issuance request'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_user::text, 82302026));
 SELECT c.password_changed_at INTO v_changed
 FROM public.users u JOIN public.user_credentials c ON c.user_id=u.user_id
 WHERE u.user_id=p_user AND u.is_active AND NOT u.is_archived
 AND c.password_algorithm_code='ARGON2ID' AND c.screening_status='VERIFIED'
 AND NOT c.must_change_password AND NOT c.login_admin_unlock_required
 AND (c.login_locked_until IS NULL OR c.login_locked_until<=v_now)
;
 IF v_changed IS NULL THEN RAISE EXCEPTION 'account not eligible for token issuance'; END IF;
 INSERT INTO public.user_auth_sessions(user_id,created_at,last_activity_at,idle_expires_at,absolute_expires_at,credential_version)
 VALUES(p_user,v_now,v_now,v_now+interval '30 minutes',v_now+interval '7 days',v_changed)
 RETURNING session_id INTO s;
 INSERT INTO public.user_access_tokens(session_id,token_type,token_hash,issued_at,expires_at)
 VALUES(s,'ACCESS',p_access,v_now,v_now+interval '15 minutes'),
       (s,'RENEWAL',p_renewal,v_now,v_now+interval '7 days');
 INSERT INTO public.authentication_security_events(event_type,target_user_id,outcome,reason)
 VALUES('TOKEN_ISSUED',p_user,'ACCEPTED','SESSION_ISSUED');
 RETURN s;
END $$;

REVOKE ALL ON FUNCTION public.epms_auth_issue(uuid,bytea,bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.epms_auth_issue(uuid,bytea,bytea) TO epms_app;
ALTER FUNCTION public.epms_auth_issue(uuid,bytea,bytea) OWNER TO epms_auth_function_owner;
