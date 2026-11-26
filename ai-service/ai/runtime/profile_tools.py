"""Bounded customer profile and session history read tools.

Uses the exact HMAC-signed narrow customer runtime routes, strict closed
Pydantic schemas, and fresh current-version ReadAuthorization binding.
"""
import asyncio
from dataclasses import dataclass, field
import hashlib
import hmac
import json
import re
from typing import Literal
from uuid import UUID

import httpx
from pydantic import Field, field_validator, model_validator

from .customer_tools import UUID_PATTERN
from .source import HEADER, _CustomerTransport, credential
from .tickets import ticket_timestamp
from .tools import ReadAuthorization, ReadTool, ToolModel
from .types import RunInput
from .worker import PermanentFailure, TransientFailure

SHA_PATTERN = r'^[0-9a-f]{64}$'
CUSTOMER_NO_PATTERN = r'^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'
SESSION_NO_PATTERN = r'^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'

CUSTOMER_PROFILE_VERSION = 'customer-profile-v1'
SESSION_HISTORY_VERSION = 'session-history-v1'


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def customer_profile_digest(
    source: RunInput,
    run_id: UUID | str,
    snapshot_id: UUID | str,
    source_hash: str,
    *,
    bound: bool,
    customer_no: str | None = None,
    masked_name: str | None = None,
    masked_phone: str | None = None,
    level: int | None = None,
    customer_type: int | None = None,
    points: int | None = None,
    orders_count: int | None = None,
    ticket_count: int | None = None,
    session_count: int | None = None,
    risk_level: int | None = None,
) -> str:
    canonical = '\n'.join((
        'supportflow-customer-profile:v1',
        source.tenant,
        str(source.source_event_id),
        str(run_id),
        str(snapshot_id),
        source_hash,
        'true' if bound else 'false',
        '' if customer_no is None else customer_no,
        '' if masked_name is None else masked_name,
        '' if masked_phone is None else masked_phone,
        '' if level is None else str(level),
        '' if customer_type is None else str(customer_type),
        '' if points is None else str(points),
        '' if orders_count is None else str(orders_count),
        '' if ticket_count is None else str(ticket_count),
        '' if session_count is None else str(session_count),
        '' if risk_level is None else str(risk_level),
    ))
    return _sha256(canonical)


def session_history_digest(
    source: RunInput,
    run_id: UUID | str,
    snapshot_id: UUID | str,
    source_hash: str,
    *,
    bound: bool,
    sessions: list[dict] | tuple[dict, ...],
    limit: int | None = None,
) -> str:
    parts = [
        'supportflow-session-history:v1' if limit is None else 'supportflow-session-history-tool:v1',
        source.tenant,
        str(source.source_event_id),
        str(run_id),
        str(snapshot_id),
        source_hash,
        'true' if bound else 'false',
    ]
    if limit is not None:
        parts.append(str(limit))
    parts.append(str(len(sessions)))
    for session in sessions:
        ended = session.get('ended_at')
        messages = session['messages']
        parts.extend((
            session['session_no'],
            str(session['status']),
            session['started_at'],
            '' if ended is None else ended,
            str(len(messages)),
        ))
        for msg in messages:
            parts.extend((
                str(msg['seq']),
                str(msg['sender_type']),
                _sha256(msg['content']),
                msg['sent_at'],
            ))
    return _sha256('\n'.join(parts))


