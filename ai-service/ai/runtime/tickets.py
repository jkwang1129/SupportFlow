"""Bounded status facts from the fixed customer runtime route, never member impersonation."""
from dataclasses import dataclass
from datetime import datetime
import hashlib
import hmac
import json
import re
from uuid import UUID

from .source import _CustomerTransport
from .types import RunInput
from .worker import PermanentFailure


def ticket_digest(source, run_id, snapshot_id, source_hash, ticket_no, status, updated_at):
    canonical = '\n'.join(('supportflow-ticket-status:v1', source.tenant, str(source.source_event_id), str(run_id),
                           str(snapshot_id), source_hash, ticket_no, str(status), updated_at))
    return hashlib.sha256(canonical.encode()).hexdigest()


def ticket_timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?', value):
        raise ValueError('Invalid ticket timestamp')
    datetime.fromisoformat(value)
    return value


@dataclass(frozen=True)
class TicketStatus:
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    source_hash: str
    ticket_no: str
    status: int
    updated_at: str
    resource_version: str

    def result(self):
        return dict(schema_version='ticket-status-v1', source_event_id=str(self.event_id), run_id=str(self.run_id),
                    snapshot_id=str(self.snapshot_id), source_hash=self.source_hash, ticket_no=self.ticket_no,
                    status=self.status, updated_at=self.updated_at, resource_version=self.resource_version)


def decode_ticket_status(value, source, run, ticket_no):
    fields = {'schema_version', 'tenant_code', 'source_event_id', 'run_id', 'snapshot_id', 'source_hash',
              'ticket_no', 'status', 'updated_at', 'resource_version'}
    try:
        if (not isinstance(value, dict) or set(value) != fields or value['schema_version'] != 'ticket-status-v1'
                or value['tenant_code'] != source.tenant or value['source_event_id'] != str(source.source_event_id)
                or value['run_id'] != str(run) or value['ticket_no'] != ticket_no):
            raise ValueError()
        snapshot = UUID(value['snapshot_id'])
        if str(snapshot) != value['snapshot_id'] or type(value['status']) is not int or not 1 <= value['status'] <= 5:
            raise ValueError()
        for key in ('source_hash', 'resource_version'):
            if not isinstance(value[key], str) or not re.fullmatch('[0-9a-f]{64}', value[key]):
                raise ValueError()
        updated = ticket_timestamp(value['updated_at'])
        digest = ticket_digest(source, run, snapshot, value['source_hash'], ticket_no, value['status'], updated)
        if not hmac.compare_digest(digest, value['resource_version']):
            raise ValueError()
        return TicketStatus(run, source.source_event_id, snapshot, value['source_hash'], ticket_no, value['status'], updated, digest)
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
        raise PermanentFailure('TICKET_PAYLOAD_INVALID') from None


class TicketClient(_CustomerTransport):
    async def read(self, source: RunInput, run_id: UUID, ticket_no: str):
        if not isinstance(source, RunInput) or not isinstance(ticket_no, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', ticket_no):
            raise ValueError('Invalid trusted ticket query')
        run = UUID(str(run_id))
        status, raw = await self._exchange('GET', source, run, 'tickets/' + ticket_no, b'', prefix='TICKET', limit=8192,
                                           accepted=(200, 404, 409, 410, 413, 422))
        return self._decode(status, raw, source, run, ticket_no)

    @staticmethod
    def _decode(status, raw, source, run, ticket_no):
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('Duplicate ticket field')
                result[key] = value
            return result
        try:
            root = json.loads(raw, object_pairs_hook=unique)
            if not isinstance(root, dict) or type(root.get('code')) is not int:
                raise ValueError()
            if root['code'] != 0:
                codes = {40402: (404, 'TICKET_SOURCE_REVOKED'), 40404: (404, 'TICKET_REVOKED'), 40902: (409, 'TICKET_SOURCE_CHANGED'),
                         41002: (410, 'TICKET_SOURCE_EXPIRED'), 41302: (413, 'TICKET_SOURCE_TOO_LARGE'),
                         42202: (422, 'TICKET_SOURCE_INVALID'), 42204: (422, 'TICKET_INVALID_STATE')}
                expected = codes.get(root['code'])
                if expected is None or status != expected[0] or root.get('data') is not None:
                    raise ValueError()
                raise PermanentFailure(expected[1])
            if status != 200:
                raise ValueError()
            return decode_ticket_status(root['data'], source, run, ticket_no)
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('TICKET_PAYLOAD_INVALID') from None
