import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_env import runtime_environment


class RuntimeEnvironmentTest(unittest.TestCase):
    def test_metering_signing_key_only_reaches_customer_and_billing(self):
        source = {'SUPPORTFLOW_BILLING_CUSTOMER_SECRET': 'synthetic-metering'}
        for service in ('gateway', 'customer', 'ops', 'tenant', 'realtime', 'user', 'ai', 'billing'):
            filtered = runtime_environment(service + '-service', source)
            self.assertEqual('SUPPORTFLOW_BILLING_CUSTOMER_SECRET' in filtered, service in ('customer', 'billing'))

    def test_runtime_never_receives_bootstrap_or_foreign_database_credentials(self):
        roles = ('USER', 'TENANT', 'CUSTOMER', 'BILLING', 'OPS', 'AI', 'KB', 'CUSTOMER_PLATFORM')
        source = {f'SUPPORTFLOW_{r}_DB_PASSWORD': 'synthetic-' + r for r in roles}
        source.update(SUPPORTFLOW_DB_PASSWORD='bootstrap', SUPPORTFLOW_DB_USER='supportflow',
                      SUPPORTFLOW_DB_HOST='127.0.0.1', SUPPORTFLOW_AI_DATABASE_URL='private-ai-dsn')
        for role in ('USER', 'TENANT', 'CUSTOMER', 'BILLING', 'OPS', 'AI', 'GATEWAY', 'REALTIME'):
            filtered = runtime_environment(role.lower() + '-service', source)
            self.assertNotIn('SUPPORTFLOW_DB_PASSWORD', filtered)
            self.assertNotIn('SUPPORTFLOW_DB_USER', filtered)
            allowed = {'AI', 'KB'} if role == 'AI' else {role}
            if role == 'CUSTOMER':
                allowed.add('CUSTOMER_PLATFORM')
            self.assertEqual({k for k in filtered if k.endswith('_DB_PASSWORD')},
                             {f'SUPPORTFLOW_{r}_DB_PASSWORD' for r in roles if r in allowed})
            self.assertEqual(filtered['SUPPORTFLOW_DB_HOST'], '127.0.0.1')
            self.assertEqual('SUPPORTFLOW_AI_DATABASE_URL' in filtered, role == 'AI')

    def test_service_signing_keys_are_distributed_only_to_their_consumer(self):
        source = {f'SUPPORTFLOW_AI_{r}_SECRET': 'synthetic-' + r for r in ('GATEWAY', 'CUSTOMER', 'OPS', 'TENANT')}
        source.update({f'SUPPORTFLOW_CUSTOMER_{r}_SECRET': 'synthetic-' +
                      r for r in ('REALTIME', 'OPS', 'TENANT')})
        for service in ('gateway', 'customer', 'ops', 'tenant', 'realtime', 'user'):
            filtered = runtime_environment(service + '-service', source)
            ai = {k for k in filtered if k.startswith('SUPPORTFLOW_AI_')}
            self.assertEqual(ai, {f'SUPPORTFLOW_AI_{service.upper()}_SECRET'}
                             if service in ('gateway', 'customer', 'ops', 'tenant') else set())
            customer = {k for k in filtered if k.startswith('SUPPORTFLOW_CUSTOMER_')}
            expected = {f'SUPPORTFLOW_CUSTOMER_{r}_SECRET' for r in ('REALTIME', 'OPS', 'TENANT')} if service == 'customer' else (
                {f'SUPPORTFLOW_CUSTOMER_{service.upper()}_SECRET'} if service in ('realtime', 'ops', 'tenant') else set())
            self.assertEqual(customer, expected)


if __name__ == '__main__':
    unittest.main()