class _ProfileRuntimeTransport(_CustomerTransport):
    """Signs narrow GET routes for customer-profile and session-history."""

    async def _get_resource(self, source: RunInput, run_id: UUID, resource: str, *, prefix: str, limit: int):
        if resource not in ('customer-profile', 'session-history'):
            raise ValueError('Unsupported customer runtime read resource')
        if not isinstance(source, RunInput):
            raise ValueError('Trusted runtime source input required')
        run = UUID(str(run_id))
        target = (
            f'{self.base_url}/api/customer/internal/runtime/events/'
            f'{source.source_event_id}/runs/{run}/{resource}'
        )
        raw_path = httpx.URL(target).raw_path.decode('ascii')
        headers = {
            HEADER: credential(self._secret, 'GET', raw_path, source.tenant, b''),
            'X-Tenant-Code': source.tenant,
            'X-Request-Id': source.trace_id,
            'Accept-Encoding': 'identity',
            'Content-Type': 'application/json',
        }
        accepted = (200, 404, 409, 410, 413, 422)
        try:
            async with asyncio.timeout(self.deadline):
                async with httpx.AsyncClient(
                    transport=self.transport,
                    trust_env=False,
                    follow_redirects=False,
                    timeout=httpx.Timeout(min(5, self.deadline), connect=min(2, self.deadline)),
                ) as client:
                    async with client.stream('GET', target, headers=headers, content=b'') as response:
                        if response.status_code in (408, 425, 429) or response.status_code >= 500:
                            raise TransientFailure(prefix + '_UNAVAILABLE')
                        if response.status_code in (401, 403):
                            raise PermanentFailure(prefix + '_AUTHORIZATION_DENIED')
                        if response.status_code not in accepted:
                            raise PermanentFailure(prefix + '_PROTOCOL_ERROR')
                        types = response.headers.get_list('content-type')
                        if len(types) != 1 or types[0].split(';')[0].strip().lower() != 'application/json':
                            raise PermanentFailure(prefix + '_PROTOCOL_ERROR')
                        if response.headers.get('content-encoding', 'identity').lower() != 'identity':
                            raise PermanentFailure(prefix + '_PROTOCOL_ERROR')
                        content = bytearray()
                        async for chunk in response.aiter_raw():
                            if len(content) + len(chunk) > limit:
                                raise PermanentFailure(prefix + '_RESPONSE_TOO_LARGE')
                            content.extend(chunk)
        except (TimeoutError, httpx.TimeoutException):
            raise TransientFailure(prefix + '_TIMEOUT') from None
        except httpx.RequestError:
            raise TransientFailure(prefix + '_NETWORK_ERROR') from None
        return response.status_code, bytes(content)


def _decode_envelope(raw: bytes):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field')
            result[key] = item
        return result

    def invalid_constant(_):
        raise ValueError('Invalid JSON constant')

    root = json.loads(raw.decode('utf-8'), object_pairs_hook=unique, parse_constant=invalid_constant)
    required = {'code', 'message', 'data'}
    if (type(root) is not dict or not required <= set(root)
            or set(root) - required - {'requestId', 'timestamp'}
            or type(root['code']) is not int):
        raise ValueError('Invalid runtime envelope')
    return root


@dataclass(frozen=True)
class CustomerProfile:
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    source_hash: str
    bound: bool
    customer_no: str | None
    masked_name: str | None = field(repr=False)
    masked_phone: str | None = field(repr=False)
    level: int | None
    customer_type: int | None
    points: int | None
    orders_count: int | None
    ticket_count: int | None
    session_count: int | None
    risk_level: int | None
    resource_version: str

    def result(self) -> dict:
        return dict(
            schema_version=CUSTOMER_PROFILE_VERSION,
            source_event_id=str(self.event_id),
            run_id=str(self.run_id),
            snapshot_id=str(self.snapshot_id),
            source_hash=self.source_hash,
            bound=self.bound,
            customer_no=self.customer_no,
            masked_name=self.masked_name,
            masked_phone=self.masked_phone,
            level=self.level,
            customer_type=self.customer_type,
            points=self.points,
            orders_count=self.orders_count,
            ticket_count=self.ticket_count,
            session_count=self.session_count,
            risk_level=self.risk_level,
            resource_version=self.resource_version,
        )


