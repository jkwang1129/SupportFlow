"""Approved ticket execution against real Runtime, customer and AI authority services."""
import asyncio
from base64 import urlsafe_b64encode
from contextlib import contextmanager, ExitStack, nullcontext
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import time
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import httpx

from ticket_write_tool_fixture import ticket_proxy
from ticket_notice_fixture import NOTICE_PATH, receive

ROOT = Path(__file__).resolve().parents[1]
FOREIGN_TENANT = 'LP_FOREIGN'


@contextmanager
def approval_role_server(visitor_tenant):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            request = urlsplit(self.path)
            tenant = parse_qs(request.query).get('tenantCode', [])
            if tenant not in ([visitor_tenant], [FOREIGN_TENANT]):
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


def _fixture_token(key, tenant, user_id, *, visitor_session=None):
    # The disposable customer JAR uses a random 64-byte key for HS512.
    def part(value):
        return urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=')

    now = int(time.time())
    claims = {'sub': str(user_id), 'uno': 'FIXTURE', 'name': 'Fixture reviewer',
              'type': 2, 'tnt': tenant, 'iat': now, 'exp': now + 60}
    if visitor_session:
        claims.update(typ=4, sno=visitor_session, name='Fixture visitor')
    signed = part({'alg': 'HS512'}) + b'.' + part(claims)
    signature = urlsafe_b64encode(hmac.digest(key.encode(), signed, 'sha512')).rstrip(b'=')
    return 'Bearer ' + (signed + b'.' + signature).decode()


def _review_path(base, source, run):
    return base + f'/api/customer/agent-approvals/events/{source.source_event_id}/runs/{run}'


def _assert_progress(base, key, source, run, facts):
    path = _review_path(base, source, run) + '/progress'
    admin = {'Authorization': _fixture_token(key, source.tenant, 123)}
    agent = {'Authorization': _fixture_token(key, source.tenant, 124)}
    foreign = {'Authorization': _fixture_token(key, FOREIGN_TENANT, 123)}
    with httpx.Client(trust_env=False, timeout=3) as client:
        assert client.get(path).status_code == 401
        assert client.get(path, headers=agent).status_code == 403
        missing = client.get(path, headers=foreign)
        assert missing.status_code == 404 and missing.json().get('data') is None
        response = client.get(path, headers=admin)
        assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
        assert response.json()['data'] == {name: facts[name] for name in ('state', 'attempt', 'step_count')}


def _assert_latest_progress(base, key, source, facts=None, *, unavailable=False):
    path = base + f'/api/customer/agent-approvals/sessions/{source.session_no}/latest-progress'
    admin = {'Authorization': _fixture_token(key, source.tenant, 123)}
    agent = {'Authorization': _fixture_token(key, source.tenant, 124)}
    foreign = {'Authorization': _fixture_token(key, FOREIGN_TENANT, 123)}
    with httpx.Client(trust_env=False, timeout=3) as client:
        assert client.get(path).status_code == 401
        assert client.get(path, headers=agent).status_code == 403
        assert client.get(path, headers=foreign).status_code == 404
        response = client.get(path, headers=admin)
        if unavailable:
            assert response.status_code == 404 and response.json().get('data') is None
            return
        assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
        assert response.json().get('data') == (
            None if facts is None else {name: facts[name] for name in ('state', 'attempt', 'step_count')})


def _event_page(base, key, source, run, after_seq, *, expected_status=200):
    path = _review_path(base, source, run) + '/timeline'
    with httpx.Client(trust_env=False, timeout=3) as client:
        auth = {'Authorization': _fixture_token(key, source.tenant, 123)}
        response = client.get(path, headers=auth, params={'afterSeq': after_seq})
        assert response.status_code == expected_status and response.headers['cache-control'] == 'no-store'
        if expected_status != 200:
            assert response.json().get('data') is None
            return None
        data = response.json()['data']
        assert set(data) == {'schema_version', 'run_id', 'next_seq', 'has_more', 'events'}
        assert data['schema_version'] == 'agent-run-events-v1' and data['run_id'] == str(run)
        assert [event['seq'] for event in data['events']] == list(range(after_seq+1, data['next_seq']+1))
        assert all(set(event) == {'seq', 'event_type', 'occurred_at', 'step_no', 'outcome'} for event in data['events'])
        return data


