"""Body-bound, bounded ASGI authentication for durable runtime operations only."""
import hashlib
import hmac
import json
import re
import time

from starlette.requests import Request
from starlette.responses import JSONResponse

HEADER = 'X-SupportFlow-AI-Auth'
AUDIENCE = 'supportflow-ai-runtime:v1'
MAX_BODY_BYTES = 8192
FORMAT = re.compile(r'runtime:(customer|gateway|ops|tenant):([0-9]{1,12}):([a-f0-9]{64})')
RUN_PATH = re.compile(r'/ai/v1/runtime/runs/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}')
AUTHORIZED = 'supportflow.runtime.authorized'


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key] = value
    return result


class RuntimeAuthMiddleware:
    def __init__(self, app, *, prefix, secrets):
        self.app, self.prefix, self.secrets = app, prefix, secrets

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or not scope['path'].startswith(self.prefix + '/ai/v1/runtime/'):
            return await self.app(scope, receive, send)
        request = Request(scope, receive)

        async def reject(code, detail):
            await JSONResponse({'detail': detail}, status_code=code)(scope, receive, send)

        credentials = request.headers.getlist(HEADER)
        tenants = request.headers.getlist('X-Tenant-Code')
        match = FORMAT.fullmatch(credentials[0]) if len(credentials) == 1 else None
        if match is None or len(tenants) != 1:
            return await reject(401, 'Runtime service authentication required')
        caller, expires, signature = match.groups()
        tenant = tenants[0]
        key = self.secrets[caller]
        now = int(time.time())
        if len(key) < 32 or not now < int(expires) <= now + 35:
            return await reject(401, 'Invalid runtime service credential')
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > MAX_BODY_BYTES:
                return await reject(413, 'Runtime request exceeds reference payload limit')
            body.extend(chunk)
        body = bytes(body)
        raw_path = scope.get('raw_path', scope['path'].encode()).decode('ascii', errors='replace')
        query = scope.get('query_string', b'').decode('ascii', errors='replace')
        canonical = '\n'.join((AUDIENCE, caller, expires, scope['method'], raw_path, query, tenant,
                               hashlib.sha256(body).hexdigest()))
        expected = hmac.digest(key, canonical.encode(), 'sha256').hex()
        if not hmac.compare_digest(signature, expected):
            return await reject(401, 'Invalid runtime service credential')
        path = scope['path'][len(self.prefix):]
        allowed = ((scope['method'] == 'POST' and path == '/ai/v1/runtime/runs')
                   or (scope['method'] == 'POST' and path in (
                       '/ai/v1/runtime/runs/ticket-approval-active', '/ai/v1/runtime/runs/ticket-approval-latest-progress'))
                   or (scope['method'] == 'GET' and RUN_PATH.fullmatch(path))
                   or (scope['method'] == 'POST' and path.endswith('/authorize') and RUN_PATH.fullmatch(path[:-10]))
                   or (scope['method'] == 'POST' and path.endswith('/review-ticket-approval')
                       and RUN_PATH.fullmatch(path.removesuffix('/review-ticket-approval')))
                   or (scope['method'] == 'POST' and path.endswith('/ticket-approval-preview')
                       and RUN_PATH.fullmatch(path.removesuffix('/ticket-approval-preview')))
                   or (scope['method'] == 'POST' and path.endswith('/cancel') and RUN_PATH.fullmatch(path[:-7])))
        if caller != 'customer' or not allowed or query or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,15}', tenant) or tenant.upper() == 'PLATFORM':
            return await reject(403, 'Runtime operation is not authorized')
        if scope['method'] == 'GET':
            if body:
                return await reject(400, 'Runtime GET cannot carry a body')
        else:
            content_types = request.headers.getlist('content-type')
            if len(content_types) != 1 or content_types[0].split(';')[0].strip().lower() != 'application/json':
                return await reject(415, 'Runtime request must use JSON')
            try:
                document = json.loads(body, object_pairs_hook=unique_object)
            except (ValueError, UnicodeError, RecursionError):
                return await reject(400, 'Invalid runtime JSON request')
            if not isinstance(document, dict):
                return await reject(400, 'Runtime request must be an object')
            if any(document[field] != tenant for field in ('tenant_code', 'tenantCode') if field in document):
                return await reject(403, 'Runtime tenant does not match service authorization')
        scope[AUTHORIZED] = True
        scope.setdefault('state', {})['runtime_tenant'] = tenant
        delivered = False

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': body, 'more_body': False}
            return await receive()

        return await self.app(scope, replay, send)