def decode_customer_profile(value: dict, source: RunInput, run: UUID) -> CustomerProfile:
    fields = {
        'schema_version', 'tenant_code', 'source_event_id', 'run_id', 'snapshot_id', 'source_hash',
        'bound', 'customer_no', 'masked_name', 'masked_phone', 'level', 'customer_type',
        'points', 'orders_count', 'ticket_count', 'session_count', 'risk_level', 'resource_version',
    }
    try:
        if (type(value) is not dict or set(value) != fields
                or value['schema_version'] != CUSTOMER_PROFILE_VERSION
                or value['tenant_code'] != source.tenant
                or value['source_event_id'] != str(source.source_event_id)
                or value['run_id'] != str(run)
                or type(value['bound']) is not bool):
            raise ValueError()
        snapshot = UUID(value['snapshot_id'])
        if str(snapshot) != value['snapshot_id']:
            raise ValueError()
        for key in ('source_hash', 'resource_version'):
            if type(value[key]) is not str or not re.fullmatch(r'[0-9a-f]{64}', value[key]):
                raise ValueError()
        bound = value['bound']
        if not bound:
            nullable_keys = (
                'customer_no', 'masked_name', 'masked_phone', 'level', 'customer_type',
                'points', 'orders_count', 'ticket_count', 'session_count', 'risk_level',
            )
            if any(value[k] is not None for k in nullable_keys):
                raise ValueError()
        else:
            customer_no = value['customer_no']
            if type(customer_no) is not str or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', customer_no):
                raise ValueError()
            masked_name = value['masked_name']
            if masked_name is not None:
                if (type(masked_name) is not str or len(masked_name) < 2 or len(masked_name) > 16
                        or not masked_name.endswith('*') or '\n' in masked_name or '\0' in masked_name):
                    raise ValueError()
            masked_phone = value['masked_phone']
            if masked_phone is not None:
                if (type(masked_phone) is not str or len(masked_phone) < 4 or len(masked_phone) > 16
                        or '****' not in masked_phone or '\n' in masked_phone or '\0' in masked_phone):
                    raise ValueError()
            if type(value['level']) is not int or not 1 <= value['level'] <= 5:
                raise ValueError()
            if type(value['customer_type']) is not int or not 1 <= value['customer_type'] <= 2:
                raise ValueError()
            if type(value['risk_level']) is not int or not 1 <= value['risk_level'] <= 3:
                raise ValueError()
            for count_key in ('points', 'orders_count', 'ticket_count', 'session_count'):
                item = value[count_key]
                if type(item) is not int or not 0 <= item <= 2147483647:
                    raise ValueError()
        digest = customer_profile_digest(
            source, run, snapshot, value['source_hash'],
            bound=bound,
            customer_no=value['customer_no'],
            masked_name=value['masked_name'],
            masked_phone=value['masked_phone'],
            level=value['level'],
            customer_type=value['customer_type'],
            points=value['points'],
            orders_count=value['orders_count'],
            ticket_count=value['ticket_count'],
            session_count=value['session_count'],
            risk_level=value['risk_level'],
        )
        if not hmac.compare_digest(digest, value['resource_version']):
            raise ValueError()
        return CustomerProfile(
            run_id=run,
            event_id=source.source_event_id,
            snapshot_id=snapshot,
            source_hash=value['source_hash'],
            bound=bound,
            customer_no=value['customer_no'],
            masked_name=value['masked_name'],
            masked_phone=value['masked_phone'],
            level=value['level'],
            customer_type=value['customer_type'],
            points=value['points'],
            orders_count=value['orders_count'],
            ticket_count=value['ticket_count'],
            session_count=value['session_count'],
            risk_level=value['risk_level'],
            resource_version=digest,
        )
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
        raise PermanentFailure('CUSTOMER_PROFILE_PAYLOAD_INVALID') from None


class CustomerProfileClient(_ProfileRuntimeTransport):
    async def read(self, source: RunInput, run_id: UUID) -> CustomerProfile:
        if not isinstance(source, RunInput):
            raise ValueError('Invalid trusted customer profile query')
        run = UUID(str(run_id))
        status, raw = await self._get_resource(
            source, run, 'customer-profile', prefix='CUSTOMER_PROFILE', limit=8192)
        return self._decode(status, raw, source, run)

    @staticmethod
    def _decode(status: int, raw: bytes, source: RunInput, run: UUID) -> CustomerProfile:
        try:
            root = _decode_envelope(raw)
            if root['code'] != 0:
                codes = {
                    40402: (404, 'CUSTOMER_PROFILE_SOURCE_REVOKED'),
                    40407: (404, 'CUSTOMER_PROFILE_REVOKED'),
                    40902: (409, 'CUSTOMER_PROFILE_SOURCE_CHANGED'),
                    41002: (410, 'CUSTOMER_PROFILE_SOURCE_EXPIRED'),
                    41302: (413, 'CUSTOMER_PROFILE_SOURCE_TOO_LARGE'),
                    42202: (422, 'CUSTOMER_PROFILE_SOURCE_INVALID'),
                    42207: (422, 'CUSTOMER_PROFILE_INVALID_STATE'),
                }
                expected = codes.get(root['code'])
                if expected is None or status != expected[0] or root.get('data') is not None:
                    raise ValueError()
                raise PermanentFailure(expected[1])
            if status != 200:
                raise ValueError()
            return decode_customer_profile(root['data'], source, run)
        except PermanentFailure:
            raise
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('CUSTOMER_PROFILE_PAYLOAD_INVALID') from None


