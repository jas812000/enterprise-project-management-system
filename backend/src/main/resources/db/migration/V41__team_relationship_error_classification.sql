-- B9A: classify missing or mismatched relationship targets after existing admin checks.
-- No changes to applied migrations, authorization rules, owner or execution grants.

CREATE OR REPLACE FUNCTION public.epms_team_assign_permanent(p_actor uuid,p_team uuid,p_membership uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team relationship unavailable' USING ERRCODE='23514'; END IF;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'LEADER_ASSIGN',p_reason);
 RETURN public.epms_team_open_leadership(p_membership,p_team,v_event,'PERMANENT');
END $$;

CREATE OR REPLACE FUNCTION public.epms_team_end_planned(p_actor uuid,p_team uuid,p_assignment uuid,p_replacement uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO v_user FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team relationship unavailable' USING ERRCODE='23514'; END IF;
 IF v_user=p_actor THEN RAISE EXCEPTION 'Self-removal not allowed' USING ERRCODE='42501'; END IF;
 IF p_replacement IS NOT NULL THEN PERFORM public.epms_team_assign_permanent(p_actor,p_team,p_replacement,p_reason); END IF;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'LEADER_END',p_reason);
 PERFORM public.epms_team_close_leadership(p_assignment,v_event);
 IF EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active)
 AND NOT public.epms_team_has_eligible_leader(p_team) THEN
  RAISE EXCEPTION 'Active Team requires a leader; replace or deactivate first' USING ERRCODE='23514'; END IF;
END $$;

CREATE OR REPLACE FUNCTION public.epms_team_designate_alternate(p_actor uuid,p_team uuid,p_membership uuid,p_priority smallint,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_id uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team relationship unavailable' USING ERRCODE='23514'; END IF;
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

CREATE OR REPLACE FUNCTION public.epms_team_leave_member(p_actor uuid,p_team uuid,p_membership uuid,p_end_type text,p_reason_code text,p_comment text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_comment);
 SELECT user_id INTO v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team AND left_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team relationship unavailable' USING ERRCODE='23514'; END IF;
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

CREATE OR REPLACE FUNCTION public.epms_team_revoke_alternate(p_actor uuid,p_team uuid,p_alternate uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO v_user FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=p_alternate AND team_id=p_team AND revoked_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Team relationship unavailable' USING ERRCODE='23514'; END IF;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'ALTERNATE_REVOKE',p_reason);
 UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor,revocation_reason=p_reason WHERE team_leadership_alternate_id=p_alternate;
END $$;
