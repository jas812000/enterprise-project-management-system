-- Classify only a missing active emergency leadership relationship after authorization.
-- Retain the existing owner, ACL, invoker context, search path and advisory lock.
CREATE OR REPLACE FUNCTION public.epms_team_emergency_succession(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid; v_incoming uuid; v_alt uuid; v_outcome text; a record;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 IF p_type IS NULL OR p_type NOT IN ('ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_LEADERSHIP_SUSPENSION','EMERGENCY_UNAVAILABILITY','EMERGENCY_TERMINATION') THEN
  RAISE EXCEPTION 'Emergency succession requires an emergency event' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active) THEN
  RAISE EXCEPTION 'Emergency succession requires active Team' USING ERRCODE='23514'; END IF;
 SELECT user_id INTO v_user FROM public.team_leadership_assignments
 WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
 IF NOT FOUND THEN
  RAISE EXCEPTION 'Active leadership assignment unavailable' USING ERRCODE='23514'; END IF;
 IF p_actor=v_user THEN RAISE EXCEPTION 'Self-declared emergency prohibited' USING ERRCODE='42501'; END IF;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'EMERGENCY',p_reason);
 PERFORM public.epms_team_close_leadership(p_assignment,v_event);
 UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revocation_event_id=v_event,revocation_reason=p_reason
 WHERE team_id=p_team AND user_id=v_user AND revoked_at IS NULL;
 IF public.epms_team_has_eligible_leader(p_team) THEN v_outcome:='OTHER_LEAD_AVAILABLE';
 ELSE
  FOR a IN SELECT * FROM public.team_leadership_alternates WHERE team_id=p_team AND revoked_at IS NULL ORDER BY priority LOOP
   IF a.user_id<>v_user AND a.user_id<>p_actor AND public.epms_team_eligible_member(a.team_membership_id,p_team)
   AND NOT EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=a.user_id AND ended_at IS NULL)
   AND NOT EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
    WHERE x.user_id=a.user_id AND x.revoked_at IS NULL AND r.code IN ('TEAM_LEAD','AUDITOR')) THEN
    v_incoming:=public.epms_team_open_leadership(a.team_membership_id,p_team,v_event,'ACTING');
    v_alt:=a.team_leadership_alternate_id;
    v_outcome:=CASE WHEN a.priority=1 THEN 'FIRST_ALTERNATE_ACTIVATED' ELSE 'SECOND_ALTERNATE_ACTIVATED' END;
    UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revocation_event_id=v_event,revocation_reason='Activated as acting leader'
    WHERE team_leadership_alternate_id=v_alt;
    EXIT;
   END IF;
  END LOOP;
  IF v_incoming IS NULL THEN
   v_outcome:='NO_ELIGIBLE_SUCCESSOR';
   INSERT INTO public.team_security_alerts(event_id,alert_code) VALUES(v_event,'NO_ELIGIBLE_SUCCESSOR');
   INSERT INTO public.team_lifecycle_events(organization_id,team_id,actor_user_id,from_status,to_status,resulting_version,reason)
    SELECT v_org,p_team,p_actor,lifecycle_status,'INACTIVE',version+1,btrim(p_reason) FROM public.teams WHERE team_id=p_team;
   UPDATE public.teams SET lifecycle_status='INACTIVE',is_active=false,updated_by_user_id=p_actor,updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team;
  END IF;
 END IF;
 INSERT INTO public.team_leadership_succession_events(organization_id,team_id,outgoing_user_id,evaluated_alternate_id,incoming_assignment_id,emergency_event_type,outcome,event_reason,operation_event_id)
 VALUES(v_org,p_team,v_user,v_alt,v_incoming,p_type,v_outcome,btrim(p_reason),v_event);
 RETURN v_incoming;
END $$;
