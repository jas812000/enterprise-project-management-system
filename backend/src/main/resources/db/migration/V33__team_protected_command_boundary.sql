-- Phase 12 B5.3: fixed command entry points for the trusted RBAC backend.
-- Actor IDs must originate from authenticated server-side identity, never request DTOs.
-- The migration owner retains the existing owner-only functions and table rights.
-- No runtime table writes, helper execution, new login or role memberships.
-- Each command is atomic; no cross-datasource transaction is implied.

CREATE FUNCTION public.epms_team_command_join_member(p_actor uuid, p_team uuid, p_user uuid, p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_join_member(p_actor, p_team, p_user, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_join_member(uuid,uuid,uuid,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_join_member(uuid,uuid,uuid,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_leave_member(p_actor uuid, p_team uuid, p_membership uuid, p_end_type text, p_reason_code text, p_comment text)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_leave_member(p_actor, p_team, p_membership, p_end_type, p_reason_code, p_comment)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_leave_member(uuid,uuid,uuid,text,text,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_leave_member(uuid,uuid,uuid,text,text,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_assign_permanent(p_actor uuid, p_team uuid, p_membership uuid, p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_assign_permanent(p_actor, p_team, p_membership, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_assign_permanent(uuid,uuid,uuid,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_assign_permanent(uuid,uuid,uuid,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_end_planned(p_actor uuid, p_team uuid, p_assignment uuid, p_replacement uuid, p_reason text)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_end_planned(p_actor, p_team, p_assignment, p_replacement, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_end_planned(uuid,uuid,uuid,uuid,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_end_planned(uuid,uuid,uuid,uuid,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_designate_alternate(p_actor uuid, p_team uuid, p_membership uuid, p_priority smallint, p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_designate_alternate(p_actor, p_team, p_membership, p_priority, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_designate_alternate(uuid,uuid,uuid,smallint,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_designate_alternate(uuid,uuid,uuid,smallint,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_revoke_alternate(p_actor uuid, p_team uuid, p_alternate uuid, p_reason text)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_revoke_alternate(p_actor, p_team, p_alternate, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_revoke_alternate(uuid,uuid,uuid,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_revoke_alternate(uuid,uuid,uuid,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_transition(p_actor uuid, p_team uuid, p_status text, p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_transition(p_actor, p_team, p_status, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_transition(uuid,uuid,text,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_transition(uuid,uuid,text,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_disable_account(p_actor uuid, p_target uuid, p_type text, p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_disable_account(p_actor, p_target, p_type, p_reason)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_disable_account(uuid,uuid,text,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_disable_account(uuid,uuid,text,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_has_permission(p_user uuid, p_team uuid, p_permission text)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_has_permission(p_user, p_team, p_permission)
$$;
REVOKE ALL ON FUNCTION public.epms_team_command_has_permission(uuid,uuid,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_has_permission(uuid,uuid,text) TO epms_rbac_runtime;

CREATE FUNCTION public.epms_team_command_emergency_leadership(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF p_type IS NULL OR p_type NOT IN ('EMERGENCY_LEADERSHIP_SUSPENSION','EMERGENCY_UNAVAILABILITY') THEN
  RAISE EXCEPTION 'Account emergencies require protected account lifecycle operation';
 END IF;
 RETURN public.epms_team_emergency_succession(p_actor,p_team,p_assignment,p_type,p_reason);
END $$;
REVOKE ALL ON FUNCTION public.epms_team_command_emergency_leadership(uuid,uuid,uuid,text,text) FROM PUBLIC, epms_app, epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_emergency_leadership(uuid,uuid,uuid,text,text) TO epms_rbac_runtime;
