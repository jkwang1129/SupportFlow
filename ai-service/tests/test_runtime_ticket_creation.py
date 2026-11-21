import asyncio
from contextlib import contextmanager
from dataclasses import FrozenInstanceError,replace
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from threading import Thread
import time
from uuid import UUID,uuid4

import httpx
import pytest

from ai.runtime.ticket_creation import TicketCreationClient,TicketCreationReceipt,arguments_hash,receipt_reference
from ai.runtime.source import HEADER,credential
from ai.runtime.types import RunInput,Lease
from ai.runtime.worker import PermanentFailure,TransientFailure

KEY='synthetic-ticket-create-pair-'+'0'*40
RUN,EVENT,SNAPSHOT=(UUID('00000000-0000-0000-0000-00000000000'+str(i)) for i in (1,2,3))
SOURCE=RunInput('TENANT_A',EVENT,'S1',1,42,'visitor-session:7','ticket-create-trace')
LEASE=Lease(SOURCE.tenant,RUN,'ticket-worker',1)
TITLE,CONTENT='标题','原文\r\nbody '
DIGEST='97638edb7b243df0c8c0f35590b7c87cf423d3c4c642d57363000b8a0047dc86'


def data(**changes):
    return {**dict(tenant_code=SOURCE.tenant,source_event_id=str(EVENT),run_id=str(RUN),snapshot_id=str(SNAPSHOT),
        schema_version='ticket-create-v1',operation='ticket.create',arguments_sha256=DIGEST,ticket_id='9007199254740993',ticket_no='TK_123'),**changes}


def raw(value=None,*,code=0,**metadata):
    return json.dumps(dict(code=code,message='private synthetic upstream message',data=data() if value is None else value,**metadata),ensure_ascii=False,separators=(',',':')).encode()


def response(value=None,*,status=200,code=0,**metadata):
    return httpx.Response(status,headers={'Content-Type':'application/json'},stream=httpx.ByteStream(raw(value,code=code,**metadata)))


def client(handler,**options):return TicketCreationClient('http://customer.invalid',KEY,transport=httpx.MockTransport(handler),**options)
def create(api):return asyncio.run(api.create(SOURCE,LEASE,SNAPSHOT,TITLE,CONTENT))


def test_shared_java_golden_digest_preserves_line_endings_and_trailing_text():
    assert arguments_hash(SOURCE,RUN,SNAPSHOT,TITLE,CONTENT)==DIGEST
    assert arguments_hash(SOURCE,RUN,SNAPSHOT,TITLE,CONTENT.replace('\r\n','\n'))!=DIGEST
    assert arguments_hash(SOURCE,RUN,SNAPSHOT,TITLE,CONTENT.rstrip())!=DIGEST
    assert arguments_hash(replace(SOURCE,tenant='TENANT_B'),RUN,SNAPSHOT,TITLE,CONTENT)!=DIGEST


def test_exact_post_wire_signature_closed_body_and_typed_result():
    calls=[]
    def handle(request):
        calls.append(request);assert request.method=='POST' and request.url.path.endswith(f'/events/{EVENT}/runs/{RUN}/ticket-create')
        assert request.headers['X-Tenant-Code']==SOURCE.tenant and request.headers['X-Request-Id']==SOURCE.trace_id
        assert 'Authorization' not in request.headers and request.headers['Accept-Encoding']=='identity'
        expiry=int(request.headers[HEADER].split(':')[2])
        assert request.headers[HEADER]==credential(KEY,'POST',request.url.raw_path.decode(),SOURCE.tenant,request.content,expires=expiry)
        assert json.loads(request.content)==dict(snapshot_id=str(SNAPSHOT),owner=LEASE.owner,fence=1,arguments_sha256=DIGEST,title=TITLE,content=CONTENT,version='ticket-create-v1')
        return response(timestamp='1700000000000',requestId='synthetic-trace')
    result=create(client(handle));assert len(calls)==1 and result.ticket_id==9007199254740993 and result.ticket_no=='TK_123'
    assert TITLE not in repr(result) and CONTENT not in repr(result)
    with pytest.raises(FrozenInstanceError):result.ticket_id=7
    reference=receipt_reference(result,SOURCE,RUN,SNAPSHOT,DIGEST)
    assert reference==dict(operation='ticket.create',version='ticket-create-v1',outcome='ticket-committed',business_ref='ticket:9007199254740993',resource_ref='TK_123',input_ref=str(SNAPSHOT),arguments_sha256=DIGEST)


