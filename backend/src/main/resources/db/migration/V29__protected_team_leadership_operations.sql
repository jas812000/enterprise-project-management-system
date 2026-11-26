-- Phase 12 B4.1b/B4.2: protected planned operations and emergency succession.
-- Staged migration: no application EXECUTE grants and V28 activation remains closed.
-- Account lifecycle hooks, runtime routing and resource authorization follow separately.
CREATE TABLE public.team_operation_events (
    event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL,
    team_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN
      ('MEMBER_JOIN','MEMBER_LEAVE','LEADER_ASSIGN','LEADER_END','ALTERNATE_DESIGNATE',
       'ALTERNATE_REVOKE','EMERGENCY','DEACTIVATE','ARCHIVE')),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id,team_id) REFERENCES public.teams(organization_id,team_id),
    FOREIGN KEY (organization_id,actor_user_id) REFERENCES public.users(organization_id,user_id),
    FOREIGN KEY (organization_id,target_user_id) REFERENCES public.users(organization_id,user_id)
);
CREATE TRIGGER trg_team_operation_events_immutable BEFORE UPDATE OR DELETE
 ON public.team_operation_events FOR EACH ROW
 EXECUTE FUNCTION public.epms_reject_security_history_mutation();
CREATE TABLE public.team_security_alerts (
    alert_id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid NOT NULL UNIQUE REFERENCES public.team_operation_events(event_id),
    alert_code text NOT NULL CHECK (alert_code='NO_ELIGIBLE_SUCCESSOR'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER trg_team_security_alerts_immutable BEFORE UPDATE OR DELETE
 ON public.team_security_alerts FOR EACH ROW
 EXECUTE FUNCTION public.epms_reject_security_history_mutation();
ALTER TABLE public.team_leadership_succession_events
 ADD COLUMN operation_event_id uuid REFERENCES public.team_operation_events(event_id);
ALTER TABLE public.team_leadership_assignments
 ADD COLUMN assignment_event_id uuid REFERENCES public.team_operation_events(event_id),
 ADD COLUMN end_event_id uuid REFERENCES public.team_operation_events(event_id),
 ADD COLUMN rbac_assignment_id uuid REFERENCES public.security_user_roles(assignment_id);
CREATE UNIQUE INDEX ux_team_leadership_rbac_assignment
 ON public.team_leadership_assignments(rbac_assignment_id) WHERE rbac_assignment_id IS NOT NULL;

-- The authenticated human initiates an emergency; automatic actions reference that
-- immutable event instead of claiming that the human performed automatic decisions.
ALTER TABLE public.security_user_roles
 ADD COLUMN grant_team_event_id uuid REFERENCES public.team_operation_events(event_id),
 ADD COLUMN revoke_team_event_id uuid REFERENCES public.team_operation_events(event_id);
ALTER TABLE public.security_user_roles DROP CONSTRAINT ck_security_assignment_revoke_actor;
ALTER TABLE public.security_user_roles ADD CONSTRAINT ck_security_assignment_revoke_actor CHECK (
 (revoked_at IS NULL AND revoked_by_user_id IS NULL AND revoke_team_event_id IS NULL) OR
 (revoked_at IS NOT NULL AND num_nonnulls(revoked_by_user_id,revoke_team_event_id)=1));
ALTER TABLE public.security_role_assignment_history
 ALTER COLUMN actor_user_id DROP NOT NULL,
 ADD COLUMN team_event_id uuid REFERENCES public.team_operation_events(event_id),
 ADD CONSTRAINT ck_rbac_history_actor_source CHECK (num_nonnulls(actor_user_id,team_event_id)=1);
ALTER TABLE public.team_leadership_alternates
 ADD COLUMN revocation_event_id uuid REFERENCES public.team_operation_events(event_id);
ALTER TABLE public.team_leadership_alternates DROP CONSTRAINT ck_team_alternate_revocation;
ALTER TABLE public.team_leadership_alternates ADD CONSTRAINT ck_team_alternate_revocation CHECK (
 (revoked_at IS NULL AND revoked_by_user_id IS NULL AND revocation_event_id IS NULL AND revocation_reason IS NULL)
 OR (revoked_at IS NOT NULL AND num_nonnulls(revoked_by_user_id,revocation_event_id)=1
 AND revocation_reason IS NOT NULL AND length(btrim(revocation_reason))>0 AND revoked_at>=designated_at));

CREATE FUNCTION public.epms_team_require_admin(p_actor uuid,p_team uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'Team operation requires a reason'; END IF;
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
  RAISE EXCEPTION 'Team operation requires current organization administration authority'; END IF;
 RETURN v_org;
END $$;
CREATE FUNCTION public.epms_team_record_event(p_org uuid,p_team uuid,p_actor uuid,p_target uuid,p_operation text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_id uuid;
BEGIN
 INSERT INTO public.team_operation_events(organization_id,team_id,actor_user_id,target_user_id,operation,reason)
 VALUES(p_org,p_team,p_actor,p_target,p_operation,btrim(p_reason)) RETURNING event_id INTO v_id;
 RETURN v_id;
END $$;
CREATE FUNCTION public.epms_team_eligible_member(p_membership uuid,p_team uuid)
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT EXISTS (SELECT 1 FROM public.team_memberships m JOIN public.users u
 ON u.user_id=m.user_id AND u.organization_id=m.organization_id
 JOIN public.account_types a USING(account_type_id)
 JOIN public.teams t ON t.team_id=m.team_id AND t.organization_id=m.organization_id
 WHERE m.team_membership_id=p_membership AND m.team_id=p_team AND m.left_at IS NULL
 AND u.is_active AND NOT u.is_archived AND a.code='HUMAN' AND NOT t.is_archived)
$$;
CREATE FUNCTION public.epms_team_has_eligible_leader(p_team uuid)
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT EXISTS (SELECT 1 FROM public.team_leadership_assignments l
 JOIN public.security_user_roles x ON x.assignment_id=l.rbac_assignment_id AND x.user_id=l.user_id
 JOIN public.security_roles r ON r.role_id=x.role_id
 WHERE l.team_id=p_team AND l.ended_at IS NULL AND r.code='TEAM_LEAD' AND r.is_active
 AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp()
 AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp())
 AND public.epms_team_eligible_member(l.team_membership_id,p_team))
$$;

-- Extend V12 guard without weakening final SYSTEM_ADMIN or AUDITOR protections.
CREATE OR REPLACE FUNCTION public.epms_rbac_guard_assignment() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE v_role text; v_actor uuid; v_current boolean; v_count integer;
BEGIN
 PERFORM pg_advisory_xact_lock(1832, 12);
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION 'Role assignments must be revoked, not deleted';
 END IF;
 SELECT code INTO v_role FROM public.security_roles WHERE role_id=NEW.role_id;
 IF v_role IS NULL THEN RAISE EXCEPTION 'Unknown RBAC role'; END IF;
 IF (TG_OP='INSERT' OR NEW.revoked_at IS NULL) AND NOT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types a ON a.account_type_id=u.account_type_id
                WHERE u.user_id=NEW.user_id AND a.code='HUMAN' AND u.is_active AND NOT u.is_archived)
 THEN RAISE EXCEPTION 'RBAC roles require an active HUMAN account'; END IF;
 IF v_role='SYSTEM_ADMIN' AND NEW.valid_until IS NOT NULL THEN
  RAISE EXCEPTION 'SYSTEM_ADMIN assignment cannot expire automatically'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.role_id IS DISTINCT FROM OLD.role_id
     OR NEW.granted_by_user_id IS DISTINCT FROM OLD.granted_by_user_id
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR NEW.grant_team_event_id IS DISTINCT FROM OLD.grant_team_event_id
  THEN RAISE EXCEPTION 'Role assignment identity and validity are immutable'; END IF;
  IF OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                                    OR NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id
                                    OR NEW.revoke_team_event_id IS DISTINCT FROM OLD.revoke_team_event_id)
  THEN RAISE EXCEPTION 'Revocation cannot be reversed or rewritten'; END IF;
  IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL AND NEW.revoked_by_user_id IS NULL AND NEW.revoke_team_event_id IS NULL
  THEN RAISE EXCEPTION 'Revocation requires an actor'; END IF;
 END IF;
 -- Only the protected operation owner can attach a trusted Team event.
 IF NEW.grant_team_event_id IS NOT NULL AND v_role<>'TEAM_LEAD' THEN
  RAISE EXCEPTION 'Team event cannot grant unrelated role'; END IF;
 IF NEW.revoke_team_event_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM public.team_operation_events e WHERE e.event_id=NEW.revoke_team_event_id
  AND e.operation='EMERGENCY' AND e.target_user_id=NEW.user_id AND v_role='TEAM_LEAD') THEN
  RAISE EXCEPTION 'Invalid emergency revocation provenance'; END IF;
 IF TG_OP='UPDATE' AND v_role='TEAM_LEAD' AND NEW.revoked_at IS NOT NULL
 AND EXISTS (SELECT 1 FROM public.team_leadership_assignments l
 WHERE l.rbac_assignment_id=NEW.assignment_id AND l.ended_at IS NULL) THEN
  RAISE EXCEPTION 'Close Team leadership through protected operation before role revocation'; END IF;
 -- AUDITOR cannot coexist with any other role (including SYSTEM_ADMIN).
 IF NEW.revoked_at IS NULL AND (TG_OP='INSERT' OR OLD.revoked_at IS NOT NULL) THEN
  IF v_role='AUDITOR' AND EXISTS (
   SELECT 1 FROM public.security_user_roles x WHERE x.user_id=NEW.user_id AND x.revoked_at IS NULL
     AND (TG_OP='INSERT' OR x.assignment_id<>NEW.assignment_id)
  ) THEN RAISE EXCEPTION 'AUDITOR role cannot be combined with another role'; END IF;
  IF v_role<>'AUDITOR' AND EXISTS (
   SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r ON r.role_id=x.role_id
   WHERE x.user_id=NEW.user_id AND x.revoked_at IS NULL AND r.code='AUDITOR'
     AND (TG_OP='INSERT' OR x.assignment_id<>NEW.assignment_id)
  ) THEN RAISE EXCEPTION 'AUDITOR role cannot be combined with another role'; END IF;
 END IF;
 -- Never allow revocation of the final active administrator.
 IF TG_OP='UPDATE' AND v_role='SYSTEM_ADMIN' AND OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
  SELECT count(*) INTO v_count FROM public.security_user_roles x
   JOIN public.security_roles r ON r.role_id=x.role_id
   JOIN public.users u ON u.user_id=x.user_id
   JOIN public.account_types a ON a.account_type_id=u.account_type_id
   WHERE r.code='SYSTEM_ADMIN' AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp()
    AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp())
    AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
    AND x.assignment_id<>OLD.assignment_id;
  IF v_count=0 THEN RAISE EXCEPTION 'Cannot revoke the last active SYSTEM_ADMIN'; END IF;
 END IF;
 RETURN NEW;
