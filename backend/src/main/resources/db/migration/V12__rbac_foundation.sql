-- B18.32h-2: RBAC persistence foundation. No application administration access is granted here.
-- V12 does not activate role-management APIs; application enforcement is a separate batch.
CREATE TABLE security_roles (
 role_id uuid PRIMARY KEY DEFAULT uuidv7(),
 code varchar(64) NOT NULL UNIQUE,
 display_name varchar(120) NOT NULL,
 is_system_defined boolean NOT NULL DEFAULT true,
 is_active boolean NOT NULL DEFAULT true,
 CONSTRAINT ck_security_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);
CREATE TABLE security_permissions (
 permission_id uuid PRIMARY KEY DEFAULT uuidv7(),
 code varchar(100) NOT NULL UNIQUE,
 description text NOT NULL,
 is_active boolean NOT NULL DEFAULT true,
 CONSTRAINT ck_security_permission_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);
CREATE TABLE security_role_permissions (
 role_id uuid NOT NULL REFERENCES security_roles(role_id),
 permission_id uuid NOT NULL REFERENCES security_permissions(permission_id),
 PRIMARY KEY (role_id, permission_id)
);
CREATE TABLE security_user_roles (
 assignment_id uuid PRIMARY KEY DEFAULT uuidv7(),
 user_id uuid NOT NULL REFERENCES users(user_id),
 role_id uuid NOT NULL REFERENCES security_roles(role_id),
 granted_by_user_id uuid REFERENCES users(user_id),
 valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
 valid_until timestamptz,
 revoked_at timestamptz,
 revoked_by_user_id uuid REFERENCES users(user_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT ck_security_assignment_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
 CONSTRAINT ck_security_assignment_revoke CHECK (revoked_at IS NULL OR revoked_at >= valid_from),
 CONSTRAINT ck_security_assignment_revoke_actor CHECK (revoked_at IS NULL OR revoked_by_user_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_security_user_roles_current ON security_user_roles(user_id,role_id)
 WHERE revoked_at IS NULL;
CREATE INDEX ix_security_user_roles_lookup ON security_user_roles(user_id,role_id,valid_from,valid_until);
CREATE TABLE security_role_assignment_history (
 history_id uuid PRIMARY KEY DEFAULT uuidv7(),
 assignment_id uuid NOT NULL REFERENCES security_user_roles(assignment_id),
 target_user_id uuid NOT NULL REFERENCES users(user_id),
 actor_user_id uuid NOT NULL REFERENCES users(user_id),
 action varchar(12) NOT NULL CHECK (action IN ('GRANT','REVOKE')),
 reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE security_permission_change_history (
 history_id uuid PRIMARY KEY DEFAULT uuidv7(),
 role_id uuid NOT NULL REFERENCES security_roles(role_id),
 permission_id uuid NOT NULL REFERENCES security_permissions(permission_id),
 actor_user_id uuid NOT NULL REFERENCES users(user_id),
 action varchar(12) NOT NULL CHECK (action IN ('GRANT','REVOKE')),
 reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER trg_security_role_assignment_history_immutable
 BEFORE UPDATE OR DELETE ON security_role_assignment_history
 FOR EACH ROW EXECUTE FUNCTION epms_reject_security_history_mutation();
CREATE TRIGGER trg_security_permission_change_history_immutable
 BEFORE UPDATE OR DELETE ON security_permission_change_history
 FOR EACH ROW EXECUTE FUNCTION epms_reject_security_history_mutation();

-- Database defense in depth: only active HUMAN accounts can receive RBAC roles.
CREATE FUNCTION epms_reject_nonhuman_role_assignment() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM public.users u JOIN public.account_types a ON a.account_type_id=u.account_type_id
  WHERE u.user_id=NEW.user_id AND a.code='HUMAN' AND u.is_active AND NOT u.is_archived
 ) THEN RAISE EXCEPTION 'RBAC roles require an active HUMAN account'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_security_user_roles_human
 BEFORE INSERT OR UPDATE OF user_id ON security_user_roles
 FOR EACH ROW EXECUTE FUNCTION epms_reject_nonhuman_role_assignment();
REVOKE ALL ON FUNCTION epms_reject_nonhuman_role_assignment() FROM PUBLIC;

INSERT INTO security_roles(code,display_name) VALUES
 ('SYSTEM_ADMIN','System Administrator'),('ORG_ADMIN','Organization Administrator'),
 ('PROJECT_MANAGER','Project Manager'),('TEAM_LEAD','Team Lead'),
 ('MEMBER','Member'),('VIEWER','Viewer'),('AUDITOR','Auditor');
INSERT INTO security_permissions(code,description) VALUES
 ('SECURITY_CONFIG_MANAGE','Manage security configuration'),
 ('PRIVILEGED_ROLE_ASSIGN','Assign privileged roles'),
 ('OPERATIONAL_ROLE_ASSIGN','Assign operational roles'),
 ('USER_MANAGE','Manage authorized user accounts'),
 ('DEPARTMENT_MANAGE','Manage departments'),
 ('ACCOUNT_UNLOCK_GRANT_MANAGE','Manage account unlock authorization'),
 ('SECURITY_AUDIT_READ','Read security audit history'),
 ('PROJECT_READ','Read authorized projects'),
 ('PROJECT_CREATE','Create authorized projects'),
 ('PROJECT_UPDATE','Update authorized projects'),
 ('TASK_ASSIGN','Assign tasks in authorized scope'),
 ('TASK_UPDATE_ASSIGNED','Update assigned tasks'),
 ('TIMESHEET_SUBMIT_OWN','Submit own timesheets'),
 ('TIMESHEET_EDIT_OWN_DRAFT','Edit own draft timesheets'),
 ('TIMESHEET_READ_TEAM','Read authorized team timesheets'),
 ('TIMESHEET_APPROVE_TEAM','Approve authorized team timesheets'),
 ('TIMESHEET_REJECT_TEAM','Reject authorized team timesheets'),
 ('TIMESHEET_APPROVAL_HISTORY_READ','Read authorized timesheet approval history');
-- Seed only unambiguous role permissions. Scoped operations still require resource checks.
WITH grants(role_code,permission_code) AS (VALUES
 ('SYSTEM_ADMIN','SECURITY_CONFIG_MANAGE'),('SYSTEM_ADMIN','PRIVILEGED_ROLE_ASSIGN'),
 ('SYSTEM_ADMIN','OPERATIONAL_ROLE_ASSIGN'),('SYSTEM_ADMIN','USER_MANAGE'),
 ('SYSTEM_ADMIN','ACCOUNT_UNLOCK_GRANT_MANAGE'),('SYSTEM_ADMIN','SECURITY_AUDIT_READ'),
 ('ORG_ADMIN','OPERATIONAL_ROLE_ASSIGN'),('ORG_ADMIN','USER_MANAGE'),
 ('ORG_ADMIN','DEPARTMENT_MANAGE'),('ORG_ADMIN','PROJECT_READ'),
 ('PROJECT_MANAGER','PROJECT_READ'),('PROJECT_MANAGER','PROJECT_CREATE'),
 ('PROJECT_MANAGER','PROJECT_UPDATE'),('PROJECT_MANAGER','TASK_ASSIGN'),
 ('PROJECT_MANAGER','TASK_UPDATE_ASSIGNED'),('PROJECT_MANAGER','TIMESHEET_SUBMIT_OWN'),
 ('PROJECT_MANAGER','TIMESHEET_EDIT_OWN_DRAFT'),('PROJECT_MANAGER','TIMESHEET_READ_TEAM'),
 ('PROJECT_MANAGER','TIMESHEET_APPROVAL_HISTORY_READ'),
 ('TEAM_LEAD','PROJECT_READ'),('TEAM_LEAD','TASK_ASSIGN'),
 ('TEAM_LEAD','TASK_UPDATE_ASSIGNED'),('TEAM_LEAD','TIMESHEET_SUBMIT_OWN'),
 ('TEAM_LEAD','TIMESHEET_EDIT_OWN_DRAFT'),('TEAM_LEAD','TIMESHEET_READ_TEAM'),
 ('TEAM_LEAD','TIMESHEET_APPROVE_TEAM'),('TEAM_LEAD','TIMESHEET_REJECT_TEAM'),
 ('TEAM_LEAD','TIMESHEET_APPROVAL_HISTORY_READ'),
 ('MEMBER','PROJECT_READ'),('MEMBER','TASK_UPDATE_ASSIGNED'),
 ('MEMBER','TIMESHEET_SUBMIT_OWN'),('MEMBER','TIMESHEET_EDIT_OWN_DRAFT'),
 ('MEMBER','TIMESHEET_APPROVAL_HISTORY_READ'),
 ('VIEWER','PROJECT_READ'),('AUDITOR','SECURITY_AUDIT_READ')
)
INSERT INTO security_role_permissions(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM grants g
JOIN security_roles r ON r.code=g.role_code
JOIN security_permissions p ON p.code=g.permission_code;

-- The migrator owns the objects; no DML grants to epms_app or epms_security_admin.
REVOKE ALL ON security_roles,security_permissions,security_role_permissions,
 security_user_roles,security_role_assignment_history,security_permission_change_history FROM PUBLIC;

-- B18.32h-3: coordinated RBAC database safeguards
-- The application must still enforce authenticated actor identity and permissions.
-- Ordinary epms_app has no direct DML access to these RBAC tables.

-- Keep system-defined catalogs stable; changes require a separately reviewed migration.
CREATE FUNCTION epms_rbac_catalog_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
 RAISE EXCEPTION 'RBAC catalog changes require a reviewed migration';
END $$;
CREATE TRIGGER trg_rbac_roles_catalog BEFORE UPDATE OR DELETE ON security_roles
 FOR EACH ROW EXECUTE FUNCTION epms_rbac_catalog_immutable();
CREATE TRIGGER trg_rbac_permissions_catalog BEFORE UPDATE OR DELETE ON security_permissions
 FOR EACH ROW EXECUTE FUNCTION epms_rbac_catalog_immutable();
CREATE TRIGGER trg_rbac_role_permissions_catalog BEFORE UPDATE OR DELETE ON security_role_permissions
 FOR EACH ROW EXECUTE FUNCTION epms_rbac_catalog_immutable();

-- Serialize all role and account lifecycle transitions, including concurrent transactions.
-- An advisory transaction lock is used because no administrator row exists before bootstrap.
CREATE FUNCTION epms_rbac_guard_assignment() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE v_role text; v_actor uuid; v_current boolean; v_count integer;
BEGIN
 PERFORM pg_advisory_xact_lock(1832, 12);
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION 'Role assignments must be revoked, not deleted';
 END IF;
 SELECT code INTO v_role FROM public.security_roles WHERE role_id=NEW.role_id;
 IF v_role IS NULL THEN RAISE EXCEPTION 'Unknown RBAC role'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types a ON a.account_type_id=u.account_type_id
                WHERE u.user_id=NEW.user_id AND a.code='HUMAN' AND u.is_active AND NOT u.is_archived)
 THEN RAISE EXCEPTION 'RBAC roles require an active HUMAN account'; END IF;
 IF v_role='SYSTEM_ADMIN' AND NEW.valid_until IS NOT NULL THEN
  RAISE EXCEPTION 'SYSTEM_ADMIN assignment cannot expire automatically'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.role_id IS DISTINCT FROM OLD.role_id
     OR NEW.granted_by_user_id IS DISTINCT FROM OLD.granted_by_user_id
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
  THEN RAISE EXCEPTION 'Role assignment identity and validity are immutable'; END IF;
  IF OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                                    OR NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id)
  THEN RAISE EXCEPTION 'Revocation cannot be reversed or rewritten'; END IF;
  IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL AND NEW.revoked_by_user_id IS NULL
  THEN RAISE EXCEPTION 'Revocation requires an actor'; END IF;
 END IF;
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
CREATE TRIGGER trg_rbac_assignment_guard BEFORE INSERT OR UPDATE OR DELETE ON security_user_roles
 FOR EACH ROW EXECUTE FUNCTION epms_rbac_guard_assignment();

-- Disabling/archiving/changing the account type of the last active administrator is forbidden.
-- Other accounts may be disabled; their assignments cease to be effective immediately.
CREATE FUNCTION epms_rbac_guard_user_lifecycle() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE v_count integer;
BEGIN
 IF TG_OP='UPDATE' AND (NEW.is_active IS DISTINCT FROM OLD.is_active OR
   NEW.is_archived IS DISTINCT FROM OLD.is_archived OR
   NEW.account_type_id IS DISTINCT FROM OLD.account_type_id) THEN
  PERFORM pg_advisory_xact_lock(1832, 12);
  IF EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r ON r.role_id=x.role_id
             WHERE x.user_id=OLD.user_id AND r.code='SYSTEM_ADMIN' AND x.revoked_at IS NULL
               AND x.valid_from<=clock_timestamp())
    AND (NOT NEW.is_active OR NEW.is_archived OR NOT EXISTS
      (SELECT 1 FROM public.account_types a WHERE a.account_type_id=NEW.account_type_id AND a.code='HUMAN')) THEN
    SELECT count(*) INTO v_count FROM public.security_user_roles x
      JOIN public.security_roles r ON r.role_id=x.role_id
      JOIN public.users u ON u.user_id=x.user_id
      JOIN public.account_types a ON a.account_type_id=u.account_type_id
      WHERE r.code='SYSTEM_ADMIN' AND x.user_id<>OLD.user_id AND x.revoked_at IS NULL
        AND x.valid_from<=clock_timestamp() AND u.is_active AND NOT u.is_archived AND a.code='HUMAN';
    IF v_count=0 THEN RAISE EXCEPTION 'Cannot disable or reclassify the last active SYSTEM_ADMIN'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_rbac_user_lifecycle BEFORE UPDATE OF is_active,is_archived,account_type_id ON users
 FOR EACH ROW EXECUTE FUNCTION epms_rbac_guard_user_lifecycle();

-- Current authorization is evaluated from database state, never a stale session snapshot.
CREATE VIEW security_effective_user_permissions AS
 SELECT DISTINCT x.user_id,p.code AS permission_code
 FROM security_user_roles x
 JOIN users u ON u.user_id=x.user_id
 JOIN account_types a ON a.account_type_id=u.account_type_id
 JOIN security_roles r ON r.role_id=x.role_id
 JOIN security_role_permissions rp ON rp.role_id=r.role_id
 JOIN security_permissions p ON p.permission_id=rp.permission_id
 WHERE u.is_active AND NOT u.is_archived AND a.code='HUMAN'
   AND r.is_active AND p.is_active AND x.revoked_at IS NULL
   AND x.valid_from<=clock_timestamp()
   AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp());
REVOKE ALL ON FUNCTION epms_rbac_catalog_immutable() FROM PUBLIC;
REVOKE ALL ON FUNCTION epms_rbac_guard_assignment() FROM PUBLIC;
REVOKE ALL ON FUNCTION epms_rbac_guard_user_lifecycle() FROM PUBLIC;
REVOKE ALL ON security_effective_user_permissions FROM PUBLIC;
-- No direct application grant to the view until Java authorization integration is reviewed.
