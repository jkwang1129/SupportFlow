"""Approved ticket execution against real Runtime, customer and AI authority services."""
import asyncio
import hashlib
import hmac
import json
from pathlib import Path
import time
from uuid import uuid4

import httpx


def _seed_source(connect, source, run, session_id):
    with connect('customer_db') as db:
        db.execute("""INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
            VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)""", (session_id, source.tenant, source.session_no))
        db.execute("""INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time)
            VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic approval source',CURRENT_TIMESTAMP)""",
            (source.message_id, source.tenant, session_id, 'APPROVAL_MESSAGE_'+source.tenant))
        db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,
            principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
            VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
            (source.tenant, source.source_event_id, run, source.message_id, session_id, source.session_no, source.principal_ref, source.trace_id))


def _signed_review(client, ai_base, secret, source, run, row):
    path = f'/api/ai/v1/runtime/runs/{run}/review-ticket-approval'
    expiry = str(int(time.time()) + 20)

    def request(digest):
        payload = json.dumps(dict(approval_id=str(row['approval_id']), source_event_id=str(source.source_event_id),
            arguments_sha256=digest, reviewer_ref='staff:123', decision='approve'), separators=(',', ':')).encode()
        canonical = '\n'.join(('supportflow-ai-runtime:v1', 'customer', expiry, 'POST', path, '', source.tenant,
                                hashlib.sha256(payload).hexdigest()))
        token = hmac.digest(secret.encode(), canonical.encode(), 'sha256').hex()
        return client.post(ai_base+path, content=payload,
                           headers={'X-Tenant-Code': source.tenant, 'X-SupportFlow-AI-Auth': f'runtime:customer:{expiry}:{token}',
                                    'Content-Type': 'application/json'})

    assert request('0'*64).status_code == 409
    result = request(row['arguments_sha256'])
    assert result.status_code == 200 and result.json() == {'approval_id': str(row['approval_id']), 'status': 'APPROVED'}


def verify_ticket_approval(connect, base, ai_base, secret, store):
    from ai.runtime.approvals import TicketApprovals
    from ai.runtime.source import SourceClient
    from ai.runtime.ticket_creation import TicketCreationClient
    from ai.runtime.ticket_write_tool import ticket_create_tool
    from ai.runtime.tools import ToolExecutor, ToolRegistry
    from ai.runtime.types import RunInput
    from ai.runtime.worker import RuntimeWorker

    source_client, tickets = SourceClient(base, secret), TicketCreationClient(base, secret)
    executor = ToolExecutor(ToolRegistry([ticket_create_tool(source_client, tickets)]), frozenset({'ticket:create'}))
    approved_title, approved_body = '审批工单🙂', 'synthetic approved body\n原始内容 '
    observations = []
    for index, mode in enumerate(('accepted', 'taken_over_after_approval', 'expired_before_resume')):
        tenant = 'TC_APPROVAL_'+str(index)
        session_id = 20500001 + index * 10
        source = RunInput(tenant, uuid4(), 'TICKET_APPROVAL_'+str(index), 1, session_id+1,
                          'visitor-session:'+str(session_id), 'approval-jar-'+str(index))
        run = store.enqueue(source)['run_id']
        _seed_source(connect, source, run, session_id)
        snapshot = asyncio.run(source_client.capture(source, run))
        approvals = TicketApprovals(store)
        worker = RuntimeWorker(store, {'runtime-v1': lambda context: approvals.pause(
            context, snapshot.snapshot_id, approved_title, approved_body)}, 'approval-first')
        waiting = worker.run_once(tenant)
        row = approvals.get(tenant, run)
        assert waiting['state'] == 'WAITING_APPROVAL' and row['snapshot_id'] == snapshot.snapshot_id
        assert waiting['step_count'] == 1 and store.claim_next(tenant, 'must-wait') is None
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=3) as client:
            _signed_review(client, ai_base, secret, source, run, row)
        assert approvals.get(tenant, run)['decided_by'] == 'staff:123'

        if mode == 'taken_over_after_approval':
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=99 WHERE id=%s', (session_id,))
        if mode == 'expired_before_resume':
            with connect('ai_db') as db:
                db.execute("""UPDATE agent_approval SET requested_at=clock_timestamp()-interval '3 minutes',
                    decided_at=clock_timestamp()-interval '150 seconds',expires_at=clock_timestamp()-interval '2 minutes'
                    WHERE tenant_code=%s AND run_id=%s""", (tenant, run))
        resumed = RuntimeWorker(store, {'runtime-v1': lambda ctx: TicketApprovals(store).execute(ctx, executor)}, 'approval-second')
        result = resumed.run_once(tenant)
        if mode == 'expired_before_resume':
            assert result is None
            result = store.get(tenant, run)
        assert resumed.run_once(tenant) is None
        audit = approvals.get(tenant, run)
        writes = [step for step in store.steps(tenant, run) if step['effect'] == 'WRITE']
        receipt = asyncio.run(tickets.receipt(source, run, expected_snapshot_id=snapshot.snapshot_id, expected_hash=row['arguments_sha256']))
        with connect('customer_db') as db:
            facts = db.execute('SELECT id,ticket_no,title,"desc",source_session_id FROM ticket WHERE tenant_code=%s', (tenant,)).fetchall()
            events = db.execute('SELECT count(*) FROM ticket_event WHERE tenant_code=%s', (tenant,)).fetchone()[0]
        if mode == 'accepted':
            assert result['state'] == 'SUCCEEDED' and audit['status'] == 'CONSUMED'
            assert len(writes) == 1 and writes[0]['state'] == 'SUCCEEDED' and audit['consumed_step_no'] == writes[0]['step_no']
            assert facts == [(receipt.ticket_id, receipt.ticket_no, approved_title, approved_body, session_id)] and events == 1
            assert (result['step_count'], result['tool_calls'], result['model_calls'], result['retry_count']) == (4, 2, 0, 0)
        else:
            expected_error = 'SOURCE_REVOKED' if mode == 'taken_over_after_approval' else 'APPROVAL_EXPIRED'
            assert result['state'] == 'FAILED' and result['error_code'] == expected_error
            assert writes == [] and receipt is None and facts == [] and events == 0
            assert audit['consumed_step_no'] is None
        observations.append(dict(case=mode, waiting_state=waiting['state'], final_state=result['state'], error_code=result['error_code'],
            approval_status=audit['status'], steps=result['step_count'], tools=result['tool_calls'], models=result['model_calls'],
            tickets=len(facts), receipts=int(receipt is not None), events=events))
    artifact = Path(__file__).resolve().parents[1] / '.local/verification/runtime-ticket-approval.json'
    artifact.write_text(json.dumps(dict(format='ticket-approval-boundary-v2', mode='SYNTHETIC_SOURCE_REAL_JAVA_AI_PG',
        cases=observations), indent=2)+'\n', encoding='utf-8')
    print('Actual signed AI ticket review: 3 cases passed; wrong digest rejected, one approved ticket/receipt, zero writes after source takeover or expiry', flush=True)
