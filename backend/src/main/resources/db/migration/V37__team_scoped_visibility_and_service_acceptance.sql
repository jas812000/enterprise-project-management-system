-- B6.5: approved Team profile read scopes; no new mutation grants.
-- V1-V36 are immutable. Project, viewer and auditor resource grants are not yet modeled.
-- Read authority is evaluated from current HUMAN accounts and active temporal RBAC assignments.
CREATE OR REPLACE FUNCTION public.epms_team_command_profiles(p_actor uuid,p_team uuid,p_limit integer,p_offset integer)
RETURNS TABLE(team_id uuid,name text,description text,lifecycle_status text,version bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_now timestamptz;
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR p_offset IS NULL OR p_offset<0 THEN
  RAISE EXCEPTION 'Invalid Team page' USING ERRCODE='22023'; END IF;
 PERFORM pg_advisory_xact_lock(1832,12);
 v_now:=clock_timestamp();
 IF NOT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types a USING(account_type_id)
  JOIN public.security_user_roles x ON x.user_id=u.user_id JOIN public.security_roles r USING(role_id)
  WHERE u.user_id=p_actor AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
  AND r.is_active AND x.revoked_at IS NULL AND x.valid_from<=v_now
  AND (x.valid_until IS NULL OR x.valid_until>v_now)
  AND r.code IN ('SYSTEM_ADMIN','ORG_ADMIN','PROJECT_MANAGER','TEAM_LEAD','MEMBER','VIEWER','AUDITOR')) THEN
  RAISE EXCEPTION 'Team read denied' USING ERRCODE='42501'; END IF;
 -- Repeat account/role checks within the actual result query, before sorting and paging.
 RETURN QUERY WITH actor_roles AS (
  SELECT u.organization_id,r.code FROM public.users u JOIN public.account_types a USING(account_type_id)
  JOIN public.security_user_roles x ON x.user_id=u.user_id JOIN public.security_roles r USING(role_id)
  WHERE u.user_id=p_actor AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
  AND r.is_active AND x.revoked_at IS NULL AND x.valid_from<=v_now
  AND (x.valid_until IS NULL OR x.valid_until>v_now)
 ) SELECT t.team_id,t.name::text,t.description::text,t.lifecycle_status::text,t.version::bigint
 FROM public.teams t WHERE (p_team IS NULL OR t.team_id=p_team) AND EXISTS (
  SELECT 1 FROM actor_roles ar WHERE ar.organization_id=t.organization_id AND (
   ar.code IN ('SYSTEM_ADMIN','ORG_ADMIN') OR (NOT t.is_archived AND (
    (ar.code IN ('MEMBER','TEAM_LEAD') AND EXISTS (
     SELECT 1 FROM public.team_memberships m WHERE m.organization_id=t.organization_id
     AND m.team_id=t.team_id AND m.user_id=p_actor AND m.left_at IS NULL
     AND m.joined_at<=v_now)) OR
    (ar.code='TEAM_LEAD' AND public.epms_team_has_leadership_scope(p_actor,t.team_id))
   ))))
 ORDER BY t.name,t.team_id LIMIT p_limit OFFSET p_offset;
END $$;
-- Preserve the existing restricted execution ACL explicitly.
REVOKE ALL ON FUNCTION public.epms_team_command_profiles(uuid,uuid,integer,integer) FROM PUBLIC,epms_app;
GRANT EXECUTE ON FUNCTION public.epms_team_command_profiles(uuid,uuid,integer,integer) TO epms_rbac_runtime;

