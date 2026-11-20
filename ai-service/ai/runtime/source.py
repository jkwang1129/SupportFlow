"""Bounded source loading. Run references authorize a lookup; model text never selects a URL."""
import asyncio
from dataclasses import dataclass, field
from datetime import datetime
import hashlib
import hmac
import json
import re
import time
from urllib.parse import urlsplit
from uuid import UUID

import httpx

from .types import RunInput
from .worker import PermanentFailure, TransientFailure

AUDIENCE = 'supportflow-customer-runtime:v1'
HEADER = 'X-SupportFlow-Customer-Auth'
MAX_RESPONSE = 131072


def credential(secret, method, raw_path, tenant, body, *, expires=None):
    key = secret.encode('utf-8')
    if len(key) < 32:
        raise ValueError('Runtime source credential is not configured')
    expiry = str(int(time.time())+30 if expires is None else expires)
    digest = hashlib.sha256(body).hexdigest()
    canonical = '\n'.join((AUDIENCE, 'ai', expiry, method, raw_path, '', tenant, digest))
    signature = hmac.digest(key, canonical.encode(), 'sha256').hex()
    return f'runtime:ai:{expiry}:{digest}:{signature}'


@dataclass(frozen=True)
class SourceSnapshot:
    snapshot_id: UUID
    payload_hash: str
    payload_json: str = field(repr=False)
    text: str = field(repr=False)
    customer_ref: str | None = field(repr=False)
    created_at: datetime
    expires_at: datetime


