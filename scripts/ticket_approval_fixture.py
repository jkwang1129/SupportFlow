"""Approval pause/resume against the real Runtime and customer service."""
import asyncio
import json
from pathlib import Path
from threading import Event
from uuid import uuid4


def verify_ticket_approval(connect, base, secret, store):
    from ai.runtime.approvals import TicketApprovals
    from ai.runtime.source import SourceClient
    from ai.runtime.ticket_creation import TicketCreationClient
    from ai.runtime.types import RunInput
    from ai.runtime.worker import ExecutionContext, PermanentFailure, RuntimeWorker

    tenant = 'TC_APPROVAL'
    session_id, message_id = 20500001, 20500002
    source = RunInput(tenant, uuid4(), 'TICKET_APPROVAL_JAR', 1, message_id, 'visitor-session:'+str(session_id), 'approval-jar')
    run = store.enqueue(source)['run_id']
    with connect('customer_db') as db:
        db.execute("""INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
            VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)""", (session_id, tenant, source.session_no))
        db.execute("""INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time)
            VALUES(%s,%s,%s,'TC_APPROVAL_SOURCE',1,1,1,1,'synthetic approval source',CURRENT_TIMESTAMP)""", (message_id, tenant, session_id))
        db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,
            principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
            VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
            (tenant, source.source_event_id, run, message_id, session_id, source.session_no, source.principal_ref, source.trace_id))
    snapshot = asyncio.run(SourceClient(base, secret).capture(source, run))
    approvals = TicketApprovals(store)
    title, content = '审批工单🙂', 'synthetic approved body\n原始内容 '
    worker = RuntimeWorker(store, {'runtime-v1': lambda context: approvals.pause(context, snapshot.snapshot_id, title, content)}, 'approval-fixture-first')
    waiting = worker.run_once(tenant)
    row = approvals.get(tenant, run)
    assert waiting['state'] == 'WAITING_APPROVAL' and row['snapshot_id'] == snapshot.snapshot_id
    assert store.claim_next(tenant, 'must-wait') is None
    assert approvals.decide(tenant, run, row['approval_id'], 'fixture:reviewer', approve=True)['status'] == 'APPROVED'
    resumed = store.claim_next(tenant, 'approval-fixture-second')
    context = ExecutionContext(store, resumed, Event(), 30)
    args = TicketApprovals(store).approved_arguments(context)
    assert args['title'] == title and args['content'] == content and args['arguments_sha256'] == row['arguments_sha256']
    # An explicit negative test of the real permit endpoint: an old style
    # reserved write cannot use approved but unconsumed authority.
    store.reserve_step(context.lease, 'fixture-unconsumed-write', 'TOOL', 'WRITE', dict(operation='ticket.create', version='ticket-create-v1',
        resource_ref=source.principal_ref, input_ref=str(args['snapshot_id']), arguments_sha256=args['arguments_sha256']))
    tickets = TicketCreationClient(base, secret)
    try:
        asyncio.run(tickets.create(source, context.lease, args['snapshot_id'], title, content))
    except PermanentFailure as failure:
        assert failure.code == 'TICKET_CREATE_AUTHORITY_DENIED', failure.code
    else:
        raise AssertionError('Unconsumed approval authorized a write')
    assert asyncio.run(tickets.receipt(source, run, expected_snapshot_id=args['snapshot_id'], expected_hash=args['arguments_sha256'])) is None
    with connect('customer_db') as db:
        assert db.execute('SELECT count(*) FROM ticket WHERE tenant_code=%s', (tenant,)).fetchone()[0] == 0
        assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE tenant_code=%s', (tenant,)).fetchone()[0] == 0
    result = store.cancel(tenant, run)
    assert approvals.get(tenant, run)['status'] == 'CANCELLED' and result['error_code'] == 'SIDE_EFFECT_UNKNOWN'
    artifact = Path(__file__).resolve().parents[1] / '.local/verification/runtime-ticket-approval.json'
    artifact.write_text(json.dumps(dict(format='ticket-approval-boundary-v1', waiting_state=waiting['state'], resumed_fence=resumed['fence'],
        task_budget_preserved=resumed['step_count'] == waiting['step_count'], new_tickets=0, receipts=0, models=0,
        unconsumed_permit_denied=True, mode='SYNTHETIC_SOURCE_REAL_JAVA_AI_PG'), indent=2)+'\n', encoding='utf-8')
    print('Actual ticket approval: durable wait, trusted positive decision and exact checkpoint restored; real Java/AI write permit refused unconsumed approval; zero tickets and receipts', flush=True)