END $$;
GRANT SELECT ON public.team_operation_events,public.team_leadership_assignments TO epms_rbac_function_owner;

-- Internal assignment helper. No runtime can execute it. Existing independent
-- TEAM_LEAD grants are not silently adopted or revoked; reject until reconciled.
CREATE FUNCTION public.epms_team_open_leadership(p_membership uuid,p_team uuid,p_event uuid,p_type text)
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
  RAISE EXCEPTION 'Invalid leadership assignment'; END IF;
 IF EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=m.user_id AND ended_at IS NULL)
 OR EXISTS (SELECT 1 FROM public.team_leadership_alternates WHERE user_id=m.user_id AND revoked_at IS NULL AND team_id<>p_team) THEN
  RAISE EXCEPTION 'User already has a conflicting Team relationship'; END IF;
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
 RETURN v_id;
END $$;
CREATE FUNCTION public.epms_team_close_leadership(p_assignment uuid,p_event uuid)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE l public.team_leadership_assignments%ROWTYPE; e public.team_operation_events%ROWTYPE; v_now timestamptz;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 SELECT * INTO STRICT l FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND ended_at IS NULL;
 SELECT * INTO STRICT e FROM public.team_operation_events WHERE event_id=p_event;
 IF e.team_id<>l.team_id OR e.target_user_id<>l.user_id OR e.operation NOT IN ('LEADER_END','EMERGENCY')
 OR l.rbac_assignment_id IS NULL THEN RAISE EXCEPTION 'Invalid leadership closure'; END IF;
 v_now:=clock_timestamp();
 UPDATE public.team_leadership_assignments SET ended_at=v_now,
 ended_by_user_id=CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 end_event_id=p_event,end_reason_code=e.operation,end_comment=e.reason,updated_at=v_now,version=version+1
 WHERE team_leadership_assignment_id=p_assignment;
 UPDATE public.security_user_roles SET revoked_at=v_now,
 revoked_by_user_id=CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 revoke_team_event_id=CASE WHEN e.operation='EMERGENCY' THEN p_event END
 WHERE assignment_id=l.rbac_assignment_id AND revoked_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Leadership RBAC assignment missing or revoked'; END IF;
 INSERT INTO public.security_role_assignment_history(assignment_id,target_user_id,actor_user_id,team_event_id,action,reason)
 VALUES(l.rbac_assignment_id,l.user_id,CASE WHEN e.operation<>'EMERGENCY' THEN e.actor_user_id END,
 CASE WHEN e.operation='EMERGENCY' THEN p_event END,'REVOKE',e.reason);
