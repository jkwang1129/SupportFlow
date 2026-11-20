"""Actual Worker -> Executor -> SDK -> customer JAR -> PG ACL qualification."""
import json
from pathlib import Path

import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]


def verify_source_tools(connect, runtime_dsn, base, secret, specs):
    from ai.runtime.customer_tools import current_message_tool
    from ai.runtime.source import SourceClient
    from ai.runtime.store import RuntimeStore
    from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
    from ai.runtime.worker import RuntimeWorker, TransientFailure

    store = RuntimeStore(lambda: psycopg.connect(runtime_dsn, row_factory=dict_row))
    tools = ToolExecutor(ToolRegistry([current_message_tool(SourceClient(base, secret))]), frozenset({'session:read'}))
    known = {run_id: source for source, run_id in specs}
    with connect('ai_db') as db:
        ordered = [row[0] for row in db.execute('SELECT run_id FROM agent_run WHERE run_id=ANY(%s) ORDER BY created_at,event_seq,run_id',
                                               (list(known),)).fetchall()]
    assert len(ordered) == 2
    returned = {}

    def handler(ctx):
        result = tools.execute(ctx, 'current-message', ToolCall('get_current_message', 'source-v1', {}))
        assert result['text'] == 'synthetic-private-source' and result['role'] == 'user'
        returned[ctx.lease.run_id] = returned.get(ctx.lease.run_id, 0) + 1
        if returned[ctx.lease.run_id] == 1:
            raise TransientFailure('AFTER_SOURCE_READ', delay_seconds=0)
        return {'outcome': 'source-verified'}

    runner = RuntimeWorker(store, {'runtime-v1': handler}, 'source-tool-qualification')
    evidence = []
    for index, run_id in enumerate(ordered):
        source = known[run_id]
        first = runner.run_once(source.tenant)
        assert first['run_id'] == run_id and first['state'] == 'RETRY_WAIT' and first['tool_calls'] == 2
        if index == 1:
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=12345 WHERE tenant_code=%s AND session_no=%s',
                           (source.tenant, source.session_no))
        resumed = runner.run_once(source.tenant)
        assert resumed['run_id'] == run_id
        assert (resumed['tool_calls'], resumed['model_calls'], resumed['step_count'], resumed['retry_count']) == (3, 0, 3, 1)
        if index == 0:
            assert resumed['state'] == 'SUCCEEDED' and returned[run_id] == 2
        else:
            assert resumed['state'] == 'FAILED' and resumed['error_code'] == 'SOURCE_REVOKED' and returned[run_id] == 1
        with connect('ai_db') as db:
            count = db.execute('SELECT count(*) FROM agent_payload WHERE tenant_code=%s AND run_id=%s',
                               (source.tenant, run_id)).fetchone()[0]
            assert count == 1
        steps = store.steps(source.tenant, run_id)
        # Only one invoke payload; the second execution is a fresh authorization,
        # not a second source invocation. No body/JWT in step refs or report.
        assert sum('resource_ref' in step['result_ref'] and step['result_ref']['resource_ref'].startswith('payload:') for step in steps) == 1
        assert 'synthetic-private-source' not in str(steps)
        evidence.append(dict(run_id=str(run_id), source_event_id=str(source.source_event_id), state=resumed['state'],
                             error_code=resumed['error_code'], tool_calls=resumed['tool_calls'], model_calls=resumed['model_calls'],
                             step_count=resumed['step_count'], retry_count=resumed['retry_count'], payload_rows=count,
                             result_exposures=returned[run_id]))
    report = ROOT/'.local/verification/runtime-source-tools.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(dict(schema_version='source-tool-qualification-v1', mode='SYNTHETIC_LOCAL_REAL_HTTP_PG',
                                      cases=evidence, performance='NOT_MEASURED', token_cost='NOT_MEASURED'), indent=2), encoding='utf-8')
    print('Actual Worker -> read Executor -> signed source SDK -> customer JAR/PG: 2 cases passed; cached result reused only after current ACL proof; human takeover blocked cached body; budgets 3 reads/model 0 per run', flush=True)
