"""Inspect backlog and a bounded completion sample using the operator's read-only connection."""
import argparse
import json
import math
import os
import sys

import psycopg

from .trace_export import _metadata
from .trace_timeline import AI_DSN, reader
from .types import RuntimeConflict, tenant_code


MAX_WINDOW_MINUTES = 1440
SAMPLE_LIMIT = 1000


def _latency(values):
    ordered = sorted(value for value in values if value is not None and value >= 0)
    size = len(ordered)
    return {'samples': size,
            'p50_ms': ordered[math.ceil(.50 * size) - 1] if size else None,
            'p95_ms': ordered[math.ceil(.95 * size) - 1] if size >= 2 else None}


def _completion_metrics(rows):
    sample = rows[:SAMPLE_LIMIT]
    outcomes = {state.lower(): sum(row['state'] == state for row in sample)
                for state in ('SUCCEEDED', 'FAILED', 'CANCELLED')}
    decided = outcomes['succeeded'] + outcomes['failed']
    return {'sample_limit': SAMPLE_LIMIT, 'sample_size': len(sample), 'truncated': len(rows) > SAMPLE_LIMIT,
            **outcomes, 'success_rate': round(outcomes['succeeded'] / decided, 6) if decided else None,
            'admission_to_first_start': _latency(row['first_start_ms'] for row in sample),
            'admission_to_finish': _latency(row['finish_ms'] for row in sample)}


def snapshot(connect, tenant, window_minutes):
    tenant = tenant_code(tenant)
    if type(window_minutes) is not int or not 1 <= window_minutes <= MAX_WINDOW_MINUTES:
        raise ValueError('Invalid completion window')
    with connect() as connection, connection.transaction():
        connection.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
        connection.execute("SELECT set_config('app.tenant_code',%s,true)", (tenant,))
        backlog = connection.execute("""
            SELECT transaction_timestamp() AS observed_at, count(*) AS active,
                   count(*) FILTER (WHERE state IN ('PENDING','RETRY_WAIT') AND ready_at <= transaction_timestamp()) AS due,
                   count(*) FILTER (WHERE state IN ('PENDING','RETRY_WAIT') AND ready_at > transaction_timestamp()) AS delayed,
                   count(*) FILTER (WHERE state = 'WAITING_APPROVAL') AS waiting_approval,
                   count(*) FILTER (WHERE state = 'RUNNING') AS running,
                   count(*) FILTER (WHERE state = 'RUNNING' AND lease_until <= transaction_timestamp()) AS expired_leases,
                   count(*) FILTER (WHERE deadline_at <= transaction_timestamp()) AS expired_deadlines,
                   (extract(epoch FROM transaction_timestamp() - min(ready_at) FILTER (
                       WHERE state IN ('PENDING','RETRY_WAIT') AND ready_at <= transaction_timestamp())) * 1000)::double precision
                       AS oldest_due_ms
              FROM public.agent_run WHERE tenant_code=%s
               AND state IN ('PENDING','RETRY_WAIT','WAITING_APPROVAL','RUNNING')
            """, (tenant,)).fetchone()
        rows = connection.execute("""
            WITH candidates AS MATERIALIZED (
                SELECT tenant_code, run_id, state, created_at, finished_at FROM public.agent_run
                 WHERE tenant_code=%s AND state IN ('SUCCEEDED','FAILED','CANCELLED')
                   AND finished_at > transaction_timestamp() - make_interval(mins => %s)
                   AND finished_at <= transaction_timestamp()
                 ORDER BY finished_at DESC, run_id DESC LIMIT %s
            )
            SELECT r.state,
                   (extract(epoch FROM e.occurred_at - r.created_at) * 1000)::double precision AS first_start_ms,
                   (extract(epoch FROM r.finished_at - r.created_at) * 1000)::double precision AS finish_ms
              FROM candidates r LEFT JOIN LATERAL (
                   SELECT occurred_at FROM public.agent_run_event
                    WHERE tenant_code=r.tenant_code AND run_id=r.run_id AND event_type='run_started'
                    ORDER BY seq LIMIT 1
              ) e ON true
             ORDER BY r.finished_at DESC, r.run_id DESC
            """, (tenant, window_minutes, SAMPLE_LIMIT + 1)).fetchall()
    observed_at = backlog.pop('observed_at')
    if backlog['oldest_due_ms'] is not None:
        backlog['oldest_due_ms'] = round(backlog['oldest_due_ms'], 3)
    return {'schema_version': 'runtime-metrics-v1', 'tenant': tenant, 'observed_at': observed_at,
            'window_minutes': window_minutes, 'backlog': backlog, 'completions': _completion_metrics(rows)}


def _positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('must be a positive integer')
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True)
    parser.add_argument('--window-minutes', type=_positive, default=60)
    parser.add_argument('--alert-due-count', type=_positive, help='Alert when the due count reaches this number')
    parser.add_argument('--alert-due-seconds', type=_positive, help='Alert when the oldest due task reaches this age')
    args = parser.parse_args(argv)
    if args.window_minutes > MAX_WINDOW_MINUTES:
        parser.error(f'--window-minutes must be at most {MAX_WINDOW_MINUTES}')
    dsn = os.environ.get(AI_DSN)
    if not dsn:
        parser.error(f'Set {AI_DSN} for the operator session')
    try:
        result = snapshot(reader(dsn, ('public.agent_run', 'public.agent_run_event')), args.tenant, args.window_minutes)
        backlog = result['backlog']
        alerts = []
        if args.alert_due_count is not None and backlog['due'] >= args.alert_due_count:
            alerts.append('DUE_COUNT')
        if args.alert_due_seconds is not None and (backlog['oldest_due_ms'] or 0) >= args.alert_due_seconds * 1000:
            alerts.append('DUE_AGE')
        result['alerts'] = alerts
        print(json.dumps(result, default=_metadata))
        return 3 if alerts else 0
    except (psycopg.Error, RuntimeConflict, OSError, ValueError) as failure:
        print('Runtime metrics unavailable: ' + type(failure).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
