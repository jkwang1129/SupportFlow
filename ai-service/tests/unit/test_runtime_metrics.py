from ai.runtime import metrics


def test_latency_uses_nearest_rank_and_excludes_invalid_observations():
    assert metrics._latency([None, -1, 600, 100, 200, 300]) == {'samples': 4, 'p50_ms': 200, 'p95_ms': 600}
    assert metrics._latency([0]) == {'samples': 1, 'p50_ms': 0, 'p95_ms': None}
    assert metrics._latency([None, -1]) == {'samples': 0, 'p50_ms': None, 'p95_ms': None}


def test_completion_rate_excludes_cancelled_and_extra_sample(monkeypatch):
    monkeypatch.setattr(metrics, 'SAMPLE_LIMIT', 3)
    rows = [dict(state=state, first_start_ms=None, finish_ms=1000)
            for state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'SUCCEEDED')]
    result = metrics._completion_metrics(rows)
    assert (result['sample_size'], result['truncated'], result['success_rate']) == (3, True, .5)
    assert metrics._completion_metrics(rows[2:3])['success_rate'] is None


def test_alert_thresholds_are_inclusive_and_return_distinct_exit_code(monkeypatch, capsys):
    monkeypatch.setenv(metrics.AI_DSN, 'synthetic-fixture')
    monkeypatch.setattr(metrics, 'snapshot', lambda *_: {'backlog': {'due': 2, 'oldest_due_ms': 5000}})
    assert metrics.main(['--tenant', 'TENANT_A', '--alert-due-count', '2', '--alert-due-seconds', '5']) == 3
    assert '"alerts": ["DUE_COUNT", "DUE_AGE"]' in capsys.readouterr().out
    assert metrics.main(['--tenant', 'TENANT_A', '--alert-due-count', '3', '--alert-due-seconds', '6']) == 0
    assert '"alerts": []' in capsys.readouterr().out
