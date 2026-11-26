-- Closed-by-default Team integrity foundation. No runtime grants.
CREATE FUNCTION public.epms_team_lifecycle_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF TG_OP='INSERT' THEN
   IF NEW.lifecycle_status <> 'PENDING_SETUP' THEN
     RAISE EXCEPTION 'New teams must begin in pending setup';
   END IF;
 ELSIF NEW.lifecycle_status IS DISTINCT FROM OLD.lifecycle_status THEN
   IF NOT ((OLD.lifecycle_status='PENDING_SETUP' AND NEW.lifecycle_status IN ('ACTIVE','ARCHIVED'))
       OR (OLD.lifecycle_status='ACTIVE' AND NEW.lifecycle_status='INACTIVE')
       OR (OLD.lifecycle_status='INACTIVE' AND NEW.lifecycle_status IN ('ACTIVE','ARCHIVED'))) THEN
     RAISE EXCEPTION 'Invalid team lifecycle transition';
   END IF;
   -- Activation remains closed until protected leadership operations are installed.
   IF NEW.lifecycle_status='ACTIVE' THEN
     RAISE EXCEPTION 'Team activation is not enabled';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_lifecycle_guard BEFORE INSERT OR UPDATE ON public.teams
FOR EACH ROW EXECUTE FUNCTION public.epms_team_lifecycle_guard();
REVOKE ALL ON FUNCTION public.epms_team_lifecycle_guard() FROM PUBLIC;

CREATE FUNCTION public.epms_team_membership_insert_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE previous_number integer; previous_end timestamptz;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 IF NEW.left_at IS NOT NULL THEN RAISE EXCEPTION 'New membership must be open'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=NEW.team_id
   AND t.organization_id=NEW.organization_id AND NOT t.is_archived) THEN
   RAISE EXCEPTION 'Team is unavailable';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types a USING(account_type_id)
   WHERE u.user_id=NEW.user_id AND u.organization_id=NEW.organization_id
   AND u.is_active AND NOT u.is_archived AND a.code='HUMAN') THEN
   RAISE EXCEPTION 'Membership requires eligible human account';
 END IF;
 SELECT membership_period_number,left_at INTO previous_number,previous_end
 FROM public.team_memberships WHERE team_id=NEW.team_id AND user_id=NEW.user_id
 ORDER BY membership_period_number DESC LIMIT 1;
 IF NEW.membership_period_number <> coalesce(previous_number,0)+1
    OR (previous_number IS NOT NULL AND (previous_end IS NULL OR NEW.joined_at < previous_end)) THEN
   RAISE EXCEPTION 'Membership periods must be sequential and nonoverlapping';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_team_membership_insert_guard BEFORE INSERT ON public.team_memberships
FOR EACH ROW EXECUTE FUNCTION public.epms_team_membership_insert_guard();
REVOKE ALL ON FUNCTION public.epms_team_membership_insert_guard() FROM PUBLIC;
