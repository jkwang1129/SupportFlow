"""One configured MCP read tool, verified by the existing customer authority."""
import asyncio
import json
import re
from urllib.parse import urlsplit
from uuid import UUID, uuid4

import httpx

from ..core.runtime_auth import unique_object
from .tickets import decode_ticket_status
from .types import RunInput, fingerprint
from .worker import PermanentFailure, TransientFailure

_VERSION = '2026-07-28'
_NAME = 'get_ticket_status'
_MAX_RESPONSE_BYTES = 16384


class McpTicketClient:
    def __init__(self, endpoint, secret, *, transport=None):
        parsed = urlsplit(endpoint)
        if (not parsed.hostname or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment or parsed.scheme not in ('http', 'https')
                or parsed.scheme == 'http' and parsed.hostname not in ('127.0.0.1', '::1')
                or not re.fullmatch(r'(?:/[A-Za-z0-9_-]+)*/mcp', parsed.path)):
            raise ValueError('Expected a configured MCP endpoint')
        if type(secret) is not str or not re.fullmatch(r'[A-Za-z0-9._~-]{32,256}', secret):
            raise ValueError('A distinct MCP service credential is required')
        self._endpoint, self._secret, self._transport = endpoint, secret, transport

    def definition_hash(self):
        return fingerprint({'protocol': _VERSION, 'endpoint': self._endpoint, 'tool': _NAME, 'schema': 'ticket-status-v1'})

    async def read(self, source, run_id, ticket_no):
        if not isinstance(source, RunInput) or not isinstance(ticket_no, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', ticket_no):
            raise ValueError('A trusted ticket reference is required')
        run = UUID(str(run_id))
        request_id = str(uuid4())
        headers = {'Accept': 'application/json, text/event-stream', 'Accept-Encoding': 'identity',
                   'MCP-Protocol-Version': _VERSION, 'Mcp-Method': 'tools/call', 'Mcp-Name': _NAME,
                   'Authorization': 'Bearer ' + self._secret}
        body = dict(jsonrpc='2.0', id=request_id, method='tools/call', params={
            'name': _NAME, 'arguments': {'tenant_code': source.tenant, 'source_event_id': str(source.source_event_id),
                                         'run_id': str(run), 'ticket_no': ticket_no},
            '_meta': {'io.modelcontextprotocol/protocolVersion': _VERSION,
                      'io.modelcontextprotocol/clientInfo': {'name': 'SupportFlow', 'version': '1.0.0'},
                      'io.modelcontextprotocol/clientCapabilities': {}},
        })
        try:
            async with asyncio.timeout(3), httpx.AsyncClient(
                    transport=self._transport, trust_env=False, follow_redirects=False, timeout=3) as client:
                async with client.stream('POST', self._endpoint, headers=headers, json=body) as response:
                    if response.status_code in (408, 425, 429) or response.status_code >= 500:
                        raise TransientFailure('MCP_UNAVAILABLE')
                    if response.status_code != 200:
                        raise PermanentFailure('MCP_REQUEST_REJECTED')
                    envelope = await _read_envelope(response)
            if envelope.get('jsonrpc') != '2.0' or envelope.get('id') != request_id:
                raise ValueError('MCP response identity mismatch')
            result = envelope.get('result')
            if 'error' in envelope or not isinstance(result, dict) or result.get('isError', False) is not False:
                raise PermanentFailure('MCP_TOOL_REJECTED')
            if result.get('resultType') != 'complete' or not isinstance(result.get('content'), list):
                raise ValueError('MCP response is not a completed tool result')
            return decode_ticket_status(result.get('structuredContent'), source, run, ticket_no)
        except (TimeoutError, httpx.TimeoutException):
            raise TransientFailure('MCP_TIMEOUT') from None
        except httpx.TransportError:
            raise TransientFailure('MCP_NETWORK_ERROR') from None
        except (ValueError, TypeError, UnicodeError, RecursionError, AttributeError):
            raise PermanentFailure('MCP_PAYLOAD_INVALID') from None


async def _read_envelope(response):
    content_type = response.headers.get('content-type', '').split(';', 1)[0].strip().lower()
    if (response.headers.get('content-encoding', 'identity').lower() != 'identity'
            or content_type not in ('application/json', 'text/event-stream')):
        raise ValueError('Unsupported MCP response representation')
    buffer = bytearray()
    event = []
    size = 0
    async for chunk in response.aiter_bytes():
        size += len(chunk)
        if size > _MAX_RESPONSE_BYTES:
            raise ValueError('MCP response exceeds its size budget')
        buffer.extend(chunk)
        if content_type == 'application/json':
            continue
        while (end := buffer.find(b'\n')) >= 0:
            line = bytes(buffer[:end]).rstrip(b'\r')
            del buffer[:end + 1]
            if line.startswith(b'data:'):
                event.append(line[5:].removeprefix(b' '))
            elif not line and event:
                message = json.loads(b'\n'.join(event), object_pairs_hook=unique_object)
                event.clear()
                if isinstance(message, dict) and 'id' in message:
                    return message
                if (not isinstance(message, dict) or message.get('jsonrpc') != '2.0'
                        or message.get('method') not in ('notifications/progress', 'notifications/message')):
                    raise ValueError('Unexpected MCP stream message')
    if content_type != 'application/json':
        raise ValueError('MCP event stream ended before its result')
    return json.loads(buffer, object_pairs_hook=unique_object)
