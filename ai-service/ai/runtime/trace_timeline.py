"""Correlate Java Outbox delivery and Python Runtime using read-only database access."""
import argparse
from contextlib import contextmanager
import json
import os
import sys
from uuid import UUID

import psycopg
from psycopg.rows import dict_row

from .store import RuntimeStore
from .trace_export import _metadata
from .types import RuntimeConflict, identifier, tenant_code

CUSTOMER_DSN = 'SUPPORTFLOW_TRACE_CUSTOMER_DB_DSN'
AI_DSN = 'SUPPORTFLOW_TRACE_AI_DB_DSN'


def reader(dsn, tables):
    @contextmanager
    def connect():
        with psycopg.connect(dsn, autocommit=True, connect_timeout=2, row_factory=dict_row,
                             options='-c default_transaction_read_only=on -c statement_timeout=5000 -c search_path=pg_catalog,public') as connection:
            if not all(connection.execute('SELECT row_security_active(%s::regclass) AS enabled', (table,))
                       .fetchone()['enabled'] for table in tables):
                raise RuntimeConflict('Timeline requires active row security')
            yield connection
    return connect


def _outbox(connect, tenant, event_id):
    with connect() as connection, connection.transaction():
        connection.execute("SELECT set_config('app.tenant_code',%s,true)", (tenant,))
        return connection.execute("""SELECT event_id,run_id,message_id,event_seq,trace_id,runtime_version,
                state,attempts,error_code,created_at,ready_at,delivered_at
            FROM public.agent_event_outbox WHERE tenant_code=%s AND event_id=%s""", (tenant, event_id)).fetchone()


def _elapsed(start, end):
    if start is None or end is None or end < start:
        return None
    return round((end - start).total_seconds() * 1000, 3)


def correlate(tenant, delivery, snapshot):
    if delivery is None and snapshot is None:
        return None
    trace = snapshot['trace'] if snapshot else None
    run = trace['run'] if trace else None
    if delivery is not None and run is not None:
        source = snapshot['delivery']
        if (delivery['event_id'] != source['source_event_id']
                or any(delivery[key] != source[key] for key in ('message_id', 'event_seq'))
                or any(delivery[key] != run[key] for key in ('trace_id', 'runtime_version'))
                or delivery['run_id'] not in (None, run['run_id'])):
            raise RuntimeConflict('Outbox and Runtime binding differs')
        status = 'matched' if delivery['run_id'] is not None else 'awaiting_receipt'
    elif delivery is not None:
        status = 'receipt_without_runtime' if delivery['run_id'] is not None else 'outbox_only'
    else:
        status = 'runtime_only'

    customer = None
    if delivery is not None:
        customer = {key: delivery[key] for key in (
            'state', 'attempts', 'error_code', 'created_at', 'ready_at', 'delivered_at')}
        customer['elapsed_to_receipt_ms'] = _elapsed(delivery['created_at'], delivery['delivered_at'])
    runtime = None
    if trace is not None:
        runtime = {'run': run | {'elapsed_to_finish_ms': _elapsed(run['created_at'], run['finished_at'])},
                   'steps': [step | {'elapsed_ms': _elapsed(step['created_at'], step['completed_at'])}
                             for step in trace['steps']], 'approval': snapshot['approval']}
    return {
        'schema_version': 'cross-service-trace-v1', 'tenant': tenant, 'binding_status': status,
        'trace_id': delivery['trace_id'] if delivery is not None else run['trace_id'],
        'event_id': delivery['event_id'] if delivery is not None else snapshot['delivery']['source_event_id'],
        'run_id': run['run_id'] if run is not None else delivery['run_id'],
        'customer_delivery': customer, 'python_runtime': runtime,
    }


class TimelineReader:
    def __init__(self, customer, runtime):
        self.customer = customer
        self.runtime = runtime

    def for_event(self, tenant, event_id):
        tenant_code(tenant)
        event_id = UUID(str(event_id))
        delivery = _outbox(self.customer, tenant, event_id)
        snapshot = self.runtime.trace_delivery_snapshot(tenant, event_id, by_event=True)
        return correlate(tenant, delivery, snapshot)

    def for_run(self, tenant, run_id):
        snapshot = self.runtime.trace_delivery_snapshot(tenant_code(tenant), run_id)
        if snapshot is None:
            return None
        delivery = _outbox(self.customer, tenant, snapshot['delivery']['source_event_id'])
        return correlate(tenant, delivery, snapshot)

    def for_request(self, tenant, trace_id):
        candidates = self.runtime.lookup_trace(tenant_code(tenant), identifier(trace_id))
        timelines = [self.for_run(tenant, run['run_id']) for run in candidates['runs']]
        if any(item is None or item['trace_id'] != trace_id for item in timelines):
            raise RuntimeConflict('Runtime lookup changed during timeline export')
        return {'schema_version': 'cross-service-trace-list-v1', 'tenant': tenant, 'trace_id': trace_id,
                'truncated': candidates['truncated'], 'timelines': timelines}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True)
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument('--event', type=UUID, help='Look up one Outbox event, including before AI admission')
    selector.add_argument('--run', type=UUID, help='Look up one known Runtime task')
    selector.add_argument('--trace-id', help='Find at most 20 admitted runs for one exact request ID')
    args = parser.parse_args(argv)
    try:
        tenant = tenant_code(args.tenant)
        customer_db, ai_db = os.environ.get(CUSTOMER_DSN), os.environ.get(AI_DSN)
        if not customer_db or not ai_db:
            parser.error(f'Set {CUSTOMER_DSN} and {AI_DSN} for the operator session')
        timeline = TimelineReader(reader(customer_db, ('public.agent_event_outbox',)),
                                  RuntimeStore(reader(ai_db, ('public.agent_run', 'public.agent_step', 'public.agent_approval'))))
        if args.event is not None:
            result = timeline.for_event(tenant, args.event)
        elif args.run is not None:
            result = timeline.for_run(tenant, args.run)
        else:
            result = timeline.for_request(tenant, args.trace_id)
        if result is None:
            print('Cross-service trace not found', file=sys.stderr)
            return 1
        print(json.dumps(result, default=_metadata, indent=2))
        return 0
    except (psycopg.Error, RuntimeConflict, OSError, TypeError, ValueError) as failure:
        print('Cross-service trace unavailable: ' + type(failure).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