@pytest.mark.parametrize('patch',[dict(tenant_code='TENANT_B'),dict(source_event_id=str(uuid4())),dict(run_id=str(uuid4())),dict(snapshot_id=str(uuid4())),
    dict(snapshot_id='ABCDEF00-0000-0000-0000-000000000003'),dict(snapshot_id=3),dict(arguments_sha256='b'*64),dict(arguments_sha256=True),
    dict(operation='session.reply'),dict(schema_version='reply-v1'),dict(ticket_id=123),dict(ticket_id=True),dict(ticket_id='0'),dict(ticket_id='01'),
    dict(ticket_id='-1'),dict(ticket_id='9223372036854775808'),dict(ticket_id='１２'),dict(ticket_no='../other'),dict(ticket_no='a.b'),dict(ticket_no='x'*65),
    dict(ticket_no=None),dict(extra='authority')])
def test_substituted_and_invalid_receipts_never_succeed(patch):
    with pytest.raises(PermanentFailure):create(client(lambda _:response(data(**patch))))


@pytest.mark.parametrize('patch',[dict(tenant='TENANT_B'),dict(event_id=uuid4()),dict(run_id=uuid4()),dict(snapshot_id=uuid4()),dict(arguments_sha256='b'*64),
    dict(ticket_id=True),dict(ticket_id=0),dict(ticket_id=9223372036854775808),dict(ticket_no='../other')])
def test_forged_typed_receipts_cannot_be_persisted_as_outcomes(patch):
    result=TicketCreationReceipt(SOURCE.tenant,RUN,EVENT,SNAPSHOT,DIGEST,123,'TK_123')
    with pytest.raises(PermanentFailure,match='TICKET_CREATE_RECEIPT_MISMATCH'):receipt_reference(replace(result,**patch),SOURCE,RUN,SNAPSHOT,DIGEST)


def test_receipt_get_can_bind_expectations_and_only_exact_missing_code_is_empty():
    calls=[]
    def handle(request):calls.append(request);assert request.method=='GET' and request.content==b'';return response()
    result=asyncio.run(client(handle).receipt(SOURCE,RUN,expected_snapshot_id=SNAPSHOT,expected_hash=DIGEST));assert result.snapshot_id==SNAPSHOT and len(calls)==1
    missing=client(lambda _:httpx.Response(404,headers={'Content-Type':'application/json'},stream=httpx.ByteStream(b'{"code":40406,"message":"missing","data":null}')))
    assert asyncio.run(missing.receipt(SOURCE,RUN)) is None
    for body in (b'{"code":404,"message":"missing","data":null}',b'{"code":40406,"message":"missing","data":{}}',b'{"code":0,"message":"ok","data":null}'):
        with pytest.raises(PermanentFailure):asyncio.run(client(lambda _:httpx.Response(404,headers={'Content-Type':'application/json'},stream=httpx.ByteStream(body))).receipt(SOURCE,RUN))


@pytest.mark.parametrize('status,code,expected',[(404,404,'AUTHORITY_DENIED'),(409,409,'AUTHORITY_DENIED'),(404,40402,'SOURCE_REVOKED'),
    (404,40406,'RESOURCE_UNAVAILABLE'),(409,40902,'SOURCE_CHANGED'),(409,40906,'CONFLICT'),(410,41002,'SOURCE_EXPIRED'),
    (413,41302,'SOURCE_TOO_LARGE'),(422,42202,'SOURCE_INVALID'),(422,42206,'RESULT_INVALID')])
