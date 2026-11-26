-- System administrators may inspect bounded access metadata for a known Team.
-- Do not expose free-text justifications, original confidential contents or unrestricted exports.
CREATE FUNCTION public.epms_team_command_confidential_access_history(
 p_actor uuid,p_team uuid,p_limit integer,p_offset integer)
RETURNS TABLE(access_id uuid,actor_user_id uuid,requested_team_id uuid,requested_event_id uuid,
 source_kind text,outcome text,access_scope text,occurred_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,false);
 IF NOT public.epms_team_notification_has_role(p_actor,v_org,ARRAY['SYSTEM_ADMIN'])
 OR NOT EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=p_team AND t.organization_id=v_org) THEN
  RAISE EXCEPTION 'Audit access history denied' USING ERRCODE='42501';
 END IF;
 RETURN QUERY SELECT a.access_id,a.actor_user_id,a.requested_team_id,a.requested_event_id,
  a.source_kind,a.outcome,a.access_scope,a.occurred_at
 FROM public.team_confidential_audit_access a WHERE a.requested_team_id=p_team
 ORDER BY a.occurred_at DESC,a.access_id DESC LIMIT p_limit OFFSET p_offset;
END $$;
REVOKE ALL ON FUNCTION public.epms_team_command_confidential_access_history(uuid,uuid,integer,integer)
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_confidential_access_history(uuid,uuid,integer,integer)
 TO epms_rbac_runtime;
