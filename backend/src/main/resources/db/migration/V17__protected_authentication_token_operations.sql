-- Session-B2B: protected token state transitions; existing V16 remains immutable.
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='epms_auth_function_owner'
   AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolinherit)
 THEN RAISE EXCEPTION 'restricted epms_auth_function_owner must be provisioned before V17'; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO epms_auth_function_owner;
GRANT SELECT ON public.users, public.user_credentials TO epms_auth_function_owner;
GRANT SELECT, INSERT, UPDATE ON public.user_auth_sessions, public.user_access_tokens TO epms_auth_function_owner;
GRANT INSERT ON public.authentication_security_events TO epms_auth_function_owner;

-- Issuance requires prior successful application-level credential authentication.
-- The caller must never expose this function as a user-selected user_id operation.
CREATE FUNCTION public.epms_auth_issue(p_user uuid, p_access bytea, p_renewal bytea)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s uuid; v_now timestamptz := clock_timestamp(); v_changed timestamptz;
BEGIN
 IF p_user IS NULL OR octet_length(p_access) <> 32 OR octet_length(p_renewal) <> 32 OR p_access=p_renewal
 THEN RAISE EXCEPTION 'invalid token issuance request'; END IF;
 SELECT c.password_changed_at INTO v_changed
 FROM public.users u JOIN public.user_credentials c ON c.user_id=u.user_id
 WHERE u.user_id=p_user AND u.is_active AND NOT u.is_archived
 AND c.password_algorithm_code='ARGON2ID' AND c.screening_status='VERIFIED'
 AND NOT c.must_change_password AND NOT c.login_admin_unlock_required
 AND (c.login_locked_until IS NULL OR c.login_locked_until<=v_now)
 FOR UPDATE OF c;
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

-- Status rather than exception for replay: session revocation and audit MUST COMMIT.
-- A consumed token is never authorized for rotation.
CREATE FUNCTION public.epms_auth_rotate(p_presented bytea,p_access bytea,p_renewal bytea)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_token uuid; v_session uuid; v_user uuid; v_consumed timestamptz;
 v_expires timestamptz; v_revoked timestamptz; v_now timestamptz := clock_timestamp();
 v_last timestamptz; v_idle timestamptz; v_absolute timestamptz; v_session_revoked timestamptz;
 v_version timestamptz; v_current timestamptz; v_replacement uuid; v_renewal uuid;
