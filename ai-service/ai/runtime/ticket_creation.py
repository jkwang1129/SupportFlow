"""Fixed ticket creation protocol. Errors never prove the absence of a business commit."""
from dataclasses import dataclass
import hashlib
import json
import re
from uuid import UUID

from .source import _CustomerTransport
from .types import RunInput, Lease, identifier
from .worker import PermanentFailure

VERSION = 'ticket-create-v1'
_JAVA_WHITESPACE = frozenset(
    '\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000')


def _text(value, points, byte_limit):
    if type(value) is not str or not value or all(c in _JAVA_WHITESPACE for c in value) or '\0' in value or len(value) > points:
        raise ValueError('Invalid ticket text')
    try:
        raw = value.encode('utf-8')
    except UnicodeError:
        raise ValueError('Invalid ticket text') from None
    if len(raw) > byte_limit:
        raise ValueError('Ticket text exceeds byte limit')
    return raw


def arguments_hash(source, run_id, snapshot_id, title, content):
    if not isinstance(source, RunInput):
        raise ValueError('Trusted ticket source required')
    run, snapshot = UUID(str(run_id)), UUID(str(snapshot_id))
    title_bytes, content_bytes = _text(title, 128, 512), _text(content, 8192, 8192)
    canonical = '\n'.join(('supportflow-ticket-create:v1', source.tenant, str(run), str(source.source_event_id), str(snapshot),
                           hashlib.sha256(title_bytes).hexdigest(), hashlib.sha256(content_bytes).hexdigest(), '5', '2', '1'))
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def _positive_id(value):
    if type(value) is not str or not re.fullmatch(r'[1-9][0-9]{0,18}', value) or int(value) > 9223372036854775807:
        raise ValueError()
    return int(value)


def _ticket_no(value):
    if type(value) is not str or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', value):
        raise ValueError()
    return value


def _binding(snapshot, digest):
    if (snapshot is None) != (digest is None):
        raise ValueError('Both receipt expectations are required')
    if snapshot is None:
        return None, None
    canonical = UUID(str(snapshot))
    if str(canonical) != str(snapshot) or type(digest) is not str or not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise ValueError('Invalid receipt expectation')
    return canonical, digest


@dataclass(frozen=True)
class TicketCreationReceipt:
    tenant: str
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    arguments_sha256: str
    ticket_id: int
    ticket_no: str


def receipt_reference(receipt, source, run_id, snapshot, digest):
    """Recheck typed facts before persisting an execution outcome; no current ACL is implied."""
    try:
        expected_snapshot, expected_hash = _binding(snapshot, digest)
        if (expected_snapshot is None or not isinstance(receipt, TicketCreationReceipt) or receipt.tenant != source.tenant or
                receipt.run_id != UUID(str(run_id)) or receipt.event_id != source.source_event_id or
                receipt.snapshot_id != expected_snapshot or receipt.arguments_sha256 != expected_hash or
                type(receipt.ticket_id) is not int or not 1 <= receipt.ticket_id <= 9223372036854775807):
            raise ValueError()
        _ticket_no(receipt.ticket_no)
    except (ValueError, TypeError, AttributeError):
        raise PermanentFailure('TICKET_CREATE_RECEIPT_MISMATCH') from None
    return dict(operation='ticket.create', version=VERSION, outcome='ticket-committed', business_ref='ticket:' + str(receipt.ticket_id),
                resource_ref=receipt.ticket_no, input_ref=str(expected_snapshot), arguments_sha256=expected_hash)


