import json
from pathlib import Path
import tempfile
import unittest

from service_diagnostics import capture, structural_events


class ServiceDiagnosticsTest(unittest.TestCase):
    def test_messages_sql_and_credentials_are_never_copied(self):
        result = structural_events([
            'Authorization: Bearer synthetic-secret\n',
            'Caused by: org.postgresql.util.PSQLException: ERROR: permission denied for table secret_table\n',
            'java.lang.NumberFormatException: For input string: "synthetic-secret"\n',
            '\tat io.supportflow.customer.TicketService.create(TicketService.java:42)\n',
            'SQL: insert into thing values (\'synthetic-secret\')\n',
            '\tat external.syntheticsecret.Secret.run(Secret.java:1)\n',
        ])
        self.assertEqual(result, [
            {'line': 2, 'exception': 'org.postgresql.util.PSQLException'},
            {'line': 2, 'database_reason': 'permission_denied'},
            {'line': 3, 'exception': 'java.lang.NumberFormatException'},
            {'line': 4, 'frame': 'io.supportflow.customer.TicketService.create(TicketService.java:42)'},
        ])
        self.assertNotIn('synthetic-secret', json.dumps(result))
        self.assertNotIn('secret_table', json.dumps(result))

    def test_only_known_logs_are_read_and_report_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.test-admin').write_text('password', encoding='utf-8')
            (root / 'unlisted.log').write_text('java.lang.PasswordException: password', encoding='utf-8')
            (root / 'customer-service.log').write_text(
                'java.lang.IllegalStateException: password\n' * 250, encoding='utf-8')
            destination = root / 'reports/errors.json'
            capture(root, destination, 'FRESH DEFAULT')
            result = json.loads(destination.read_text(encoding='utf-8'))
            self.assertEqual(list(result['services']), ['customer-service'])
            self.assertEqual(len(result['services']['customer-service']), 180)
            self.assertNotIn('password', destination.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
