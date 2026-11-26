#!/usr/bin/env python3
"""Local EPMS Flyway operator. No automatic role creation or history repair."""
import argparse
import fcntl
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess  # nosec B404 - fixed argv for reviewed operator tools
import signal
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
OWNERS = ('epms_rbac_function_owner', 'epms_bootstrap_owner', 'epms_auth_function_owner')
MIGRATOR = 'epms_migrator'


def target(url):
    """Accept only an explicit local EPMS database and decimal port."""
    error = 'Only explicit local EPMS development URLs are supported'
    prefix = 'jdbc:' + 'postgresql' + '://'
    if not url.startswith(prefix):
        raise ValueError(error)
    authority, separator, database = url[len(prefix):].partition('/')
    host, colon, port = authority.partition(':')
    if (
        not separator
        or database != 'epms'
        or not colon
        or host not in ('localhost', '127.0.0.1')
    ):
        raise ValueError(error)

    if not re.fullmatch(r'[0-9]{1,5}', port) or not 1 <= int(port) <= 65535:
        raise ValueError(error)

    return host, port


def inventory(output):
    """Parse and validate the Flyway version inventory."""
    rows = {}
    header = False
    for line in output.splitlines():
        # The Maven plugin prefixes table rows with [INFO]. A table with
        # unsupported migration categories or states is not safe to apply.
        if '|' not in line:
            continue
        fields = [part.strip() for part in line[line.index('|'):].split('|')]
        if len(fields) < 9:
            raise ValueError('Malformed Flyway information table')
        if fields[1] == 'Category':
            if fields[2] != 'Version' or fields[6] != 'State':
                raise ValueError('Unexpected Flyway information table layout')
            header = True
            continue
        if not header or fields[1] != 'Versioned':
            raise ValueError('Unsupported Flyway migration category; manual review required')
        if not fields[2].isdigit() or fields[6] not in ('Success', 'Pending'):
            raise ValueError('Unexpected Flyway migration state; manual review required')
        version = int(fields[2])
        if version in rows:
            raise ValueError('Duplicate Flyway version in information table')
        rows[version] = fields[6]
    if 'Schema version:' not in output or not header or not rows:
        raise ValueError('Cannot establish migration history from Flyway info')
    return rows


def pending(output):
    """Return pending migration versions in ascending order."""
    return sorted(version for version, state in inventory(output).items() if state == 'Pending')


def review_inventory(output, config, migration_dir):
    """Reconcile reviewed SQL files and the Flyway inventory."""
    rows = inventory(output)
    files = {}
    for path in migration_dir.rglob('*.sql'):
        match = re.fullmatch(r'V([0-9]+)__.+\.sql', path.name)
        if not match or path.is_symlink() or path.parent != migration_dir:
            raise ValueError('Unsupported migration file or location; manual review required')
        version = int(match[1])
        if version in files:
            raise ValueError('Duplicate migration version on disk')
        files[version] = path
    if set(rows) != set(files):
        raise ValueError('Flyway inventory differs from repository migration files')
    requirements(sorted(rows), config, migration_dir)
    return requirements(pending(output), config, migration_dir)


def requirements(versions, config, migration_dir):
    """Verify migration hashes and collect reviewed owner roles."""
    result = set()
    for version in versions:
        if str(version) not in config:
            raise ValueError(f'V{version} has no reviewed prerequisite declaration')
        entry = config[str(version)]
        files = list(migration_dir.glob(f"V{version}__*.sql"))
        if len(files) != 1 or hashlib.sha256(files[0].read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"V{version} differs from reviewed prerequisite declaration")
        for role in entry["owners"]:
            if role not in OWNERS:
                raise ValueError('Unknown protected owner in prerequisite declaration')
            result.add(role)
    return sorted(result)


def execute_with_cleanup(grant, migrate, cleanup):
    """Run a migration with cleanup even when granting fails."""
    try:
        grant()
        migrate()
    finally:
        cleanup()


