"""Read restored attachments and runs through real services on a disposable target."""

from contextlib import ExitStack, contextmanager
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
from time import monotonic, perf_counter, sleep, time

import httpx
from psycopg.conninfo import make_conninfo

from attachment_restore_fixture import AVATAR_ID, AVATAR_KEY, CHAT_IMAGE_ID, CHAT_IMAGE_KEY
from ticket_approval_fixture import _fixture_token


ROOT = Path(__file__).resolve().parents[1]
CHAT_PATH = f'/api/customer/sessions/attachments/{CHAT_IMAGE_ID}'
AVATAR_PATH = f'/api/customer/bot/avatar/{AVATAR_ID}'
JAVA_READY = '/actuator/health/readiness'
JAVA_LIVE = '/actuator/health/liveness'
AI_READY = '/api/ai/health/ready'
AI_LIVE = '/api/ai/health/live'
OTHER_SESSION = 'RESTORE_SESSION_OTHER'


def seed_secondary_session(connect):
    with connect('customer_db') as database:
        database.execute('''INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
                            VALUES(7050030,'RESTORE_A',%s,1,2,0,CURRENT_TIMESTAMP)''', (OTHER_SESSION,))


def loopback_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


@contextmanager
def service_process(args, *, cwd, env, name):
    log_path = ROOT / '.local' / 'implementation' / f'restore-{name}.log'
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('wb') as log:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def wait_ready(client, probes):
    deadline = monotonic() + 90
    while monotonic() < deadline:
        for process, url in probes:
            if process.poll() is not None:
                raise RuntimeError('Restored service exited; inspect local restore service logs')
            try:
                if client.get(url).status_code != 200:
                    break
            except httpx.TransportError:
                break
        else:
            return
        sleep(.25)
    raise RuntimeError('Restored services did not become ready; inspect local restore service logs')


def runtime_headers(path, tenant, secret):
    expiry = str(int(time()) + 20)
    canonical = '\n'.join(('supportflow-ai-runtime:v1', 'customer', expiry, 'GET', path, '', tenant,
                          hashlib.sha256(b'').hexdigest()))
    signature = hmac.digest(secret.encode(), canonical.encode(), 'sha256').hex()
    return {'X-SupportFlow-AI-Auth': f'runtime:customer:{expiry}:{signature}', 'X-Tenant-Code': tenant}


def verify_media(client, base, key, records):
    session_a, session_b = 'RESTORE_SESSION_1', 'RESTORE_SESSION_2'
    chat, avatar = records[CHAT_IMAGE_KEY], records[AVATAR_KEY]
    visitor = _fixture_token(key, 'RESTORE_A', 1, visitor_session=session_a).removeprefix('Bearer ')
    staff = _fixture_token(key, 'RESTORE_A', 1)
    positive = (
        (CHAT_PATH, session_a, {'X-Visitor-Token': visitor}, chat, 'no-store'),
        (CHAT_PATH, session_a, {'Authorization': staff}, chat, 'no-store'),
        (AVATAR_PATH, None, {}, avatar, 'public, max-age=3600'),
    )
    for path, session, headers, entry, cache in positive:
        response = client.get(base + path, params={'sessionNo': session} if session else None, headers=headers)
        assert response.status_code == 200 and response.headers['content-type'] == entry['content_type']
        assert response.headers['cache-control'] == cache
        assert len(response.content) == entry['size'] and hashlib.sha256(response.content).hexdigest() == entry['sha256']
    other_staff = _fixture_token(key, 'RESTORE_B', 2)
    negative = (
        (CHAT_PATH, session_a, {}, 40100),
        (CHAT_PATH, session_b, {'Authorization': other_staff}, 40401),
        (CHAT_PATH, OTHER_SESSION, {'X-Visitor-Token': visitor}, 40301),
        (CHAT_PATH, OTHER_SESSION, {'Authorization': staff}, 40401),
        (f'/api/customer/bot/avatar/{CHAT_IMAGE_ID}', None, {}, 40401),
    )
    for path, session, headers, code in negative:
        response = client.get(base + path, params={'sessionNo': session} if session else None, headers=headers)
        assert response.status_code == 200 and response.json()['code'] == code and response.json()['data'] is None
    return {'media_reads': len(positive), 'media_denials': len(negative)}


def verify_runs(client, base, secret, rows):
    for tenant, event, run, _, state, calls in rows:
        path = f'/api/ai/v1/runtime/runs/{run}'
        response = client.get(base + path, headers=runtime_headers(path, tenant, secret))
        assert response.status_code == 200
        data = response.json()
        assert (data['tenant_code'], data['source_event_id'], data['run_id'], data['state'], data['model_calls'],
                data['attempt'], data['step_count'], data['tool_calls'], data['retry_count']) == (tenant, str(event), str(run), state, calls, 1, 1, 0, 0)
        other = 'RESTORE_B' if tenant == 'RESTORE_A' else 'RESTORE_A'
        assert client.get(base + path, headers=runtime_headers(path, other, secret)).status_code == 404
        assert client.get(base + path).status_code == 401
    return {'runtime_reads': len(rows), 'runtime_denials': 2 * len(rows)}


