-- B6.4: bind similarity overrides to a current, explicitly reviewed conflict snapshot.
-- V1-V35 remain immutable. All profile mutations retain the shared Team advisory lock.
ALTER TABLE public.team_operation_events ADD COLUMN reviewed_name_conflicts jsonb;

CREATE FUNCTION public.epms_team_conflict_snapshot(p_actor uuid,p_org uuid,p_team uuid,
 p_name text,p_matches uuid[])
RETURNS text LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT jsonb_build_object('actor',p_actor,'organization',p_org,'team',p_team,
  'name',btrim(p_name),'targetVersion',(SELECT t.version FROM public.teams t
    WHERE t.organization_id=p_org AND t.team_id=p_team),
  'conflicts',coalesce((SELECT jsonb_agg(jsonb_build_object('id',t.team_id,
    'name',t.name,'version',t.version) ORDER BY t.team_id)
    FROM public.teams t WHERE t.organization_id=p_org AND t.team_id=ANY(p_matches)),
    '[]'::jsonb))::text
$$;

CREATE FUNCTION public.epms_team_command_review_conflicts(p_actor uuid,p_team uuid,p_name text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_matches uuid[];
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,'Review Team name conflicts');
 IF p_team IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.teams t
  WHERE t.organization_id=v_org AND t.team_id=p_team AND NOT t.is_archived) THEN
  RAISE EXCEPTION 'Team unavailable' USING ERRCODE='42501'; END IF;
 v_matches:=public.epms_team_profile_conflicts(v_org,p_team,p_name);
 RETURN public.epms_team_conflict_snapshot(p_actor,v_org,p_team,p_name,v_matches);
END $$;

CREATE FUNCTION public.epms_team_require_conflict_review(p_actor uuid,p_org uuid,p_team uuid,
 p_name text,p_matches uuid[],p_override boolean,p_review text)
RETURNS jsonb LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_snapshot text;
BEGIN
 IF cardinality(p_matches)>0 THEN
  v_snapshot:=public.epms_team_conflict_snapshot(p_actor,p_org,p_team,p_name,p_matches);
  IF p_override IS DISTINCT FROM true OR p_review IS DISTINCT FROM v_snapshot THEN
   RAISE EXCEPTION 'Current Team name conflicts require review' USING ERRCODE='23514';
  END IF;
  RETURN v_snapshot::jsonb;
 END IF;
 IF p_override IS TRUE OR p_review IS NOT NULL THEN
  RAISE EXCEPTION 'No similar Team name to override' USING ERRCODE='22023';
 END IF;
 RETURN NULL;
END $$;

CREATE FUNCTION public.epms_team_command_create_reviewed(p_actor uuid,p_name text,p_description text,
 p_override boolean,p_reason text,p_review text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_team uuid; v_matches uuid[];
v_review jsonb;
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,p_reason);
 v_matches:=public.epms_team_profile_conflicts(v_org,NULL,p_name);
 v_review:=public.epms_team_require_conflict_review(p_actor,v_org,NULL,
  p_name,v_matches,p_override,p_review);
 INSERT INTO public.teams(organization_id,name,description,created_by_user_id,updated_by_user_id)
 VALUES(v_org,btrim(p_name),p_description,p_actor,p_actor) RETURNING team_id INTO v_team;
 INSERT INTO public.team_operation_events(organization_id,team_id,actor_user_id,target_user_id,
  operation,reason,profile_after,similar_team_ids,reviewed_name_conflicts)
 VALUES(v_org,v_team,p_actor,p_actor,'TEAM_CREATE',btrim(p_reason),
  jsonb_build_object('name',btrim(p_name),'description',p_description,'version',0),v_matches,v_review);
 RETURN v_team;
END $$;


CREATE FUNCTION public.epms_team_command_update_profile_reviewed(p_actor uuid,p_team uuid,p_version bigint,
 p_name text,p_description text,p_override boolean,p_reason text,p_review text)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_old public.teams%ROWTYPE; v_matches uuid[]:=ARRAY[]::uuid[];
