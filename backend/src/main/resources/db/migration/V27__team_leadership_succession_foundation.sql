-- EPMS Phase 12 B3.2: leadership relationships, DORMANT alternate designations,
-- protected succession event history. Does NOT grant active TEAM_LEAD authority.
CREATE TABLE public.team_leadership_assignments (
    team_leadership_assignment_id uuid PRIMARY KEY DEFAULT uuidv7(),
    team_membership_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid NOT NULL,
    assigned_by_user_id uuid,
    ended_by_user_id uuid,
    assignment_type varchar(20) NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ended_at timestamptz,
    end_reason_code varchar(50),
    end_comment text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_team_leadership_membership
      FOREIGN KEY (team_membership_id,organization_id,team_id,user_id)
      REFERENCES public.team_memberships(team_membership_id,organization_id,team_id,user_id),
    CONSTRAINT fk_team_leadership_assigned_actor FOREIGN KEY (organization_id,assigned_by_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_team_leadership_ended_actor FOREIGN KEY (organization_id,ended_by_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT ck_team_leadership_type CHECK (assignment_type IN ('PERMANENT','ACTING')),
    CONSTRAINT ck_team_leadership_period CHECK (ended_at IS NULL OR ended_at>=assigned_at),
    CONSTRAINT ck_team_leadership_version CHECK (version>=0),
    CONSTRAINT ck_team_leadership_end CHECK (
      (ended_at IS NULL AND ended_by_user_id IS NULL AND end_reason_code IS NULL AND end_comment IS NULL)
      OR
      (ended_at IS NOT NULL AND end_reason_code IS NOT NULL AND length(btrim(end_reason_code))>0
       AND end_comment IS NOT NULL AND length(btrim(end_comment))>0)
    )
);
CREATE UNIQUE INDEX ux_team_leadership_open_user
 ON public.team_leadership_assignments(user_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX ux_team_leadership_open_member
 ON public.team_leadership_assignments(team_membership_id) WHERE ended_at IS NULL;
CREATE INDEX ix_team_leadership_team
 ON public.team_leadership_assignments(team_id,assigned_at DESC);

-- An alternate designation is dormant. The separate active leadership relationship
-- and current effective permission checks must BOTH be satisfied for Team Lead access.
CREATE TABLE public.team_leadership_alternates (
    team_leadership_alternate_id uuid PRIMARY KEY DEFAULT uuidv7(),
    team_membership_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid NOT NULL,
    priority smallint NOT NULL,
    designated_by_user_id uuid NOT NULL,
    designated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_by_user_id uuid,
    revoked_at timestamptz,
    revocation_reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_team_alternates_membership
      FOREIGN KEY (team_membership_id,organization_id,team_id,user_id)
      REFERENCES public.team_memberships(team_membership_id,organization_id,team_id,user_id),
    CONSTRAINT fk_team_alternates_team FOREIGN KEY (organization_id,team_id)
      REFERENCES public.teams(organization_id,team_id),
    CONSTRAINT fk_team_alternates_user FOREIGN KEY (organization_id,user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_team_alternates_designator FOREIGN KEY (organization_id,designated_by_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_team_alternates_revoker FOREIGN KEY (organization_id,revoked_by_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT ck_team_alternate_priority CHECK (priority IN (1,2)),
    CONSTRAINT ck_team_alternate_revocation CHECK (
      (revoked_at IS NULL AND revoked_by_user_id IS NULL AND revocation_reason IS NULL)
      OR
      (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL AND
       revocation_reason IS NOT NULL AND length(btrim(revocation_reason))>0
       AND revoked_at>=designated_at)
    ),
    CONSTRAINT ck_team_alternate_self CHECK (user_id<>designated_by_user_id)
);
CREATE UNIQUE INDEX ux_team_alternates_open_user
 ON public.team_leadership_alternates(user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX ux_team_alternates_open_priority
 ON public.team_leadership_alternates(team_id,priority) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX ux_team_alternates_open_team_user
 ON public.team_leadership_alternates(team_id,user_id) WHERE revoked_at IS NULL;

CREATE TABLE public.team_leadership_succession_events (
    succession_event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL,
    team_id uuid NOT NULL,
    outgoing_user_id uuid NOT NULL,
    evaluated_alternate_id uuid REFERENCES public.team_leadership_alternates(team_leadership_alternate_id),
    incoming_assignment_id uuid REFERENCES public.team_leadership_assignments(team_leadership_assignment_id),
    emergency_event_type varchar(60) NOT NULL,
    outcome varchar(50) NOT NULL,
    event_reason text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_team_succession_team FOREIGN KEY (organization_id,team_id)
      REFERENCES public.teams(organization_id,team_id),
    CONSTRAINT fk_team_succession_outgoing FOREIGN KEY (organization_id,outgoing_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT ck_team_succession_emergency_only CHECK (emergency_event_type IN
      ('ACCOUNT_SECURITY','EMERGENCY_DEACTIVATION','EMERGENCY_LEADERSHIP_SUSPENSION',
       'EMERGENCY_UNAVAILABILITY','EMERGENCY_TERMINATION')),
    CONSTRAINT ck_team_succession_outcome CHECK (outcome IN
      ('OTHER_LEAD_AVAILABLE','FIRST_ALTERNATE_ACTIVATED','SECOND_ALTERNATE_ACTIVATED',
       'NO_ELIGIBLE_SUCCESSOR','DEACTIVATION_REQUIRED')),
    CONSTRAINT ck_team_succession_reason CHECK (length(btrim(event_reason))>0)
);
-- Existing V12 history guard prevents UPDATE and DELETE, preserving event history.
CREATE TRIGGER trg_team_succession_events_immutable
 BEFORE UPDATE OR DELETE ON public.team_leadership_succession_events
 FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();

-- A designated alternate must be a currently eligible HUMAN member.
-- Protected operations must still recheck eligibility transactionally at activation.
CREATE FUNCTION public.epms_team_alternate_designation_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS (
    SELECT 1 FROM public.team_memberships m
    JOIN public.users u ON u.user_id=m.user_id AND u.organization_id=m.organization_id
    JOIN public.account_types a ON a.account_type_id=u.account_type_id
    WHERE m.team_membership_id=NEW.team_membership_id
      AND m.organization_id=NEW.organization_id
      AND m.team_id=NEW.team_id AND m.user_id=NEW.user_id
      AND m.left_at IS NULL AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
 ) THEN
    RAISE EXCEPTION 'Emergency alternate must be an eligible current HUMAN team member';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_alternate_designation_guard
 BEFORE INSERT ON public.team_leadership_alternates
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_alternate_designation_guard();

CREATE FUNCTION public.epms_team_leadership_period_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Leadership history cannot be deleted';
 END IF;
 IF OLD.ended_at IS NOT NULL THEN
   RAISE EXCEPTION 'Closed leadership periods are immutable';
 END IF;
 IF NEW.team_leadership_assignment_id IS DISTINCT FROM OLD.team_leadership_assignment_id OR
    NEW.team_membership_id IS DISTINCT FROM OLD.team_membership_id OR
    NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
    NEW.team_id IS DISTINCT FROM OLD.team_id OR
    NEW.user_id IS DISTINCT FROM OLD.user_id OR
    NEW.assigned_at IS DISTINCT FROM OLD.assigned_at OR
    NEW.assignment_type IS DISTINCT FROM OLD.assignment_type OR
    NEW.assigned_by_user_id IS DISTINCT FROM OLD.assigned_by_user_id OR
    NEW.created_at IS DISTINCT FROM OLD.created_at THEN
   RAISE EXCEPTION 'Leadership assignment identity is immutable';
 END IF;
 IF NEW.ended_at IS NULL THEN
   RAISE EXCEPTION 'Leadership assignment updates must close the assignment';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_leadership_period_guard
 BEFORE UPDATE OR DELETE ON public.team_leadership_assignments
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_leadership_period_guard();

CREATE FUNCTION public.epms_team_alternate_period_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Alternate designation history cannot be deleted';
 END IF;
 IF OLD.revoked_at IS NOT NULL THEN
   RAISE EXCEPTION 'Revoked alternate designations are immutable';
 END IF;
 IF NEW.team_leadership_alternate_id IS DISTINCT FROM OLD.team_leadership_alternate_id OR
    NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
    NEW.team_id IS DISTINCT FROM OLD.team_id OR
    NEW.user_id IS DISTINCT FROM OLD.user_id OR
    NEW.priority IS DISTINCT FROM OLD.priority OR
    NEW.designated_at IS DISTINCT FROM OLD.designated_at OR
    NEW.designated_by_user_id IS DISTINCT FROM OLD.designated_by_user_id OR
    NEW.created_at IS DISTINCT FROM OLD.created_at THEN
   RAISE EXCEPTION 'Alternate designation identity is immutable';
 END IF;
 IF NEW.revoked_at IS NULL THEN
   RAISE EXCEPTION 'Alternate designation updates must revoke the designation';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_alternate_period_guard
 BEFORE UPDATE OR DELETE ON public.team_leadership_alternates
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_alternate_period_guard();

REVOKE ALL ON FUNCTION public.epms_team_leadership_period_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_team_alternate_period_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_team_alternate_designation_guard() FROM PUBLIC;
REVOKE ALL ON TABLE public.team_leadership_assignments, public.team_leadership_alternates,
 public.team_leadership_succession_events FROM PUBLIC;
-- Deliberate fail-closed boundary: no B3.2 runtime read/write grants, no
-- emergency activation functions, no implicit RBAC role grants.