END $$;
CREATE FUNCTION public.epms_team_assign_permanent(p_actor uuid,p_team uuid,p_membership uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'LEADER_ASSIGN',p_reason);
 RETURN public.epms_team_open_leadership(p_membership,p_team,v_event,'PERMANENT');
END $$;
CREATE FUNCTION public.epms_team_end_planned(p_actor uuid,p_team uuid,p_assignment uuid,p_replacement uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
 IF v_user=p_actor THEN RAISE EXCEPTION 'Self-removal not allowed'; END IF;
 IF p_replacement IS NOT NULL THEN PERFORM public.epms_team_assign_permanent(p_actor,p_team,p_replacement,p_reason); END IF;
 v_event:=public.epms_team_record_event(v_org,p_team,p_actor,v_user,'LEADER_END',p_reason);
 PERFORM public.epms_team_close_leadership(p_assignment,v_event);
 IF EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active)
 AND NOT public.epms_team_has_eligible_leader(p_team) THEN
  RAISE EXCEPTION 'Active Team requires a leader; replace or deactivate first'; END IF;
END $$;
CREATE FUNCTION public.epms_team_designate_alternate(p_actor uuid,p_team uuid,p_membership uuid,p_priority smallint,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_id uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team;
 IF v_user=p_actor OR NOT public.epms_team_eligible_member(p_membership,p_team)
 OR EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE user_id=v_user AND ended_at IS NULL)
 OR EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
 WHERE x.user_id=v_user AND x.revoked_at IS NULL AND r.code IN ('TEAM_LEAD','AUDITOR')) THEN
  RAISE EXCEPTION 'Ineligible dormant alternate'; END IF;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'ALTERNATE_DESIGNATE',p_reason);
 INSERT INTO public.team_leadership_alternates(team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id)
 VALUES(p_membership,v_org,p_team,v_user,p_priority,p_actor) RETURNING team_leadership_alternate_id INTO v_id;
 RETURN v_id;