def test_business_errors_are_fixed_and_do_not_copy_upstream_text(status,code,expected):
    body=json.dumps(dict(code=code,message='private synthetic upstream error',data=None)).encode()
    with pytest.raises(PermanentFailure) as failure:create(client(lambda _:httpx.Response(status,headers={'Content-Type':'application/json'},stream=httpx.ByteStream(body))))
    assert failure.value.code=='TICKET_CREATE_'+expected and 'private' not in str(failure.value)


@pytest.mark.parametrize('status',[401,403,408,425,429,500,503,302])
def test_http_failures_never_cause_hidden_second_post(status):
    calls=[]
    def handle(request):calls.append(request);return httpx.Response(status,headers={'Location':'http://other.invalid'},content=b'private cause')
    expected=TransientFailure if status in (408,425,429) or status>=500 else PermanentFailure
    with pytest.raises(expected) as failure:create(client(handle))
    assert len(calls)==1 and 'private' not in str(failure.value)


@pytest.mark.parametrize('title,content',[(None,'body'),('','body'),(' \t','body'),('x'*129,'body'),('🙂'*129,'body'),('\ud800','body'),('a\0','body'),
    ('title',None),('title',''),('title','\n\t'),('title','x'*8193),('title','中'*2731),('title','a\0'),('title','\ud800')])
def test_invalid_text_rejected_before_any_network(title,content):
    api=client(lambda _:pytest.fail('Invalid arguments reached network'))
    with pytest.raises(ValueError):asyncio.run(api.create(SOURCE,LEASE,SNAPSHOT,title,content))


@pytest.mark.parametrize('patch',[dict(tenant='TENANT_B'),dict(fence=True),dict(fence=0),dict(fence=9223372036854775808),dict(owner='../worker'),dict(run_id='bad')])
def test_invalid_lease_rejected_before_any_network(patch):
    with pytest.raises(ValueError):asyncio.run(client(lambda _:pytest.fail()).create(SOURCE,replace(LEASE,**patch),SNAPSHOT,TITLE,CONTENT))


def test_java_blank_semantics_and_exact_maximum_utf8_text():
    # Java Character.isWhitespace excludes NBSP, figure space, narrow NBSP and NEL.
    for title in ('\u00a0','\u2007','\u202f','\u0085','🙂'*128):assert arguments_hash(SOURCE,RUN,SNAPSHOT,title,'中'*2730+'xx')
    for title in ('\u1680','\u2000','\u2028','\u3000','\x1c'):
        with pytest.raises(ValueError):arguments_hash(SOURCE,RUN,SNAPSHOT,title,'body')


def test_maximum_unicode_and_body_can_cross_transport_without_truncation():
    title,content='🙂'*128,'中'*2730+'xx';calls=[]
    def handle(request):
        calls.append(request);body=json.loads(request.content)
        assert body['title']==title and body['content']==content and len(request.content)>8192
        return response(data(arguments_sha256=arguments_hash(SOURCE,RUN,SNAPSHOT,title,content)))
    result=asyncio.run(client(handle).create(SOURCE,LEASE,SNAPSHOT,title,content));assert result.ticket_id>2**53 and len(calls)==1


@pytest.mark.parametrize('method,resource,prefix,body',[
    ('POST','tickets/create','TICKET_CREATE',b'{}'),('POST','ticket-create','REPLY',b'{}'),
    ('GET','ticket-create','TICKET_CREATE',b'{}'),('DELETE','ticket-create','TICKET_CREATE',b'')])
def test_transport_addition_does_not_open_other_paths_prefixes_methods_or_get_bodies(method,resource,prefix,body):
    with pytest.raises(ValueError):asyncio.run(client(lambda _:pytest.fail())._exchange(method,SOURCE,RUN,resource,body,prefix=prefix))


@pytest.mark.parametrize('value',['1','9223372036854775807'])
def test_canonical_int64_boundary_values_remain_exact(value):
    assert create(client(lambda _:response(data(ticket_id=value)))).ticket_id==int(value)


