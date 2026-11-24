"""Disposable realtime JAR and a loopback fault for the approved notice HTTP call."""
from contextlib import contextmanager
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
from threading import Thread
import time

import httpx
from websockets.sync.client import connect as websocket_connect

ROOT = Path(__file__).resolve().parents[1]
NOTICE_PATH = '/api/realtime/internal/ticket-notices'


def notice_headers(key, tenant, path, caller='CUSTOMER_NOTICE'):
    expires = str(int(time.time()) + 20)
    canonical = '\n'.join(('supportflow-customer:v1', caller, expires, 'POST', path, '', tenant))
    tag = hmac.digest(key.encode(), canonical.encode(), 'sha256').hex()
    return {'X-Tenant-Code': tenant, 'X-SupportFlow-Customer-Auth': f'{expires}:{tag}'}


def receive(ws, kind, *, reject_notice=False):
    deadline = time.monotonic() + 7
    while time.monotonic() < deadline:
        frame = json.loads(ws.recv(timeout=max(0, deadline - time.monotonic())))
        if frame['type'] == 'ERROR':
            raise AssertionError('Unexpected realtime protocol error')
        if reject_notice and frame['type'] == 'MESSAGE':
            assert not (frame.get('data', {}).get('clientMsgNo') or '').startswith('agent-ticket-notice:')
        if frame['type'] == kind:
            return frame
    raise AssertionError(f'Missing realtime frame {kind}')


class LiveNotices:
    def __init__(self, base, proxy, key, requests, unavailable):
        self.base, self.proxy, self.key = base, proxy, key
        self.requests, self.unavailable = requests, unavailable

    @contextmanager
    def visitor(self, token):
        with websocket_connect(self.base.replace('http:', 'ws:') + '/ws/realtime?token=' + token,
                               proxy=None, open_timeout=6) as ws:
            receive(ws, 'CONNECTED')
            receive(ws, 'JOINED')
            yield ws

    def check_request_boundary(self, path, tenant, prior_message_path):
        with httpx.Client(trust_env=False, timeout=3, follow_redirects=False) as client:
            assert client.post(self.base + path).status_code == 401
            assert client.post(self.base + path, headers=notice_headers(self.key, tenant, path, 'REALTIME')).status_code == 401
            assert client.post(self.base + path, headers=notice_headers(self.key, tenant, prior_message_path)).status_code == 401
            other_tenant = notice_headers(self.key, tenant, path) | {'X-Tenant-Code': 'NOTICE_FOREIGN'}
            assert client.post(self.base + path, headers=other_tenant).status_code == 401
            assert client.post(self.base + prior_message_path,
                               headers=notice_headers(self.key, tenant, prior_message_path)).status_code == 404


@contextmanager
def realtime_notice_service(java, customer_port, child, connect, logs, node):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    requests, unavailable = {}, set()
    key = child['SUPPORTFLOW_CUSTOMER_REALTIME_SECRET']
    allow = {'PATH', 'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP',
             'SUPPORTFLOW_JWT_SECRET', 'SUPPORTFLOW_CUSTOMER_REALTIME_SECRET'}
    env = {name: value for name, value in child.items() if name.upper() in allow}
    jar, = (ROOT/'backend/supportflow-realtime-service/target').glob('*.jar')

    class Proxy(BaseHTTPRequestHandler):
        def do_POST(self):
            if not self.path.startswith(NOTICE_PATH + '/'):
                self.send_error(404)
                return
            session, seq, message_id = self.path.removeprefix(NOTICE_PATH + '/').split('/')
            tenant = self.headers['X-Tenant-Code']
            # The HTTP call must be observable only after all three rows committed.
            with connect('customer_db') as db:
                count = db.execute("""SELECT count(*) FROM session_message m
                    JOIN session s ON s.tenant_code=m.tenant_code AND s.id=m.session_id
                    JOIN agent_ticket_receipt r ON r.tenant_code=m.tenant_code
                      AND m.client_msg_no='agent-ticket-notice:'||r.run_id::text
                    JOIN ticket t ON t.tenant_code=m.tenant_code AND t.id=r.ticket_id AND t.source_session_id=s.id
                    WHERE s.tenant_code=%s AND s.session_no=%s AND m.seq=%s AND m.id=%s""",
                    (tenant, session, int(seq), int(message_id))).fetchone()[0]
            if session in unavailable:
                status = 503
            else:
                headers = {name: self.headers[name] for name in ('X-Tenant-Code', 'X-SupportFlow-Customer-Auth')}
                status = httpx.post(base + self.path, headers=headers, timeout=2, trust_env=False).status_code
            requests.setdefault(session, []).append((self.path, count, status))
            self.send_response(status)
            self.end_headers()

        def log_message(self, *_):
            pass

    with (logs/f'realtime-notice-{node}.log').open('wb') as log, ThreadingHTTPServer(('127.0.0.1', 0), Proxy) as proxy:
        process = subprocess.Popen([str(java), '-Xms24m', '-Xmx192m', '-jar', str(jar), f'--server.port={port}',
                                    f'--supportflow.customer.internal-base-url=http://127.0.0.1:{customer_port}'],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        worker = Thread(target=proxy.serve_forever, kwargs={'poll_interval': .05}, daemon=True)
        worker.start()
        try:
            with httpx.Client(trust_env=False, timeout=1) as client:
                for _ in range(200):
                    if process.poll() is not None:
                        raise RuntimeError(f'Disposable realtime node {node} exited')
                    try:
                        if client.get(base + '/actuator/health').status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(.25)
                else:
                    raise RuntimeError(f'Disposable realtime node {node} did not become ready')
            yield LiveNotices(base, f'http://127.0.0.1:{proxy.server_port}', key, requests, unavailable)
        finally:
            proxy.shutdown()
            worker.join(timeout=2)
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
