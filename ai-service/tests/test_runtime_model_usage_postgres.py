"""Observe usage committed through the real Runtime and restricted PostgreSQL role."""
import json
import os
from pathlib import Path
import subprocess
import sys

import httpx
import pytest

from ai.runtime.embeddings import RuntimeEmbeddingClient
from ai.runtime.models import RuntimeChatClient
from ai.runtime.payloads import encode
from ai.runtime.types import Limits, RuntimeConflict
from ai.runtime.worker import RuntimeWorker
from test_chat_transport import response
from test_runtime_embeddings import answer as embedding_answer, profile as embedding_profile, TEXT
from test_runtime_models import answer, profile, MESSAGES
from test_runtime_store_postgres import admin, request, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def _runner(store, handler):
    return RuntimeWorker(store, {'runtime-v1': handler}, 'usage-test-worker')


def _export(tenant, run_id, directory):
    env = {key: os.environ[key] for key in ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP') if key in os.environ}
    env.update(PYTHONUTF8='1', PYTHONPATH=str(Path(__file__).resolve().parents[1]),
               SUPPORTFLOW_AI_DATABASE_URL=os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'])
    return subprocess.run([sys.executable, '-m', 'ai.runtime.trace_export', '--tenant', tenant,
                           '--run', str(run_id), '--model-usage'], cwd=directory, env=env,
                          capture_output=True, text=True, encoding='utf-8', timeout=10)


def test_runtime_model_usage_cli_observes_chat_embedding_and_inflight_retry_once(store, tmp_path):
    source = request()
    run_id = store.enqueue(source, Limits(max_model_calls=3))['run_id']
    calls = []

    def chat_server(_):
        calls.append('chat')
        if calls == ['chat']:
            raise httpx.ReadTimeout('private fixture input')
        return response(answer())

    def embedding_server(_):
        calls.append('embedding')
        return response(embedding_answer())

    chat = RuntimeChatClient(profile(), transport=httpx.MockTransport(chat_server))
    embedding = RuntimeEmbeddingClient(embedding_profile(), transport=httpx.MockTransport(embedding_server))

    def handler(context):
        chat.complete(context, 'plan', MESSAGES)
        chat.complete(context, 'plan', MESSAGES)
        embedding.embed(context, 'retrieve', TEXT)
        return {'outcome': 'fixture-verified'}

    runner = _runner(store, handler)
    assert runner.run_once(source.tenant)['state'] == 'RETRY_WAIT'
    waiting = store.model_usage(source.tenant, run_id)
    assert waiting['attempts_without_observed_usage'] == 1 and waiting['observed_input_tokens'] is None
    with admin() as connection:
        connection.execute('UPDATE agent_run SET ready_at=clock_timestamp() WHERE run_id=%s', (run_id,))
    finished = runner.run_once(source.tenant)
    assert finished['state'] == 'SUCCEEDED' and calls == ['chat', 'chat', 'embedding']

    cli = _export(source.tenant, run_id, tmp_path)
    assert cli.returncode == 0 and cli.stderr == ''
    observed = json.loads(cli.stdout)
    assert observed == store.model_usage(source.tenant, run_id) == {
        'schema_version': 'runtime-model-usage-v1', 'run_id': str(run_id), 'model_attempts_reserved': 3,
        'attempts_with_observed_usage': 2, 'attempts_without_observed_usage': 1,
        'observed_input_tokens': 21, 'observed_chat_output_tokens': 7,
    }
    denied = _export('TENANT_B', run_id, tmp_path)
    assert denied.returncode == 1 and denied.stdout == '' and str(run_id) not in denied.stderr
    assert store.get(source.tenant, run_id) == finished and calls == ['chat', 'chat', 'embedding']
    report = Path(__file__).resolve().parents[2] / '.local/verification/runtime-model-usage.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({'usage_source': 'SYNTHETIC_PROVIDER_REAL_POSTGRES', 'provider_calls': calls,
                                  'after_timeout': waiting, 'after_retry': observed}, indent=2)+'\n', encoding='utf-8')


@pytest.mark.parametrize('scenario', ['missing', 'expired', 'deleted', 'reported-zero'])
def test_runtime_model_usage_distinguishes_unknown_and_zero(store, scenario):
    row = store.enqueue(request())
    run_id, tenant = row['run_id'], row['tenant_code']
    assert store.model_usage(tenant, run_id)['model_attempts_reserved'] == 0
    result = answer()
    if scenario == 'missing':
        result.pop('usage')
    if scenario == 'reported-zero':
        result['usage'] = {'prompt_tokens': 0, 'completion_tokens': 0}
    chat = RuntimeChatClient(profile(), transport=httpx.MockTransport(lambda _: response(result)))

    def handler(context):
        chat.complete(context, 'plan', MESSAGES)
        return {'outcome': 'fixture-verified'}

    assert _runner(store, handler).run_once(tenant)['state'] == 'SUCCEEDED'
    with admin() as connection:
        if scenario == 'expired':
            connection.execute("""UPDATE agent_payload SET created_at=clock_timestamp()-interval '2 hours',
                expires_at=clock_timestamp()-interval '1 hour' WHERE run_id=%s""", (run_id,))
        if scenario == 'deleted':
            connection.execute('DELETE FROM agent_payload WHERE run_id=%s', (run_id,))
    observed = store.model_usage(tenant, run_id)
    available = scenario == 'reported-zero'
    assert observed['attempts_with_observed_usage'] == int(available)
    assert observed['attempts_without_observed_usage'] == int(not available)
    assert observed['observed_input_tokens'] == observed['observed_chat_output_tokens'] == (0 if available else None)


@pytest.mark.parametrize('fault', ['checksum', 'binding', 'usage', 'reservation-count'])
def test_runtime_model_usage_refuses_invalid_persisted_evidence(store, fault):
    row = store.enqueue(request())
    run_id, tenant = row['run_id'], row['tenant_code']
    chat = RuntimeChatClient(profile(), transport=httpx.MockTransport(lambda _: response(answer())))

    def handler(context):
        chat.complete(context, 'plan', MESSAGES)
        return {'outcome': 'fixture-verified'}

    assert _runner(store, handler).run_once(tenant)['state'] == 'SUCCEEDED'
    with admin() as connection:
        if fault == 'checksum':
            connection.execute("UPDATE agent_payload SET payload_hash=repeat('0',64) WHERE run_id=%s", (run_id,))
        elif fault == 'reservation-count':
            connection.execute('UPDATE agent_run SET model_calls=0 WHERE run_id=%s', (run_id,))
        else:
            payload = connection.execute('SELECT payload_json FROM agent_payload WHERE run_id=%s', (run_id,)).fetchone()
            value = json.loads(payload['payload_json'])
            if fault == 'binding':
                value['profile_ref'] = 'different-profile'
            else:
                value['usage']['input_tokens'] = True
            raw, digest = encode('model-output-v1', value)
            connection.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s WHERE run_id=%s', (raw, digest, run_id))
    with pytest.raises(RuntimeConflict, match='invalid|inconsistent'):
        store.model_usage(tenant, run_id)
