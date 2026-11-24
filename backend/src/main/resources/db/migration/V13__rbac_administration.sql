-- B18.32h-7A.2: restricted, transactional RBAC administration.
-- Production prerequisite: migrator must be authorized to SET ROLE to
-- epms_rbac_function_owner for ALTER FUNCTION OWNER; runtime is never owner.
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='epms_rbac_function_owner'
   AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole)
 THEN RAISE EXCEPTION 'restricted epms_rbac_function_owner must be provisioned'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='epms_rbac_runtime'
   AND rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolinherit)
 THEN RAISE EXCEPTION 'restricted epms_rbac_runtime LOGIN must be provisioned'; END IF;
END $$;
-- Definer needs only these rights. Do not grant direct table access to runtime.
GRANT USAGE ON SCHEMA public TO epms_rbac_function_owner, epms_rbac_runtime;
GRANT SELECT ON public.users, public.account_types, public.security_roles,
 public.security_permissions, public.security_role_permissions,
 public.security_user_roles TO epms_rbac_function_owner;
GRANT INSERT, UPDATE ON public.security_user_roles TO epms_rbac_function_owner;
GRANT INSERT ON public.security_role_assignment_history TO epms_rbac_function_owner;
CREATE FUNCTION public.epms_rbac_assign_role(p_actor uuid,p_target uuid,p_role text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_role uuid; v_assignment uuid; v_priv text;
BEGIN
 IF p_actor IS NULL OR p_target IS NULL OR p_role IS NULL OR p_reason IS NULL
    OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500
 THEN RAISE EXCEPTION 'invalid RBAC assignment request'; END IF;
 IF p_actor=p_target THEN RAISE EXCEPTION 'self-assignment prohibited'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(1832,12);
 SELECT r.role_id INTO v_role FROM public.security_roles r
 WHERE r.code=p_role AND r.is_active;
 IF v_role IS NULL THEN RAISE EXCEPTION 'unknown or inactive role'; END IF;
 v_priv:=CASE WHEN p_role IN ('SYSTEM_ADMIN','AUDITOR','ORG_ADMIN')
 THEN 'PRIVILEGED_ROLE_ASSIGN' ELSE 'OPERATIONAL_ROLE_ASSIGN' END;
 IF NOT EXISTS (
 SELECT 1 FROM public.security_user_roles a
 JOIN public.security_roles r ON r.role_id=a.role_id AND r.is_active
 JOIN public.security_role_permissions rp ON rp.role_id=r.role_id
 JOIN public.security_permissions p ON p.permission_id=rp.permission_id AND p.is_active
 JOIN public.users u ON u.user_id=a.user_id AND u.is_active AND NOT u.is_archived
 JOIN public.account_types t ON t.account_type_id=u.account_type_id AND t.code='HUMAN'
 WHERE a.user_id=p_actor AND a.revoked_at IS NULL
 AND a.valid_from<=clock_timestamp() AND (a.valid_until IS NULL OR a.valid_until>clock_timestamp())
 AND p.code=v_priv)
 THEN RAISE EXCEPTION 'RBAC actor lacks current assignment authority'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types t
 ON t.account_type_id=u.account_type_id WHERE u.user_id=p_target
 AND u.is_active AND NOT u.is_archived AND t.code='HUMAN')
 THEN RAISE EXCEPTION 'target must be an active HUMAN user'; END IF;
 IF EXISTS (SELECT 1 FROM public.security_user_roles
 WHERE user_id=p_target AND role_id=v_role AND revoked_at IS NULL)
 THEN RAISE EXCEPTION 'duplicate active role assignment'; END IF;
 INSERT INTO public.security_user_roles(user_id,role_id,granted_by_user_id)
 VALUES(p_target,v_role,p_actor) RETURNING assignment_id INTO v_assignment;
 INSERT INTO public.security_role_assignment_history
 (assignment_id,target_user_id,actor_user_id,action,reason)
 VALUES(v_assignment,p_target,p_actor,'GRANT',btrim(p_reason));
 RETURN v_assignment;
END $$;
CREATE FUNCTION public.epms_rbac_revoke_role(p_actor uuid,p_assignment uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_target uuid; v_role text; v_priv text; v_result uuid;
BEGIN
 IF p_actor IS NULL OR p_assignment IS NULL OR p_reason IS NULL
 OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500
 THEN RAISE EXCEPTION 'invalid RBAC revocation request'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(1832,12);
 SELECT a.user_id,r.code INTO v_target,v_role FROM public.security_user_roles a
 JOIN public.security_roles r ON r.role_id=a.role_id
 WHERE a.assignment_id=p_assignment AND a.revoked_at IS NULL;
 IF v_target IS NULL THEN RAISE EXCEPTION 'assignment missing or already revoked'; END IF;
 IF p_actor=v_target THEN RAISE EXCEPTION 'self-revocation prohibited'; END IF;
 v_priv:=CASE WHEN v_role IN ('SYSTEM_ADMIN','AUDITOR','ORG_ADMIN')
 THEN 'PRIVILEGED_ROLE_ASSIGN' ELSE 'OPERATIONAL_ROLE_ASSIGN' END;
 IF NOT EXISTS (
 SELECT 1 FROM public.security_user_roles a
 JOIN public.security_roles r ON r.role_id=a.role_id AND r.is_active
 JOIN public.security_role_permissions rp ON rp.role_id=r.role_id
 JOIN public.security_permissions p ON p.permission_id=rp.permission_id AND p.is_active
 JOIN public.users u ON u.user_id=a.user_id AND u.is_active AND NOT u.is_archived
 JOIN public.account_types t ON t.account_type_id=u.account_type_id AND t.code='HUMAN'
 WHERE a.user_id=p_actor AND a.revoked_at IS NULL
 AND a.valid_from<=clock_timestamp() AND (a.valid_until IS NULL OR a.valid_until>clock_timestamp())
 AND p.code=v_priv)
 THEN RAISE EXCEPTION 'RBAC actor lacks current revocation authority'; END IF;
 UPDATE public.security_user_roles SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor
 WHERE assignment_id=p_assignment AND revoked_at IS NULL RETURNING assignment_id INTO v_result;
 INSERT INTO public.security_role_assignment_history
 (assignment_id,target_user_id,actor_user_id,action,reason)
 VALUES(v_result,v_target,p_actor,'REVOKE',btrim(p_reason));
 RETURN v_result;
END $$;
REVOKE ALL ON FUNCTION public.epms_rbac_assign_role(uuid,uuid,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_rbac_revoke_role(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.epms_rbac_assign_role(uuid,uuid,text,text) TO epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_rbac_revoke_role(uuid,uuid,text) TO epms_rbac_runtime;
ALTER FUNCTION public.epms_rbac_assign_role(uuid,uuid,text,text) OWNER TO epms_rbac_function_owner;
ALTER FUNCTION public.epms_rbac_revoke_role(uuid,uuid,text) OWNER TO epms_rbac_function_owner;