class TicketCreationClient(_CustomerTransport):
    async def create(self, source: RunInput, lease: Lease, snapshot_id: UUID, title: str, content: str):
        if (not isinstance(source, RunInput) or not isinstance(lease, Lease) or lease.tenant != source.tenant or
                type(lease.fence) is not int or not 1 <= lease.fence <= 9223372036854775807):
            raise ValueError('Invalid ticket lease')
        identifier(lease.owner)
        run, snapshot = UUID(str(lease.run_id)), UUID(str(snapshot_id))
        digest = arguments_hash(source, run, snapshot, title, content)
        body = json.dumps(dict(snapshot_id=str(snapshot), owner=lease.owner, fence=lease.fence, arguments_sha256=digest,
                               title=title, content=content, version=VERSION), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        status, raw = await self._exchange('POST', source, run, 'ticket-create', body, prefix='TICKET_CREATE', limit=8192, accepted=(200, 404, 409, 410, 413, 422))
        return self._decode(status, raw, source, run, snapshot, digest)

    async def receipt(self, source: RunInput, run_id: UUID, *, expected_snapshot_id=None, expected_hash=None):
        if not isinstance(source, RunInput):
            raise ValueError('Trusted ticket source required')
        run = UUID(str(run_id))
        snapshot, digest = _binding(expected_snapshot_id, expected_hash)
        status, raw = await self._exchange('GET', source, run, 'ticket-create', b'', prefix='TICKET_CREATE', limit=8192, accepted=(200, 404))
        return self._decode(status, raw, source, run, snapshot, digest, read=True)

    @staticmethod
    def _decode(status, raw, source, run, snapshot, digest, *, read=False):
        def unique(pairs):
            value = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError()
                value[key] = item
            return value

        def invalid_constant(_): raise ValueError()
        try:
            root = json.loads(raw.decode('utf-8'), object_pairs_hook=unique, parse_constant=invalid_constant)
            required = {'code', 'message', 'data'}
            if (type(root) is not dict or not required <= set(root) or set(root) - required - {'requestId', 'timestamp'} or
                    type(root['code']) is not int or type(root['message']) is not str):
                raise ValueError()
            if 'requestId' in root and root['requestId'] is not None and (type(root['requestId']) is not str or len(root['requestId']) > 128):
                raise ValueError()
            if 'timestamp' in root:
                stamp = root['timestamp']
                if type(stamp) is str and re.fullmatch(r'0|[1-9][0-9]{0,18}', stamp):
                    stamp = int(stamp)
                if type(stamp) is not int or not 0 <= stamp <= 9223372036854775807:
                    raise ValueError()
            if read and status == 404 and root['code'] == 40406 and root['data'] is None:
                return None
            if root['code'] != 0:
                codes = {404: (404, 'TICKET_CREATE_AUTHORITY_DENIED'), 409: (409, 'TICKET_CREATE_AUTHORITY_DENIED'),
                         40402: (404, 'TICKET_CREATE_SOURCE_REVOKED'), 40406: (404, 'TICKET_CREATE_RESOURCE_UNAVAILABLE'),
                         40902: (409, 'TICKET_CREATE_SOURCE_CHANGED'), 40906: (409, 'TICKET_CREATE_CONFLICT'),
                         41002: (410, 'TICKET_CREATE_SOURCE_EXPIRED'), 41302: (413, 'TICKET_CREATE_SOURCE_TOO_LARGE'),
                         42202: (422, 'TICKET_CREATE_SOURCE_INVALID'), 42206: (422, 'TICKET_CREATE_RESULT_INVALID')}
                expected = codes.get(root['code'])
                if expected is None or status != expected[0] or root['data'] is not None:
                    raise ValueError()
                raise PermanentFailure(expected[1])
            if status != 200:
                raise ValueError()
            value = root['data']
            fields = {'tenant_code', 'source_event_id', 'run_id', 'snapshot_id',
                      'schema_version', 'operation', 'arguments_sha256', 'ticket_id', 'ticket_no'}
            if type(value) is not dict or set(value) != fields:
                raise ValueError()
            for key, item in dict(tenant_code=source.tenant, source_event_id=str(source.source_event_id), run_id=str(run), schema_version=VERSION, operation='ticket.create').items():
                if value[key] != item:
                    raise ValueError()
            actual_snapshot = UUID(value['snapshot_id'])
            actual_hash = value['arguments_sha256']
            if str(actual_snapshot) != value['snapshot_id'] or type(actual_hash) is not str or not re.fullmatch(r'[0-9a-f]{64}', actual_hash):
                raise ValueError()
            ticket_id, ticket_no = _positive_id(value['ticket_id']), _ticket_no(value['ticket_no'])
            if snapshot is not None and (actual_snapshot != snapshot or actual_hash != digest):
                raise PermanentFailure('TICKET_CREATE_RECEIPT_MISMATCH')
            return TicketCreationReceipt(source.tenant, run, source.source_event_id, actual_snapshot, actual_hash, ticket_id, ticket_no)
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('TICKET_CREATE_PAYLOAD_INVALID') from None
