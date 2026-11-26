"""Staged upgrade and logical restore of synthetic data in the disposable test container."""

import json
import os
import re
import shutil
import subprocess  # nosec B404 - fixed disposable Docker commands
import sys
from unittest.mock import patch


# Include the security and notification histories, not just the Team profile.
SNAPSHOT_SQL = """
SELECT jsonb_build_object(
 'organizations',(SELECT jsonb_agg(to_jsonb(t) ORDER BY organization_id)
                  FROM public.organizations t),
 'users',(SELECT jsonb_agg(to_jsonb(t) ORDER BY user_id) FROM public.users t),
 'roles',(SELECT jsonb_agg(to_jsonb(t) ORDER BY assignment_id) FROM public.security_user_roles t),
 'teams',(SELECT jsonb_agg(to_jsonb(t) ORDER BY team_id) FROM public.teams t),
 'members',(SELECT jsonb_agg(to_jsonb(t) ORDER BY team_membership_id)
            FROM public.team_memberships t),
 'leaders',(SELECT jsonb_agg(to_jsonb(t) ORDER BY team_leadership_assignment_id)
            FROM public.team_leadership_assignments t),
 'events',(SELECT jsonb_agg(to_jsonb(t) ORDER BY event_id) FROM public.team_operation_events t),
 'lifecycle',(SELECT jsonb_agg(to_jsonb(t) ORDER BY event_id) FROM public.team_lifecycle_events t),
 'dispatches',(SELECT jsonb_agg(to_jsonb(t) ORDER BY dispatch_id)
               FROM public.team_notification_dispatches t),
 'notifications',(SELECT jsonb_agg(to_jsonb(t) ORDER BY notification_id)
                  FROM public.team_notifications t),
 'attempts',(SELECT jsonb_agg(to_jsonb(t) ORDER BY attempt_id)
             FROM public.team_notification_attempts t),
 'access',(SELECT jsonb_agg(to_jsonb(t) ORDER BY access_id)
           FROM public.team_confidential_audit_access t)
)::text;
"""


def migrate_to_prior_version(operator, owners, backend):
    """Use an explicit Flyway target only in this isolated harness, not the operator CLI."""
    original = operator.flyway

    def staged(goal):
        if goal != 'migrate':
            return original(goal)
        output = operator.run(['./mvnw', '-B', '-Dstyle.color=never', '-Dflyway.target=41',
                               'flyway:migrate'], operator.env,
                              cwd=backend)
        if f'Database: {operator.env["EPMS_FLYWAY_URL"]} (PostgreSQL' not in output:
            raise RuntimeError('Staged migration did not confirm disposable target')
        return output

    with patch.object(operator, 'flyway', side_effect=staged), \
            patch.object(sys.stdin, 'isatty', return_value=True), \
            patch('builtins.input', return_value='ELEVATE epms'):
        operator.apply(owners)
    operator.approved_versions = None


