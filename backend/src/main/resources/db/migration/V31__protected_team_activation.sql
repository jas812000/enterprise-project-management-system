-- Phase 12 B4.4: owner-only protected activation and active-Team integrity.
-- Runtime routing remains closed until authenticated service integration is verified.
CREATE TABLE public.team_lifecycle_events (
 event_id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL,
 team_id uuid NOT NULL,
 actor_user_id uuid NOT NULL,
 from_status varchar(32) NOT NULL,
 to_status varchar(32) NOT NULL,
 resulting_version bigint NOT NULL,
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(team_id,resulting_version),
 FOREIGN KEY(organization_id,team_id) REFERENCES public.teams(organization_id,team_id),
 FOREIGN KEY(organization_id,actor_user_id) REFERENCES public.users(organization_id,user_id),
 CHECK ((from_status IN ('PENDING_SETUP','INACTIVE') AND to_status='ACTIVE')
     OR (from_status='ACTIVE' AND to_status='INACTIVE')
     OR (from_status IN ('PENDING_SETUP','INACTIVE') AND to_status='ARCHIVED'))
);
CREATE TRIGGER trg_team_lifecycle_events_immutable BEFORE UPDATE OR DELETE ON public.team_lifecycle_events
 FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
REVOKE ALL ON public.team_lifecycle_events FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.epms_team_lifecycle_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF TG_OP='INSERT' THEN
  IF NEW.lifecycle_status<>'PENDING_SETUP' THEN RAISE EXCEPTION 'New teams must begin in pending setup'; END IF;
 ELSIF NEW.lifecycle_status IS DISTINCT FROM OLD.lifecycle_status THEN
  IF NOT ((OLD.lifecycle_status IN ('PENDING_SETUP','INACTIVE') AND NEW.lifecycle_status IN ('ACTIVE','ARCHIVED'))
   OR (OLD.lifecycle_status='ACTIVE' AND NEW.lifecycle_status='INACTIVE')) THEN
   RAISE EXCEPTION 'Invalid team lifecycle transition';
  END IF;
  IF NEW.version<>OLD.version+1 OR NOT EXISTS (
    SELECT 1 FROM public.team_lifecycle_events e WHERE e.team_id=OLD.team_id
    AND e.organization_id=OLD.organization_id AND e.from_status=OLD.lifecycle_status
    AND e.to_status=NEW.lifecycle_status AND e.resulting_version=NEW.version
    AND e.actor_user_id=NEW.updated_by_user_id) THEN
    RAISE EXCEPTION 'Team lifecycle requires protected operation';
   END IF;
  IF NEW.lifecycle_status='ACTIVE' THEN
   IF NOT public.epms_team_has_eligible_leader(OLD.team_id) THEN
    RAISE EXCEPTION 'Team activation requires an eligible member and leader';
   END IF;
  END IF;
  IF NEW.lifecycle_status='ARCHIVED' AND EXISTS (
   SELECT 1 FROM public.team_memberships WHERE team_id=OLD.team_id AND left_at IS NULL) THEN
   RAISE EXCEPTION 'Close Team dependencies before archival';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.epms_team_transition(p_actor uuid,p_team uuid,p_status text,p_reason text)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; t public.teams%ROWTYPE; v_event uuid; v_op uuid; v_now timestamptz; l record;
BEGIN
 v_org:=public.epms_team_require_admin(p_actor,p_team,p_reason);
 SELECT * INTO STRICT t FROM public.teams WHERE team_id=p_team FOR UPDATE;
 IF p_status IS NULL OR NOT ((t.lifecycle_status IN ('PENDING_SETUP','INACTIVE') AND p_status IN ('ACTIVE','ARCHIVED'))
 OR (t.lifecycle_status='ACTIVE' AND p_status='INACTIVE')) THEN RAISE EXCEPTION 'Invalid Team transition'; END IF;
 IF p_status='ACTIVE' AND NOT public.epms_team_has_eligible_leader(p_team) THEN
  RAISE EXCEPTION 'Team activation requires eligible leadership'; END IF;
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

-- Resource scope must be evaluated in addition to any ordinary permission check.
-- Owner-only predicate; no runtime grants or assumption of trusted caller identity.
CREATE FUNCTION public.epms_team_has_leadership_scope(p_user uuid,p_team uuid)
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT EXISTS (SELECT 1 FROM public.team_leadership_assignments l
 JOIN public.teams t ON t.team_id=l.team_id AND t.organization_id=l.organization_id
 JOIN public.security_user_roles x ON x.assignment_id=l.rbac_assignment_id AND x.user_id=l.user_id
 JOIN public.security_roles r ON r.role_id=x.role_id
 WHERE l.user_id=p_user AND l.team_id=p_team AND t.is_active AND NOT t.is_archived
 AND l.ended_at IS NULL AND r.code='TEAM_LEAD' AND r.is_active AND x.revoked_at IS NULL
 AND x.valid_from<=clock_timestamp() AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp())
 AND public.epms_team_eligible_member(l.team_membership_id,p_team))