def _staff_review(client, base, key, source, run, row, title, content, *, revoke=False):
    auth = {'Authorization': _fixture_token(key, source.tenant, 123)}
    agent = {'Authorization': _fixture_token(key, source.tenant, 124)}
    foreign = {'Authorization': _fixture_token(key, FOREIGN_TENANT, 123)}
    active = base + f'/api/customer/agent-approvals/sessions/{source.session_no}/active'
    assert client.get(active).status_code == 401
    assert client.get(active, headers=agent).status_code == 403
    assert client.get(active, headers=foreign).status_code == 404
    found = client.get(active, headers=auth)
    assert found.status_code == 200 and found.headers['cache-control'] == 'no-store'
    reference = found.json()['data']
    assert reference['source_event_id'] == str(source.source_event_id) and reference['run_id'] == str(run)
    assert reference['status'] == 'PENDING' and set(reference) == {'source_event_id', 'run_id', 'status', 'expires_at'}
    path = base + f"/api/customer/agent-approvals/events/{reference['source_event_id']}/runs/{reference['run_id']}"
    assert client.get(path+'/proposal').status_code == 401
    assert client.get(path+'/proposal', headers=agent).status_code == 403
    missing = client.get(path+'/proposal', headers=foreign)
    assert missing.status_code == 404 and missing.json().get('data') is None
    response = client.get(path+'/proposal', headers=auth)
    assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
    proposal = response.json()['data']
    assert proposal['title'] == title and proposal['content'] == content and proposal['status'] == 'PENDING'
    assert proposal['approval_id'] == str(row['approval_id']) and proposal['arguments_sha256'] == row['arguments_sha256']

    def request(digest, headers, decision='approve'):
        return client.post(path+'/decisions', headers=headers, json={
            'approval_id': proposal['approval_id'], 'arguments_sha256': digest, 'decision': decision,
        })

    digest = proposal['arguments_sha256']
    assert request(digest, {}).status_code == 401
    assert request(digest, agent).status_code == 403
    assert request(digest, foreign).status_code == 404
    conflict = request('0'*64, auth)
    assert conflict.status_code == 409, (conflict.status_code, conflict.json().get('code'))
    for _ in range(2):
        result = request(digest, auth)
        assert result.status_code == 200, (result.status_code, result.json().get('code'))
        assert result.json()['data'] == {'approval_id': str(row['approval_id']), 'status': 'APPROVED'}
    if revoke:
        result = request(digest, auth, 'revoke')
        assert result.status_code == 200
        assert result.json()['data'] == {'approval_id': str(row['approval_id']), 'status': 'REVOKED'}


def _run_ticket_worker(tenant, base, secret, runtime_dsn, *, propose=False, authority_enabled=False):
    allowed = ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'LANG', 'LC_ALL')
    env = {key: os.environ[key] for key in allowed if key in os.environ}
    env.update(PYTHONUTF8='1', PYTHONPATH=str(ROOT/'ai-service'),
               SUPPORTFLOW_AI_DATABASE_URL=runtime_dsn, SUPPORTFLOW_AI_RUNTIME_API_ENABLED='true',
               SUPPORTFLOW_AI_RUNTIME_TICKET_ACTIONS_ENABLED='true' if authority_enabled else 'false',
               SUPPORTFLOW_AI_CUSTOMER_RUNTIME_URL=base, SUPPORTFLOW_AI_CUSTOMER_SECRET=secret)
    mode = ['--approved-tickets-only']
    if propose:
        mode = ['--provider', 'qwen']
        env.update(SUPPORTFLOW_AI_RUNTIME_TICKET_PROPOSAL_ENABLED='true', SUPPORTFLOW_AI_QWEN_MODEL='proposal-fixture',
                   SUPPORTFLOW_AI_QWEN_API_KEY='synthetic-proposal-key', SUPPORTFLOW_AI_QWEN_BASE_URL='http://127.0.0.1:1/v1')
    command = [sys.executable, '-m', 'ai.runtime.ticket_worker', '--tenant', tenant, *mode, '--once']
    with TemporaryDirectory(prefix='supportflow-approval-worker-') as directory:
        return subprocess.run(command, cwd=directory, env=env, capture_output=True, text=True,
                              encoding='utf-8', timeout=35,
                              creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)