END $$;
CREATE FUNCTION public.epms_team_emergency_succession(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid; v_event uuid; v_incoming uuid; v_alt uuid; v_outcome text; a record;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 IF p_type IS NULL OR p_type NOT IN ('ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_LEADERSHIP_SUSPENSION','EMERGENCY_UNAVAILABILITY','EMERGENCY_TERMINATION') THEN
  RAISE EXCEPTION 'Emergency succession requires an emergency event'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active) THEN
  RAISE EXCEPTION 'Emergency succession requires active Team'; END IF;
 SELECT user_id INTO STRICT v_user FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=p_assignment AND team_id=p_team AND ended_at IS NULL;
 IF p_actor=v_user THEN RAISE EXCEPTION 'Self-declared emergency prohibited'; END IF;
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
   UPDATE public.teams SET lifecycle_status='INACTIVE',is_active=false,updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team;
  END IF;
 END IF;
 INSERT INTO public.team_leadership_succession_events(organization_id,team_id,outgoing_user_id,evaluated_alternate_id,incoming_assignment_id,emergency_event_type,outcome,event_reason,operation_event_id)
 VALUES(v_org,p_team,v_user,v_alt,v_incoming,p_type,v_outcome,btrim(p_reason),v_event);
 RETURN v_incoming;
END $$;

