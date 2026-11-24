-- B18.32d: narrowly scoped database primitives. Requires preprovisioned epms_security_admin.
-- Application authorization and privileged connection are a separate, required batch.
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'epms_security_admin'
                AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole) THEN
   RAISE EXCEPTION 'epms_security_admin must be provisioned as a restricted NOLOGIN role';
 END IF;
END $$;

CREATE TABLE account_unlock_grant_history (
 history_id UUID PRIMARY KEY DEFAULT uuidv7(),
 target_user_id UUID NOT NULL REFERENCES users(user_id),
 actor_user_id UUID NOT NULL REFERENCES users(user_id),
 operation VARCHAR(12) NOT NULL CHECK (operation IN ('GRANT','REVOKE')),
 reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_unlock_grant_history_target_time
 ON account_unlock_grant_history(target_user_id, occurred_at DESC);
REVOKE ALL ON account_unlock_grant_history FROM PUBLIC;

CREATE TABLE authentication_ip_retention_runs (
 run_id UUID PRIMARY KEY DEFAULT uuidv7(),
 executed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 removed_ip_count INTEGER NOT NULL CHECK (removed_ip_count >= 0)
);
REVOKE ALL ON authentication_ip_retention_runs FROM PUBLIC;

-- Prevent rewriting history, including by the application runtime role.
CREATE FUNCTION epms_reject_security_history_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
 RAISE EXCEPTION 'security history is append-only';
END $$;
CREATE TRIGGER trg_unlock_history_immutable
 BEFORE UPDATE OR DELETE ON account_unlock_grant_history
 FOR EACH ROW EXECUTE FUNCTION epms_reject_security_history_mutation();
CREATE TRIGGER trg_ip_retention_history_immutable
 BEFORE UPDATE OR DELETE ON authentication_ip_retention_runs
 FOR EACH ROW EXECUTE FUNCTION epms_reject_security_history_mutation();

-- Invoker identity is supplied by the trusted backend only after application
-- authentication and SYSTEM_ADMIN authorization. This is NOT an identity oracle:
-- database membership alone does not verify the supplied actor UUID.
CREATE FUNCTION epms_admin_set_unlock_grant(
 p_target UUID, p_actor UUID, p_grant BOOLEAN, p_reason TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public AS $$
DECLARE v_changed BOOLEAN := FALSE; v_rows INTEGER;
BEGIN
 IF p_target IS NULL OR p_actor IS NULL OR p_grant IS NULL OR p_reason IS NULL
    OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'invalid security administration request';
 END IF;
 IF p_target = p_actor THEN
  RAISE EXCEPTION 'self-administration is not permitted';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.users WHERE user_id = p_actor AND is_active = TRUE AND is_archived = FALSE) THEN
  RAISE EXCEPTION 'actor must be an active user';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.users WHERE user_id = p_target) THEN
  RAISE EXCEPTION 'target user does not exist';
 END IF;
 -- Serialize grant transitions for this target using its existing user row.
 PERFORM 1 FROM public.users WHERE user_id = p_target FOR UPDATE;
 IF p_grant THEN
  INSERT INTO public.account_unlock_grants(user_id, granted_by, granted_at, revoked_at)
  VALUES (p_target, p_actor, clock_timestamp(), NULL)
  ON CONFLICT (user_id) DO UPDATE SET granted_by = EXCLUDED.granted_by,
      granted_at = EXCLUDED.granted_at, revoked_at = NULL
  WHERE account_unlock_grants.revoked_at IS NOT NULL;
 ELSE
  UPDATE public.account_unlock_grants SET revoked_at = clock_timestamp()
  WHERE user_id = p_target AND revoked_at IS NULL;
 END IF;
 GET DIAGNOSTICS v_rows = ROW_COUNT;
 v_changed := v_rows > 0;
 IF v_changed THEN
  INSERT INTO public.account_unlock_grant_history(target_user_id, actor_user_id, operation, reason)
  VALUES (p_target, p_actor, CASE WHEN p_grant THEN 'GRANT' ELSE 'REVOKE' END, btrim(p_reason));
 END IF;
 RETURN v_changed;
END $$;

CREATE FUNCTION epms_admin_clear_expired_auth_ips()
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public AS $$
DECLARE v_count INTEGER;
BEGIN
 -- Exact 90-day boundary: retain IPs on and after the cutoff.
 UPDATE public.authentication_security_events
 SET source_ip = NULL
 WHERE source_ip IS NOT NULL AND occurred_at < clock_timestamp() - INTERVAL '90 days';
 GET DIAGNOSTICS v_count = ROW_COUNT;
 INSERT INTO public.authentication_ip_retention_runs(removed_ip_count) VALUES (v_count);
 RETURN v_count;
END $$;

REVOKE ALL ON FUNCTION epms_reject_security_history_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION epms_admin_set_unlock_grant(UUID,UUID,BOOLEAN,TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION epms_admin_clear_expired_auth_ips() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION epms_admin_set_unlock_grant(UUID,UUID,BOOLEAN,TEXT) TO epms_security_admin;
GRANT EXECUTE ON FUNCTION epms_admin_clear_expired_auth_ips() TO epms_security_admin;
-- epms_app retains only its V8 SELECT on account_unlock_grants.