@dataclass(frozen=True)
class SessionHistory:
    tenant: str
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    source_hash: str
    bound: bool
    sessions: tuple[dict, ...] = field(repr=False)
    wire_resource_version: str

    def for_limit(self, limit: int) -> 'BoundedSessionHistory':
        if type(limit) is not int or not 1 <= limit <= 5:
            raise ValueError('Invalid session history limit')
        sliced = tuple(
            dict(
                session_no=item['session_no'],
                status=item['status'],
                started_at=item['started_at'],
                ended_at=item['ended_at'],
                messages=[dict(msg) for msg in item['messages']],
            )
            for item in self.sessions[:limit]
        )
        source_stub = _DigestSource(self.tenant, self.event_id)
        digest = session_history_digest(
            source_stub,
            self.run_id,
            self.snapshot_id,
            self.source_hash,
            bound=self.bound,
            sessions=sliced,
            limit=limit,
        )
        return BoundedSessionHistory(
            run_id=self.run_id,
            event_id=self.event_id,
            snapshot_id=self.snapshot_id,
            source_hash=self.source_hash,
            bound=self.bound,
            limit=limit,
            sessions=sliced,
            resource_version=digest,
        )


@dataclass(frozen=True)
class _DigestSource:
    tenant: str
    source_event_id: UUID


@dataclass(frozen=True)
class BoundedSessionHistory:
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    source_hash: str
    bound: bool
    limit: int
    sessions: tuple[dict, ...] = field(repr=False)
    resource_version: str

    def result(self) -> dict:
        return dict(
            schema_version=SESSION_HISTORY_VERSION,
            source_event_id=str(self.event_id),
            run_id=str(self.run_id),
            snapshot_id=str(self.snapshot_id),
            source_hash=self.source_hash,
            bound=self.bound,
            limit=self.limit,
            sessions=[
                dict(
                    session_no=item['session_no'],
                    status=item['status'],
                    started_at=item['started_at'],
                    ended_at=item['ended_at'],
                    messages=[dict(msg) for msg in item['messages']],
                )
                for item in self.sessions
            ],
            resource_version=self.resource_version,
        )