-- Planned membership operations; no implicit emergency succession.
CREATE FUNCTION public.epms_team_join_member(p_actor uuid,p_team uuid,p_user uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_id uuid; v_period integer;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT coalesce(max(membership_period_number),0)+1 INTO v_period FROM public.team_memberships WHERE team_id=p_team AND user_id=p_user;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,p_user,'MEMBER_JOIN',p_reason);
 INSERT INTO public.team_memberships(organization_id,team_id,user_id,membership_period_number)
 VALUES(v_org,p_team,p_user,v_period) RETURNING team_membership_id INTO v_id;
 RETURN v_id;
END $$;
CREATE FUNCTION public.epms_team_leave_member(p_actor uuid,p_team uuid,p_membership uuid,p_end_type text,p_reason_code text,p_comment text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_comment);
 SELECT user_id INTO STRICT v_user FROM public.team_memberships WHERE team_membership_id=p_membership AND team_id=p_team AND left_at IS NULL;
 IF p_end_type IS NULL OR p_end_type NOT IN ('VOLUNTARY','ADMINISTRATIVE') OR NOT EXISTS
 (SELECT 1 FROM public.membership_end_reasons WHERE code=p_reason_code AND is_active) THEN
  RAISE EXCEPTION 'Invalid planned membership removal reason'; END IF;
 IF EXISTS (SELECT 1 FROM public.team_leadership_assignments WHERE team_membership_id=p_membership AND ended_at IS NULL) THEN
  RAISE EXCEPTION 'End leadership before membership'; END IF;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'MEMBER_LEAVE',p_comment);
 UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor,revocation_reason=p_comment
 WHERE team_membership_id=p_membership AND revoked_at IS NULL;
 UPDATE public.team_memberships SET left_at=clock_timestamp(),removed_by_user_id=p_actor,end_type_code=p_end_type,
 removal_reason_code=p_reason_code,removal_comment=p_comment,updated_at=clock_timestamp(),version=version+1 WHERE team_membership_id=p_membership;
 IF EXISTS (SELECT 1 FROM public.teams WHERE team_id=p_team AND is_active)
 AND NOT public.epms_team_has_eligible_leader(p_team) THEN RAISE EXCEPTION 'Active Team requires eligible leadership'; END IF;
END $$;
CREATE FUNCTION public.epms_team_revoke_alternate(p_actor uuid,p_team uuid,p_alternate uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_user uuid;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT user_id INTO STRICT v_user FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=p_alternate AND team_id=p_team AND revoked_at IS NULL;
 PERFORM public.epms_team_record_event(v_org,p_team,p_actor,v_user,'ALTERNATE_REVOKE',p_reason);
 UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),revoked_by_user_id=p_actor,revocation_reason=p_reason WHERE team_leadership_alternate_id=p_alternate;
END $$;

-- No SECURITY DEFINER exposure in this staged migration. Runtime access remains closed.
REVOKE ALL ON public.team_operation_events,public.team_security_alerts FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_team_require_admin(uuid,uuid,text),
 public.epms_team_record_event(uuid,uuid,uuid,uuid,text,text),
 public.epms_team_eligible_member(uuid,uuid),public.epms_team_has_eligible_leader(uuid),
 public.epms_team_open_leadership(uuid,uuid,uuid,text),public.epms_team_close_leadership(uuid,uuid),
 public.epms_team_assign_permanent(uuid,uuid,uuid,text),public.epms_team_end_planned(uuid,uuid,uuid,uuid,text),
 public.epms_team_designate_alternate(uuid,uuid,uuid,smallint,text),
 public.epms_team_emergency_succession(uuid,uuid,uuid,text,text),
 public.epms_team_join_member(uuid,uuid,uuid,text),public.epms_team_leave_member(uuid,uuid,uuid,text,text,text),
 public.epms_team_revoke_alternate(uuid,uuid,uuid,text) FROM PUBLIC;
