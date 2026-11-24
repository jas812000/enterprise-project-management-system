-- B18.32h-7B.32: one-time CLI bootstrap database authority.
-- The only caller is the separately provisioned epms_bootstrap_runtime login.
-- Password verification and Argon2id hashing MUST occur in the CLI before this call.
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='epms_bootstrap_owner'
   AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole)
 THEN RAISE EXCEPTION 'restricted epms_bootstrap_owner must be provisioned'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='epms_bootstrap_runtime'
   AND rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolinherit)
 THEN RAISE EXCEPTION 'restricted epms_bootstrap_runtime login must be provisioned'; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO epms_bootstrap_owner, epms_bootstrap_runtime;
GRANT SELECT ON public.organizations, public.account_types, public.users,
 public.security_roles, public.security_bootstrap_state,
 public.security_user_roles TO epms_bootstrap_owner;
GRANT INSERT ON public.users, public.user_credentials,
 public.security_user_roles, public.security_role_assignment_history,
 public.security_bootstrap_audit TO epms_bootstrap_owner;
GRANT UPDATE ON public.security_bootstrap_state TO epms_bootstrap_owner;

CREATE FUNCTION public.epms_bootstrap_initial_admin(
 p_first_name text, p_last_name text, p_email text, p_argon2id_hash text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_account uuid; v_role uuid; v_user uuid; v_assignment uuid;
BEGIN
 PERFORM pg_catalog.pg_advisory_xact_lock(1832,12);
 IF NOT EXISTS (SELECT 1 FROM public.security_bootstrap_state
   WHERE singleton_id=1 AND completed_at IS NULL AND administrator_user_id IS NULL)
 THEN RAISE EXCEPTION 'initial administrator bootstrap is unavailable'; END IF;
 IF EXISTS (SELECT 1 FROM public.security_user_roles a
   JOIN public.security_roles r ON r.role_id=a.role_id
   WHERE r.code='SYSTEM_ADMIN' AND a.revoked_at IS NULL)
 THEN RAISE EXCEPTION 'SYSTEM_ADMIN assignment already exists'; END IF;
 IF p_first_name IS NULL OR length(btrim(p_first_name)) NOT BETWEEN 1 AND 100
   OR p_last_name IS NULL OR length(btrim(p_last_name)) NOT BETWEEN 1 AND 100
   OR p_email IS NULL OR length(btrim(p_email)) NOT BETWEEN 3 AND 320
   OR p_email !~ '^[^[:space:]@]+@[^[:space:]@]+$'
   OR p_argon2id_hash IS NULL OR length(p_argon2id_hash) NOT BETWEEN 50 AND 500
   OR p_argon2id_hash NOT LIKE '$argon2id$%'
 THEN RAISE EXCEPTION 'invalid bootstrap input'; END IF;
 SELECT organization_id INTO v_org FROM public.organizations WHERE is_active;
 IF v_org IS NULL THEN RAISE EXCEPTION 'active organization required'; END IF;
 SELECT account_type_id INTO v_account FROM public.account_types
   WHERE code='HUMAN' AND is_active;
 IF v_account IS NULL THEN RAISE EXCEPTION 'active HUMAN account type required'; END IF;
 SELECT role_id INTO v_role FROM public.security_roles
   WHERE code='SYSTEM_ADMIN' AND is_active;
 IF v_role IS NULL THEN RAISE EXCEPTION 'active SYSTEM_ADMIN role required'; END IF;
 INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
 VALUES(v_org,v_account,btrim(p_first_name),btrim(p_last_name),btrim(p_email))
 RETURNING user_id INTO v_user;
 INSERT INTO public.user_credentials(user_id,password_hash,password_algorithm_code,
 must_change_password) VALUES(v_user,p_argon2id_hash,'ARGON2ID',false);
 INSERT INTO public.security_user_roles(user_id,role_id,granted_by_user_id)
 VALUES(v_user,v_role,v_user) RETURNING assignment_id INTO v_assignment;
 INSERT INTO public.security_role_assignment_history
 (assignment_id,target_user_id,actor_user_id,action,reason)
 VALUES(v_assignment,v_user,v_user,'GRANT','Initial SYSTEM_ADMIN CLI bootstrap');
 UPDATE public.security_bootstrap_state SET completed_at=clock_timestamp(),
 administrator_user_id=v_user WHERE singleton_id=1 AND completed_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'bootstrap state transition failed'; END IF;
 INSERT INTO public.security_bootstrap_audit
 (administrator_user_id,assignment_id,event_type,provenance)
 VALUES(v_user,v_assignment,'INITIAL_SYSTEM_ADMIN_CREATED','EXPLICIT_ADMIN_CLI');
 RETURN v_user;
END $$;
REVOKE ALL ON FUNCTION public.epms_bootstrap_initial_admin(text,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.epms_bootstrap_initial_admin(text,text,text,text)
 TO epms_bootstrap_runtime;
ALTER FUNCTION public.epms_bootstrap_initial_admin(text,text,text,text)
 OWNER TO epms_bootstrap_owner;
