-- EPMS Phase 12 B3.2: closed-by-default physical foundation; not an API enablement.
-- Single-organization ownership uses existing V1 composite FK patterns.
CREATE TABLE public.membership_end_reasons (
    membership_end_reason_id uuid PRIMARY KEY DEFAULT uuidv7(),
    code varchar(50) NOT NULL UNIQUE,
    display_name varchar(150) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_membership_end_reason_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_membership_end_reason_display CHECK (length(btrim(display_name)) > 0)
);
INSERT INTO public.membership_end_reasons(code,display_name,sort_order) VALUES
 ('VOLUNTARY_DEPARTURE','Voluntary departure',10),
 ('TEAM_REASSIGNMENT','Team reassignment',20),
 ('ROLE_CHANGE','Role change',30),
 ('ORGANIZATIONAL_CHANGE','Organizational change',40),
 ('EMPLOYMENT_ENDED','Employment ended',50),
 ('ADMINISTRATIVE_DECISION','Administrative decision',60),
 ('ACCOUNT_SECURITY','Account security',70),
 ('TEAM_DISSOLVED','Team dissolved',80),
 ('OTHER','Other',90);

CREATE TABLE public.teams (
    team_id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES public.organizations(organization_id),
    created_by_user_id uuid,
    updated_by_user_id uuid,
    archived_by_user_id uuid,
    name varchar(200) NOT NULL,
    description text,
    lifecycle_status varchar(32) NOT NULL DEFAULT 'PENDING_SETUP',
    is_active boolean NOT NULL DEFAULT false,
    is_archived boolean NOT NULL DEFAULT false,
    archived_at timestamptz,
    archive_reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_teams_org_team UNIQUE (organization_id,team_id),
    CONSTRAINT fk_teams_created_actor FOREIGN KEY (organization_id,created_by_user_id)
        REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_teams_updated_actor FOREIGN KEY (organization_id,updated_by_user_id)
        REFERENCES public.users(organization_id,user_id),
    CONSTRAINT fk_teams_archived_actor FOREIGN KEY (organization_id,archived_by_user_id)
        REFERENCES public.users(organization_id,user_id),
    CONSTRAINT ck_teams_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_teams_status_flags CHECK (
        (lifecycle_status='ACTIVE' AND is_active AND NOT is_archived)
        OR (lifecycle_status IN ('PENDING_SETUP','INACTIVE') AND NOT is_active AND NOT is_archived)
        OR (lifecycle_status='ARCHIVED' AND NOT is_active AND is_archived)
    ),
    CONSTRAINT ck_teams_archive_metadata CHECK (
        (lifecycle_status='ARCHIVED' AND archived_at IS NOT NULL
         AND archive_reason IS NOT NULL AND length(btrim(archive_reason)) > 0)
        OR (lifecycle_status<>'ARCHIVED' AND archived_at IS NULL AND archive_reason IS NULL)
    ),
    CONSTRAINT ck_teams_version CHECK (version>=0)
);
CREATE UNIQUE INDEX ux_teams_org_normalized_name
 ON public.teams(organization_id,lower(regexp_replace(btrim(name),'[[:space:]]+',' ','g')));

-- Archived teams never return to an operational state.
CREATE FUNCTION public.epms_team_archive_terminal() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Teams must be archived, not deleted';
 END IF;
 IF OLD.lifecycle_status='ARCHIVED' AND NEW IS DISTINCT FROM OLD THEN
   RAISE EXCEPTION 'Archived teams are immutable';
 END IF;
 IF OLD.lifecycle_status='ACTIVE' AND NEW.lifecycle_status='ARCHIVED' THEN
   RAISE EXCEPTION 'Deactivate active teams before archival';
 END IF;
 IF NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
    NEW.team_id IS DISTINCT FROM OLD.team_id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR
    NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id THEN
   RAISE EXCEPTION 'Team ownership and creation identity are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_teams_archive_terminal
 BEFORE UPDATE OR DELETE ON public.teams
 FOR EACH ROW EXECUTE FUNCTION public.epms_team_archive_terminal();
REVOKE ALL ON FUNCTION public.epms_team_archive_terminal() FROM PUBLIC;
REVOKE ALL ON TABLE public.membership_end_reasons, public.teams FROM PUBLIC;
-- No ordinary runtime DML grants: protected service operations follow in B4/B5.