$$;

-- A separate trigger avoids rewriting the V29 RBAC protections. The RBAC definer
-- can read role catalog and event records under the preexisting V13/V29 grants.
CREATE FUNCTION public.epms_team_guard_role_grant() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF EXISTS (SELECT 1 FROM public.security_roles WHERE role_id=NEW.role_id AND code='TEAM_LEAD') THEN
  IF NEW.grant_team_event_id IS NULL OR NEW.valid_until IS NOT NULL OR NOT EXISTS (
   SELECT 1 FROM public.team_operation_events e WHERE e.event_id=NEW.grant_team_event_id
    AND ((e.operation='LEADER_ASSIGN' AND e.target_user_id=NEW.user_id AND e.actor_user_id=NEW.granted_by_user_id)
      OR (e.operation='EMERGENCY' AND NEW.granted_by_user_id IS NULL AND e.target_user_id<>NEW.user_id))) THEN
   RAISE EXCEPTION 'TEAM_LEAD requires protected Team leadership assignment';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_guard_role_grant BEFORE INSERT ON public.security_user_roles
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_guard_role_grant();

-- Validate final transaction state, allowing atomic replacement and emergency
-- succession to pass through intermediate states inside the same transaction.
-- Definer required for trigger execution from restricted RBAC and user writers.
CREATE FUNCTION public.epms_team_validate_transaction() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF EXISTS (SELECT 1 FROM public.teams t WHERE t.is_active
  AND NOT public.epms_team_has_eligible_leader(t.team_id)) THEN
  RAISE EXCEPTION 'Active Team must retain eligible membership and leadership';
 END IF;
 IF EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
  WHERE r.code='TEAM_LEAD' AND x.revoked_at IS NULL AND NOT EXISTS (
   SELECT 1 FROM public.team_leadership_assignments l WHERE l.rbac_assignment_id=x.assignment_id
   AND l.user_id=x.user_id AND l.ended_at IS NULL AND public.epms_team_eligible_member(l.team_membership_id,l.team_id))) THEN
  RAISE EXCEPTION 'TEAM_LEAD requires an open eligible Team relationship';
 END IF;
 IF EXISTS (SELECT 1 FROM public.team_leadership_assignments l JOIN public.teams t USING(team_id)
  WHERE l.ended_at IS NULL AND (NOT public.epms_team_eligible_member(l.team_membership_id,l.team_id) OR NOT EXISTS (
   SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
   WHERE x.assignment_id=l.rbac_assignment_id AND x.user_id=l.user_id AND r.code='TEAM_LEAD'
   AND r.is_active AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp() AND x.valid_until IS NULL))) THEN
  RAISE EXCEPTION 'Active Team leadership requires valid scoped RBAC';
 END IF;
 RETURN NULL;
END $$;
-- Reject migration of unreconciled legacy grants rather than silently revoking them.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
  WHERE r.code='TEAM_LEAD' AND x.revoked_at IS NULL AND NOT EXISTS (
   SELECT 1 FROM public.team_leadership_assignments l WHERE l.rbac_assignment_id=x.assignment_id AND l.user_id=x.user_id
   AND l.ended_at IS NULL AND public.epms_team_eligible_member(l.team_membership_id,l.team_id))) THEN
  RAISE EXCEPTION 'Reconcile legacy independent TEAM_LEAD grants before V31';
 END IF;
END $$;
CREATE CONSTRAINT TRIGGER trg_team_final_state AFTER INSERT OR UPDATE ON public.teams
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.epms_team_validate_transaction();
CREATE CONSTRAINT TRIGGER trg_team_membership_final_state AFTER INSERT OR UPDATE ON public.team_memberships
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.epms_team_validate_transaction();
CREATE CONSTRAINT TRIGGER trg_team_leadership_final_state AFTER INSERT OR UPDATE ON public.team_leadership_assignments
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.epms_team_validate_transaction();
CREATE CONSTRAINT TRIGGER trg_team_rbac_final_state AFTER INSERT OR UPDATE ON public.security_user_roles
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.epms_team_validate_transaction();
CREATE CONSTRAINT TRIGGER trg_team_account_final_state AFTER UPDATE OF is_active,is_archived,account_type_id ON public.users
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.epms_team_validate_transaction();
REVOKE ALL ON FUNCTION public.epms_team_transition(uuid,uuid,text,text),
 public.epms_team_has_leadership_scope(uuid,uuid), public.epms_team_guard_role_grant(),
 public.epms_team_validate_transaction() FROM PUBLIC;