class Operator:
    """Manage migration execution, auditing and recovery state."""
    def __init__(self, args, report):
        self.args = args
        self.report = report
        # Keep runtime/bootstrapping secrets out of Maven and psql child environments.
        self.env = {key: val for key, val in os.environ.items()
                    if not key.startswith('EPMS_') or key in (
                        'EPMS_FLYWAY_URL', 'EPMS_FLYWAY_USERNAME', 'EPMS_FLYWAY_PASSWORD')}
        target(self.env.get('EPMS_FLYWAY_URL', ''))
        if (self.env.get('EPMS_FLYWAY_USERNAME') != MIGRATOR
                or not self.env.get('EPMS_FLYWAY_PASSWORD')):
            raise ValueError('Expected configured epms_migrator credentials')
        for key in ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', \
            '_JAVA_OPTIONS'):
            if self.env.get(key):
                raise ValueError(f'Clear {key} before using this utility')
        if any(k.startswith("FLYWAY_") for k in self.env):
            raise ValueError("Clear FLYWAY_* overrides before using this utility")
        for base in (ROOT, ROOT / "backend"):
            for name in ("maven.config", "jvm.config", "extensions.xml"):
                if (base / ".mvn" / name).exists():
                    raise ValueError("Unreviewed Maven configuration override; manual review " \
                        "required")
        config_path = Path(__file__).parent / "migration-prerequisites.json"
        self.config = json.loads(config_path.read_text())
        for relative, expected in self.config["_maven"].items():
            if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != expected:
                raise ValueError(f"Unreviewed Maven execution configuration: {relative}")
        self.admin_password = None
        self.approved_versions = None
        if self.args.admin_user and not re.fullmatch(r'[a-zA-Z_][a-zA-Z_0-9]*', \
            self.args.admin_user):
            raise ValueError('Invalid administrator login name')
        self.events = []

    def log(self, msg):
        """Record a controlled operator event."""
        self.events.append(msg)
        print(msg, flush=True)
        (self.report / 'events.json').write_text(json.dumps(self.events, indent=2) + '\n')

    def run(self, cmd, env, cwd=None, input_text=None):
        """Execute a reviewed command and redact raw failure output."""
        if not cmd or cmd[0] not in ('psql', './mvnw'):
            raise ValueError('Unsupported operator executable')
        with subprocess.Popen(  # nosec B603 - reviewed argv, no shell
                cmd, env=env, cwd=cwd, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, stdin=subprocess.PIPE,
                              start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(input=input_text, timeout=1800)
            except BaseException:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate()
                raise
        if process.returncode:
            # Do not persist arbitrary tool output which may contain SQL or secrets.
            states = sorted(set(re.findall(r'SQL State\s*:\s*([A-Z0-9]{5})\b', stdout + stderr)))
            raise RuntimeError(
                f'{cmd[0]} operation failed (exit {process.returncode}); '
                f'SQL states={states}; inspect locally')
        return stdout

    def sql(self, statement, admin=False, **parameters):
        """Execute SQL using the selected restricted or administrator login."""
        env = {k: v for k, v in self.env.items() if not k.startswith(('PG', 'EPMS_'))}
        if admin:
            if self.admin_password is None:
                self.admin_password = getpass.getpass('PostgreSQL administrator password: ')
            user, password = self.args.admin_user, self.admin_password
        else:
            user, password = MIGRATOR, self.env['EPMS_FLYWAY_PASSWORD']
        env['PGPASSWORD'] = password
        host, port = target(self.env['EPMS_FLYWAY_URL'])
        command = ['psql', '-X', '-w', '-v', 'ON_ERROR_STOP=1', '-At', '-F', '|',
                   '-h', host, '-p', port, '-d', 'epms', '-U', user]
        if parameters:
            for key, value in parameters.items():
                if not re.fullmatch(r'[a-z_]+', key):
                    raise ValueError('Invalid SQL parameter name')
                command.extend(['-v', f'{key}={value}'])
            # psql variable interpolation is supported in script input, not -c.
            return self.run(command + ['-f', '-'], env,
                            input_text=statement + '\n').strip()
        return self.run(command + ['-c', statement], env).strip()


    def flyway(self, goal):
        """Run a Flyway goal against the confirmed local target."""
        if goal not in ('info', 'validate', 'migrate'):
            raise ValueError('Unsupported Flyway goal')
        output = self.run(['./mvnw', '-B', '-Dstyle.color=never', 'flyway:' + goal], self.env, \
            ROOT / 'backend')
        output = re.sub(r'\x1b\[[0-9;]*m', '', output)
        if f'Database: {self.env["EPMS_FLYWAY_URL"]} (PostgreSQL' not in output:
            raise RuntimeError('Flyway output does not confirm configured database')
        return output

    def identity(self):
        """Verify database identity and migrator schema privileges."""
        if self.sql('SELECT current_user, current_database()') != 'epms_migrator|epms':
            raise RuntimeError('Unexpected database identity')
        if self.sql("SELECT has_schema_privilege(current_user,'public','CREATE')") != 't':
            raise RuntimeError('Migrator lacks schema CREATE')

    def role_violations(self, role):
        """Collect role attributes and schema privilege violations for one role."""
        row = self.sql("SELECT rolcanlogin, rolsuper OR rolcreatedb OR rolcreaterole "
                       "OR rolreplication OR rolbypassrls FROM pg_roles "
                       "WHERE rolname=:'role';", role=role)
        if not row:
            return [f'Missing role: {role}; use reviewed provisioning runbook'], False
        violations = []
        if row != ('f|f' if role in (*OWNERS, 'epms_security_admin') else 't|f'):
            violations.append(f'Unsafe role: {role}; use reviewed provisioning runbook')
        if role in (*OWNERS, 'epms_rbac_runtime', 'epms_bootstrap_runtime',
                    'epms_security_admin'):
            if self.sql("SELECT rolinherit FROM pg_roles WHERE rolname=:'role';",
                        role=role) != 'f':
                violations.append(f'Unexpected INHERIT flag: {role}')
        if role != MIGRATOR and self.sql(
                "SELECT has_schema_privilege(:'role','public','CREATE');", role=role) != 'f':
            violations.append(f'Unexpected schema CREATE: {role}')
        return violations, True

    def membership_violations(self, existing):
        """Check only existing roles; missing roles are reported separately."""
        violations = []
        for role in OWNERS:
            if {MIGRATOR, role} <= existing and self.sql(
                    "SELECT pg_has_role(:'member',:'role','MEMBER');",
                    member=MIGRATOR, role=role) != 'f':
                violations.append(f'Unexpected migrator membership: {role}')
        for runtime in ('epms_app', 'epms_rbac_runtime', 'epms_bootstrap_runtime'):
            for owner in (*OWNERS, MIGRATOR, 'epms_security_admin'):
                if {runtime, owner} <= existing and self.sql(
                        "SELECT pg_has_role(:'member',:'role','MEMBER');",
                        member=runtime, role=owner) != 'f':
                    violations.append(f'Unexpected privileged membership: {runtime} -> {owner}')
        return violations

    def audit(self):
        """Report all role and schema violations before blocking migration."""
        violations = []
        existing = set()
        for role in ('epms_app', 'epms_rbac_runtime', MIGRATOR, *OWNERS,
                     'epms_bootstrap_runtime', 'epms_security_admin'):
            findings, present = self.role_violations(role)
            violations.extend(findings)
            if present:
                existing.add(role)
        violations.extend(self.membership_violations(existing))
        if violations:
            for index, finding in enumerate(violations, 1):
                self.log(f'{index}. {finding}')
            raise RuntimeError(f'{len(violations)} security violations require reconciliation')
        self.log('Role and schema boundary audit PASS')

    def owner_audit(self):
        """Verify protected function ownership and history privileges."""
        for name, owner in (('epms_rbac_assign_role', OWNERS[0]), ('epms_rbac_revoke_role', \
            OWNERS[0]),
                            ('epms_auth_issue', OWNERS[2]), ('epms_auth_rotate', OWNERS[2]),
                            ('epms_bootstrap_initial_admin', OWNERS[1]), ('epms_auth_revoke', \
                                OWNERS[2]), ('epms_auth_validate_access', OWNERS[2])):
            result = self.sql("SELECT DISTINCT pg_get_userbyid(p.proowner) FROM pg_proc p "
                              "JOIN pg_namespace n ON n.oid=p.pronamespace "
                              "WHERE n.nspname='public' AND p.proname=:'name';", name=name)
            if result != owner:
                raise RuntimeError(f'Unexpected function owner or missing function: {name}')
        for role in OWNERS:
            if self.sql("SELECT has_schema_privilege(:'role','public','USAGE');", role=role) != 't':
                raise RuntimeError(f'Missing schema USAGE: {role}')
        for runtime in ('epms_app', 'epms_rbac_runtime', 'epms_bootstrap_runtime'):
            if self.sql("SELECT has_table_privilege(:'runtime','public.flyway_schema_history',"
                        "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE');", runtime=runtime) != 'f':
                raise RuntimeError(f'Unexpected Flyway history privilege: {runtime}')
        self.log('Protected function ownership and Flyway history audit PASS')

    def checked_migrate(self):
        """Recheck authorized migration inventory before execution."""
        if self.approved_versions is not None:
            output = self.flyway('info')
            if pending(output) != self.approved_versions:
                raise RuntimeError('Pending migration inventory changed after authorization')
            review_inventory(output, self.config, ROOT / 'backend/src/main/resources/db/migration')
        self.flyway('migrate')

    def apply(self, owners):
        """Apply reviewed migrations with authorized temporary grants."""
        if any(owner not in OWNERS for owner in owners):
            raise ValueError('Unreviewed owner role')
        if not owners:
            self.checked_migrate()
            return
        if not self.args.allow_elevation or not self.args.admin_user:
            raise RuntimeError('Protected owner prerequisites require --allow-elevation and ' \
                '--admin-user')
        if not sys.stdin.isatty() or input('Type ELEVATE epms to authorize temporary owner ' \
            'privileges: ') != 'ELEVATE epms':
            raise RuntimeError('Elevation not authorized')
        if self.sql('SELECT current_user, current_database()', \
            True) != self.args.admin_user + '|epms':
            raise RuntimeError('Unexpected administrator identity')
        # Refuse pre-existing grants rather than overwrite or revoke someone else's privileges.
        self.audit()
        grants = ' '.join(
            f'GRANT {role} TO {MIGRATOR} WITH INHERIT TRUE, SET TRUE; '
            f'GRANT CREATE ON SCHEMA public TO {role};' for role in owners)
        revokes = ' '.join(
            f'REVOKE CREATE ON SCHEMA public FROM {role}; '
            f'REVOKE {role} FROM {MIGRATOR};' for role in owners)
        journal = self.report / 'RECOVERY.sql'
        with journal.open('x') as handle:
            handle.write('BEGIN;\n' + revokes + '\nCOMMIT;\n')
            handle.flush()
            os.fsync(handle.fileno())
        self.log('Recovery SQL recorded before elevation; retain until cleanup succeeds')

        def grant():
            self.sql('BEGIN; ' + grants + ' COMMIT;', True)
            self.log('Temporary grants applied')

        def cleanup():
            cleaned = False
            try:
                self.sql('BEGIN; ' + revokes + ' COMMIT;', True)
                self.audit()
                journal.unlink()
                self.log('Temporary grants removed and verified')
                cleaned = True
            finally:
                if not cleaned:
                    self.log('CRITICAL: cleanup failed; run RECOVERY.sql as the '
                             'administrator and audit before continuing')

        execute_with_cleanup(grant, self.checked_migrate, cleanup)


def run_inventory(operator, args):
    """Review pending migrations and execute only explicitly approved changes."""
    info = operator.flyway('info')
    versions = pending(info)
    for line in info.splitlines():
        if 'Schema version:' in line:
            operator.log('Schema version: ' + line.split('Schema version:', 1)[1].strip())
        elif args.info and '| Versioned' in line:
            operator.log(line[line.index('|'):])
    operator.log('Pending versions: ' + str(versions))
    if not args.info:
        owners = review_inventory(info, operator.config, \
            ROOT / 'backend/src/main/resources/db/migration')
        operator.audit()
        operator.log('Required temporary owner roles: ' + str(owners))
        if args.apply:
            apply_inventory(operator, args, versions, owners)


def apply_inventory(operator, args, versions, owners):
    """Apply the reviewed inventory and verify the post-migration state."""
    if not args.backup_confirmed:
        raise RuntimeError('Confirm reviewed migrations and restorable backup with ' \
            '--backup-confirmed')
    if versions:
        if pending(operator.flyway('info')) != versions:
            raise RuntimeError('Pending migration inventory changed during preflight')
        if requirements(versions, operator.config, \
            ROOT / 'backend/src/main/resources/db/migration') != owners:
            raise RuntimeError('Migration prerequisites changed during preflight')
        operator.approved_versions = versions
        operator.apply(owners)
    operator.flyway('validate')
    if pending(operator.flyway('info')):
        raise RuntimeError('Pending migrations remain')
    operator.audit()
    operator.owner_audit()


def run_operator(args, report):
    """Dispatch the selected operator mode."""
    operator = Operator(args, report)
    operator.identity()
    operator.log('Target: ' + operator.env['EPMS_FLYWAY_URL'])
    if args.audit:
        operator.audit()
        operator.owner_audit()
    elif args.validate:
        operator.flyway('validate')
    else:
        run_inventory(operator, args)
    operator.log('PASS')


def main():
    """Run the command-line workflow and return its exit status."""
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    for mode in ('info', 'dry-run', 'apply', 'validate', 'audit'):
        modes.add_argument('--' + mode, action='store_true')
    parser.add_argument('--allow-elevation', action='store_true')
    parser.add_argument('--admin-user')
    parser.add_argument('--backup-confirmed', action='store_true')
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    downloads = Path.home() / 'Downloads'
    downloads.mkdir(exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S_%fZ')
    report = downloads / ('epms_migration_' + timestamp)
    report.mkdir(mode=0o700)
    print('Report:', report)
    try:
        with (downloads / '.epms-migration.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if (any(downloads.glob('epms_migration_*/RECOVERY.sql'))
                    and not (args.audit or args.info)):
                raise RuntimeError('Unresolved recovery journal exists; reconcile before running')
            run_operator(args, report)
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError,
            KeyboardInterrupt) as exc:
        # Controlled messages only; no raw SQL output or credentials.
        message = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print('FAIL:', message, file=sys.stderr)
        (report / 'failure.txt').write_text(message + '\n')
        return 1


if __name__ == '__main__':
    sys.exit(main())
