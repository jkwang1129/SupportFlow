"""Actual Python source SDK -> actual customer JAR, only on disposable data."""
import asyncio
from dataclasses import replace
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

import httpx

from local_env import runtime_environment

ROOT = Path(__file__).resolve().parents[1]


def verify_sources(connect, environment, passwords, secret, sources, runtime_dsn):
    sys.path.insert(0,str(ROOT/'ai-service'))
    from ai.runtime.source import SourceClient, credential, HEADER
    from ai.runtime.types import RunInput
    from ai.runtime.worker import PermanentFailure

    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0)); port=reservation.getsockname()[1]
    service_env = {key:value for key,value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    service_env.update(
        SUPPORTFLOW_DB_HOST='127.0.0.1', SUPPORTFLOW_DB_PORT=environment['SUPPORTFLOW_DB_PORT'],
        SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'],
        SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'],
        SUPPORTFLOW_AI_CUSTOMER_SECRET=secret, SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(48),
        SUPPORTFLOW_RUSTFS_SECRET_KEY=secrets.token_urlsafe(32),
        SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000',
    )
    service_env=runtime_environment('customer-service',service_env)
    java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
    jar,=(ROOT/'backend/supportflow-customer-service/target').glob('*.jar')
    logs=ROOT/'.local/runtime-source-service'; logs.mkdir(parents=True,exist_ok=True)
    failed=False
    with (logs/'customer-service.log').open('wb') as output:
        process=subprocess.Popen([str(java),'-Xms32m','-Xmx384m','-jar',str(jar),f'--server.port={port}',
                                  '--supportflow.bot.runtime-source-enabled=true'],cwd=ROOT,env=service_env,
                                 stdout=output,stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        try:
            base=f'http://127.0.0.1:{port}'; deadline=time.monotonic()+60
            with httpx.Client(trust_env=False,timeout=1) as client:
                while time.monotonic()<deadline:
                    if process.poll() is not None: raise RuntimeError('Disposable customer source service exited')
                    try:
                        if client.get(base+'/actuator/health/readiness').status_code==200: break
                    except httpx.HTTPError: pass
                    time.sleep(.25)
                else: raise RuntimeError('Disposable customer source service was not ready')
            specs=[]
            for tenant,event_id,run_id,message_id,sequence in sources:
                with connect('customer_db') as connection:
                    session,principal,trace,version=connection.execute(
                        'SELECT session_no,principal_ref,trace_id,runtime_version FROM agent_event_outbox WHERE tenant_code=%s AND event_id=%s',
                        (tenant,event_id)).fetchone()
                specs.append((RunInput(tenant,event_id,session,sequence,message_id,principal,trace,version),run_id))
            async def qualify():
                source_client=SourceClient(base,secret)
                for source,run_id in specs:
                    first=await source_client.capture(source,run_id)
                    assert first==await source_client.read(source,run_id,expected_snapshot_id=first.snapshot_id,
                                                          expected_hash=first.payload_hash)==await source_client.capture(source,run_id)
                    assert first.text=='synthetic-private-source'
                from source_tool_fixture import verify_source_tools
                await asyncio.to_thread(verify_source_tools, connect, runtime_dsn, base, secret, specs)
                source,run_id=specs[-1]
                try: await source_client.read(replace(source,tenant='TENANT_B'),run_id)
                except PermanentFailure as failure: assert failure.code=='SOURCE_REVOKED'
                else: raise AssertionError('Foreign tenant read unexpectedly passed')
                try: await SourceClient(base,'wrong-pair-credential-'+'0'*40).read(source,run_id)
                except PermanentFailure as failure: assert failure.code=='SOURCE_AUTHORIZATION_DENIED'
                else: raise AssertionError('Wrong service credential unexpectedly passed')
                with connect('customer_db') as connection:
                    connection.execute('UPDATE session SET agent_id=12345 WHERE tenant_code=%s AND session_no=%s',(source.tenant,source.session_no))
                try: await source_client.read(source,run_id)
                except PermanentFailure as failure: assert failure.code=='SOURCE_REVOKED'
                else: raise AssertionError('Human takeover did not revoke source access')
                async with httpx.AsyncClient(trust_env=False,timeout=3) as anonymous:
                    path=f'/api/customer/internal/runtime/events/{source.source_event_id}/runs/{run_id}/snapshot'
                    assert (await anonymous.get(base+path)).status_code==401
                    result_path=path.removesuffix('snapshot')+'reply'
                    assert (await anonymous.get(base+result_path)).status_code==401
                    disabled=await anonymous.get(base+result_path,headers={HEADER:credential(secret,'GET',result_path,source.tenant,b''),
                                                                        'X-Tenant-Code':source.tenant})
                    assert disabled.status_code==503 and disabled.headers['Retry-After']=='5'
                    ticket_path=path.removesuffix('snapshot')+'tickets/NO-SUCH-TICKET'
                    assert (await anonymous.get(base+ticket_path)).status_code==401
                    disabled_read=await anonymous.get(base+ticket_path,headers={HEADER:credential(secret,'GET',ticket_path,source.tenant,b''),
                                                                             'X-Tenant-Code':source.tenant})
                    assert disabled_read.status_code==503 and disabled_read.headers['Retry-After']=='5'
            asyncio.run(qualify())
            with connect('customer_db') as connection:
                count=connection.execute("SELECT count(*) FROM agent_source_snapshot WHERE event_id=ANY(%s)",([row[1] for row in sources],)).fetchone()[0]
                assert count==2
            print('Actual Python SDK -> signed customer JAR -> immutable source: 2 flows passed; same snapshots, hash checks, foreign/anonymous/credential/takeover denials passed; result API default closed',flush=True)
        except BaseException:
            failed=True
            raise
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
            if failed:
                from security.service_diagnostics import capture
                capture(logs,ROOT/'.local/verification/source-service-errors.json','runtime-source')
