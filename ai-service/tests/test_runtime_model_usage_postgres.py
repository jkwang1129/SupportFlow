"""Observe usage committed through the real Runtime and restricted PostgreSQL role."""
import json
import os
from pathlib import Path
import subprocess
import sys

import httpx
import psycopg
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
    with admin() as connection:
        receipts = connection.execute('''SELECT input_tokens,chat_output_tokens FROM agent_model_usage
            WHERE run_id=%s ORDER BY step_no''', (run_id,)).fetchall()
        assert [(row['input_tokens'], row['chat_output_tokens']) for row in receipts] == [(13, 7), (8, None)]
        connection.execute('DELETE FROM agent_payload WHERE run_id=%s', (run_id,))
    assert json.loads(_export(source.tenant, run_id, tmp_path).stdout) == observed
    denied = _export('TENANT_B', run_id, tmp_path)
    assert denied.returncode == 1 and denied.stdout == '' and str(run_id) not in denied.stderr
    assert store.get(source.tenant, run_id) == finished and calls == ['chat', 'chat', 'embedding']
    report = Path(__file__).resolve().parents[2] / '.local/verification/runtime-model-usage.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({'usage_source': 'SYNTHETIC_PROVIDER_REAL_POSTGRES', 'provider_calls': calls,
                                  'after_timeout': waiting, 'after_retry': observed}, indent=2)+'\n', encoding='utf-8')


@pytest.mark.parametrize('scenario', ['missing', 'expired', 'deleted', 'reported-zero', 'legacy-live', 'legacy-expired'])
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
        if scenario.startswith('legacy-'):
            connection.execute('DELETE FROM agent_model_usage WHERE run_id=%s', (run_id,))
        if scenario in ('expired', 'legacy-expired'):
            connection.execute("""UPDATE agent_payload SET created_at=clock_timestamp()-interval '2 hours',
                expires_at=clock_timestamp()-interval '1 hour' WHERE run_id=%s""", (run_id,))
        if scenario == 'deleted':
            connection.execute('DELETE FROM agent_payload WHERE run_id=%s', (run_id,))
    observed = store.model_usage(tenant, run_id)
    expected = {
        'missing': (None, None), 'expired': (13, 7), 'deleted': (13, 7), 'reported-zero': (0, 0),
        'legacy-live': (13, 7), 'legacy-expired': (None, None),
    }[scenario]
    available = expected[0] is not None
    assert observed['attempts_with_observed_usage'] == int(available)
    assert observed['attempts_without_observed_usage'] == int(not available)
    assert (observed['observed_input_tokens'], observed['observed_chat_output_tokens']) == expected


@pytest.mark.parametrize('fault', ['checksum', 'binding', 'usage', 'reservation-count', 'receipt-count', 'receipt-fence-after-cleanup'])
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
        elif fault == 'receipt-count':
            connection.execute('UPDATE agent_model_usage SET input_tokens=1 WHERE run_id=%s', (run_id,))
        elif fault == 'receipt-fence-after-cleanup':
            connection.execute('DELETE FROM agent_payload WHERE run_id=%s', (run_id,))
            connection.execute('UPDATE agent_model_usage SET fence=fence+1 WHERE run_id=%s', (run_id,))
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


def test_runtime_model_usage_rolls_back_receipt_checkpoint_and_step_together(store, monkeypatch):
    source = request()
    run_id = store.enqueue(source)['run_id']
    chat = RuntimeChatClient(profile(), transport=httpx.MockTransport(lambda _: response(answer())))
    owned = store._owned
    observed_writes = []

    def lose_connection_before_commit(connection, lease):
        result = owned(connection, lease)
        row = connection.execute('''SELECT u.input_tokens,s.state FROM agent_model_usage u JOIN agent_step s
            USING (tenant_code,run_id,step_no) WHERE u.run_id=%s''', (run_id,)).fetchone()
        if row is not None:
            observed_writes.append((row['input_tokens'], row['state']))
            raise psycopg.OperationalError('synthetic failure after writing receipt before commit')
        return result

    monkeypatch.setattr(store, '_owned', lose_connection_before_commit)
    outcome = _runner(store, lambda ctx: chat.complete(ctx, 'plan', MESSAGES)).run_once(source.tenant)
    assert observed_writes == [(13, 'SUCCEEDED')]
    assert outcome['state'] == 'RETRY_WAIT' and outcome['error_code'] == 'RUNTIME_PERSISTENCE_UNAVAILABLE'
    assert store.steps(source.tenant, run_id)[0]['state'] == 'UNKNOWN'
    with admin() as connection:
        assert connection.execute('SELECT count(*) AS n FROM agent_payload WHERE run_id=%s', (run_id,)).fetchone()['n'] == 0
        assert connection.execute('SELECT count(*) AS n FROM agent_model_usage WHERE run_id=%s', (run_id,)).fetchone()['n'] == 0
    assert store.model_usage(source.tenant, run_id)['attempts_without_observed_usage'] == 1


def test_runtime_model_usage_receipts_are_scoped_and_immutable(store):
    source = request()
    run_id = store.enqueue(source)['run_id']
    chat = RuntimeChatClient(profile(), transport=httpx.MockTransport(lambda _: response(answer())))

    def handler(context):
        chat.complete(context, 'plan', MESSAGES)
        return {'outcome': 'verified'}

    assert _runner(store, handler).run_once(source.tenant)['state'] == 'SUCCEEDED'
    with store.connector() as connection:
        for scope, expected in (('', []), ('TENANT_B', []), ('PLATFORM', []), (source.tenant, [run_id])):
            connection.execute("SELECT set_config('app.tenant_code',%s,true)", (scope,))
            rows = connection.execute('SELECT run_id FROM agent_model_usage').fetchall()
            assert [row['run_id'] for row in rows] == expected
        receipt = connection.execute('''SELECT tenant_code,run_id,step_no,fence,intent_hash,payload_id,
            payload_hash,input_tokens,chat_output_tokens FROM agent_model_usage''').fetchone()
        connection.execute("SELECT set_config('app.tenant_code','TENANT_B',true)")
        with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
            connection.execute('''INSERT INTO agent_model_usage
                (tenant_code,run_id,step_no,fence,intent_hash,payload_id,payload_hash,input_tokens,chat_output_tokens)
                VALUES(%(tenant_code)s,%(run_id)s,%(step_no)s,%(fence)s,%(intent_hash)s,%(payload_id)s,
                %(payload_hash)s,%(input_tokens)s,%(chat_output_tokens)s)''', receipt)
        connection.execute("SELECT set_config('app.tenant_code',%s,true)", (source.tenant,))
        for statement in ('UPDATE agent_model_usage SET input_tokens=0', 'DELETE FROM agent_model_usage',
                          'TRUNCATE agent_model_usage', 'ALTER TABLE agent_model_usage NO FORCE ROW LEVEL SECURITY'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                connection.execute(statement)