v_review jsonb;
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,p_reason);
 SELECT * INTO v_old FROM public.teams WHERE organization_id=v_org AND team_id=p_team
  AND NOT is_archived FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team unavailable' USING ERRCODE='42501'; END IF;
 IF p_version IS DISTINCT FROM v_old.version THEN
  RAISE EXCEPTION 'Stale Team version' USING ERRCODE='40001'; END IF;
 IF btrim(p_name) IS DISTINCT FROM v_old.name THEN
  v_matches:=public.epms_team_profile_conflicts(v_org,p_team,p_name);
 END IF;
 v_review:=public.epms_team_require_conflict_review(p_actor,v_org,p_team,
  p_name,v_matches,p_override,p_review);
 UPDATE public.teams SET name=btrim(p_name),description=p_description,updated_by_user_id=p_actor,
  updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team;
 INSERT INTO public.team_operation_events(organization_id,team_id,actor_user_id,target_user_id,
  operation,reason,profile_before,profile_after,similar_team_ids,reviewed_name_conflicts)
 VALUES(v_org,p_team,p_actor,p_actor,'TEAM_PROFILE_UPDATE',btrim(p_reason),
  jsonb_build_object('name',v_old.name,'description',v_old.description,'version',v_old.version),
  jsonb_build_object('name',btrim(p_name),'description',p_description,'version',v_old.version+1),
  v_matches,v_review);
 RETURN v_old.version+1;
END $$;

-- Legacy entry points cannot bypass confirmation. Non-override callers remain compatible.
CREATE OR REPLACE FUNCTION public.epms_team_command_create(p_actor uuid,p_name text,p_description text,
 p_override boolean,p_reason text)
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_command_create_reviewed(p_actor,p_name,p_description,p_override,p_reason,NULL)
$$;
CREATE OR REPLACE FUNCTION public.epms_team_command_update_profile(p_actor uuid,p_team uuid,p_version bigint,
 p_name text,p_description text,p_override boolean,p_reason text)
RETURNS bigint LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT public.epms_team_command_update_profile_reviewed(p_actor,p_team,p_version,p_name,
  p_description,p_override,p_reason,NULL)