class SourceClient:
    def __init__(self, base_url, secret, *, deadline=10, transport=None):
        if not isinstance(base_url,str) or '?' in base_url or '#' in base_url:
            raise ValueError('Invalid customer service base URL')
        try:
            url = urlsplit(base_url)
            _ = url.port
        except ValueError:
            raise ValueError('Invalid customer service base URL') from None
        if url.scheme not in ('http', 'https') or not url.hostname or url.username is not None or url.password is not None or url.query or url.fragment:
            raise ValueError('Expected the configured customer service base URL')
        if any(part and not re.fullmatch(r'[A-Za-z0-9_-]+', part) for part in url.path.split('/')):
            raise ValueError('Invalid customer service context path')
        if len(secret.encode()) < 32 or type(deadline) not in (int, float) or not .05 <= deadline <= 10:
            raise ValueError('Invalid runtime source configuration')
        self.base_url, self._secret, self.deadline, self.transport = base_url.rstrip('/'), secret, deadline, transport

    async def capture(self, source: RunInput, run_id: UUID):
        return await self._request('POST', source, run_id)

    async def read(self, source: RunInput, run_id: UUID, *, expected_snapshot_id=None, expected_hash=None):
        value = await self._request('GET', source, run_id)
        if ((expected_snapshot_id is not None and str(value.snapshot_id) != str(expected_snapshot_id))
                or (expected_hash is not None and value.payload_hash != expected_hash)):
            raise PermanentFailure('SOURCE_CHANGED')
        return value

    async def _request(self, method, source, run_id):
        run_id = UUID(str(run_id))
        target = self.base_url+f'/api/customer/internal/runtime/events/{source.source_event_id}/runs/{run_id}/snapshot'
        raw_path = httpx.URL(target).raw_path.decode('ascii')
        body = b'{}' if method == 'POST' else b''
        headers = {HEADER: credential(self._secret, method, raw_path, source.tenant, body),
                   'X-Tenant-Code': source.tenant, 'X-Request-Id': source.trace_id,
                   'Accept-Encoding': 'identity', 'Content-Type': 'application/json'}
        try:
            # A client belongs to one async operation, not to unrelated Worker
            # asyncio.run() loops. All I/O and body completion share this deadline.
            async with asyncio.timeout(self.deadline):
                async with httpx.AsyncClient(transport=self.transport, trust_env=False, follow_redirects=False,
                                            timeout=httpx.Timeout(min(5,self.deadline), connect=min(2,self.deadline))) as client:
                    async with client.stream(method, target, headers=headers, content=body) as response:
                        if response.status_code in (408, 425, 429) or response.status_code >= 500:
                            raise TransientFailure('SOURCE_UNAVAILABLE')
                        if response.status_code in (401, 403):
                            raise PermanentFailure('SOURCE_AUTHORIZATION_DENIED')
                        if response.status_code != 200:
                            raise PermanentFailure('SOURCE_PROTOCOL_ERROR')
                        types = response.headers.get_list('content-type')
                        if len(types) != 1 or types[0].split(';')[0].strip().lower() != 'application/json':
                            raise PermanentFailure('SOURCE_PROTOCOL_ERROR')
                        if response.headers.get('content-encoding', 'identity').lower() != 'identity':
                            raise PermanentFailure('SOURCE_PROTOCOL_ERROR')
                        content = bytearray()
                        async for chunk in response.aiter_raw():
                            if len(content)+len(chunk) > MAX_RESPONSE:
                                raise PermanentFailure('SOURCE_RESPONSE_TOO_LARGE')
                            content.extend(chunk)
        except (TimeoutError, httpx.TimeoutException):
            raise TransientFailure('SOURCE_TIMEOUT') from None
        except httpx.RequestError:
            raise TransientFailure('SOURCE_NETWORK_ERROR') from None
        return self._decode(bytes(content), source, run_id)

    @staticmethod
    def _decode(content, source, run_id):
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result: raise ValueError('Duplicate JSON field')
                result[key] = value
            return result
        try:
            root = json.loads(content, object_pairs_hook=unique)
            if not isinstance(root,dict) or type(root.get('code')) is not int:
                raise ValueError()
            if root['code'] != 0:
                code = root['code']
                if code == 503: raise TransientFailure('SOURCE_UNAVAILABLE')
                codes = {40402:'SOURCE_REVOKED',40902:'SOURCE_CHANGED',41002:'SOURCE_EXPIRED',41302:'SOURCE_TOO_LARGE',42202:'SOURCE_INVALID_INPUT'}
                raise PermanentFailure(codes.get(code,'SOURCE_REJECTED'))
            value = root['data']
            fields = {'tenant_code','source_event_id','run_id','snapshot_id','schema_version','payload_json','payload_hash','created_at','expires_at'}
            if not isinstance(value,dict) or set(value)!=fields or value['tenant_code']!=source.tenant or value['source_event_id']!=str(source.source_event_id) or value['run_id']!=str(run_id):
                raise ValueError()
            if value['schema_version']!='source-v1' or not isinstance(value['payload_json'],str) or len(value['payload_json'].encode())>65536:
                raise ValueError()
            raw = value['payload_json']
            digest = value['payload_hash']
            if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest) or not hmac.compare_digest(hashlib.sha256(raw.encode()).hexdigest(),digest):
                raise ValueError()
            payload = json.loads(raw,object_pairs_hook=unique)
            if set(payload)!={'schema_version','trusted_source','untrusted_input'} or payload['schema_version']!='source-v1': raise ValueError()
            trusted, untrusted = payload['trusted_source'], payload['untrusted_input']
            expected = dict(tenant_code=source.tenant, source_event_id=str(source.source_event_id),run_id=str(run_id),
                            session_no=source.session_no,message_id=str(source.message_id),event_seq=source.event_seq,principal_ref=source.principal_ref)
            if not isinstance(trusted,dict) or set(trusted)!=set(expected)|{'customer_ref'} or any(trusted[k]!=v for k,v in expected.items()) or type(trusted['event_seq']) is not int:
                raise ValueError()
            customer = trusted['customer_ref']
            if customer is not None and (not isinstance(customer,str) or not re.fullmatch('[1-9][0-9]{0,18}',customer) or int(customer)>9223372036854775807): raise ValueError()
            if not isinstance(untrusted,dict) or set(untrusted)!={'role','content'} or untrusted['role']!='user': raise ValueError()
            text = untrusted['content']
            if not isinstance(text,str) or not text.strip() or len(text.encode())>8192: raise ValueError()
            snapshot_id = UUID(value['snapshot_id'])
            if str(snapshot_id)!=value['snapshot_id']: raise ValueError()
            created = datetime.fromisoformat(value['created_at'].replace('Z','+00:00'))
            expires = datetime.fromisoformat(value['expires_at'].replace('Z','+00:00'))
            if created.tzinfo is None or expires.tzinfo is None or expires<=created: raise ValueError()
            return SourceSnapshot(snapshot_id,digest,raw,text,customer,created,expires)
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('SOURCE_PAYLOAD_INVALID') from None
