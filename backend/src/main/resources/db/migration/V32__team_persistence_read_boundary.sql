-- Phase 12 B5.1: read-only operational projections for the trusted Java backend.
-- These views are not end-user authorization decisions. No protected command
-- execution, confidential history, alternate identities or raw DML is granted.
CREATE VIEW public.team_persistence_teams WITH (security_barrier=true) AS
 SELECT team_id, organization_id, name, lifecycle_status, is_active, is_archived,
        created_at, updated_at, version
 FROM public.teams;

CREATE VIEW public.team_persistence_memberships WITH (security_barrier=true) AS
 SELECT team_membership_id, organization_id, team_id, user_id,
        membership_period_number, joined_at, version
 FROM public.team_memberships WHERE left_at IS NULL;

CREATE VIEW public.team_persistence_leadership WITH (security_barrier=true) AS
 SELECT team_leadership_assignment_id, team_membership_id, organization_id,
        team_id, user_id, assignment_type, assigned_at, version
 FROM public.team_leadership_assignments WHERE ended_at IS NULL;

REVOKE ALL ON public.team_persistence_teams, public.team_persistence_memberships,
 public.team_persistence_leadership FROM PUBLIC, epms_rbac_runtime, epms_app;
GRANT SELECT ON public.team_persistence_teams, public.team_persistence_memberships,
 public.team_persistence_leadership TO epms_app;
COMMENT ON VIEW public.team_persistence_teams IS
 'Trusted backend projection; application service must authorize each requested resource.';
COMMENT ON VIEW public.team_persistence_memberships IS
 'Open membership periods only; no removal details or historical periods.';
COMMENT ON VIEW public.team_persistence_leadership IS
 'Open assignments only; not proof of effective leadership permission.';
