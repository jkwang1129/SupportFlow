import asyncio
import hashlib
import json
from uuid import UUID, uuid4

import httpx
import pytest

from ai.runtime.source import HEADER, SourceClient, credential
from ai.runtime.types import RunInput
from ai.runtime.worker import PermanentFailure, TransientFailure

KEY = 'source-test-pair-000000000000000000000000000'
RUN = UUID('00000000-0000-0000-0000-000000000002')
INPUT = RunInput('TENANT_A',UUID('00000000-0000-0000-0000-000000000001'),'S1',1,42,'visitor-session:7','trace-1')
PATH = f'/api/customer/internal/runtime/events/{INPUT.source_event_id}/runs/{RUN}/snapshot'


def envelope():
    payload = dict(schema_version='source-v1', trusted_source=dict(tenant_code=INPUT.tenant,source_event_id=str(INPUT.source_event_id),
        run_id=str(RUN),session_no=INPUT.session_no,message_id=str(INPUT.message_id),event_seq=INPUT.event_seq,
        principal_ref=INPUT.principal_ref,customer_ref=None), untrusted_input=dict(role='user',content='测试来源文本'))
    raw = json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':'))
    return dict(code=0,message='成功',data=dict(tenant_code=INPUT.tenant,source_event_id=str(INPUT.source_event_id),run_id=str(RUN),
        snapshot_id=str(uuid4()),schema_version='source-v1',payload_json=raw,payload_hash=hashlib.sha256(raw.encode()).hexdigest(),
        created_at='2026-11-20T01:00:00Z',expires_at='2026-11-20T02:00:00Z'))


def response(value, *, status=200, content_type='application/json'):
    raw = value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode()
    return httpx.Response(status,headers={'Content-Type':content_type},stream=httpx.ByteStream(raw))


def invoke(handler, *, method='capture', deadline=10):
    client = SourceClient('http://customer.test',KEY,transport=httpx.MockTransport(handler),deadline=deadline)
    return asyncio.run(getattr(client,method)(INPUT,RUN))


def test_pinned_wire_credential_matches_the_java_fixture():
    assert credential(KEY,'POST',PATH,INPUT.tenant,b'{}',expires=1700000020) == (
        'runtime:ai:1700000020:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a:'
        '5ac0feea6a414654abe7d1a77056856caa9a75997b5fc3db6bc65486bbda7b9d')


def test_capture_and_read_send_only_signed_run_references_and_hide_payload_from_repr():
    value=envelope(); calls=[]
    def server(request):
        calls.append(request)
        assert request.url.raw_path.decode()==PATH
        assert request.headers['X-Tenant-Code']==INPUT.tenant and request.headers['X-Request-Id']=='trace-1'
        assert 'Authorization' not in request.headers and request.headers['Accept-Encoding']=='identity'
        token=request.headers[HEADER]
        expiry=int(token.split(':')[2])
        assert token==credential(KEY,request.method,PATH,INPUT.tenant,request.content,expires=expiry)
        return response(value)
    captured=invoke(server); loaded=invoke(server,method='read')
    assert captured==loaded and captured.text=='测试来源文本'
    assert calls[0].content==b'{}' and calls[1].content==b''
    assert '测试来源文本' not in repr(captured) and 'trusted_source' not in repr(captured)


@pytest.mark.parametrize('status,kind,code',[(401,PermanentFailure,'SOURCE_AUTHORIZATION_DENIED'),(403,PermanentFailure,'SOURCE_AUTHORIZATION_DENIED'),
    (302,PermanentFailure,'SOURCE_PROTOCOL_ERROR'),(404,PermanentFailure,'SOURCE_PROTOCOL_ERROR'),
    (408,TransientFailure,'SOURCE_UNAVAILABLE'),(429,TransientFailure,'SOURCE_UNAVAILABLE'),(503,TransientFailure,'SOURCE_UNAVAILABLE')])
def test_http_failures_are_classified_without_retries_or_raw_error_retention(status,kind,code):
    calls=[]
    def server(request):
        calls.append(request); return response(b'synthetic-private-dsn',status=status)
    with pytest.raises(kind) as failure: invoke(server)
    assert failure.value.code==code and 'synthetic-private' not in str(failure.value) and len(calls)==1


@pytest.mark.parametrize('code,expected',[(40402,'SOURCE_REVOKED'),(40902,'SOURCE_CHANGED'),(41002,'SOURCE_EXPIRED'),
                                      (41302,'SOURCE_TOO_LARGE'),(42202,'SOURCE_INVALID_INPUT'),(99999,'SOURCE_REJECTED')])