def decode_session_history(value: dict, source: RunInput, run: UUID) -> SessionHistory:
    fields = {
        'schema_version', 'tenant_code', 'source_event_id', 'run_id',
        'snapshot_id', 'source_hash', 'bound', 'sessions', 'resource_version',
    }
    try:
        if (type(value) is not dict or set(value) != fields
                or value['schema_version'] != SESSION_HISTORY_VERSION
                or value['tenant_code'] != source.tenant
                or value['source_event_id'] != str(source.source_event_id)
                or value['run_id'] != str(run)
                or type(value['bound']) is not bool):
            raise ValueError()
        snapshot = UUID(value['snapshot_id'])
        if str(snapshot) != value['snapshot_id']:
            raise ValueError()
        for key in ('source_hash', 'resource_version'):
            if type(value[key]) is not str or not re.fullmatch(r'[0-9a-f]{64}', value[key]):
                raise ValueError()
        bound = value['bound']
        raw_sessions = value['sessions']
        if type(raw_sessions) is not list or len(raw_sessions) > 5 or (not bound and len(raw_sessions) > 1):
            raise ValueError()
        checked_sessions = []
        for item in raw_sessions:
            if type(item) is not dict or set(item) != {'session_no', 'status', 'started_at', 'ended_at', 'messages'}:
                raise ValueError()
            session_no = item['session_no']
            if type(session_no) is not str or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}', session_no):
                raise ValueError()
            if type(item['status']) is not int or not 1 <= item['status'] <= 4:
                raise ValueError()
            started_at = ticket_timestamp(item['started_at'])
            ended_at = None if item['ended_at'] is None else ticket_timestamp(item['ended_at'])
            raw_msgs = item['messages']
            if type(raw_msgs) is not list or len(raw_msgs) > 3:
                raise ValueError()
            checked_msgs = []
            last_seq = 0
            for msg in raw_msgs:
                if type(msg) is not dict or set(msg) != {'seq', 'sender_type', 'content', 'sent_at'}:
                    raise ValueError()
                seq = msg['seq']
                if type(seq) is not int or not 1 <= seq <= 9223372036854775807 or seq <= last_seq:
                    raise ValueError()
                last_seq = seq
                if type(msg['sender_type']) is not int or not 1 <= msg['sender_type'] <= 4:
                    raise ValueError()
                content = msg['content']
                if (type(content) is not str or not content.strip() or '\0' in content
                        or len(content) > 240 or len(content.encode('utf-8')) > 960):
                    raise ValueError()
                sent_at = ticket_timestamp(msg['sent_at'])
                checked_msgs.append(dict(seq=seq, sender_type=msg['sender_type'], content=content, sent_at=sent_at))
            checked_sessions.append(dict(
                session_no=session_no,
                status=item['status'],
                started_at=started_at,
                ended_at=ended_at,
                messages=checked_msgs,
            ))
        wire_digest = session_history_digest(
            source, run, snapshot, value['source_hash'],
            bound=bound, sessions=checked_sessions, limit=None,
        )
        if not hmac.compare_digest(wire_digest, value['resource_version']):
            raise ValueError()
        return SessionHistory(
            tenant=source.tenant,
            run_id=run,
            event_id=source.source_event_id,
            snapshot_id=snapshot,
            source_hash=value['source_hash'],
            bound=bound,
            sessions=tuple(checked_sessions),
            wire_resource_version=wire_digest,
        )
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
        raise PermanentFailure('SESSION_HISTORY_PAYLOAD_INVALID') from None


class SessionHistoryClient(_ProfileRuntimeTransport):
    async def read(self, source: RunInput, run_id: UUID) -> SessionHistory:
        if not isinstance(source, RunInput):
            raise ValueError('Invalid trusted session history query')
        run = UUID(str(run_id))
        status, raw = await self._get_resource(
            source, run, 'session-history', prefix='SESSION_HISTORY', limit=32768)
        return self._decode(status, raw, source, run)

    @staticmethod
    def _decode(status: int, raw: bytes, source: RunInput, run: UUID) -> SessionHistory:
        try:
            root = _decode_envelope(raw)
            if root['code'] != 0:
                codes = {
                    40402: (404, 'SESSION_HISTORY_SOURCE_REVOKED'),
                    40408: (404, 'SESSION_HISTORY_REVOKED'),
                    40902: (409, 'SESSION_HISTORY_SOURCE_CHANGED'),
                    41002: (410, 'SESSION_HISTORY_SOURCE_EXPIRED'),
                    41302: (413, 'SESSION_HISTORY_SOURCE_TOO_LARGE'),
                    42202: (422, 'SESSION_HISTORY_SOURCE_INVALID'),
                    42208: (422, 'SESSION_HISTORY_INVALID_STATE'),
                }
                expected = codes.get(root['code'])
                if expected is None or status != expected[0] or root.get('data') is not None:
                    raise ValueError()
                raise PermanentFailure(expected[1])
            if status != 200:
                raise ValueError()
            return decode_session_history(root['data'], source, run)
        except PermanentFailure:
            raise
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('SESSION_HISTORY_PAYLOAD_INVALID') from None


class CustomerProfileArguments(ToolModel):
    """Model cannot override tenant, session, or customer identity."""


