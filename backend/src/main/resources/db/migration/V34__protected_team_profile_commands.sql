-- Phase 12 B6.1: protected Team profile writes, no new runtime table rights.
-- Trusted service must resolve the actor from authenticated identity (B6.2).
ALTER TABLE public.team_operation_events DROP CONSTRAINT team_operation_events_operation_check;
ALTER TABLE public.team_operation_events ADD CONSTRAINT team_operation_events_operation_check
 CHECK (operation IN ('MEMBER_JOIN','MEMBER_LEAVE','LEADER_ASSIGN','LEADER_END',
  'ALTERNATE_DESIGNATE','ALTERNATE_REVOKE','EMERGENCY','DEACTIVATE','ARCHIVE',
  'TEAM_CREATE','TEAM_PROFILE_UPDATE'));
ALTER TABLE public.team_operation_events
 ADD COLUMN profile_before jsonb,
 ADD COLUMN profile_after jsonb,
 ADD COLUMN similar_team_ids uuid[] NOT NULL DEFAULT ARRAY[]::uuid[],
 ADD CONSTRAINT ck_team_operation_profile CHECK (
  (operation='TEAM_CREATE' AND profile_before IS NULL AND profile_after IS NOT NULL)
  OR (operation='TEAM_PROFILE_UPDATE' AND profile_before IS NOT NULL AND profile_after IS NOT NULL)
  OR (operation NOT IN ('TEAM_CREATE','TEAM_PROFILE_UPDATE') AND profile_before IS NULL
      AND profile_after IS NULL AND cardinality(similar_team_ids)=0));

-- Same lock and administrative authority as existing protected Team mutations.
CREATE FUNCTION public.epms_team_require_profile_admin(p_actor uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'Team operation requires a reason' USING ERRCODE='22023';
 END IF;
 SELECT u.organization_id INTO v_org FROM public.users u
 JOIN public.account_types a USING(account_type_id)
 WHERE u.user_id=p_actor AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
 AND EXISTS (
  SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
  WHERE x.user_id=u.user_id AND r.code IN ('SYSTEM_ADMIN','ORG_ADMIN') AND r.is_active
  AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp()
  AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp()))
 AND EXISTS (SELECT 1 FROM public.security_effective_user_permissions p
  WHERE p.user_id=u.user_id AND p.permission_code='OPERATIONAL_ROLE_ASSIGN');
 IF v_org IS NULL THEN
  RAISE EXCEPTION 'Team operation requires current organization administration authority'
   USING ERRCODE='42501';
 END IF;
 RETURN v_org;
END $$;

-- Deterministic punctuation/spacing equivalence only; broader fuzzy matching is deferred.
CREATE FUNCTION public.epms_team_profile_conflicts(p_org uuid,p_team uuid,p_name text)
RETURNS uuid[] LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_matches uuid[];
BEGIN
 IF p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 200
  OR regexp_replace(lower(p_name),'[^[:alnum:]]','','g')='' THEN
  RAISE EXCEPTION 'Invalid Team name' USING ERRCODE='22023';
 END IF;
 IF EXISTS (SELECT 1 FROM public.teams t WHERE t.organization_id=p_org
  AND t.team_id IS DISTINCT FROM p_team
  AND lower(regexp_replace(btrim(t.name),'[[:space:]]+',' ','g'))=
      lower(regexp_replace(btrim(p_name),'[[:space:]]+',' ','g'))) THEN
  RAISE EXCEPTION 'Duplicate Team name' USING ERRCODE='23505';
 END IF;
 SELECT coalesce(array_agg(t.team_id ORDER BY t.team_id),ARRAY[]::uuid[]) INTO v_matches
 FROM public.teams t WHERE t.organization_id=p_org AND t.team_id IS DISTINCT FROM p_team
 AND regexp_replace(lower(t.name),'[^[:alnum:]]','','g')=
     regexp_replace(lower(p_name),'[^[:alnum:]]','','g');
 RETURN v_matches;
END $$;

CREATE FUNCTION public.epms_team_command_create(p_actor uuid,p_name text,p_description text,
 p_override boolean,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_team uuid; v_matches uuid[];
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,p_reason);
 v_matches:=public.epms_team_profile_conflicts(v_org,NULL,p_name);
 IF cardinality(v_matches)>0 AND p_override IS DISTINCT FROM true THEN
  RAISE EXCEPTION 'Similar Team name requires explicit override' USING ERRCODE='23514';
 END IF;
 IF p_override IS TRUE AND cardinality(v_matches)=0 THEN
  RAISE EXCEPTION 'No similar Team name to override' USING ERRCODE='22023';
 END IF;
 INSERT INTO public.teams(organization_id,name,description,created_by_user_id,updated_by_user_id)
 VALUES(v_org,btrim(p_name),p_description,p_actor,p_actor) RETURNING team_id INTO v_team;
 INSERT INTO public.team_operation_events(organization_id,team_id,actor_user_id,target_user_id,
  operation,reason,profile_after,similar_team_ids)
 VALUES(v_org,v_team,p_actor,p_actor,'TEAM_CREATE',btrim(p_reason),
  jsonb_build_object('name',btrim(p_name),'description',p_description,'version',0),v_matches);
 RETURN v_team;
END $$;

CREATE FUNCTION public.epms_team_command_update_profile(p_actor uuid,p_team uuid,p_version bigint,
 p_name text,p_description text,p_override boolean,p_reason text)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_old public.teams%ROWTYPE; v_matches uuid[]:=ARRAY[]::uuid[];
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
 IF cardinality(v_matches)>0 AND p_override IS DISTINCT FROM true THEN
  RAISE EXCEPTION 'Similar Team name requires explicit override' USING ERRCODE='23514';
 END IF;
 IF p_override IS TRUE AND cardinality(v_matches)=0 THEN
  RAISE EXCEPTION 'No similar Team name to override' USING ERRCODE='22023';
 END IF;
 UPDATE public.teams SET name=btrim(p_name),description=p_description,updated_by_user_id=p_actor,
  updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team;
 INSERT INTO public.team_operation_events(organization_id,team_id,actor_user_id,target_user_id,
  operation,reason,profile_before,profile_after,similar_team_ids)
 VALUES(v_org,p_team,p_actor,p_actor,'TEAM_PROFILE_UPDATE',btrim(p_reason),
  jsonb_build_object('name',v_old.name,'description',v_old.description,'version',v_old.version),
  jsonb_build_object('name',btrim(p_name),'description',p_description,'version',v_old.version+1),
  v_matches);
 RETURN v_old.version+1;
END $$;
REVOKE ALL ON FUNCTION public.epms_team_require_profile_admin(uuid,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
REVOKE ALL ON FUNCTION public.epms_team_profile_conflicts(uuid,uuid,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
REVOKE ALL ON FUNCTION public.epms_team_command_create(uuid,text,text,boolean,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
REVOKE ALL ON FUNCTION public.epms_team_command_update_profile(uuid,uuid,bigint,text,text,boolean,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_create(uuid,text,text,boolean,text)
 TO epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_update_profile(uuid,uuid,bigint,text,text,boolean,text)
 TO epms_rbac_runtime;