def test_business_rejection_is_stable_and_cannot_be_mistaken_for_context(code,expected):
    with pytest.raises(PermanentFailure) as failure:
        invoke(lambda request: response(dict(code=code,message='synthetic-secret',data=None)))
    assert failure.value.code==expected and 'synthetic-secret' not in str(failure.value)


@pytest.mark.parametrize('field,value',[('tenant_code','TENANT_B'),('run_id',str(uuid4())),('source_event_id',str(uuid4())),
    ('schema_version','future-v9'),('snapshot_id','NOT_A_UUID'),('payload_hash','0'*64),('payload_json','{}'),
    ('created_at','2026-11-20T01:00:00'),('expires_at','2026-11-19T01:00:00Z')])
def test_untrusted_envelopes_cannot_substitute_resources_integrity_version_or_dates(field,value):
    data=envelope(); data['data'][field]=value
    with pytest.raises(PermanentFailure) as failure: invoke(lambda request:response(data))
    assert failure.value.code=='SOURCE_PAYLOAD_INVALID'


@pytest.mark.parametrize('change',[('principal_ref','admin:1'),('message_id','99'),('event_seq',True),('customer_ref','-1')])
def test_even_a_rehashed_payload_must_match_the_persisted_run_authority(change):
    data=envelope(); payload=json.loads(data['data']['payload_json']); payload['trusted_source'][change[0]]=change[1]
    data['data']['payload_json']=json.dumps(payload,ensure_ascii=False)
    data['data']['payload_hash']=hashlib.sha256(data['data']['payload_json'].encode()).hexdigest()
    with pytest.raises(PermanentFailure) as failure: invoke(lambda request:response(data))
    assert failure.value.code=='SOURCE_PAYLOAD_INVALID'


def test_duplicate_json_unknown_envelope_fields_and_invalid_content_type_are_rejected():
    invalid=envelope(); invalid['data']['access_token']='synthetic-secret'
    for raw,ctype in [(b'{"code":1,"code":0,"data":{}}','application/json'),(invalid,'application/json'),(envelope(),'text/plain')]:
        with pytest.raises(PermanentFailure): invoke(lambda request:response(raw,content_type=ctype))
    compressed=response(envelope()); compressed.headers['Content-Encoding']='gzip'
    with pytest.raises(PermanentFailure) as failure: invoke(lambda request:compressed)
    assert failure.value.code=='SOURCE_PROTOCOL_ERROR'


def test_response_size_and_whole_body_deadline_are_enforced():
    with pytest.raises(PermanentFailure) as failure: invoke(lambda request:response(b'x'*131073))
    assert failure.value.code=='SOURCE_RESPONSE_TOO_LARGE'
    class Slow(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b'{'  # Headers and some body arrive; rest must still have a deadline.
            await asyncio.sleep(.3)
            yield b'}'
    def server(request): return httpx.Response(200,headers={'Content-Type':'application/json'},stream=Slow())
    with pytest.raises(TransientFailure) as failure: invoke(server,deadline=.05)
    assert failure.value.code=='SOURCE_TIMEOUT'


def test_network_errors_and_unsafe_configuration_fail_closed():
    def failed(request): raise httpx.ConnectError('synthetic-private-key',request=request)
    with pytest.raises(TransientFailure) as failure: invoke(failed)
    assert failure.value.code=='SOURCE_NETWORK_ERROR' and 'synthetic-private' not in str(failure.value)
    for url in ('file:///tmp/data','http://user:password@customer.test','http://customer.test?tenant=OTHER','http://customer.test/../escape','http://customer.test?','http://customer.test:invalid'):
        with pytest.raises(ValueError): SourceClient(url,KEY)


def test_resume_can_bind_the_previously_persisted_snapshot_identity_and_hash():
    value=envelope()
    client=SourceClient('http://customer.test',KEY,transport=httpx.MockTransport(lambda request:response(value)))
    async def verify():
        first=await client.capture(INPUT,RUN)
        assert await client.read(INPUT,RUN,expected_snapshot_id=first.snapshot_id,expected_hash=first.payload_hash)==first
        for mismatch in ({'expected_snapshot_id':uuid4()},{'expected_hash':'0'*64}):
            with pytest.raises(PermanentFailure) as failure: await client.read(INPUT,RUN,**mismatch)
            assert failure.value.code=='SOURCE_CHANGED'
    asyncio.run(verify())
