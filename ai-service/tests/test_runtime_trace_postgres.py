"""Read live Runtime trace metadata from PostgreSQL with the restricted account."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from ai.runtime.types import RuntimeConflict
from ai.runtime.trace_export import _metadata
from ai.runtime.trace_replay import replay
from ai.runtime.worker import PermanentFailure, RuntimeWorker, TransientFailure
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def test_trace_cli_exports_run_and_ordered_steps_without_private_references(store, tmp_path):
    source = request()
    queued = store.enqueue(source)
    run = queued['run_id']

    def handler(context):
        context.payload_step('fixture-private-step', 'TOOL', 'READ', {'resource_ref': 'fixture-private-resource'},
                             'tool-output-v1', lambda: {'content': 'fixture-private-content'})
        return {'outcome': 'fixture-private-result'}

    completed = RuntimeWorker(store, {'runtime-v1': handler}, 'fixture-private-owner').run_once(source.tenant)
    assert completed['state'] == 'SUCCEEDED'
    assert store.trace_snapshot('TENANT_B', run) is None
    server_env = {key: os.environ[key] for key in ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP') if key in os.environ}
    server_env.update(PYTHONUTF8='1', PYTHONPATH=str(Path(__file__).resolve().parents[1]),
                      SUPPORTFLOW_AI_DATABASE_URL=os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'])
    export = subprocess.run([sys.executable, '-m', 'ai.runtime.trace_export', '--tenant', source.tenant, '--run', str(run)],
                            cwd=tmp_path, env=server_env, capture_output=True, text=True, encoding='utf-8', timeout=10)
    assert export.returncode == 0 and 'fixture-private-' not in export.stdout + export.stderr
    trace = json.loads(export.stdout)
    assert trace['schema_version'] == 'runtime-trace-v1'
    assert {'run_id', 'trace_id', 'state', 'error_code', 'created_at', 'finished_at'} <= trace['run'].keys()
    assert trace['run']['state'] == 'SUCCEEDED' and trace['run']['tool_calls'] == 1
    assert trace['run']['run_id'] == str(run) and trace['run']['trace_id'] == source.trace_id
    step, = trace['steps']
    assert set(step) == {'step_no', 'kind', 'effect', 'state', 'created_at', 'completed_at'}
    assert (step['step_no'], step['kind'], step['effect'], step['state']) == (1, 'TOOL', 'READ', 'SUCCEEDED')
    assert store.get(source.tenant, run) == completed


def test_incomplete_trace_is_rejected_without_correcting_the_database(store):
    source = request()
    run = store.enqueue(source)['run_id']
    with admin() as db:
        db.execute('UPDATE agent_run SET step_count=1 WHERE run_id=%s', (run,))
    with pytest.raises(RuntimeConflict, match='inconsistent'):
        store.trace_snapshot(source.tenant, run)
    assert store.get(source.tenant, run)['step_count'] == 1 and store.steps(source.tenant, run) == []


def test_replay_real_unknown_write_trace_and_detect_a_changed_outcome(store):
    source = request()
    run = store.enqueue(source)['run_id']

    def lost_confirmation():
        raise TransientFailure('FIXTURE_WRITE_ACK_LOST')

    def handler(context):
        return context.step('write-without-confirmation', 'TOOL', 'WRITE', {'operation': 'fixture.write'},
                            lost_confirmation)

    terminal = RuntimeWorker(store, {'runtime-v1': handler}, 'fixture-worker').run_once(source.tenant)
    assert terminal['state'] == 'FAILED' and terminal['error_code'] == 'SIDE_EFFECT_UNKNOWN'
    trace = store.trace_snapshot(source.tenant, run)
    result = replay(trace)
    assert result['issues'] == [] and result['unknown_writes'] == 1 and result['successful_writes'] == 0
    altered = trace | {'run': trace['run'] | {'state': 'SUCCEEDED', 'error_code': None}}
    changed = replay(altered)
    assert changed['issues'] == ['UNKNOWN_WRITE_WITHOUT_HOLD']
    assert store.get(source.tenant, run) == terminal
    output = Path(__file__).resolve().parents[2] / '.local/verification/runtime-trace-replay.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {'schema_version': 1, 'mode': 'SYNTHETIC_FAULT_REAL_POSTGRES',
              'trace': trace, 'replay': result, 'altered_outcome_replay': changed}
    output.write_text(json.dumps(report, default=_metadata, indent=2)+'\n', encoding='utf-8')


def test_runtime_trace_suite_runs_a_fixed_postgres_fault_matrix(store):
    traces = {}
    tenant = 'TENANT_A'
    scenarios = {
        'cancelled-before-dispatch': None,
        'tool-denied': ('TOOL', 'READ', PermanentFailure('FIXTURE_TOOL_DENIED')),
        'write-ack-lost': ('TOOL', 'WRITE', TransientFailure('FIXTURE_WRITE_ACK_LOST')),
        'model-timeout': ('MODEL', 'NONE', TransientFailure('FIXTURE_MODEL_TIMEOUT', delay_seconds=30)),
    }
    for case, spec in scenarios.items():
        source = request(tenant, session=case)
        run_id = store.enqueue(source)['run_id']
        if spec is None:
            store.cancel(tenant, run_id)
        else:
            kind, effect, failure = spec

            def fault():
                raise failure

            def handler(context):
                return context.step('fixture-fault', kind, effect, {'operation': 'fixture.fault'}, fault)

            row = RuntimeWorker(store, {'runtime-v1': handler}, 'fixture-fault-worker').run_once(tenant)
            assert row['run_id'] == run_id
        traces[case] = store.trace_snapshot(tenant, run_id)

    test_root = Path(__file__).resolve().parent
    directory = test_root.parents[1]/'.local/verification'
    directory.mkdir(parents=True, exist_ok=True)
    baseline = test_root/'fixtures/runtime-trace-baseline.json'
    trace_file = directory/'runtime-trace-suite.json'
    suite = {'schema_version': 'runtime-trace-suite-v1', 'cases': traces}
    trace_file.write_text(json.dumps(suite, default=_metadata, indent=2)+'\n', encoding='utf-8')
    command = [sys.executable, '-m', 'ai.runtime.trace_replay', '--suite', str(trace_file), '--expect', str(baseline)]
    output = subprocess.run(command, cwd=test_root.parent, capture_output=True, text=True, encoding='utf-8', timeout=10)
    assert output.returncode == 0 and output.stderr == ''
    result = json.loads(output.stdout)
    assert set(result['cases']) == set(scenarios) and result['passed'] is True
    (directory/'runtime-trace-suite-result.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
