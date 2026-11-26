-- Preserve the V36 account lifecycle contract and all authorization/credential locks.
-- Notification recipient foreign keys hold KEY SHARE on users. Account lifecycle
-- changes only non-key fields; NO KEY UPDATE still serializes competing writers
-- while permitting referential-integrity checks. No ACL or ownership changes.
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
 WHERE u.user_id=p_target AND u.is_active AND NOT u.is_archived AND a.code='HUMAN' FOR NO KEY UPDATE OF u;
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