BEGIN
 IF octet_length(p_presented)<>32 OR octet_length(p_access)<>32 OR octet_length(p_renewal)<>32
 OR p_presented=p_access OR p_presented=p_renewal OR p_access=p_renewal
 THEN RETURN 'REJECTED'; END IF;
 -- Lock session first, then token: consistent ordering for concurrent renewals.
 SELECT t.session_id INTO v_session FROM public.user_access_tokens t
 WHERE t.token_hash=p_presented AND t.token_type='RENEWAL';
 IF v_session IS NULL THEN RETURN 'REJECTED'; END IF;
 SELECT s.user_id,s.last_activity_at,s.idle_expires_at,s.absolute_expires_at,s.revoked_at,s.credential_version
 INTO v_user,v_last,v_idle,v_absolute,v_session_revoked,v_version
 FROM public.user_auth_sessions s WHERE s.session_id=v_session FOR UPDATE;
 SELECT t.token_id,t.consumed_at,t.expires_at,t.revoked_at
 INTO v_token,v_consumed,v_expires,v_revoked
 FROM public.user_access_tokens t WHERE t.token_hash=p_presented AND t.token_type='RENEWAL' FOR UPDATE;
 IF v_session_revoked IS NOT NULL THEN RETURN 'REJECTED'; END IF;
 IF v_consumed IS NOT NULL THEN
   UPDATE public.user_auth_sessions SET revoked_at=v_now,revocation_reason='RENEWAL_REPLAY'
   WHERE session_id=v_session AND revoked_at IS NULL;
   UPDATE public.user_access_tokens SET revoked_at=v_now WHERE session_id=v_session AND revoked_at IS NULL;
   INSERT INTO public.authentication_security_events(event_type,target_user_id,outcome,reason)
   VALUES('TOKEN_REPLAY_DETECTED',v_user,'REJECTED','RENEWAL_REPLAY');
   RETURN 'REPLAY_REVOKED';
 END IF;
 IF v_revoked IS NOT NULL OR v_expires<=v_now OR v_idle<=v_now OR v_absolute<=v_now
 THEN RETURN 'EXPIRED'; END IF;
 SELECT c.password_changed_at INTO v_current FROM public.users u
 JOIN public.user_credentials c ON c.user_id=u.user_id
 WHERE u.user_id=v_user AND u.is_active AND NOT u.is_archived
 AND c.password_algorithm_code='ARGON2ID' AND c.screening_status='VERIFIED'
 AND NOT c.must_change_password AND NOT c.login_admin_unlock_required
 AND (c.login_locked_until IS NULL OR c.login_locked_until<=v_now);
 IF v_current IS DISTINCT FROM v_version THEN RETURN 'REJECTED'; END IF;
 -- Partial unique index permits only one unconsumed renewal credential.
 UPDATE public.user_access_tokens SET consumed_at=v_now WHERE token_id=v_token;
 INSERT INTO public.user_access_tokens(session_id,token_type,token_hash,issued_at,expires_at)
 VALUES(v_session,'ACCESS',p_access,v_now,LEAST(v_now+interval '15 minutes',v_absolute));
 INSERT INTO public.user_access_tokens(session_id,token_type,token_hash,issued_at,expires_at)
 VALUES(v_session,'RENEWAL',p_renewal,v_now,v_absolute) RETURNING token_id INTO v_replacement;
 UPDATE public.user_access_tokens SET replaced_by_token_id=v_replacement WHERE token_id=v_token;
 UPDATE public.user_auth_sessions SET last_activity_at=v_now,
 idle_expires_at=LEAST(v_now+interval '30 minutes',v_absolute)
 WHERE session_id=v_session;
 INSERT INTO public.authentication_security_events(event_type,target_user_id,outcome,reason)
 VALUES('TOKEN_RENEWED',v_user,'ACCEPTED','RENEWAL_ROTATED');
 RETURN 'ROTATED';
END $$;

CREATE FUNCTION public.epms_auth_revoke(p_session uuid,p_reason text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_user uuid; v_now timestamptz:=clock_timestamp();
BEGIN
 IF p_session IS NULL OR p_reason NOT IN ('LOGOUT','LOGOUT_ALL','PASSWORD_CHANGED','ADMIN_REVOKED','SECURITY_EVENT')
 THEN RAISE EXCEPTION 'invalid revocation request'; END IF;
 SELECT user_id INTO v_user FROM public.user_auth_sessions WHERE session_id=p_session FOR UPDATE;
 IF v_user IS NULL THEN RETURN false; END IF;
 UPDATE public.user_auth_sessions SET revoked_at=v_now,revocation_reason=p_reason
 WHERE session_id=p_session AND revoked_at IS NULL;
 IF NOT FOUND THEN RETURN false; END IF;
 UPDATE public.user_access_tokens SET revoked_at=v_now WHERE session_id=p_session AND revoked_at IS NULL;
 INSERT INTO public.authentication_security_events(event_type,target_user_id,outcome,reason)
 VALUES('SESSION_REVOKED',v_user,'ACCEPTED',p_reason);
 RETURN true;
END $$;

REVOKE ALL ON public.user_auth_sessions,public.user_access_tokens FROM epms_app;
-- Read access remains necessary for future token validation; no direct mutations.
GRANT SELECT ON public.user_auth_sessions,public.user_access_tokens TO epms_app;
REVOKE ALL ON FUNCTION public.epms_auth_issue(uuid,bytea,bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_auth_rotate(bytea,bytea,bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_auth_revoke(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.epms_auth_issue(uuid,bytea,bytea) TO epms_app;
GRANT EXECUTE ON FUNCTION public.epms_auth_rotate(bytea,bytea,bytea) TO epms_app;
GRANT EXECUTE ON FUNCTION public.epms_auth_revoke(uuid,text) TO epms_app;
ALTER FUNCTION public.epms_auth_issue(uuid,bytea,bytea) OWNER TO epms_auth_function_owner;
ALTER FUNCTION public.epms_auth_rotate(bytea,bytea,bytea) OWNER TO epms_auth_function_owner;
ALTER FUNCTION public.epms_auth_revoke(uuid,text) OWNER TO epms_auth_function_owner;