$$;
REVOKE ALL ON FUNCTION public.epms_team_conflict_snapshot(uuid,uuid,uuid,text,uuid[]),
 public.epms_team_require_conflict_review(uuid,uuid,uuid,text,uuid[],boolean,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
REVOKE ALL ON FUNCTION public.epms_team_command_create_reviewed(uuid,text,text,boolean,text,text),
 public.epms_team_command_update_profile_reviewed(uuid,uuid,bigint,text,text,boolean,text,text),
 public.epms_team_command_review_conflicts(uuid,uuid,text) FROM PUBLIC,epms_app,epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_create_reviewed(uuid,text,text,boolean,text,text),
 public.epms_team_command_update_profile_reviewed(uuid,uuid,bigint,text,text,boolean,text,text),
 public.epms_team_command_review_conflicts(uuid,uuid,text) TO epms_rbac_runtime;

-- Explicit SQLSTATE classification, unchanged operation logic.
CREATE OR REPLACE FUNCTION public.epms_team_require_admin(p_actor uuid,p_team uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'Team operation requires a reason' USING ERRCODE='22023'; END IF;
 SELECT organization_id INTO v_org FROM public.teams WHERE team_id=p_team AND NOT is_archived;
 IF v_org IS NULL OR NOT EXISTS (
  SELECT 1 FROM public.users u JOIN public.account_types a USING(account_type_id)
  JOIN public.security_user_roles x ON x.user_id=u.user_id
  JOIN public.security_roles r ON r.role_id=x.role_id
  JOIN public.security_effective_user_permissions p ON p.user_id=u.user_id
  WHERE u.user_id=p_actor AND u.organization_id=v_org AND u.is_active AND NOT u.is_archived
   AND a.code='HUMAN' AND r.code IN ('SYSTEM_ADMIN','ORG_ADMIN') AND r.is_active
   AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp()
   AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp())
   AND p.permission_code='OPERATIONAL_ROLE_ASSIGN') THEN
  RAISE EXCEPTION 'Team operation requires current organization administration authority' USING ERRCODE='42501'; END IF;
 RETURN v_org;
END $$;

-- Explicit SQLSTATE classification, unchanged operation logic.
CREATE OR REPLACE FUNCTION public.epms_team_disable_account(p_actor uuid,p_target uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 v_org uuid; v_event uuid; v_operation uuid; v_now timestamptz;
 v_emergency boolean; v_archive boolean; v_end_type text; v_reason_code text;
 l record; m record; r record; s record;
BEGIN
 IF p_actor IS NULL OR p_target IS NULL OR p_actor=p_target OR p_reason IS NULL
 OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 OR p_type IS NULL OR p_type NOT IN
 ('PLANNED_DEACTIVATION','PLANNED_ARCHIVAL','ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_TERMINATION') THEN
  RAISE EXCEPTION 'Invalid account lifecycle request' USING ERRCODE='22023';
 END IF;
 -- Acquire shared RBAC lock before per-user credential lock, before row locks.
 PERFORM pg_advisory_xact_lock(1832,12);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_target::text,82302026));
 SELECT u.organization_id INTO v_org FROM public.users u JOIN public.account_types a USING(account_type_id)
 WHERE u.user_id=p_target AND u.is_active AND NOT u.is_archived AND a.code='HUMAN' FOR UPDATE OF u;
 IF v_org IS NULL OR NOT EXISTS (
  SELECT 1 FROM public.users u JOIN public.account_types a USING(account_type_id)
  JOIN public.security_user_roles x ON x.user_id=u.user_id JOIN public.security_roles sr ON sr.role_id=x.role_id
  WHERE u.user_id=p_actor AND u.organization_id=v_org AND u.is_active AND NOT u.is_archived
  AND a.code='HUMAN' AND sr.code='SYSTEM_ADMIN' AND sr.is_active AND x.revoked_at IS NULL
  AND x.valid_from<=clock_timestamp() AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp())
 ) THEN RAISE EXCEPTION 'Account lifecycle requires current SYSTEM_ADMIN authority' USING ERRCODE='42501'; END IF;
 v_emergency:=p_type IN ('ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_TERMINATION');
 v_archive:=p_type IN ('PLANNED_ARCHIVAL','EMERGENCY_TERMINATION');
 v_end_type:=CASE WHEN v_archive THEN 'ACCOUNT_ARCHIVED' ELSE 'ACCOUNT_DEACTIVATED' END;
 v_reason_code:=CASE WHEN p_type='ACCOUNT_SECURITY' THEN 'ACCOUNT_SECURITY'
  WHEN p_type='EMERGENCY_TERMINATION' THEN 'EMPLOYMENT_ENDED' ELSE 'ADMINISTRATIVE_DECISION' END;
 INSERT INTO public.team_account_lifecycle_events(organization_id,actor_user_id,target_user_id,event_type,reason)
 VALUES(v_org,p_actor,p_target,p_type,btrim(p_reason)) RETURNING event_id INTO v_event;
 -- Revoke sessions through the existing protected session-before-token operation.
 FOR s IN SELECT session_id FROM public.user_auth_sessions WHERE user_id=p_target AND revoked_at IS NULL ORDER BY session_id LOOP
  PERFORM public.epms_auth_revoke(s.session_id,CASE WHEN v_emergency THEN 'SECURITY_EVENT' ELSE 'ADMIN_REVOKED' END);
 END LOOP;
 -- Close leadership before membership and before disabling the account.
 FOR l IN SELECT a.team_leadership_assignment_id,a.team_id,t.is_active
   FROM public.team_leadership_assignments a JOIN public.teams t USING(team_id)
   WHERE a.user_id=p_target AND a.ended_at IS NULL ORDER BY a.team_id LOOP
  IF v_emergency AND l.is_active THEN
   PERFORM public.epms_team_emergency_succession(p_actor,l.team_id,l.team_leadership_assignment_id,p_type,p_reason);
   SELECT end_event_id INTO STRICT v_operation FROM public.team_leadership_assignments
    WHERE team_leadership_assignment_id=l.team_leadership_assignment_id;
  ELSE
   v_operation:=public.epms_team_record_event(v_org,l.team_id,p_actor,p_target,
      CASE WHEN v_emergency THEN 'EMERGENCY' ELSE 'LEADER_END' END,p_reason);
   PERFORM public.epms_team_close_leadership(l.team_leadership_assignment_id,v_operation);
   IF l.is_active AND NOT public.epms_team_has_eligible_leader(l.team_id) THEN
    RAISE EXCEPTION 'Planned departure requires leadership replacement or prior Team deactivation' USING ERRCODE='23514';
   END IF;
  END IF;
  INSERT INTO public.team_account_operation_links VALUES(v_operation,v_event);
 END LOOP;
 FOR m IN SELECT team_membership_id,team_id FROM public.team_memberships
   WHERE user_id=p_target AND left_at IS NULL ORDER BY team_id LOOP
  v_operation:=public.epms_team_record_event(v_org,m.team_id,p_actor,p_target,'MEMBER_LEAVE',p_reason);
  INSERT INTO public.team_account_operation_links VALUES(v_operation,v_event);
  v_now:=clock_timestamp();
  UPDATE public.team_leadership_alternates SET revoked_at=v_now,revoked_by_user_id=p_actor,revocation_reason=btrim(p_reason)
   WHERE team_membership_id=m.team_membership_id AND revoked_at IS NULL;
  UPDATE public.team_memberships SET left_at=v_now,end_type_code=v_end_type,
   removal_reason_code=v_reason_code,removal_comment=btrim(p_reason),removed_by_user_id=p_actor,
   account_lifecycle_event_id=v_event,updated_at=v_now,version=version+1 WHERE team_membership_id=m.team_membership_id;
 END LOOP;
 -- Revoke remaining RBAC assignments so reactivation cannot silently restore them.
 -- Future-dated grants are not issued by the current administration path; retain
 -- the existing revoked_at>=valid_from constraint rather than weakening it.
 FOR r IN SELECT assignment_id,valid_from FROM public.security_user_roles WHERE user_id=p_target AND revoked_at IS NULL ORDER BY assignment_id LOOP
  UPDATE public.security_user_roles SET revoked_at=greatest(clock_timestamp(),r.valid_from),revoked_by_user_id=p_actor
   WHERE assignment_id=r.assignment_id;
  INSERT INTO public.security_role_assignment_history(assignment_id,target_user_id,actor_user_id,action,reason)
   VALUES(r.assignment_id,p_target,p_actor,'REVOKE',btrim(p_reason));
 END LOOP;
 v_now:=clock_timestamp();
 UPDATE public.users SET is_active=false,is_archived=v_archive,
  archived_at=CASE WHEN v_archive THEN v_now END,archived_by_user_id=CASE WHEN v_archive THEN p_actor END,
  archive_reason=CASE WHEN v_archive THEN btrim(p_reason) END,
  updated_by_user_id=p_actor,updated_at=v_now,version=version+1 WHERE user_id=p_target;
 RETURN v_event;
