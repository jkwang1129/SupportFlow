"""Operator-owned tenant and model selection for the read-only worker host."""
from types import SimpleNamespace

import pytest

from ai.config import Settings
from ai.runtime import ticket_worker
from ai.runtime.worker import RuntimeWorker


def settings(**changes):
    return Settings(_env_file=None, **({
        'runtime_api_enabled': True,
        'customer_runtime_url': 'http://127.0.0.1:14000',
        'customer_secret': 'synthetic-test-service-credential-01',
        'qwen_api_key': 'synthetic-test-qwen-key',
        'qwen_base_url': 'http://127.0.0.1:14001/v1',
        'qwen_model': 'fixture-chat',
        'deepseek_api_key': '',
    } | changes))


def test_operator_config_is_bound_to_one_tenant_and_read_capabilities():
    worker = ticket_worker.build_worker(settings(), 'TENANT_A', 'qwen', owner='fixture-worker')
    loop = worker.handlers['runtime-v1']
    assert worker.tenant == loop.model.profile.tenant == 'TENANT_A'
    assert worker.approval_mode == 'without_approval'
    assert not loop.canonical_history
    assert loop.model.profile.model == 'fixture-chat'
    assert 'synthetic-test-qwen-key' not in repr(loop.model.profile)
    assert {v['name'] for v in loop.source_access.registry.catalog()} == {'get_current_message'}
    assert {v['name'] for v in loop.tools.registry.catalog()} == {'get_ticket_status'}


def test_canonical_planner_history_is_explicitly_enabled_for_the_read_worker():
    worker = ticket_worker.build_worker(settings(runtime_canonical_planner_history=True), 'TENANT_A', 'qwen',
                                        owner='fixture-context-worker')
    assert worker.handlers['runtime-v1'].canonical_history


def test_mcp_requires_separate_credentials_and_changes_the_bound_read_policy():
    standard = ticket_worker.build_worker(settings(), 'TENANT_A', 'qwen', owner='fixture-worker')
    url = 'http://127.0.0.1:14002/mcp'
    configured = ticket_worker.build_worker(
        settings(runtime_ticket_mcp_url=url, runtime_ticket_mcp_secret='synthetic-mcp-service-credential-00002'),
        'TENANT_A', 'qwen', owner='fixture-worker')
    assert standard.handlers['runtime-v1'].transport_hash is None
    assert len(configured.handlers['runtime-v1'].transport_hash) == 64
    with pytest.raises(ValueError):
        ticket_worker.build_worker(settings(runtime_ticket_mcp_url=url,
            runtime_ticket_mcp_secret='synthetic-test-service-credential-01'), 'TENANT_A', 'qwen', owner='fixture-worker')


@pytest.mark.parametrize('changes, tenant, provider', [
    ({'runtime_api_enabled': False}, 'TENANT_A', 'qwen'),
    ({}, 'PLATFORM', 'qwen'),
    ({}, 'TENANT_A', 'mock'),
    ({}, 'TENANT_A', 'deepseek'),
    ({'customer_secret': ''}, 'TENANT_A', 'qwen'),
])
def test_invalid_policy_fails_closed_without_default_model_or_key(changes, tenant, provider):
    with pytest.raises(ValueError) as failure:
        ticket_worker.build_worker(settings(**changes), tenant, provider, owner='fixture-worker')
    assert 'synthetic-test' not in str(failure.value)


def test_scoped_worker_never_discovers_or_processes_other_tenants(monkeypatch):
    events = []
    worker = RuntimeWorker(object(), {'runtime-v1': lambda _: {}}, 'fixture-worker', tenant='TENANT_A')
    assert worker.run_once('TENANT_B') is None
    monkeypatch.setattr(worker, 'run_once', lambda tenant: events.append(tenant))
    assert worker.tick() is None and events == ['TENANT_A']


def test_cli_once_requires_runtime_schema_and_restricted_role(monkeypatch):
    events = []
    monkeypatch.setattr(ticket_worker, 'get_settings', settings)
    monkeypatch.setattr(ticket_worker, 'build_worker', lambda *_args, **_kw:
                        SimpleNamespace(run_once=lambda tenant: events.append(tenant)))
    args = ['--tenant', 'TENANT_A', '--provider', 'qwen', '--once']
    monkeypatch.setattr(ticket_worker, 'runtime_missing', lambda: {'ai_db.runtime_capability': 'unavailable'})
    assert ticket_worker.main(args) == 1 and events == []
    monkeypatch.setattr(ticket_worker, 'runtime_missing', lambda: {})
    assert ticket_worker.main(args) == 0 and events == ['TENANT_A']


def test_approval_worker_has_no_model_requirement_and_uses_only_approval_queue():
    worker = ticket_worker.build_approval_worker(settings(runtime_ticket_actions_enabled=True, qwen_api_key=''),
                                                'TENANT_A', owner='fixture-approved-worker')
    assert worker.tenant == 'TENANT_A' and worker.approval_mode == 'approved_only'
    with pytest.raises(ValueError):
        ticket_worker.build_approval_worker(settings(runtime_ticket_actions_enabled=False),
                                            'TENANT_A', owner='fixture-approved-worker')