def seed_prior_state(operator):
    """Create only synthetic rows and exercise protected Team commands before upgrade."""
    operator.sql("INSERT INTO public.organizations(name) VALUES ('Upgrade fixture');", True)
    operator.sql("""
        INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
        SELECT o.organization_id,a.account_type_id,'Fixture','User',e.email
        FROM public.organizations o CROSS JOIN public.account_types a
        CROSS JOIN (VALUES ('admin@example.invalid'),('leader@example.invalid')) e(email)
        WHERE a.code='HUMAN';
        INSERT INTO public.security_user_roles(user_id,role_id)
        SELECT u.user_id,r.role_id FROM public.users u CROSS JOIN public.security_roles r
        WHERE (u.email='admin@example.invalid' AND r.code='SYSTEM_ADMIN')
           OR (u.email='leader@example.invalid' AND r.code='MEMBER');
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_create_reviewed(user_id,'Upgrade fixture team',
            NULL,false,'Synthetic upgrade fixture',NULL)
        FROM public.users WHERE email='admin@example.invalid';
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_join_member(a.user_id,t.team_id,l.user_id,
            'Synthetic membership')
        FROM public.users a CROSS JOIN public.teams t CROSS JOIN public.users l
        WHERE a.email='admin@example.invalid' AND l.email='leader@example.invalid';
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_assign_permanent(a.user_id,m.team_id,
            m.team_membership_id,'Synthetic leadership')
        FROM public.users a CROSS JOIN public.team_memberships m
        WHERE a.email='admin@example.invalid';
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_transition(a.user_id,t.team_id,'ACTIVE',
            'Synthetic activation')
        FROM public.users a CROSS JOIN public.teams t WHERE a.email='admin@example.invalid';
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_dispatch_notifications(user_id,100)
        FROM public.users WHERE email='admin@example.invalid';
        """, True)
    operator.sql("""
        SELECT public.epms_team_command_confidential_event(a.user_id,e.team_id,e.event_id,
            'OPERATION','Synthetic audit review')
        FROM public.users a CROSS JOIN public.team_operation_events e
        WHERE a.email='admin@example.invalid' AND e.operation='MEMBER_JOIN';
        """, True)
    if operator.sql("SELECT count(*) FROM public.teams WHERE lifecycle_status='ACTIVE'",
                    True) != '1':
        raise AssertionError('Representative active Team was not created')


def snapshot(operator):
    """Capture deterministic synthetic data without writing any credentials to reports."""
    data = operator.sql(SNAPSHOT_SQL, True)
    rows = json.loads(data)
    if any(not rows.get(key) for key in ('teams', 'leaders', 'members', 'notifications',
                                         'attempts', 'access')):
        raise AssertionError('Representative security or notification data was not created')
    return data


def disposable_exec(container, args, input_text=None):
    """Execute only PostgreSQL client commands in the identified ephemeral container."""
    if not re.fullmatch('[a-f0-9]{64}', container) or not args:
        raise ValueError('Unexpected disposable restore command')

    if args[0] not in ('pg_dump', 'psql'):
        raise ValueError('Unexpected disposable restore command')

    docker_executable = shutil.which('docker')
    if not docker_executable or not os.path.isabs(docker_executable):
        raise RuntimeError('Docker executable not found at an absolute path')

    result = subprocess.run(  # nosec B603 - reviewed argv, no shell, disposable container
        [docker_executable, 'exec', '-i', container, *args],
        input=input_text, text=True, capture_output=True, timeout=180, check=False)

    if result.returncode:
        raise RuntimeError(f'Disposable {args[0]} failed: exit {result.returncode}')

    return result.stdout


def verify_backup_restore(container, report, expected):
    """Dump V41 and restore into a second database inside the same disposable container."""
    dump = disposable_exec(container, ['pg_dump', '-U', 'postgres', '-d', 'epms', '--no-password'])
    if not dump.strip():
        raise RuntimeError('Empty disposable PostgreSQL backup')
    backup = report / 'synthetic-v41-backup.sql'
    with backup.open('x', encoding='utf-8') as handle:
        handle.write(dump)
    client = ['psql', '-X', '-w', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-At']
    disposable_exec(container, client + ['-d', 'postgres', '-c',
                                        'CREATE DATABASE epms_b9d_restore;'])
    restored = client + ['-d', 'epms_b9d_restore']
    disposable_exec(container, restored, backup.read_text(encoding='utf-8'))
    if disposable_exec(container, restored + ['-c', SNAPSHOT_SQL]).strip() != expected:
        raise AssertionError('Restored representative data differs')
    inventory = ("SELECT count(*) FROM public.flyway_schema_history "
                 "WHERE success AND version IS NOT NULL")
    if disposable_exec(container, restored + ['-c', inventory]).strip() != '41':
        raise AssertionError('Restored migration history differs')
    print('PASS: V41 synthetic logical backup and restore preserve data and migration history')
