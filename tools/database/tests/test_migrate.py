"""Offline tests; never connect to a live EPMS database."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

MODULE = Path(__file__).resolve().parents[1] / 'migrate.py'
SPEC = importlib.util.spec_from_file_location('epms_migrate', MODULE)
MIGRATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATE)
INFO = '''[INFO] Schema version: 33
| Category | Version | Description | Type | Installed On | State | Undoable |
| Versioned | 33 | command boundary | SQL | 2026-11-25 | Success | No |
| Versioned | 34 | next | SQL | | Pending | No |
'''


class MigrationTests(unittest.TestCase):
    """Test fail-closed parsing, prerequisites and privilege restoration."""

    def test_audit_aggregates_privilege_violations(self):
        """Report both drift findings without changing grants."""
        operator = MIGRATE.Operator.__new__(MIGRATE.Operator)

        def query(statement, **parameters):
            role = parameters['role']

            if 'rolcanlogin' in statement:
                return 'f|f' if role in (*MIGRATE.OWNERS, 'epms_security_admin') else 't|f'

            if 'rolinherit' in statement:
                return 'f'

            if 'has_schema_privilege' in statement:
                return 't' if role == MIGRATE.OWNERS[0] else 'f'

            if 'pg_has_role' in statement:
                return 't' if (
                    role == MIGRATE.OWNERS[0]
                    and parameters['member'] == MIGRATE.MIGRATOR
                ) else 'f'

            raise AssertionError('Unexpected query')

        with patch.object(operator, 'sql', side_effect=query), \
                patch.object(operator, 'log') as log:
            with self.assertRaisesRegex(RuntimeError, '2 security violations'):
                operator.audit()

        self.assertIn(
            'Unexpected schema CREATE',
            log.call_args_list[0].args[0]
        )

        self.assertIn(
            'Unexpected migrator membership',
            log.call_args_list[1].args[0]
        )

    def test_target(self):
        """Reject remote databases, credentials and malformed local addresses."""
        scheme = 'jdbc:' + 'postgresql' + '://'
        local = scheme + 'localhost:5432'
        self.assertEqual(MIGRATE.target(local + '/epms'), ('localhost', '5432'))
        self.assertEqual(MIGRATE.target(scheme + '127.0.0.1:05432/epms'),
                         ('127.0.0.1', '05432'))
        for value in (scheme + 'remote:5432/epms', local + '/other',
                      local + '/epms?user=postgres', local + '/epms?',
                      local + '/epms#', local + '/epms\n',
                      scheme + 'localhost:0/epms', scheme + 'localhost:65536/epms',
                      scheme + 'localhost/epms', scheme + 'localhost:abc/epms',
                      scheme + 'user@localhost:5432/epms', ''):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MIGRATE.target(value)

    def test_sql_parameters_use_script_input(self):
        """Pass SQL values separately using psql's literal quoting mechanism."""
        operator = MIGRATE.Operator.__new__(MIGRATE.Operator)
        operator.env = {
            'EPMS_FLYWAY_PASSWORD': str(object()),
            'EPMS_FLYWAY_URL': 'jdbc:' + 'postgresql' + '://localhost:5432/epms',
        }
        statement = "SELECT :'role';"
        with patch.object(operator, 'run', return_value='ok') as run:
            self.assertEqual(operator.sql(statement, role="x' OR true"), 'ok')
        argv = run.call_args.args[0]
        self.assertIn("role=x' OR true", argv)
        self.assertEqual(argv[-2:], ['-f', '-'])
        self.assertEqual(run.call_args.kwargs['input_text'], statement + '\n')

    def test_pending(self):
        """Verify pending."""
        self.assertEqual(MIGRATE.pending(INFO), [34])
        self.assertEqual(MIGRATE.pending(INFO.replace('Pending', 'Success')), [])

    def test_unknown_status(self):
        """Verify unknown status."""
        for output in (INFO.replace('Pending', 'Failed'), INFO.replace('Pending', 'Missing'),
                       INFO.replace('Versioned | 34', 'Repeatable | 34'), ''):
            with self.subTest(output=output), self.assertRaises(ValueError):
                MIGRATE.pending(output)

    def test_prerequisites(self):
        """Verify prerequisites."""
        directory = MODULE.parents[2] / 'backend/src/main/resources/db/migration'
        config = json.loads((MODULE.parent / 'migration-prerequisites.json').read_text())
        # Exercise a reviewed actual migration; fixture below avoids any repository mutation.
        self.assertEqual(MIGRATE.requirements([], config, directory), [])
        with self.assertRaises(ValueError):
            MIGRATE.requirements([999], config, directory)
        with self.assertRaises(ValueError):
            MIGRATE.requirements([33], {'33': {'owners': [], 'sha256': 'wrong'}}, directory)

    def test_known_hash_and_owner_classification(self):
        """Verify known hash and owner classification."""
        folder = Mock()
        path = Mock()
        path.read_bytes.return_value = b'SELECT 1;'
        folder.glob.return_value = [path]
        config = {'34': {'owners': [MIGRATE.OWNERS[2]],
                         'sha256': hashlib.sha256(b'SELECT 1;').hexdigest()}}
        self.assertEqual(MIGRATE.requirements([34], config, folder), [MIGRATE.OWNERS[2]])
        config['34']['owners'] = ['postgres']
        with self.assertRaisesRegex(ValueError, 'Unknown protected owner'):
            MIGRATE.requirements([34], config, folder)
        config['34']['owners'] = []
        path.read_bytes.return_value = b'SELECT 2;'
        with self.assertRaisesRegex(ValueError, 'differs'):
            MIGRATE.requirements([34], config, folder)

    def test_unreviewed_owner_cannot_be_granted(self):
        """Verify unreviewed owner cannot be granted."""
        operator = Mock()
        with self.assertRaisesRegex(ValueError, 'Unreviewed owner'):
            MIGRATE.Operator.apply(operator, ['postgres'])
        operator.sql.assert_not_called()

    def test_cleanup_after_success(self):
        """Verify cleanup after success."""
        sequence = []
        MIGRATE.execute_with_cleanup(lambda: sequence.append('grant'), \
            lambda: sequence.append('migrate'),
                                     lambda: sequence.append('cleanup'))
        self.assertEqual(sequence, ['grant', 'migrate', 'cleanup'])

    def test_cleanup_after_migration_failure(self):
        """Verify cleanup after migration failure."""
        cleanup = Mock()
        with self.assertRaises(RuntimeError):
            MIGRATE.execute_with_cleanup(Mock(), \
                Mock(side_effect=RuntimeError('migration failed')), cleanup)
        cleanup.assert_called_once()

    def test_cleanup_after_grant_failure(self):
        """Verify cleanup after grant failure."""
        cleanup, migrate = Mock(), Mock()
        with self.assertRaises(RuntimeError):
            MIGRATE.execute_with_cleanup(Mock(side_effect=RuntimeError('grant failed')), \
                migrate, cleanup)
        migrate.assert_not_called()
        cleanup.assert_called_once()

    def test_cleanup_failure_is_not_success(self):
        """Verify cleanup failure is not success."""
        with self.assertRaisesRegex(RuntimeError, 'cleanup failed'):
            MIGRATE.execute_with_cleanup(Mock(), Mock(), \
                Mock(side_effect=RuntimeError('cleanup failed')))

    def test_routine_does_not_use_admin(self):
        """Verify routine does not use admin."""
        operator = Mock()
        MIGRATE.Operator.apply(operator, [])
        operator.checked_migrate.assert_called_once()
        operator.sql.assert_not_called()

    def test_elevation_requires_authorization(self):
        """Verify elevation requires authorization."""
        operator = Mock()
        operator.args.allow_elevation = False
        with self.assertRaisesRegex(RuntimeError, 'allow-elevation'):
            MIGRATE.Operator.apply(operator, [MIGRATE.OWNERS[0]])
        operator.sql.assert_not_called()
        operator.flyway.assert_not_called()

    def test_elevation_declined(self):
        """Verify elevation declined."""
        operator = Mock()
        operator.args.allow_elevation = True
        with patch.object(MIGRATE.sys.stdin, 'isatty', return_value=True), \
            patch('builtins.input', return_value='no'):
            with self.assertRaisesRegex(RuntimeError, 'not authorized'):
                MIGRATE.Operator.apply(operator, [MIGRATE.OWNERS[0]])
        operator.sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()
