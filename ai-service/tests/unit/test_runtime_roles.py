from types import SimpleNamespace
from unittest.mock import MagicMock
import pytest
from ai.core import runtime_roles


def test_runtime_role_guard_checks_both_databases_and_rejects_any_admin_flag(monkeypatch):
    monkeypatch.setattr(runtime_roles, 'get_settings', lambda: SimpleNamespace(
        enforce_runtime_db_roles=True, database_url='ai-dsn', kb_database_url='kb-dsn'))
    connect = MagicMock()
    monkeypatch.setattr(runtime_roles.psycopg, 'connect', connect)
    result = connect.return_value.__enter__.return_value.execute.return_value
    result.fetchone.return_value = (False, False, False, False, False)
    runtime_roles.require_runtime_roles()
    assert [c.args[0] for c in connect.call_args_list] == ['ai-dsn', 'kb-dsn']
    for i in range(5):
        row = [False] * 5
        row[i] = True
        result.fetchone.return_value = row
        with pytest.raises(RuntimeError, match='non-owner'):
            runtime_roles.require_runtime_roles()
    result.fetchone.return_value = None
    with pytest.raises(RuntimeError):
        runtime_roles.require_runtime_roles()
