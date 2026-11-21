"""Approved ticket execution against real Runtime, customer and AI authority services."""
import asyncio
from base64 import urlsafe_b64encode
from contextlib import contextmanager
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
import time
from urllib.parse import parse_qs, urlsplit
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


@contextmanager
def approval_role_server():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            request = urlsplit(self.path)
            tenant = parse_qs(request.query).get('tenantCode', [])
            if tenant not in (['TC_APPROVAL_0'], ['TC_APPROVAL_1'], ['TC_APPROVAL_2']):
                self.send_error(404)
                return
            roles = {
                '/api/user/internal/users/123/roles': ['ADMIN'],
                '/api/user/internal/users/124/roles': ['AGENT'],
            }
            if request.path not in roles:
                self.send_error(404)
                return
            raw = json.dumps({'code': 0, 'message': 'OK', 'data': roles[request.path],
                              'requestId': 'fixture-role', 'timestamp': int(time.time() * 1000)}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
        thread = Thread(target=server.serve_forever, kwargs={'poll_interval': .05}, daemon=True)
        thread.start()
        try:
            yield f'http://127.0.0.1:{server.server_port}'
        finally:
            server.shutdown()
            thread.join(timeout=2)


def _fixture_token(key, tenant, user_id):
    # The disposable customer JAR uses a random 64-byte key for HS512.
    def part(value):
        return urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=')

    now = int(time.time())
    claims = {'sub': str(user_id), 'uno': 'FIXTURE', 'name': 'Fixture reviewer',
              'type': 2, 'tnt': tenant, 'iat': now, 'exp': now + 60}
    signed = part({'alg': 'HS512'}) + b'.' + part(claims)
    signature = urlsafe_b64encode(hmac.digest(key.encode(), signed, 'sha512')).rstrip(b'=')
    return 'Bearer ' + (signed + b'.' + signature).decode()


def _review_path(base, source, run):
    return base + f'/api/customer/agent-approvals/events/{source.source_event_id}/runs/{run}'


def _staff_review(client, base, key, source, run, row, title, content):
    path = _review_path(base, source, run)
    auth = {'Authorization': _fixture_token(key, source.tenant, 123)}
    agent = {'Authorization': _fixture_token(key, source.tenant, 124)}
    assert client.get(path+'/proposal').status_code == 401
    assert client.get(path+'/proposal', headers=agent).status_code == 403
    response = client.get(path+'/proposal', headers=auth)
    assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
    proposal = response.json()['data']
    assert proposal['title'] == title and proposal['content'] == content and proposal['status'] == 'PENDING'
    assert proposal['approval_id'] == str(row['approval_id']) and proposal['arguments_sha256'] == row['arguments_sha256']

    def request(digest, headers):
        return client.post(path+'/decisions', headers=headers, json={
            'approval_id': proposal['approval_id'], 'arguments_sha256': digest, 'decision': 'approve',
        })

    digest = proposal['arguments_sha256']
    assert request(digest, {}).status_code == 401
    assert request(digest, agent).status_code == 403
    conflict = request('0'*64, auth)
    assert conflict.status_code == 409, (conflict.status_code, conflict.json().get('code'))
    for _ in range(2):
        result = request(digest, auth)
        assert result.status_code == 200, (result.status_code, result.json().get('code'))
        assert result.json()['data'] == {'approval_id': str(row['approval_id']), 'status': 'APPROVED'}


def verify_ticket_approval(connect, base, jwt_key, secret, store):
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
            _staff_review(client, base, jwt_key, source, run, row, approved_title, approved_body)
        assert approvals.get(tenant, run)['decided_by'] == 'staff:123'

        if mode == 'taken_over_after_approval':
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=99 WHERE id=%s', (session_id,))
        if mode == 'expired_before_resume':
            with connect('ai_db') as db:
                db.execute("""UPDATE agent_approval SET requested_at=clock_timestamp()-interval '3 minutes',
                    decided_at=clock_timestamp()-interval '150 seconds',expires_at=clock_timestamp()-interval '2 minutes'
                    WHERE tenant_code=%s AND run_id=%s""", (tenant, run))
        if mode != 'accepted':
            with httpx.Client(trust_env=False, timeout=3) as client:
                unavailable = client.get(_review_path(base, source, run)+'/proposal',
                    headers={'Authorization': _fixture_token(jwt_key, source.tenant, 123)})
                assert unavailable.status_code == 404
                assert approved_title not in unavailable.text
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
    artifact.write_text(json.dumps(dict(format='ticket-approval-boundary-v4', mode='SYNTHETIC_ROLE_SOURCE_REAL_JAVA_AI_PG',
        cases=observations), indent=2)+'\n', encoding='utf-8')
    print('Authenticated customer-JAR proposal preview and review: 3 cases passed; anonymous, ordinary staff and wrong digest rejected; '
          'duplicate approval preserved, one approved ticket/receipt, zero writes after takeover or expiry', flush=True)