def verify_restored_services(connect, target_env, passwords, storage_env, records, rows):
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else 'java'
    jars = list((ROOT / 'backend/supportflow-customer-service/target').glob('*.jar'))
    if len(jars) != 1:
        raise RuntimeError('Build the customer service JAR before the restore drill')
    child_env = {key: value for key, value in os.environ.items() if not key.startswith(('SUPPORTFLOW_', 'SPRING_', 'UVICORN_'))}
    jwt_key, ai_key = secrets.token_urlsafe(48), secrets.token_urlsafe(48)
    pg_port = target_env['SUPPORTFLOW_DB_PORT']
    customer_env = dict(child_env, **storage_env, SUPPORTFLOW_DB_HOST='127.0.0.1', SUPPORTFLOW_DB_PORT=pg_port,
                        SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'],
                        SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'],
                        SUPPORTFLOW_JWT_SECRET=jwt_key, SUPPORTFLOW_BOT_ENABLED='false',
                        SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000')

    def dsn(database, account):
        return make_conninfo(host='127.0.0.1', port=pg_port, dbname=database,
                             user=f'supportflow_{account.lower()}', password=passwords[account])

    ai_env = dict(child_env, PYTHONPATH=str(ROOT / 'ai-service'), SUPPORTFLOW_AI_RUNTIME_API_ENABLED='true',
                  SUPPORTFLOW_AI_ENFORCE_RUNTIME_DB_ROLES='true', SUPPORTFLOW_AI_LLM_DEFAULT_PROVIDER='mock',
                  SUPPORTFLOW_AI_DATABASE_URL=dsn('ai_db', 'AI'), SUPPORTFLOW_AI_KB_DATABASE_URL=dsn('customer_db', 'KB'),
                  SUPPORTFLOW_AI_CUSTOMER_SECRET=ai_key, SUPPORTFLOW_AI_GATEWAY_SECRET=secrets.token_urlsafe(48),
                  SUPPORTFLOW_AI_TENANT_SECRET=secrets.token_urlsafe(48), SUPPORTFLOW_AI_OPS_SECRET=secrets.token_urlsafe(48))
    by_key = {record['key']: record for record in records}
    with ExitStack() as stack:
        cwd = stack.enter_context(TemporaryDirectory(prefix='supportflow-restore-services-'))
        customer_port = loopback_port()
        started = perf_counter()
        customer = stack.enter_context(service_process(
            [java, '-Xms32m', '-Xmx384m', '-jar', str(jars[0]), '--server.address=127.0.0.1',
             f'--server.port={customer_port}'], cwd=cwd, env=customer_env, name='customer'))
        ai_port = loopback_port()
        ai = stack.enter_context(service_process(
            [sys.executable, '-m', 'uvicorn', 'ai.main:app', '--host', '127.0.0.1', '--port', str(ai_port),
             '--no-access-log'], cwd=cwd, env=ai_env, name='ai'))
        http = stack.enter_context(httpx.Client(timeout=5, trust_env=False))
        customer_base, ai_base = f'http://127.0.0.1:{customer_port}', f'http://127.0.0.1:{ai_port}'
        wait_ready(http, ((customer, customer_base+JAVA_READY), (ai, ai_base+AI_READY)))
        startup_ms = round((perf_counter() - started) * 1000, 3)
        before = verify_media(http, customer_base, jwt_key, by_key) | verify_runs(http, ai_base, ai_key, rows)

        with connect('customer_db') as database:
            database.execute("SET lock_timeout = '3s'")
            database.execute('ALTER TABLE kb_chunk RENAME COLUMN content TO restore_drill_content')
            try:
                assert http.get(customer_base+JAVA_LIVE).status_code == 200
                assert http.get(ai_base+AI_LIVE).status_code == 200
                assert http.get(customer_base+JAVA_READY).status_code == 503
                assert http.get(ai_base+AI_READY).status_code == 503
                response = http.get(customer_base+CHAT_PATH, params={'sessionNo': 'RESTORE_SESSION_1'},
                                    headers={'Authorization': _fixture_token(jwt_key, 'RESTORE_A', 1)})
                assert response.status_code == 503 and response.headers['retry-after'] == '5'
                path = f'/api/ai/v1/runtime/runs/{rows[0][2]}'
                assert http.get(ai_base+path, headers=runtime_headers(path, rows[0][0], ai_key)).status_code == 503
            finally:
                database.execute('ALTER TABLE kb_chunk RENAME COLUMN restore_drill_content TO content')
        repaired = perf_counter()
        wait_ready(http, ((customer, customer_base+JAVA_READY), (ai, ai_base+AI_READY)))
        after = verify_media(http, customer_base, jwt_key, by_key) | verify_runs(http, ai_base, ai_key, rows)
        assert before == after
        return {'first_read_checks': before, 'post_repair_read_checks': after, 'live_during_fault': 2,
                'unready_during_fault': 2, 'business_reads_blocked_during_fault': 2,
                'startup_to_ready_ms': startup_ms, 'repair_to_verified_reads_ms': round((perf_counter()-repaired)*1000, 3)}