class CustomerProfileResult(ToolModel):
    schema_version: Literal['customer-profile-v1']
    source_event_id: str = Field(pattern=UUID_PATTERN)
    run_id: str = Field(pattern=UUID_PATTERN)
    snapshot_id: str = Field(pattern=UUID_PATTERN)
    source_hash: str = Field(pattern=SHA_PATTERN)
    bound: bool
    customer_no: str | None = Field(default=None, pattern=CUSTOMER_NO_PATTERN)
    masked_name: str | None = Field(default=None, min_length=2, max_length=16, repr=False)
    masked_phone: str | None = Field(default=None, min_length=4, max_length=16, repr=False)
    level: int | None = Field(default=None, ge=1, le=5)
    customer_type: int | None = Field(default=None, ge=1, le=2)
    points: int | None = Field(default=None, ge=0, le=2147483647)
    orders_count: int | None = Field(default=None, ge=0, le=2147483647)
    ticket_count: int | None = Field(default=None, ge=0, le=2147483647)
    session_count: int | None = Field(default=None, ge=0, le=2147483647)
    risk_level: int | None = Field(default=None, ge=1, le=3)
    resource_version: str = Field(pattern=SHA_PATTERN)

    @model_validator(mode='after')
    def check_binding_invariants(self):
        bound_fields = (
            self.customer_no, self.level, self.customer_type, self.points,
            self.orders_count, self.ticket_count, self.session_count, self.risk_level,
        )
        if not self.bound:
            if any(v is not None for v in bound_fields) or self.masked_name is not None or self.masked_phone is not None:
                raise ValueError('Unbound anonymous projection cannot carry customer fields')
        else:
            if any(v is None for v in bound_fields):
                raise ValueError('Bound customer profile requires non-null summary fields')
            if self.masked_name is not None and (not self.masked_name.endswith('*') or '\n' in self.masked_name or '\0' in self.masked_name):
                raise ValueError('Customer name must be redacted')
            if self.masked_phone is not None and ('****' not in self.masked_phone or '\n' in self.masked_phone or '\0' in self.masked_phone):
                raise ValueError('Customer phone must be redacted')
        return self


def customer_profile_tool(client: CustomerProfileClient, *, timeout_seconds: float = 3) -> ReadTool:
    if not isinstance(client, CustomerProfileClient):
        raise ValueError('Configured authenticated CustomerProfileClient required')

    def load(identity, deadline):
        async def bounded():
            async with asyncio.timeout(deadline.remaining()):
                return await client.read(identity.source, identity.run_id)
        return asyncio.run(bounded())

    def _resource_ref(bound: bool, customer_no: str | None) -> str:
        return customer_no if bound and customer_no else 'visitor:anonymous'

    def authorize(identity, arguments, deadline):
        value = load(identity, deadline)
        return ReadAuthorization({
            'resource_ref': _resource_ref(value.bound, value.customer_no),
            'input_ref': str(value.snapshot_id),
            'version': CUSTOMER_PROFILE_VERSION,
            'arguments_sha256': value.resource_version,
            'business_ref': value.source_hash,
        })

    def invoke(identity, arguments, deadline):
        return load(identity, deadline).result()

    def verify(identity, arguments, result, grant):
        proof = grant.references
        expected_digest = customer_profile_digest(
            identity.source,
            identity.run_id,
            result['snapshot_id'],
            result['source_hash'],
            bound=result['bound'],
            customer_no=result['customer_no'],
            masked_name=result['masked_name'],
            masked_phone=result['masked_phone'],
            level=result['level'],
            customer_type=result['customer_type'],
            points=result['points'],
            orders_count=result['orders_count'],
            ticket_count=result['ticket_count'],
            session_count=result['session_count'],
            risk_level=result['risk_level'],
        )
        return (
            result['source_event_id'] == str(identity.source.source_event_id)
            and result['run_id'] == str(identity.run_id)
            and _resource_ref(result['bound'], result['customer_no']) == proof.get('resource_ref')
            and result['snapshot_id'] == proof.get('input_ref')
            and result['source_hash'] == proof.get('business_ref')
            and proof.get('version') == CUSTOMER_PROFILE_VERSION
            and result['resource_version'] == proof.get('arguments_sha256')
            and hmac.compare_digest(expected_digest, result['resource_version'])
        )

    return ReadTool(
        'get_customer_profile',
        CUSTOMER_PROFILE_VERSION,
        'customer:profile:read',
        CustomerProfileArguments,
        CustomerProfileResult,
        authorize,
        invoke,
        verify,
        timeout_seconds,
        cache_policy='current-version',
    )


class SessionHistoryArguments(ToolModel):
    limit: int = Field(default=3, ge=1, le=5)