END $$;

-- Explicit SQLSTATE classification, unchanged operation logic.
CREATE OR REPLACE FUNCTION public.epms_team_transition(p_actor uuid,p_team uuid,p_status text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; t public.teams%ROWTYPE; v_event uuid; v_op uuid; v_now timestamptz; l record;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT * INTO STRICT t FROM public.teams WHERE team_id=p_team FOR UPDATE;
 IF p_status IS NULL OR NOT ((t.lifecycle_status IN ('PENDING_SETUP','INACTIVE') AND p_status IN ('ACTIVE','ARCHIVED'))
 OR (t.lifecycle_status='ACTIVE' AND p_status='INACTIVE')) THEN RAISE EXCEPTION 'Invalid Team transition' USING ERRCODE='23514'; END IF;
 IF p_status='ACTIVE' AND NOT public.epms_team_has_eligible_leader(p_team) THEN
  RAISE EXCEPTION 'Team activation requires eligible leadership' USING ERRCODE='23514'; END IF;
 IF p_status='ARCHIVED' THEN
  FOR l IN SELECT team_leadership_assignment_id,user_id FROM public.team_leadership_assignments
   WHERE team_id=p_team AND ended_at IS NULL ORDER BY user_id LOOP
   v_op:=public.epms_team_record_event(v_org,p_team,p_actor,l.user_id,'LEADER_END',p_reason);
   PERFORM public.epms_team_close_leadership(l.team_leadership_assignment_id,v_op);
  END LOOP;
  UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor,
   revocation_reason=btrim(p_reason) WHERE team_id=p_team AND revoked_at IS NULL;
  UPDATE public.team_memberships SET left_at=clock_timestamp(),end_type_code='TEAM_ARCHIVED',
   removal_reason_code='TEAM_DISSOLVED',removal_comment=btrim(p_reason),removed_by_user_id=p_actor,
   updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team AND left_at IS NULL;
 END IF;
 INSERT INTO public.team_lifecycle_events(organization_id,team_id,actor_user_id,from_status,to_status,resulting_version,reason)
 VALUES(v_org,p_team,p_actor,t.lifecycle_status,p_status,t.version+1,btrim(p_reason)) RETURNING event_id INTO v_event;
 v_now:=clock_timestamp();
 UPDATE public.teams SET lifecycle_status=p_status,is_active=(p_status='ACTIVE'),is_archived=(p_status='ARCHIVED'),
 archived_at=CASE WHEN p_status='ARCHIVED' THEN v_now END,
 archived_by_user_id=CASE WHEN p_status='ARCHIVED' THEN p_actor END,
 archive_reason=CASE WHEN p_status='ARCHIVED' THEN btrim(p_reason) END,
 updated_by_user_id=p_actor,updated_at=v_now,version=version+1 WHERE team_id=p_team;
 RETURN v_event;
END $$;
