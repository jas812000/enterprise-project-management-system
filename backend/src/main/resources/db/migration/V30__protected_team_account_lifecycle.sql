-- Phase 12 B4.3: staged account lifecycle integration. No runtime entry point.
-- V28 activation remains closed; the ACTIVE branches require B4.4 acceptance.
CREATE TABLE public.team_account_lifecycle_events (
    event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    event_type text NOT NULL CHECK (event_type IN
      ('PLANNED_DEACTIVATION','PLANNED_ARCHIVAL','ACCOUNT_SECURITY',
       'EMERGENCY_DEACTIVATION','EMERGENCY_TERMINATION')),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (actor_user_id <> target_user_id),
    FOREIGN KEY (organization_id,actor_user_id) REFERENCES public.users(organization_id,user_id),
    FOREIGN KEY (organization_id,target_user_id) REFERENCES public.users(organization_id,user_id)
);
CREATE TRIGGER trg_team_account_lifecycle_events_immutable BEFORE UPDATE OR DELETE
 ON public.team_account_lifecycle_events FOR EACH ROW
 EXECUTE FUNCTION public.epms_reject_security_history_mutation();
ALTER TABLE public.team_memberships ADD COLUMN account_lifecycle_event_id uuid
 REFERENCES public.team_account_lifecycle_events(event_id);
CREATE TABLE public.team_account_operation_links (
    operation_event_id uuid PRIMARY KEY REFERENCES public.team_operation_events(event_id),
    account_event_id uuid NOT NULL REFERENCES public.team_account_lifecycle_events(event_id)
);
CREATE TRIGGER trg_team_account_operation_links_immutable BEFORE UPDATE OR DELETE
 ON public.team_account_operation_links FOR EACH ROW
 EXECUTE FUNCTION public.epms_reject_security_history_mutation();

-- Guard direct profile/lifecycle writes that would strand Team dependencies.
-- Trigger owner reads protected tables; it cannot be called as an ordinary function.
CREATE FUNCTION public.epms_team_guard_account_lifecycle() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.is_active IS DISTINCT FROM OLD.is_active OR NEW.is_archived IS DISTINCT FROM OLD.is_archived
 OR NEW.account_type_id IS DISTINCT FROM OLD.account_type_id THEN
  PERFORM pg_advisory_xact_lock(1832,12);
  IF (NOT NEW.is_active OR NEW.is_archived OR NOT EXISTS
      (SELECT 1 FROM public.account_types WHERE account_type_id=NEW.account_type_id AND code='HUMAN'))
    AND (EXISTS (SELECT 1 FROM public.team_memberships WHERE user_id=OLD.user_id AND left_at IS NULL)
      OR EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=OLD.user_id AND ended_at IS NULL)
      OR EXISTS (SELECT 1 FROM public.team_leadership_alternates WHERE user_id=OLD.user_id AND revoked_at IS NULL)) THEN
   RAISE EXCEPTION 'Close Team dependencies through protected account lifecycle operation';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_guard_account_lifecycle BEFORE UPDATE OF is_active,is_archived,account_type_id
 ON public.users FOR EACH ROW EXECUTE FUNCTION public.epms_team_guard_account_lifecycle();
REVOKE ALL ON FUNCTION public.epms_team_guard_account_lifecycle() FROM PUBLIC;

-- Owner-only staging operation. SYSTEM_ADMIN only until the account administration
-- authorization policy is integrated. No automatic promotion for planned departures.
CREATE FUNCTION public.epms_team_disable_account(p_actor uuid,p_target uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 v_org uuid; v_event uuid; v_operation uuid; v_now timestamptz;
 v_emergency boolean; v_archive boolean; v_end_type text; v_reason_code text;
 l record; m record; r record; s record;
BEGIN
 IF p_actor IS NULL OR p_target IS NULL OR p_actor=p_target OR p_reason IS NULL
 OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 OR p_type IS NULL OR p_type NOT IN
 ('PLANNED_DEACTIVATION','PLANNED_ARCHIVAL','ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_TERMINATION') THEN
  RAISE EXCEPTION 'Invalid account lifecycle request';
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
 ) THEN RAISE EXCEPTION 'Account lifecycle requires current SYSTEM_ADMIN authority'; END IF;
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
    RAISE EXCEPTION 'Planned departure requires leadership replacement or prior Team deactivation';
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
REVOKE ALL ON FUNCTION public.epms_team_disable_account(uuid,uuid,text,text) FROM PUBLIC;
REVOKE ALL ON public.team_account_lifecycle_events,public.team_account_operation_links FROM PUBLIC;
-- No grants to epms_app or epms_rbac_runtime. Activation is unchanged.