class SessionMessageItem(ToolModel):
    seq: int = Field(ge=1, le=9223372036854775807)
    sender_type: int = Field(ge=1, le=4)
    content: str = Field(min_length=1, max_length=240, repr=False)
    sent_at: str = Field(max_length=26)

    @field_validator('content')
    @classmethod
    def bounded_content(cls, value: str) -> str:
        if not value.strip() or '\0' in value or len(value) > 240 or len(value.encode('utf-8')) > 960:
            raise ValueError('Invalid session history message content')
        return value

    @field_validator('sent_at')
    @classmethod
    def valid_sent_at(cls, value: str) -> str:
        return ticket_timestamp(value)


class SessionHistoryItem(ToolModel):
    session_no: str = Field(pattern=SESSION_NO_PATTERN)
    status: int = Field(ge=1, le=4)
    started_at: str = Field(max_length=26)
    ended_at: str | None = Field(default=None, max_length=26)
    messages: list[SessionMessageItem] = Field(max_length=3, repr=False)

    @field_validator('started_at')
    @classmethod
    def valid_started_at(cls, value: str) -> str:
        return ticket_timestamp(value)

    @field_validator('ended_at')
    @classmethod
    def valid_ended_at(cls, value: str | None) -> str | None:
        return None if value is None else ticket_timestamp(value)


class SessionHistoryResult(ToolModel):
    schema_version: Literal['session-history-v1']
    source_event_id: str = Field(pattern=UUID_PATTERN)
    run_id: str = Field(pattern=UUID_PATTERN)
    snapshot_id: str = Field(pattern=UUID_PATTERN)
    source_hash: str = Field(pattern=SHA_PATTERN)
    bound: bool
    limit: int = Field(ge=1, le=5)
    sessions: list[SessionHistoryItem] = Field(max_length=5, repr=False)
    resource_version: str = Field(pattern=SHA_PATTERN)

    @model_validator(mode='after')
    def check_session_bounds(self):
        if len(self.sessions) > self.limit:
            raise ValueError('Session history exceeds requested limit')
        if not self.bound and len(self.sessions) > 1:
            raise ValueError('Unbound visitor history cannot include multiple sessions')
        return self


def session_history_tool(client: SessionHistoryClient, *, timeout_seconds: float = 3) -> ReadTool:
    if not isinstance(client, SessionHistoryClient):
        raise ValueError('Configured authenticated SessionHistoryClient required')

    def load(identity, arguments, deadline):
        async def bounded():
            async with asyncio.timeout(deadline.remaining()):
                history = await client.read(identity.source, identity.run_id)
                return history.for_limit(arguments['limit'])
        return asyncio.run(bounded())

    def authorize(identity, arguments, deadline):
        value = load(identity, arguments, deadline)
        return ReadAuthorization({
            'resource_ref': identity.source.session_no,
            'input_ref': str(value.snapshot_id),
            'version': SESSION_HISTORY_VERSION,
            'arguments_sha256': value.resource_version,
            'business_ref': value.source_hash,
        })

    def invoke(identity, arguments, deadline):
        return load(identity, arguments, deadline).result()

    def verify(identity, arguments, result, grant):
        proof = grant.references
        expected_digest = session_history_digest(
            identity.source,
            identity.run_id,
            result['snapshot_id'],
            result['source_hash'],
            bound=result['bound'],
            sessions=result['sessions'],
            limit=result['limit'],
        )
        return (
            result['source_event_id'] == str(identity.source.source_event_id)
            and result['run_id'] == str(identity.run_id)
            and identity.source.session_no == proof.get('resource_ref')
            and result['limit'] == arguments['limit']
            and len(result['sessions']) <= arguments['limit']
            and (result['bound'] or len(result['sessions']) <= 1)
            and result['snapshot_id'] == proof.get('input_ref')
            and result['source_hash'] == proof.get('business_ref')
            and proof.get('version') == SESSION_HISTORY_VERSION
            and result['resource_version'] == proof.get('arguments_sha256')
            and hmac.compare_digest(expected_digest, result['resource_version'])
        )

    return ReadTool(
        'get_session_history',
        SESSION_HISTORY_VERSION,
        'session:history:read',
        SessionHistoryArguments,
        SessionHistoryResult,
        authorize,
        invoke,
        verify,
        timeout_seconds,
        cache_policy='current-version',
    )
