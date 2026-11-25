"""Qualification against the disposable, restricted Runtime database roles."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from ai.runtime import metrics
from ai.runtime.worker import PermanentFailure, RuntimeWorker
from test_runtime_store_postgres import admin, request, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')
TABLES = ('public.agent_run', 'public.agent_run_event')


def read(tenant, window=60):
    return metrics.snapshot(metrics.reader(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], TABLES), tenant, window)


def test_backlog_state_clock_and_tenant_boundaries(store):
    runs = [store.enqueue(request(session=f'private-fixture-{i}'))['run_id'] for i in range(4)]
    store.enqueue(request('TENANT_B'))
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp()-interval '10 seconds' WHERE run_id=%s", (runs[0],))
        db.execute("UPDATE agent_run SET state='RETRY_WAIT', ready_at=clock_timestamp()+interval '1 hour' WHERE run_id=%s", (runs[1],))
        db.execute("UPDATE agent_run SET state='WAITING_APPROVAL' WHERE run_id=%s", (runs[2],))
        db.execute("""UPDATE agent_run SET state='RUNNING', owner='private-fixture-worker', attempt=1,fence=1,
                      lease_until=clock_timestamp()-interval '1 second', deadline_at=clock_timestamp()-interval '1 second'
                      WHERE run_id=%s""", (runs[3],))
    backlog = read('TENANT_A')['backlog']
    assert {field: value for field, value in backlog.items() if field != 'oldest_due_ms'} == {
        'active': 4, 'due': 1, 'delayed': 1, 'waiting_approval': 1, 'running': 1, 'expired_leases': 1, 'expired_deadlines': 1,
    }
    assert backlog['oldest_due_ms'] >= 10_000
    assert read('TENANT_B')['backlog']['active'] == 1
    assert read('TENANT_C')['backlog']['oldest_due_ms'] is None


def test_runtime_event_and_finish_durations_are_observed_without_checkpoint_contents(store):
    queued = store.enqueue(request())
    finished = RuntimeWorker(store, {'runtime-v1': lambda ctx: {'outcome': 'private-fixture'}}, 'private-worker').run_once('TENANT_A')
    assert finished['state'] == 'SUCCEEDED'
    with admin() as db:
        event = db.execute("SELECT occurred_at FROM agent_run_event WHERE run_id=%s AND event_type='run_started'",
                           (queued['run_id'],)).fetchone()
    sample = read('TENANT_A')['completions']
    assert sample['success_rate'] == 1.0 and sample['sample_size'] == 1
    first_ms = (event['occurred_at'] - queued['created_at']).total_seconds() * 1000
    finish_ms = (finished['finished_at'] - queued['created_at']).total_seconds() * 1000
    assert sample['admission_to_first_start'] == {'samples': 1, 'p50_ms': pytest.approx(first_ms), 'p95_ms': None}
    assert sample['admission_to_finish'] == {'samples': 1, 'p50_ms': pytest.approx(finish_ms), 'p95_ms': None}
    assert 'private-' not in str(sample)


def test_database_window_and_sample_limit_exclude_history_and_future(store, monkeypatch):
    monkeypatch.setattr(metrics, 'SAMPLE_LIMIT', 3)
    for i, (state, age_seconds, duration_ms) in enumerate((
            ('SUCCEEDED', 10, 100), ('FAILED', 20, 200), ('CANCELLED', 30, 900),
            ('FAILED', 40, 1200), ('FAILED', 3800, 600), ('FAILED', -10, 600))):
        row = store.enqueue(request(session=f'completed-{i}'))
        with admin() as db:
            db.execute("""UPDATE agent_run SET state=%s, finished_at=statement_timestamp()-make_interval(secs => %s),
                          created_at=statement_timestamp()-make_interval(secs => %s)-%s*interval '1 millisecond'
                          WHERE run_id=%s""", (state, age_seconds, age_seconds, duration_ms, row['run_id']))
    sample = read('TENANT_A')['completions']
    assert (sample['sample_size'], sample['truncated'], sample['success_rate']) == (3, True, .5)
    assert sample['admission_to_first_start']['samples'] == 0
    assert sample['admission_to_finish'] == {'samples': 3, 'p50_ms': 200, 'p95_ms': 900}
    monkeypatch.setattr(metrics, 'SAMPLE_LIMIT', 10)
    assert read('TENANT_A', 1)['completions']['sample_size'] == 4
    assert read('TENANT_A', 120)['completions']['sample_size'] == 5


def test_report_cli_alerts_and_rejects_administrative_or_invalid_scope(store, tmp_path):
    # Observe both real successful and failed execution paths in the exported sample.
    def fail(context):
        raise PermanentFailure('FIXTURE_FAILURE')

    store.enqueue(request(session='success-sample'))
    worker = RuntimeWorker(store, {'runtime-v1': lambda ctx: {'outcome': 'fixture'}}, 'fixture-worker')
    assert worker.run_once('TENANT_A')['state'] == 'SUCCEEDED'
    store.enqueue(request(session='failure-sample'))
    worker = RuntimeWorker(store, {'runtime-v1': fail}, 'fixture-worker')
    assert worker.run_once('TENANT_A')['state'] == 'FAILED'
    row = store.enqueue(request())
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp()-interval '10 seconds' WHERE run_id=%s", (row['run_id'],))
    env = {key: os.environ[key] for key in ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP') if key in os.environ}
    env.update(PYTHONUTF8='1', PYTHONPATH=str(Path(__file__).resolve().parents[1]))

    def run(tenant, dsn, *opts):
        return subprocess.run([sys.executable, '-m', 'ai.runtime.metrics', '--tenant', tenant, *opts],
                              env=env | {metrics.AI_DSN: dsn}, cwd=tmp_path, text=True, encoding='utf-8',
                              capture_output=True, timeout=10)

    restricted = os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']
    alert = run('TENANT_A', restricted, '--alert-due-count', '1', '--alert-due-seconds', '5')
    assert alert.returncode == 3 and not alert.stderr and str(row['run_id']) not in alert.stdout
    result = json.loads(alert.stdout)
    assert result['alerts'] == ['DUE_COUNT', 'DUE_AGE'] and result['backlog']['due'] == 1
    sample = result['completions']
    assert (sample['succeeded'], sample['failed'], sample['success_rate']) == (1, 1, .5)
    for latency in (sample['admission_to_first_start'], sample['admission_to_finish']):
        assert latency['samples'] == 2 and 0 <= latency['p50_ms'] <= latency['p95_ms']
    assert run('TENANT_B', restricted, '--alert-due-count', '1').returncode == 0
    assert run('TENANT_A', restricted, '--window-minutes', '1441').returncode == 2
    for rejected in (run('PLATFORM', restricted), run('TENANT_A', os.environ['SUPPORTFLOW_RUNTIME_ADMIN_TEST_DSN'])):
        assert rejected.returncode == 1 and not rejected.stdout and 'password' not in rejected.stderr
    if path := os.getenv('SUPPORTFLOW_RUNTIME_METRICS_TEST_REPORT'):
        Path(path).write_text(alert.stdout, encoding='utf-8')