-- Additional warning key, not a replacement for existing duplicate or similarity rules.
-- Limited Latin combining-mark folding and common Greek/Cyrillic visual substitutions.
-- This is deliberately not a claim of full Unicode confusable coverage.
CREATE FUNCTION public.epms_team_visual_name_key(p_name text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
 SELECT regexp_replace(translate(regexp_replace(lower(normalize(p_name,NFKD)),
  '[̀-ͯ]', '', 'g'), 'аеорсхуікмαορικχ', 'aeopcxyikmaopikx'), '[^[:alnum:]]', '', 'g')
$$;
REVOKE ALL ON FUNCTION public.epms_team_visual_name_key(text) FROM PUBLIC,epms_app,epms_rbac_runtime;

CREATE OR REPLACE FUNCTION public.epms_team_names_similar(p_left text,p_right text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE a text:=public.epms_team_name_key(p_left); b text:=public.epms_team_name_key(p_right);
sa text; sb text;
BEGIN
 IF a=b OR public.epms_team_one_edit(a,b) THEN RETURN true; END IF;
 IF public.epms_team_visual_name_key(p_left)<>'' AND
  public.epms_team_visual_name_key(p_left)=public.epms_team_visual_name_key(p_right) THEN
  RETURN true; END IF;
 -- Common digit/letter look-alikes; not an exhaustive Unicode confusable map.
 IF translate(a,'013457','oieast')=translate(b,'013457','oieast') THEN RETURN true; END IF;
 sa:=public.epms_team_sound_key(a); sb:=public.epms_team_sound_key(b);
 RETURN coalesce(length(sa)>=3 AND sa=sb,false);
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_close_leadership(p_assignment uuid,p_event uuid)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE l public.team_leadership_assignments%ROWTYPE; e public.team_operation_events%ROWTYPE; v_now timestamptz;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 SELECT * INTO STRICT l FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND ended_at IS NULL;
 SELECT * INTO STRICT e FROM public.team_operation_events WHERE event_id=p_event;
 IF e.team_id<>l.team_id OR e.target_user_id<>l.user_id OR e.operation NOT IN ('LEADER_END','EMERGENCY')
 OR l.rbac_assignment_id IS NULL THEN RAISE EXCEPTION 'Invalid leadership closure' USING ERRCODE='23514'; END IF;
 v_now:=clock_timestamp();
 UPDATE public.team_leadership_assignments SET ended_at=v_now,
 ended_by_user_id=CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 end_event_id=p_event,end_reason_code=e.operation,end_comment=e.reason,updated_at=v_now,version=version+1
 WHERE team_leadership_assignment_id=p_assignment;
 UPDATE public.security_user_roles SET revoked_at=v_now,
 revoked_by_user_id=CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 revoke_team_event_id=CASE WHEN e.operation='EMERGENCY' THEN p_event END
 WHERE assignment_id=l.rbac_assignment_id AND revoked_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Leadership RBAC assignment missing or revoked' USING ERRCODE='23514'; END IF;
 INSERT INTO public.security_role_assignment_history(assignment_id,target_user_id,actor_user_id,team_event_id,action,reason)
 VALUES(l.rbac_assignment_id,l.user_id,CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 CASE WHEN e.operation='EMERGENCY' THEN p_event END,'REVOKE',e.reason);
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_end_planned(p_actor uuid,p_team uuid,p_assignment uuid,p_replacement uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
 IF v_user=p_actor THEN RAISE EXCEPTION 'Self-removal not allowed' USING ERRCODE='42501'; END IF;
 IF p_replacement IS NOT NULL THEN PERFORM public.epms_team_assign_permanent(p_actor,p_team,p_replacement,p_reason); END IF;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'LEADER_END',p_reason);
 PERFORM public.epms_team_close_leadership(p_assignment,v_event);
 IF EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active)
 AND NOT public.epms_team_has_eligible_leader(p_team) THEN
  RAISE EXCEPTION 'Active Team requires a leader; replace or deactivate first' USING ERRCODE='23514'; END IF;
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_designate_alternate(p_actor uuid,p_team uuid,p_membership uuid,p_priority smallint,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_id uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team;
 IF v_user=p_actor OR NOT public.epms_team_eligible_member(p_membership,p_team)
 OR EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=v_user AND ended_at IS NULL)
 OR EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
 WHERE x.user_id=v_user AND x.revoked_at IS NULL AND r.code IN ('TEAM_LEAD','AUDITOR')) THEN
  RAISE EXCEPTION 'Ineligible dormant alternate' USING ERRCODE='23514'; END IF;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'ALTERNATE_DESIGNATE',p_reason);
 INSERT INTO public.team_leadership_alternates(team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id)
 VALUES(p_membership,v_org,p_team,v_user,p_priority,p_actor) RETURNING team_leadership_alternate_id INTO v_id;
 RETURN v_id;
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_leave_member(p_actor uuid,p_team uuid,p_membership uuid,p_end_type text,p_reason_code text,p_comment text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_comment);
 SELECT user_id INTO STRICT v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team AND left_at IS NULL;
 IF p_end_type IS NULL OR p_end_type NOT IN ('VOLUNTARY','ADMINISTRATIVE') OR NOT EXISTS
 (SELECT 1 FROM public.membership_end_reasons WHERE code=p_reason_code AND is_active) THEN
  RAISE EXCEPTION 'Invalid planned membership removal reason' USING ERRCODE='22023'; END IF;
 IF EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE team_membership_id=p_membership AND ended_at IS NULL) THEN
  RAISE EXCEPTION 'End leadership before membership' USING ERRCODE='23514'; END IF;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'MEMBER_LEAVE',p_comment);
 UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor,revocation_reason=p_comment
 WHERE team_membership_id=p_membership AND revoked_at IS NULL;
 UPDATE public.team_memberships SET left_at=clock_timestamp(),removed_by_user_id=p_actor,end_type_code=p_end_type,
 removal_reason_code=p_reason_code,removal_comment=p_comment,updated_at=clock_timestamp(),version=version+1 WHERE team_membership_id=p_membership;
 IF EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active)
 AND NOT public.epms_team_has_eligible_leader(p_team) THEN RAISE EXCEPTION 'Active Team requires eligible leadership' USING ERRCODE='23514'; END IF;
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_open_leadership(p_membership uuid,p_team uuid,p_event uuid,p_type text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE m public.team_memberships%ROWTYPE; e public.team_operation_events%ROWTYPE;
 v_role uuid; v_rbac uuid; v_id uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 SELECT * INTO m FROM public.team_memberships WHERE team_membership_id=p_membership;
 SELECT * INTO e FROM public.team_operation_events WHERE event_id=p_event;
 IF NOT public.epms_team_eligible_member(p_membership,p_team) OR e.team_id IS DISTINCT FROM p_team
 OR e.organization_id IS DISTINCT FROM m.organization_id OR p_type NOT IN ('PERMANENT','ACTING')
 OR (p_type='PERMANENT' AND (e.operation<>'LEADER_ASSIGN' OR e.target_user_id<>m.user_id))
 OR (p_type='ACTING' AND e.operation<>'EMERGENCY') OR e.actor_user_id=m.user_id THEN
  RAISE EXCEPTION 'Invalid leadership assignment' USING ERRCODE='23514'; END IF;
 IF EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=m.user_id AND ended_at IS NULL)
 OR EXISTS (SELECT 1 FROM public.team_leadership_alternates WHERE user_id=m.user_id AND revoked_at IS NULL AND team_id<>p_team) THEN
  RAISE EXCEPTION 'User already has a conflicting Team relationship' USING ERRCODE='23514'; END IF;
 SELECT role_id INTO STRICT v_role FROM public.security_roles WHERE code='TEAM_LEAD' AND is_active;
 INSERT INTO public.security_user_roles(user_id,role_id,granted_by_user_id,grant_team_event_id)
 VALUES(m.user_id,v_role,CASE WHEN p_type='PERMANENT' THEN e.actor_user_id END,p_event)
 RETURNING assignment_id INTO v_rbac;
 INSERT INTO public.security_role_assignment_history(assignment_id,target_user_id,actor_user_id,team_event_id,action,reason)
 VALUES(v_rbac,m.user_id,CASE WHEN p_type='PERMANENT' THEN e.actor_user_id END,
 CASE WHEN p_type='ACTING' THEN p_event END,'GRANT',e.reason);
 INSERT INTO public.team_leadership_assignments(team_membership_id,organization_id,team_id,user_id,
 assigned_by_user_id,assignment_type,assignment_event_id,rbac_assignment_id)
 VALUES(p_membership,m.organization_id,p_team,m.user_id,CASE WHEN p_type='PERMANENT' THEN e.actor_user_id END,
 p_type,p_event,v_rbac) RETURNING team_leadership_assignment_id INTO v_id;
 IF p_type='PERMANENT' THEN
  UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),
   revoked_by_user_id=e.actor_user_id,revocation_reason=e.reason
   WHERE user_id=m.user_id AND team_id=p_team AND revoked_at IS NULL;
 END IF;
 RETURN v_id;
END $$;

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_emergency_succession(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid; v_incoming uuid; v_alt uuid; v_outcome text; a record;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 IF p_type IS NULL OR p_type NOT IN ('ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_LEADERSHIP_SUSPENSION','EMERGENCY_UNAVAILABILITY','EMERGENCY_TERMINATION') THEN
  RAISE EXCEPTION 'Emergency succession requires an emergency event' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active) THEN
  RAISE EXCEPTION 'Emergency succession requires active Team' USING ERRCODE='23514'; END IF;
 SELECT user_id INTO STRICT v_user FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
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

-- Preserve behavior and ownership; classify explicit business errors.
CREATE OR REPLACE FUNCTION public.epms_team_command_emergency_leadership(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF p_type IS NULL OR p_type NOT IN ('EMERGENCY_LEADERSHIP_SUSPENSION','EMERGENCY_UNAVAILABILITY') THEN
  RAISE EXCEPTION 'Account emergencies require protected account lifecycle operation' USING ERRCODE='22023';
 END IF;
 RETURN public.epms_team_emergency_succession(p_actor,p_team,p_assignment,p_type,p_reason);
END $$;