-- Preserve existing protected function ACLs while completing lifecycle provenance.
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
 IF p_type='PERMANENT' THEN
  UPDATE public.team_leadership_alternates SET revoked_at=clock_timestamp(),
   revoked_by_user_id=e.actor_user_id,revocation_reason=e.reason
   WHERE user_id=m.user_id AND team_id=p_team AND revoked_at IS NULL;
 END IF;
 RETURN v_id;
END $$;
CREATE OR REPLACE FUNCTION public.epms_team_emergency_succession(p_actor uuid,p_team uuid,p_assignment uuid,p_type text,p_reason text)
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
   INSERT INTO public.team_lifecycle_events(organization_id,team_id,actor_user_id,from_status,to_status,resulting_version,reason)
    SELECT v_org,p_team,p_actor,lifecycle_status,'INACTIVE',version+1,btrim(p_reason) FROM public.teams WHERE team_id=p_team;
   UPDATE public.teams SET lifecycle_status='INACTIVE',is_active=false,updated_by_user_id=p_actor,updated_at=clock_timestamp(),version=version+1 WHERE team_id=p_team;
  END IF;
 END IF;
 INSERT INTO public.team_leadership_succession_events(organization_id,team_id,outgoing_user_id,evaluated_alternate_id,incoming_assignment_id,emergency_event_type,outcome,event_reason,operation_event_id)
 VALUES(v_org,p_team,v_user,v_alt,v_incoming,p_type,v_outcome,btrim(p_reason),v_event);
 RETURN v_incoming;
END $$;


-- TEAM_LEAD permissions must not be interpreted as global permissions.
CREATE OR REPLACE VIEW public.security_effective_user_permissions AS
 SELECT DISTINCT x.user_id,p.code AS permission_code
 FROM public.security_user_roles x
 JOIN public.users u ON u.user_id=x.user_id
 JOIN public.account_types a ON a.account_type_id=u.account_type_id
 JOIN public.security_roles r ON r.role_id=x.role_id
 JOIN public.security_role_permissions rp ON rp.role_id=r.role_id
 JOIN public.security_permissions p ON p.permission_id=rp.permission_id
 WHERE u.is_active AND NOT u.is_archived AND a.code='HUMAN'
   AND r.code<>'TEAM_LEAD' AND r.is_active AND p.is_active AND x.revoked_at IS NULL
   AND x.valid_from<=clock_timestamp()
   AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp());

CREATE FUNCTION public.epms_team_has_permission(p_user uuid,p_team uuid,p_permission text)
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT public.epms_team_has_leadership_scope(p_user,p_team) AND EXISTS (
  SELECT 1 FROM public.security_roles r JOIN public.security_role_permissions rp USING(role_id)
  JOIN public.security_permissions p USING(permission_id)
  WHERE r.code='TEAM_LEAD' AND r.is_active AND p.is_active AND p.code=p_permission)
$$;
REVOKE ALL ON FUNCTION public.epms_team_has_permission(uuid,uuid,text) FROM PUBLIC;

-- Read-only readiness report; delivery of warnings is a separate service batch.
CREATE FUNCTION public.epms_team_alternate_readiness(p_team uuid)
RETURNS TABLE(priority integer,readiness text) LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT p.priority,CASE WHEN a.team_leadership_alternate_id IS NULL THEN 'MISSING'
  WHEN public.epms_team_eligible_member(a.team_membership_id,p_team)
   AND NOT EXISTS (SELECT 1 FROM public.team_leadership_assignments l WHERE l.user_id=a.user_id AND l.ended_at IS NULL)
   AND NOT EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
    WHERE x.user_id=a.user_id AND x.revoked_at IS NULL AND r.code IN ('TEAM_LEAD','AUDITOR'))
  THEN 'ELIGIBLE' ELSE 'INELIGIBLE' END
 FROM generate_series(1,2) AS p(priority)
 LEFT JOIN public.team_leadership_alternates a ON a.team_id=p_team AND a.priority=p.priority AND a.revoked_at IS NULL
 WHERE EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=p_team AND NOT t.is_archived)
 ORDER BY p.priority
$$;
REVOKE ALL ON FUNCTION public.epms_team_alternate_readiness(uuid) FROM PUBLIC;

CREATE FUNCTION public.epms_team_guard_leadership_provenance() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.assignment_event_id IS DISTINCT FROM OLD.assignment_event_id OR
    NEW.rbac_assignment_id IS DISTINCT FROM OLD.rbac_assignment_id THEN
  RAISE EXCEPTION 'Leadership authorization provenance is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_leadership_provenance BEFORE UPDATE ON public.team_leadership_assignments
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_guard_leadership_provenance();
REVOKE ALL ON FUNCTION public.epms_team_guard_leadership_provenance() FROM PUBLIC;