def _visitor_messages(base, secret, source):
    path = f'/api/customer/internal/sessions/{source.session_no}/messages'
    query = f'tenantCode={source.tenant}&agentView=false&afterSeq={source.event_seq}'
    expires = str(int(time.time()) + 20)
    canonical = '\n'.join(('supportflow-customer:v1', 'REALTIME', expires, 'GET', path, query, source.tenant))
    signature = hmac.digest(secret.encode(), canonical.encode(), 'sha256').hex()
    headers = {'X-Tenant-Code': source.tenant, 'X-SupportFlow-Customer-Auth': f'{expires}:{signature}'}
    with httpx.Client(trust_env=False, timeout=3, follow_redirects=False) as client:
        response = client.get(f'{base}{path}?{query}', headers=headers)
        assert response.status_code == 200 and response.json()['code'] == 0, response.status_code
        return response.json()['data']


def verify_ticket_approval(connect, base, jwt_key, secret, store, runtime_dsn, *, realtime_secret, live_notices,
                           append_visitor, previous_read, timeline):
    from ai.runtime.approvals import TicketApprovals
    from ai.runtime.source import SourceClient
    from ai.runtime.ticket_creation import TicketCreationClient
    from ai.runtime.types import RunInput

    source_client, tickets = SourceClient(base, secret), TicketCreationClient(base, secret)
    approved_title, approved_body = '审批工单🙂', 'synthetic approved body\n原始内容 '
    read_source, read_run, read_session_id = previous_read
    tenant = read_source.tenant
    read_result = store.get(tenant, read_run)
    assert read_result['state'] == 'SUCCEEDED' and read_result['result_ref']['outcome'] == 'ticket-status-ready'
    observer_session = 'TICKET_NOTICE_OBSERVER'
    with connect('customer_db') as db:
        db.execute('''INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
            VALUES(20500999,%s,%s,1,2,0,CURRENT_TIMESTAMP)''', (tenant, observer_session))
    failures = {
        'taken_over_after_approval': ('SOURCE_REVOKED', 'APPROVED'),
        'expired_before_resume': ('APPROVAL_EXPIRED', 'EXPIRED'),
        'revoked_before_resume': ('APPROVAL_REVOKED', 'REVOKED'),
    }
    observations = []
    timelines = {}
    for index, mode in enumerate(('accepted', 'lost_create_ack', *failures)):
        request_text = '/ticket-request\n' + approved_title + '\n' + approved_body
        has_previous_read = mode == 'accepted'
        if has_previous_read:
            session_id, session_no = read_session_id, read_source.session_no
            source, run = append_visitor(session_no, request_text, read_source.event_seq + 1)
            assert (source.principal_ref, source.session_no) == (read_source.principal_ref, read_source.session_no)
            assert source.source_event_id != read_source.source_event_id and run != read_run and source.trace_id != read_source.trace_id
        else:
            session_id, session_no = 20500001 + index * 10, 'TICKET_APPROVAL_'+str(index)
            with connect('customer_db') as db:
                db.execute('''INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
                    VALUES(%s,%s,%s,1,2,0,CURRENT_TIMESTAMP)''', (session_id, tenant, session_no))
            source, run = append_visitor(session_no, request_text, 1)
        approvals = TicketApprovals(store)
        proposal = _run_ticket_worker(tenant, base, secret, runtime_dsn, propose=True)
        assert proposal.returncode == 0 and secret not in proposal.stderr
        waiting = store.get(tenant, run)
        snapshot = asyncio.run(source_client.read(source, run))
        row = approvals.get(tenant, run)
        assert waiting['state'] == 'WAITING_APPROVAL' and row['snapshot_id'] == snapshot.snapshot_id
        assert (waiting['step_count'], waiting['tool_calls'], waiting['model_calls']) == (4, 2, 0)
        waiting_trace = store.trace_snapshot(tenant, run)
        assert waiting_trace['run']['trace_id'] == source.trace_id
        assert waiting_trace['run']['state'] == 'WAITING_APPROVAL'
        assert store.claim_next(tenant, 'must-wait') is None
        assert not any(step['effect'] == 'WRITE' for step in store.steps(tenant, run))
        assert asyncio.run(tickets.receipt(source, run)) is None
        _assert_progress(base, jwt_key, source, run, waiting)
        _assert_latest_progress(base, jwt_key, source)
        waiting_page = None
        event_resume = None
        if has_previous_read:
            waiting_page = _event_page(base, jwt_key, source, run, 0)
            assert not waiting_page['has_more'] and waiting_page['next_seq'] == 10
            assert waiting_page['events'][0]['event_type'] == 'run_started'
            assert waiting_page['events'][-1]['event_type'] == 'approval_required'
            track = timeline.for_event(tenant, source.source_event_id)
            assert track['binding_status'] == 'matched' and track['python_runtime']['approval']['status'] == 'PENDING'
            assert track['python_runtime']['run']['state'] == 'WAITING_APPROVAL'
            timelines['approval_wait'] = track
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=3) as client:
            _staff_review(client, base, jwt_key, source, run, row, approved_title, approved_body,
                          revoke=mode == 'revoked_before_resume')
        assert approvals.get(tenant, run)['decided_by'] == 'staff:123'

        if mode == 'taken_over_after_approval':
            with connect('customer_db') as db:
                db.execute('UPDATE session SET agent_id=99 WHERE id=%s', (session_id,))
        if mode == 'expired_before_resume':
            with connect('ai_db') as db:
                db.execute("""UPDATE agent_approval SET requested_at=clock_timestamp()-interval '3 minutes',
                    decided_at=clock_timestamp()-interval '150 seconds',expires_at=clock_timestamp()-interval '2 minutes'
                    WHERE tenant_code=%s AND run_id=%s""", (tenant, run))
        if mode in ('taken_over_after_approval', 'expired_before_resume'):
            with httpx.Client(trust_env=False, timeout=3) as client:
                unavailable = client.get(_review_path(base, source, run)+'/proposal',
                    headers={'Authorization': _fixture_token(jwt_key, source.tenant, 123)})
                assert unavailable.status_code == 404
                assert approved_title not in unavailable.text
        other = store.enqueue(RunInput(tenant, uuid4(), 'READ_ONLY_COLLEAGUE_'+str(index), 1, session_id+3000,
                                       'visitor-session:'+str(session_id+3000), 'approval-worker-ordinary'))
        if has_previous_read:
            approved = store.get(tenant, run)
            disabled = _run_ticket_worker(tenant, base, secret, runtime_dsn)
            assert disabled.returncode == 1 and store.get(tenant, run) == approved
        running_progress = None

        def observe_before_create():
            nonlocal running_progress
            active = store.get(tenant, run)
            assert active['state'] == 'RUNNING'
            assert approvals.get(tenant, run)['status'] == 'CONSUMED'
            assert sum(step['effect'] == 'WRITE' and step['state'] == 'RESERVED' for step in store.steps(tenant, run)) == 1
            assert approvals.active_reference(tenant, session_no) is None
            with connect('customer_db') as db:
                assert db.execute('SELECT COUNT(*) FROM ticket WHERE tenant_code=%s AND source_session_id=%s',
                                  (tenant, session_id)).fetchone()[0] == 0
            _assert_latest_progress(base, jwt_key, source, active)
            running_progress = {name: active[name] for name in ('state', 'attempt', 'step_count')}
            after = store.get(tenant, run)
            assert all(after[name] == value for name, value in running_progress.items())

        if has_previous_read:
            transport = ticket_proxy(base, 'normal', None, before_create=observe_before_create)
        elif mode == 'lost_create_ack':
            transport = ticket_proxy(base, 'lost_ack', None)
        else:
            transport = nullcontext((base, None))
        socket_message = None
        fallback_history = False
        if mode == 'lost_create_ack':
            live_notices.unavailable.add(session_no)
        with transport as (worker_base, network), ExitStack() as sockets:
            visitor = observer = None
            if mode not in failures:
                token = _fixture_token(jwt_key, tenant, session_id, visitor_session=session_no).removeprefix('Bearer ')
                visitor = sockets.enter_context(live_notices.visitor(token))
            if has_previous_read:
                token = _fixture_token(jwt_key, tenant, 20500999, visitor_session=observer_session).removeprefix('Bearer ')
                observer = sockets.enter_context(live_notices.visitor(token))
            worker = _run_ticket_worker(tenant, worker_base, secret, runtime_dsn, authority_enabled=True)
            assert worker.returncode == 0 and secret not in worker.stderr, (mode, worker.returncode)
            result = store.get(tenant, run)
            repeated = _run_ticket_worker(tenant, worker_base, secret, runtime_dsn, authority_enabled=True)
            assert repeated.returncode == 0 and store.get(tenant, run) == result
            if mode == 'lost_create_ack':
                assert (network['committed'], network['posts'], network['gets']) == (True, 1, 1), network
            if has_previous_read:
                assert running_progress and (network['committed'], network['posts'], network['gets']) == (True, 1, 0)
                frame = receive(visitor, 'MESSAGE')
                assert frame['sessionNo'] == session_no
                socket_message = frame['data']
                observer.send(json.dumps({'type': 'PING'}))
                receive(observer, 'PONG', reject_notice=True)
            if mode == 'lost_create_ack':
                visitor.send(json.dumps({'type': 'SYNC', 'sessionNo': session_no, 'data': {'afterSeq': source.event_seq}}))
                frame = receive(visitor, 'SYNCED', reject_notice=True)
                assert frame['sessionNo'] == session_no and len(frame['data']['messages']) == 1
                socket_message = frame['data']['messages'][0]
                fallback_history = True
        assert store.get(tenant, other['run_id']) == other
        store.cancel(tenant, other['run_id'])  # keep the next case's read worker isolated
        final_trace = store.trace_snapshot(tenant, run)
        assert final_trace['run']['trace_id'] == waiting_trace['run']['trace_id']
        assert final_trace['run']['state'] == result['state'] and len(final_trace['steps']) == result['step_count']
        assert store.trace_snapshot(FOREIGN_TENANT, run) is None
        audit = approvals.get(tenant, run)
        writes = [step for step in store.steps(tenant, run) if step['effect'] == 'WRITE']
        receipt = asyncio.run(tickets.receipt(source, run, expected_snapshot_id=snapshot.snapshot_id, expected_hash=row['arguments_sha256']))
        with connect('customer_db') as db:
            facts = db.execute('SELECT id,ticket_no,title,"desc",source_session_id FROM ticket WHERE tenant_code=%s AND source_session_id=%s',
                               (tenant, session_id)).fetchall()
            events = db.execute('''SELECT count(*) FROM ticket_event e JOIN ticket t ON t.id=e.ticket_id AND t.tenant_code=e.tenant_code
                WHERE e.tenant_code=%s AND t.source_session_id=%s''', (tenant, session_id)).fetchone()[0]
            stored_receipts = db.execute('''SELECT event_id,snapshot_id,arguments_sha256,ticket_id,ticket_no,step_no
                FROM agent_ticket_receipt WHERE tenant_code=%s AND run_id=%s''', (tenant, run)).fetchall()
            ordered_sources = db.execute('''SELECT o.event_seq,o.message_id,o.event_id,o.run_id,o.state
                  FROM agent_event_outbox o JOIN session_message m ON m.tenant_code=o.tenant_code
                   AND m.session_id=o.session_id AND m.id=o.message_id AND m.seq=o.event_seq
                  WHERE o.tenant_code=%s AND o.session_id=%s ORDER BY o.event_seq''', (tenant, session_id)).fetchall()
            message_count = db.execute('SELECT count(*) FROM session_message WHERE tenant_code=%s AND session_id=%s',
                                       (tenant, session_id)).fetchone()[0]
        expected_sources = [(source.event_seq, source.message_id, source.source_event_id, run, 'DELIVERED')]
        if has_previous_read:
            expected_sources.insert(0, (read_source.event_seq, read_source.message_id, read_source.source_event_id,
                                        read_run, 'DELIVERED'))
            assert store.get(tenant, read_run) == read_result
        visitor_messages = _visitor_messages(base, realtime_secret, source)
        notice_count = int(mode not in failures)
        assert ordered_sources == expected_sources and message_count == len(expected_sources) + notice_count
        assert len(visitor_messages) == notice_count
        if mode not in failures:
            assert result['state'] == 'SUCCEEDED' and audit['status'] == 'CONSUMED'
            assert len(writes) == 1 and writes[0]['state'] == 'SUCCEEDED' and audit['consumed_step_no'] == writes[0]['step_no']
            assert receipt is not None
            assert facts == [(receipt.ticket_id, receipt.ticket_no, approved_title, approved_body, session_id)] and events == 1
            assert stored_receipts == [(source.source_event_id, snapshot.snapshot_id, row['arguments_sha256'],
                                        receipt.ticket_id, receipt.ticket_no, writes[0]['step_no'])]
            assert result['result_ref']['business_ref'] == f'ticket:{receipt.ticket_id}'
            assert result['result_ref']['resource_ref'] == receipt.ticket_no
            notice = visitor_messages[0]
            assert (int(notice['seq']), notice['senderType'], notice['msgType'], notice['visibleTo']) == (
                source.event_seq + 1, 4, 5, 1)
            assert notice['content'] == f'工单已创建，编号：{receipt.ticket_no}'
            assert notice['clientMsgNo'] == f'agent-ticket-notice:{run}'
            assert all(socket_message[k] == notice[k] for k in ('msgId','content','clientMsgNo'))
            path = f'{NOTICE_PATH}/{session_no}/{notice["seq"]}/{notice["msgId"]}'
            assert live_notices.requests.get(session_no) == [(path, 1, 503 if fallback_history else 204)]
            if has_previous_read:
                prior = f'{NOTICE_PATH}/{session_no}/{source.event_seq}/{source.message_id}'
                live_notices.check_request_boundary(path, tenant, prior)
            recovery_reads = int(mode == 'lost_create_ack')
            counts = (result['step_count'], result['tool_calls'], result['model_calls'], result['retry_count'])
            assert counts == (7 + recovery_reads, 4 + recovery_reads, 0, 0), (mode, counts)
        else:
            expected_error, expected_status = failures[mode]
            assert (result['state'], result['error_code'], audit['status']) == ('FAILED', expected_error, expected_status)
            assert writes == [] and receipt is None and stored_receipts == [] and facts == [] and events == 0
            assert audit['consumed_step_no'] is None
            assert session_no not in live_notices.requests
        if mode not in ('taken_over_after_approval', 'expired_before_resume'):
            _assert_progress(base, jwt_key, source, run, result)
        _assert_latest_progress(base, jwt_key, source, result, unavailable=mode == 'taken_over_after_approval')
        if has_previous_read:
            cursor = waiting_page['next_seq']
            resumed = _event_page(base, jwt_key, source, run, cursor)
            assert resumed == _event_page(base, jwt_key, source, run, cursor)
            assert not resumed['has_more'] and resumed['next_seq'] == 17
            assert resumed['events'][-1]['event_type'] == 'run_completed'
            drained = _event_page(base, jwt_key, source, run, resumed['next_seq'])
            assert drained['next_seq'] == 17 and not drained['has_more'] and drained['events'] == []
            _event_page(base, jwt_key, source, run, 18, expected_status=409)
            event_resume = dict(first_page=cursor, resumed_events=len(resumed['events']), final_cursor=drained['next_seq'])
            track = timeline.for_run(tenant, run)
            assert track['trace_id'] == timelines['approval_wait']['trace_id']
            assert track['python_runtime']['approval']['status'] == 'CONSUMED'
            assert track['python_runtime']['approval']['consumed_step_no'] == writes[0]['step_no']
            assert track['python_runtime']['run']['state'] == 'SUCCEEDED'
            timelines['approval_consumed'] = track
            with connect('customer_db') as db:
                db.execute("""UPDATE agent_source_snapshot SET created_at=clock_timestamp()-interval '2 hours',
                    expires_at=clock_timestamp()-interval '1 hour' WHERE tenant_code=%s AND run_id=%s""", (tenant, run))
            _assert_latest_progress(base, jwt_key, source, unavailable=True)
            _event_page(base, jwt_key, source, run, cursor, expected_status=410)
        if mode == 'taken_over_after_approval':
            _event_page(base, jwt_key, source, run, 0, expected_status=404)
        observations.append(dict(case=mode, source_enrollment='SIGNED_VISITOR_APPEND',
            session_messages=message_count, delivered_source_events=len(ordered_sources), continued_from_ticket_read=has_previous_read,
            waiting_state=waiting['state'], before_review_writes=0, final_state=result['state'], error_code=result['error_code'],
            trace_id=final_trace['run']['trace_id'],
            approval_status=audit['status'], write_requests=network['posts'] if network is not None else None,
            recovery_receipt_requests=network['gets'] if network is not None else None,
            steps=result['step_count'], tools=result['tool_calls'], models=result['model_calls'],
            tickets=len(facts), receipts=len(stored_receipts), events=events, visitor_notices=len(visitor_messages),
            live_push=has_previous_read, synced_after_push_failure=fallback_history,
            latest_progress_restored=mode != 'taken_over_after_approval', expired_source_hidden=has_previous_read,
            restored_while_running=running_progress, event_resume=event_resume))
    artifact = ROOT / '.local/verification/runtime-ticket-approval.json'
    artifact.write_text(json.dumps(dict(format='ticket-approval-boundary-v16', mode='VISITOR_REQUEST_REAL_JAVA_AI_PG_CLI',
        cases=observations), indent=2)+'\n', encoding='utf-8')
    print('Signed visitor -> Outbox -> proposal -> staff review -> approval Worker: 5 cases passed; '
          'same-session read then create, one visitor-visible notice per receipt including lost ACK, '
          'and zero writes after takeover, expiry or revocation', flush=True)
    print('Ticket notices: actual visitor WebSocket received one committed notice; a second session received none; '
          'HTTP 503 preserved one notice recovered through SYNC; unsigned, wrong-role and mismatched references rejected', flush=True)
    print('Latest reviewed progress: 4 terminal states recovered by session, 1 takeover and 1 expired snapshot hidden; '
          '1 consumed approval recovered in RUNNING before ticket creation; '
          'pending runs, anonymous users, regular agents and foreign tenants could not discover reviewed progress', flush=True)
    print('Approval event cursor: first 10 events to approval, 7 later events replayed after reconnect without advancing the cursor twice; '
          'takeover, expired source and future cursor hidden or rejected', flush=True)
    return timelines
