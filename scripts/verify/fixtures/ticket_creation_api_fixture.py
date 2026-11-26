"""Actual ticket POST/receipt GET -> customer JAR -> AI authority -> restricted databases.

Explicit synthetic host commands only; no production SDK/model tool or auto reconciliation.
"""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[2]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import asyncio
import hashlib
import json
from pathlib import Path
from uuid import uuid4

import httpx

ROOT = Path(__file__).resolve().parents[3]


def verify_ticket_creation_api(connect, base, secret, store):
    from ai.runtime.source import SourceClient, credential, HEADER
    from ai.runtime.ticket_creation import TicketCreationClient, receipt_reference
    from ai.runtime.types import RunInput, Lease
    from ai.runtime.worker import PermanentFailure

    def send(method, path, tenant, body=b'', key=secret):
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=8) as http:
            return http.request(method, base + path, content=body, headers={'X-Tenant-Code': tenant,
                                                                            HEADER: credential(key, method, path, tenant, body), 'Content-Type': 'application/json'})

    observations = []
    for index, mode in enumerate(('normal_terminal_receipt', 'lost_committed_ack', 'current_source_revoked')):
        tenant = 'TC_' + chr(ord('A') + index)
        session_id = 18_900_001 + index * 10
        message_id = session_id + 1
        customer_id = session_id + 2
        source = RunInput(tenant, uuid4(), 'TICKET_CREATE_JAR_' + str(index), 1, message_id,
                          'visitor-session:' + str(session_id), 'ticket-create-' + str(index))
        run = store.enqueue(source)['run_id']
        with connect('customer_db') as db:
            db.execute("INSERT INTO customer(id,tenant_code,customer_no,name,ticket_count) VALUES(%s,%s,%s,'SYNTHETIC PRIVATE TICKET CUSTOMER',3)",
                       (customer_id, tenant, 'TC_CUSTOMER_' + str(index)))
            db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,customer_id,start_time) VALUES(%s,%s,%s,1,2,1,%s,CURRENT_TIMESTAMP)",
                       (session_id, tenant, source.session_no, customer_id))
            db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic ticket creation source',CURRENT_TIMESTAMP)",
                       (message_id, tenant, session_id, 'TC_SOURCE_' + str(index)))
            db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                       (tenant, source.source_event_id, run, message_id, session_id, source.session_no, source.principal_ref, source.trace_id))
        snapshot = asyncio.run(SourceClient(base, secret).capture(source, run))
        lease = Lease.from_run(store.claim_next(tenant, 'ticket-api-host', lease_seconds=60))
        assert lease.run_id == run
        title = '合成问题🙂 ' + str(index)
        content = 'synthetic original body\n原文 '
        sha = lambda text: hashlib.sha256(text.encode('utf-8')).hexdigest()
        # Independent formula, not a product ticket SDK.
        digest = sha('\n'.join(('supportflow-ticket-create:v1', tenant, str(run), str(source.source_event_id),
                     str(snapshot.snapshot_id), sha(title), sha(content), '5', '2', '1')))
        store.reserve_step(lease, 'ticket-1', 'TOOL', 'WRITE', dict(operation='ticket.create', version='ticket-create-v1',
                                                                    resource_ref=source.principal_ref, input_ref=str(snapshot.snapshot_id), arguments_sha256=digest))
        path = f'/api/customer/internal/runtime/events/{source.source_event_id}/runs/{run}/ticket-create'
        value = dict(snapshot_id=str(snapshot.snapshot_id), owner=lease.owner, fence=lease.fence,
                     arguments_sha256=digest, title=title, content=content, version='ticket-create-v1')
        body = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        assert send('GET', path, tenant).status_code == 404
        if mode == 'current_source_revoked':
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=99 WHERE id=%s', (session_id,))
        api = TicketCreationClient(base, secret)
        if mode == 'normal_terminal_receipt':
            typed = asyncio.run(api.create(source, lease, snapshot.snapshot_id, title, content))
            assert typed.arguments_sha256 == digest
        result = send('POST', path, tenant, body)
        if mode == 'current_source_revoked':
            assert result.status_code == 404, result.status_code
            assert send('GET', path, tenant).status_code == 404
            try:
                asyncio.run(api.create(source, lease, snapshot.snapshot_id, title, content))
            except PermanentFailure as failure:
                assert failure.code == 'TICKET_CREATE_SOURCE_REVOKED'
            else:
                raise AssertionError('SDK accepted a revoked source')
            assert asyncio.run(api.receipt(
                source, run, expected_snapshot_id=snapshot.snapshot_id, expected_hash=digest)) is None
            store.cancel(tenant, run)
            receipt = None
        else:
            assert result.status_code == 200, result.status_code
            receipt = result.json()['data']
            assert set(receipt) == {'tenant_code', 'source_event_id', 'run_id', 'snapshot_id',
                                    'schema_version', 'operation', 'arguments_sha256', 'ticket_id', 'ticket_no'}
            assert receipt['tenant_code'] == tenant and receipt['source_event_id'] == str(
                source.source_event_id) and receipt['run_id'] == str(run)
            assert receipt['snapshot_id'] == str(snapshot.snapshot_id) and receipt['arguments_sha256'] == digest
            assert receipt['operation'] == 'ticket.create' and receipt['schema_version'] == 'ticket-create-v1'
            assert isinstance(receipt['ticket_id'], str) and int(receipt['ticket_id']) > 2**53
            assert title not in result.text and content not in result.text and 'PRIVATE TICKET CUSTOMER' not in result.text
            if mode == 'normal_terminal_receipt':
                reference = receipt_reference(typed, source, run, snapshot.snapshot_id, digest)
                store.complete_step(lease, 'ticket-1', 'SUCCEEDED', reference)
                store.finish(lease, reference)
                assert send('POST', path, tenant, body).json()['data'] == receipt
                changed = {**value, 'title': 'changed title'}
                changed['arguments_sha256'] = sha('\n'.join(('supportflow-ticket-create:v1', tenant, str(run), str(
                    source.source_event_id), str(snapshot.snapshot_id), sha(changed['title']), sha(content), '5', '2', '1')))
                assert send('POST', path, tenant, json.dumps(changed).encode()).status_code == 409
            else:
                # Discard the committed POST acknowledgement. Runtime has not
                # completed its WRITE step; cancellation makes UNKNOWN durable.
                del result
                store.cancel(tenant, run)
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=99,status=4 WHERE id=%s', (session_id,))
            assert send('GET', path, tenant).json()['data'] == receipt
            typed_result = asyncio.run(api.receipt(
                source, run, expected_snapshot_id=snapshot.snapshot_id, expected_hash=digest))
            assert typed_result.ticket_id == int(
                receipt['ticket_id']) and typed_result.ticket_no == receipt['ticket_no']
            assert receipt_reference(typed_result, source, run, snapshot.snapshot_id, digest)[
                'business_ref'] == 'ticket:' + receipt['ticket_id']
            assert send('GET', path, 'TC_FOREIGN').status_code == 404
            assert send('GET', path, tenant, key='synthetic-wrong-pair-' + '0' * 40).status_code == 401
        with connect('customer_db') as db:
            rows = db.execute(
                'SELECT id,ticket_no,title,"desc",customer_id,source_session_id,creator,editor,category,priority,source_channel,status FROM ticket WHERE tenant_code=%s', (tenant,)).fetchall()
            receipts = db.execute(
                'SELECT count(*) FROM agent_ticket_receipt WHERE tenant_code=%s AND run_id=%s', (tenant, run)).fetchone()[0]
            events = db.execute('SELECT count(*) FROM ticket_event WHERE tenant_code=%s', (tenant,)).fetchone()[0]
            count = db.execute('SELECT ticket_count FROM customer WHERE id=%s', (customer_id,)).fetchone()[0]
            messages = db.execute('SELECT count(*) FROM session_message WHERE session_id=%s',
                                  (session_id,)).fetchone()[0]
            if receipt is not None:
                assert rows == [(int(receipt['ticket_id']), receipt['ticket_no'], title, content,
                                 customer_id, session_id, 'agent-run:' + str(run), 'agent-run:' + str(run), 5, 2, 1, 1)]
                assert (receipts, events, count, messages) == (1, 1, 4, 1)
            else:
                assert (len(rows), receipts, events, count, messages) == (0, 0, 0, 3, 1)
        actual = store.get(tenant, run)
        assert (actual['step_count'], actual['model_calls'],
                actual['tool_calls'], actual['retry_count']) == (1, 0, 1, 0)
        assert actual['state'] == ('SUCCEEDED' if mode == 'normal_terminal_receipt' else 'FAILED')
        if mode == 'lost_committed_ack':
            assert actual['error_code'] == 'SIDE_EFFECT_UNKNOWN'
            with connect('ai_db') as db:
                assert db.execute('SELECT state FROM agent_step WHERE tenant_code=%s AND run_id=%s',
                                  (tenant, run)).fetchone() == ('UNKNOWN',)
        observations.append(dict(case=mode, run_id=str(run), source_event_id=str(source.source_event_id), state=actual['state'], error_code=actual['error_code'],
                                 tickets=len(rows), receipts=receipts, ticket_events=events, customer_ticket_count=count, source_messages=messages,
                                 steps=actual['step_count'], tools=actual['tool_calls'], models=actual['model_calls'], retries=actual['retry_count'],
                                 automatic_runtime_reconciliation=False))
    report = ROOT / '.local/verification/runtime-ticket-creation-api.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(dict(schema_version='ticket-create-api-qualification-v2', mode='SYNTHETIC_HOST_REAL_CUSTOMER_AI_PG', cases=observations,
                                      sdk='VERIFIED_EXPLICIT_WRITE_AND_RECEIPT_CLIENT', model_write_tool='NOT_IMPLEMENTED', performance='NOT_MEASURED', token_cost='NOT_MEASURED'), indent=2) + '\n', encoding='utf-8')
    print('Actual ticket SDK + independent signed API -> customer JAR -> AI authority/restricted PG: 3 cases passed; fresh SDK create, immutable result binding, terminal receipt, discarded ACK confirmed by GET, revoked source denied; UNKNOWN not auto-reconciled', flush=True)
