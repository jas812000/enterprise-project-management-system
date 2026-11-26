"""Exercise actual grants, rollback cleanup and Flyway in a disposable Docker PostgreSQL.

Never uses the developer database. Invoked explicitly by the B5.4 validator.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import secrets
import subprocess  # nosec B404 - disposable Docker and psql harness
import sys
import time
from unittest.mock import patch
from upgrade_recovery import (migrate_to_prior_version, seed_prior_state,
                              snapshot, verify_backup_restore)

SPEC = importlib.util.spec_from_file_location('epms_migrate', \
    Path(__file__).resolve().parents[1] / 'migrate.py')
MIGRATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATE)


def command(args, env=None, input_text=None):
    """Execute an isolated test command and check its return code."""
    if not args or args[0] not in ('docker', 'psql'):
        raise ValueError('Unsupported disposable test executable')
    result = subprocess.run(  # nosec B603 - fixed argv for disposable test tools
        args, env=env, input=input_text, capture_output=True, text=True,
        timeout=120, check=False)
    if result.returncode:
        raise RuntimeError(
            f'Disposable container operation failed: {args[0]} exit {result.returncode}')
    return result.stdout.strip()


def provision_database(psql, pgenv, migrator_password):
    """Wait for the disposable server and provision restricted roles."""
    for _ in range(90):
        try:
            if command(psql + ['-c', 'SELECT 1'], pgenv) == '1':
                break
        except RuntimeError:
            time.sleep(1)
    else:
        raise RuntimeError('Disposable PostgreSQL never became ready')
    roles = ['epms_app', 'epms_rbac_runtime', 'epms_bootstrap_runtime', 'epms_migrator',
             *MIGRATE.OWNERS, 'epms_security_admin']
    definitions = []
    for role in roles:
        login = 'NOLOGIN' if role in (*MIGRATE.OWNERS, 'epms_security_admin') else 'LOGIN'
        definitions.append(
            f'CREATE ROLE {role} {login} NOINHERIT NOSUPERUSER NOCREATEDB '
            'NOCREATEROLE NOREPLICATION NOBYPASSRLS;')
    definitions.append(f"ALTER ROLE epms_migrator PASSWORD '{migrator_password}';")
    definitions.append('REVOKE CREATE ON SCHEMA public FROM PUBLIC;')
    definitions.append('GRANT USAGE ON SCHEMA public TO ' + ', '.join(roles) + ';')
    definitions.append('GRANT CREATE ON SCHEMA public TO epms_migrator;')
    command(psql, pgenv, '\n'.join(definitions))


def verify_notification_privileges(operator):
    """Verify protected notification table and function access."""
    notification_acl = operator.sql("""
        SELECT bool_and(NOT has_table_privilege(role_name, relation_name,
         'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
        FROM unnest(ARRAY['epms_app','epms_rbac_runtime',
         'epms_bootstrap_runtime']) AS roles(role_name)
        CROSS JOIN unnest(ARRAY['public.team_notification_dispatches','public.team_notifications',
         'public.team_notification_receipts','public.team_security_alert_acknowledgements',
         'public.team_security_alert_resolutions','public.team_notification_attempts',
         'public.team_security_alert_escalations',
         'public.team_confidential_audit_access']) AS relations(relation_name);
        """)
    if notification_acl != 't':
        raise AssertionError('Unexpected direct Team notification table privileges')
    print('PASS: Notification tables deny direct runtime access')
    automation_acl = operator.sql("""
        SELECT bool_and(has_function_privilege('epms_rbac_runtime', signature, 'EXECUTE')
          AND NOT has_function_privilege('epms_app', signature, 'EXECUTE')
          AND NOT has_function_privilege('epms_bootstrap_runtime', signature, 'EXECUTE')
          AND NOT EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a
           WHERE p.oid=to_regprocedure(signature) AND a.grantee=0 AND a.privilege_type='EXECUTE'))
        FROM unnest(ARRAY['public.epms_team_system_dispatch(integer,integer,integer)',
         'public.epms_team_system_escalate(integer,integer,integer)',
         'public.epms_team_command_dispatch_failures(uuid,integer,integer)',
         'public.epms_team_command_confidential_event(uuid,uuid,uuid,text,text)',
         'public.epms_team_command_confidential_access_history(uuid,uuid,integer,integer)'])
        AS funcs(signature);
        """)
    if automation_acl != 't':
        raise AssertionError('Unexpected notification automation function privileges')
    if operator.sql("""
        SELECT has_function_privilege('epms_rbac_runtime',
         'public.epms_team_dispatch_pending(uuid,uuid,integer,integer,integer,integer)', 'EXECUTE');
        """) != 'f':
        raise AssertionError('Notification dispatch helper exposed to runtime')
    print('PASS: Notification automation commands and helper privileges')


def account_lifecycle_acl(operator):
    """Verify account and emergency function ownership, security mode, ACL and configuration."""
    return operator.sql("""
        SELECT proname, pg_get_userbyid(proowner), prosecdef, proacl::text, proconfig::text
        FROM pg_proc
        WHERE oid IN (to_regprocedure('public.epms_team_disable_account(uuid,uuid,text,text)'),
                      to_regprocedure('public.epms_team_emergency_succession(uuid,uuid,uuid,text,text)'),
                      to_regprocedure('public.epms_team_command_emergency_leadership(uuid,uuid,uuid,text,text)'))
        ORDER BY proname
        """, True)


def verify_emergency_privileges(operator):
    """Ensure the V44 internal helper is never granted to runtime or PUBLIC."""
    result = operator.sql("""
        SELECT bool_and(NOT has_function_privilege(role_name,
         'public.epms_team_emergency_succession(uuid,uuid,uuid,text,text)', 'EXECUTE'))
        FROM unnest(ARRAY['epms_app','epms_rbac_runtime','epms_bootstrap_runtime']) AS roles(role_name);
        """)
    if result != 't':
        raise AssertionError('Emergency helper exposed to a runtime identity')
    result = operator.sql("""
        SELECT NOT EXISTS (SELECT 1 FROM pg_proc p
          CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
          WHERE p.oid=to_regprocedure('public.epms_team_emergency_succession(uuid,uuid,uuid,text,text)')
          AND a.grantee=0 AND a.privilege_type='EXECUTE');
        """)
    if result != 't':
        raise AssertionError('Emergency helper exposed to PUBLIC')
    print('PASS: V44 internal emergency function remains restricted')


def exercise_migrations(report, admin_password, container):
    """Run actual migration and injected failure cleanup scenarios."""
    operator = MIGRATE.Operator(argparse.Namespace(allow_elevation=True, \
        admin_user='postgres'), report)
    operator.admin_password = admin_password
    operator.identity()
    # Verify real psql quoting, including apostrophes and SQL punctuation.
    probe = "literal' ; SELECT 42; --"
    if operator.sql("SELECT :'probe';", probe=probe) != probe:
        raise AssertionError('SQL parameter quoting changed the supplied value')
    operator.audit()
    info = operator.flyway('info')
    versions = MIGRATE.pending(info)
    if versions != list(range(1, 45)):
        raise RuntimeError('Disposable database must start with exactly V1–V44 pending')
    owners = MIGRATE.review_inventory(info, operator.config, \
        MIGRATE.ROOT / 'backend/src/main/resources/db/migration')
    operator.approved_versions = versions
    migrate_to_prior_version(operator, owners, MIGRATE.ROOT / 'backend')
    if MIGRATE.pending(operator.flyway('info')) != [42, 43, 44]:
        raise RuntimeError('Expected V42–V44 pending after staged V41 migration')
    operator.audit()
    operator.owner_audit()
    seed_prior_state(operator)
    prior = snapshot(operator)
    prior_acl = account_lifecycle_acl(operator)
    verify_backup_restore(container, report, prior)
    operator.approved_versions = [42, 43, 44]
    operator.apply([])
    operator.approved_versions = None
    operator.flyway('validate')
    if MIGRATE.pending(operator.flyway('info')) or snapshot(operator) != prior:
        raise RuntimeError('V42–V44 migration incomplete or representative data changed')
    if account_lifecycle_acl(operator) != prior_acl:
        raise AssertionError(
            'V42–V44 changed protected account/emergency function security metadata'
        )
    operator.audit()
    operator.owner_audit()
    print('PASS: V41 to V44 upgrade preserves representative Team and security data')
    verify_notification_privileges(operator)
    verify_emergency_privileges(operator)

    verify_failed_elevation_cleanup(operator, report)

def verify_failed_elevation_cleanup(operator, report):
    """Verify temporary grants are cleaned up on an injected migration failure."""
    def fail_after_grant(_goal):
        """Verify fail after grant."""
        if operator.sql("SELECT pg_has_role('epms_migrator',"
                        "'epms_auth_function_owner','USAGE')") != 't':
            raise AssertionError('Temporary inherited membership not established')
        if operator.sql("SELECT has_schema_privilege('epms_auth_function_owner',"
                        "'public','CREATE')") != 't':
            raise AssertionError('Temporary schema CREATE not established')
        raise RuntimeError('Injected migration failure')

    with patch.object(sys.stdin, 'isatty', return_value=True), patch('builtins.input', \
        return_value='ELEVATE epms'), patch.object(operator, 'flyway', \
            side_effect=fail_after_grant):
        try:
            operator.apply([MIGRATE.OWNERS[2]])
        except RuntimeError as exc:
            if str(exc) != 'Injected migration failure':
                raise
        else:
            raise AssertionError('Injected failure unexpectedly succeeded')
    operator.audit()
    operator.owner_audit()
    if (report / 'RECOVERY.sql').exists():
        raise AssertionError('Recovery journal remained after successful cleanup')
    print('PASS: Real temporary grants revoked after injected failure')


def main():
    """Run the command-line workflow and return its exit status."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    args.report.mkdir(parents=True, exist_ok=False)
    name = 'epms-b54-test-' + secrets.token_hex(8)
    admin_password = secrets.token_hex(24)
    migrator_password = secrets.token_hex(24)
    env = os.environ.copy()
    env['POSTGRES_PASSWORD'] = admin_password
    container = None
    try:
        container = command(['docker', 'run', '--rm', '-d', '--name', name, '-p', '127.0.0.1::5432',
                             '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB=epms', \
                                 'postgres:18'], env)
        if not re.fullmatch('[a-f0-9]{64}', container):
            raise RuntimeError('Could not identify disposable container')
        address = command(['docker', 'port', container, '5432/tcp'])
        if not re.fullmatch(r'127\.0\.0\.1:[0-9]+', address):
            raise RuntimeError('Unexpected disposable database port binding')
        url = 'jdbc:' + 'postgresql' + '://' + address + '/epms'
        isolated = {key: val for key, val in os.environ.items() if not key.startswith(('EPMS_', \
            'FLYWAY_', 'PG'))}
        isolated.update(EPMS_FLYWAY_URL=url, EPMS_FLYWAY_USERNAME='epms_migrator',
                        EPMS_FLYWAY_PASSWORD=migrator_password)
        pgenv = {**isolated, 'PGPASSWORD': admin_password}
        psql = ['psql', '-X', '-w', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p',
                address.split(':')[-1], '-U', 'postgres', '-d', 'epms', '-At']
        provision_database(psql, pgenv, migrator_password)
        with patch.dict(os.environ, isolated, clear=True):
            exercise_migrations(args.report, admin_password, container)
        return 0
    finally:
        if container and re.fullmatch('[a-f0-9]{64}', container):
            command(['docker', 'rm', '-f', container])


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError,
            AssertionError) as failure:
        print('FAIL: disposable migration test:', type(failure).__name__, file=sys.stderr)
        sys.exit(1)
