-- EPMS Phase 12 B3.2: temporal memberships; write operations remain restricted.
CREATE TABLE public.team_memberships (
    team_membership_id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid NOT NULL,
    removed_by_user_id uuid,
    membership_period_number integer NOT NULL,
    joined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    left_at timestamptz,
    end_type_code varchar(50),
    removal_reason_code varchar(50),
    removal_comment text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_team_memberships_identity UNIQUE
      (team_membership_id, organization_id,team_id,user_id),
    CONSTRAINT uq_team_memberships_period UNIQUE
      (team_id,user_id,membership_period_number),
    CONSTRAINT fk_team_memberships_team FOREIGN KEY (organization_id,team_id)
      REFERENCES public.teams(organization_id,team_id),
    CONSTRAINT fk_team_memberships_user FOREIGN KEY (organization_id,user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_team_memberships_removed_actor FOREIGN KEY (organization_id,removed_by_user_id)
      REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_team_memberships_reason FOREIGN KEY (removal_reason_code)
      REFERENCES public.membership_end_reasons(code),
    CONSTRAINT ck_team_memberships_period CHECK (membership_period_number>0),
    CONSTRAINT ck_team_memberships_version CHECK (version>=0),
    CONSTRAINT ck_team_memberships_end_type CHECK
      (end_type_code IS NULL OR end_type_code IN
      ('VOLUNTARY','ADMINISTRATIVE','ACCOUNT_DEACTIVATED','ACCOUNT_ARCHIVED','TEAM_ARCHIVED')),
    CONSTRAINT ck_team_memberships_end_sequence CHECK (left_at IS NULL OR left_at>=joined_at),
    CONSTRAINT ck_team_memberships_closure CHECK (
      (left_at IS NULL AND end_type_code IS NULL AND removal_reason_code IS NULL
       AND removal_comment IS NULL AND removed_by_user_id IS NULL)
      OR
      (left_at IS NOT NULL AND end_type_code IS NOT NULL AND removal_reason_code IS NOT NULL
       AND (
         (end_type_code IN ('VOLUNTARY','ADMINISTRATIVE')
            AND removed_by_user_id IS NOT NULL
            AND removal_comment IS NOT NULL AND length(btrim(removal_comment))>0)
         OR
         (end_type_code IN ('ACCOUNT_DEACTIVATED','ACCOUNT_ARCHIVED','TEAM_ARCHIVED')
            AND removal_comment IS NOT NULL AND length(btrim(removal_comment))>0)
       ))
    )
);
CREATE UNIQUE INDEX ux_team_memberships_open ON public.team_memberships(team_id,user_id)
 WHERE left_at IS NULL;
CREATE INDEX ix_team_memberships_user ON public.team_memberships(user_id,joined_at DESC);

-- Close once, never rewrite historical membership periods or delete.
CREATE FUNCTION public.epms_team_membership_history_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Team membership history cannot be deleted';
 END IF;
 IF OLD.left_at IS NOT NULL THEN
   RAISE EXCEPTION 'Closed team membership periods are immutable';
 END IF;
 IF NEW.team_membership_id IS DISTINCT FROM OLD.team_membership_id OR
    NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
    NEW.team_id IS DISTINCT FROM OLD.team_id OR
    NEW.user_id IS DISTINCT FROM OLD.user_id OR
    NEW.membership_period_number IS DISTINCT FROM OLD.membership_period_number OR
    NEW.joined_at IS DISTINCT FROM OLD.joined_at OR
    NEW.created_at IS DISTINCT FROM OLD.created_at THEN
   RAISE EXCEPTION 'Membership identity and period are immutable';
 END IF;
 IF NEW.left_at IS NULL THEN
   RAISE EXCEPTION 'Membership updates must close the period';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_membership_history_guard
 BEFORE UPDATE OR DELETE ON public.team_memberships
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_membership_history_guard();
REVOKE ALL ON FUNCTION public.epms_team_membership_history_guard() FROM PUBLIC;
REVOKE ALL ON TABLE public.team_memberships FROM PUBLIC;