@pytest.mark.parametrize('snapshot,digest',[(SNAPSHOT,None),(None,DIGEST),('bad',DIGEST),(SNAPSHOT,'A'*64),(SNAPSHOT,True)])
def test_invalid_receipt_expectations_rejected_before_network(snapshot,digest):
    with pytest.raises(ValueError):asyncio.run(client(lambda _:pytest.fail()).receipt(SOURCE,RUN,expected_snapshot_id=snapshot,expected_hash=digest))


@pytest.mark.parametrize('patch',[dict(timestamp=True),dict(timestamp='01'),dict(timestamp='9223372036854775808'),dict(timestamp=None),dict(requestId=3),dict(requestId='x'*129),dict(extra='private')])
def test_invalid_envelope_metadata_is_rejected(patch):
    with pytest.raises(PermanentFailure):create(client(lambda _:response(**patch)))


def test_malformed_duplicate_nan_non_utf8_oversize_and_wrong_encoding_are_rejected():
    valid=raw();duplicate=valid.replace(b'"ticket_no":"TK_123"',b'"ticket_no":"TK_123","ticket_no":"TK_123"')
    for body in (b' '*8193,b'[]',b'not-json',valid+b'{}',duplicate,valid.replace(b'"code":0',b'"code":NaN'),valid.decode().encode('utf-16')):
        with pytest.raises(PermanentFailure):create(client(lambda _,body=body:httpx.Response(200,headers={'Content-Type':'application/json'},stream=httpx.ByteStream(body))))
    for headers in ({'Content-Type':'text/plain'},{'Content-Type':'application/json','Content-Encoding':'gzip'}):
        with pytest.raises(PermanentFailure):create(client(lambda _,headers=headers:httpx.Response(200,headers=headers,stream=httpx.ByteStream(valid))))


def test_network_failure_does_not_copy_url_or_cause_and_has_one_request():
    calls=[]
    def handle(request):calls.append(request);raise httpx.ConnectError('private network cause',request=request)
    with pytest.raises(TransientFailure) as failure:create(client(handle))
    assert failure.value.code=='TICKET_CREATE_NETWORK_ERROR' and failure.value.__cause__ is None and len(calls)==1


@contextmanager
def tcp_server(mode):
    calls=[]
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            calls.append(self.path);self.rfile.read(int(self.headers['Content-Length']))
            if mode=='redirect':
                self.send_response(302);self.send_header('Location',self.path);self.send_header('Content-Length','0');self.end_headers();return
            body=raw();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers()
            if mode=='slow':
                self.wfile.write(body[:1]);self.wfile.flush();time.sleep(2)
                try:self.wfile.write(body[1:])
                except OSError:pass
            else:self.wfile.write(body)
        def log_message(self,*_):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
    thread=Thread(target=lambda:server.serve_forever(poll_interval=.02),daemon=True);thread.start()
    try:yield 'http://127.0.0.1:'+str(server.server_port),calls
    finally:server.shutdown();server.server_close();thread.join(timeout=2)


@pytest.mark.parametrize('mode',['normal','redirect','slow'])
def test_real_tcp_no_proxy_redirect_or_second_post(mode,monkeypatch):
    monkeypatch.setenv('HTTP_PROXY','http://127.0.0.1:1');monkeypatch.setenv('ALL_PROXY','http://127.0.0.1:1');monkeypatch.setenv('NO_PROXY','')
    with tcp_server(mode) as (base,calls):
        api=TicketCreationClient(base,KEY,deadline=1)
        if mode=='normal':assert create(api).ticket_id==9007199254740993
        else:
            with pytest.raises(TransientFailure if mode=='slow' else PermanentFailure) as failure:create(api)
            assert failure.value.code==('TICKET_CREATE_TIMEOUT' if mode=='slow' else 'TICKET_CREATE_PROTOCOL_ERROR')
        assert len(calls)==1
