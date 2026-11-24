-- B29-B: one-time initial administrator bootstrap persistence foundation.
-- This migration does NOT enable bootstrap or grant a runtime role access.
-- A later reviewed migration must implement a restricted bootstrap operation.
CREATE TABLE public.security_bootstrap_state (
    singleton_id smallint PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    completed_at timestamptz,
    administrator_user_id uuid REFERENCES public.users(user_id),
    CONSTRAINT ck_security_bootstrap_state_complete CHECK
      ((completed_at IS NULL AND administrator_user_id IS NULL) OR
       (completed_at IS NOT NULL AND administrator_user_id IS NOT NULL))
);
INSERT INTO public.security_bootstrap_state(singleton_id) VALUES (1);

CREATE TABLE public.security_bootstrap_audit (
    bootstrap_event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    administrator_user_id uuid NOT NULL REFERENCES public.users(user_id),
    assignment_id uuid NOT NULL UNIQUE REFERENCES public.security_user_roles(assignment_id),
    event_type varchar(40) NOT NULL CHECK (event_type = 'INITIAL_SYSTEM_ADMIN_CREATED'),
    provenance varchar(40) NOT NULL CHECK (provenance = 'EXPLICIT_ADMIN_CLI'),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TRIGGER trg_security_bootstrap_audit_immutable
 BEFORE UPDATE OR DELETE ON public.security_bootstrap_audit
 FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();

-- State must not be deleted or reset after completion, even by a future
-- bootstrap executor. Privileged migration owners remain trusted DB operators.
CREATE FUNCTION public.epms_bootstrap_state_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
   RAISE EXCEPTION 'bootstrap state cannot be deleted';
 END IF;
 IF OLD.completed_at IS NOT NULL THEN
   RAISE EXCEPTION 'completed bootstrap state is immutable';
 END IF;
 IF NEW.singleton_id IS DISTINCT FROM OLD.singleton_id THEN
   RAISE EXCEPTION 'bootstrap state identity is immutable';
 END IF;
 IF NEW.completed_at IS NULL THEN
   RAISE EXCEPTION 'bootstrap state may only transition to completed';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_security_bootstrap_state_guard
 BEFORE UPDATE OR DELETE ON public.security_bootstrap_state
 FOR EACH ROW EXECUTE FUNCTION public.epms_bootstrap_state_guard();

REVOKE ALL ON TABLE public.security_bootstrap_state, public.security_bootstrap_audit FROM PUBLIC;
REVOKE ALL ON FUNCTION public.epms_bootstrap_state_guard() FROM PUBLIC;
-- No INSERT/UPDATE/DELETE or EXECUTE grants to epms_app, epms_rbac_runtime,
-- or any bootstrap login are made by this foundation migration.
