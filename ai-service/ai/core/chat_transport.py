"""Private bounded transport for Chat, Vision and Embedding."""
import asyncio
from contextlib import nullcontext
import json
import math
import os
import re
from urllib.parse import urlsplit

import httpx

from .circuit_breaker import (
    CircuitBreaker,
    CircuitBreakerOpenError,
    default_circuit_breaker_registry,
)

MAX_CHAT_BYTES = 262144
MAX_VISION_REQUEST_BYTES = 19 * 1024 * 1024


def _request_body(payload, limit):
    pending = [(payload, 0)]
    nodes = units = 0
    while pending:
        item, depth = pending.pop()
        nodes += 1
        if depth > 64 or nodes > 16384:
            raise ValueError()
        if type(item) in (dict, list):
            if nodes + len(pending) + len(item) > 16384:
                raise ValueError()
            if type(item) is dict:
                for key in item:
                    if type(key) is not str:
                        raise ValueError()
                    units += len(key)
                children = item.values()
            else:
                children = item
            pending.extend((child, depth + 1) for child in children)
        elif type(item) is str:
            units += len(item)
        elif type(item) is float:
            if not math.isfinite(item):
                raise ValueError()
        elif type(item) is int:
            if not -9223372036854775808 <= item <= 9223372036854775807:
                raise ValueError()
        elif item is not None and type(item) is not bool:
            raise ValueError()
        if units > limit:
            raise ValueError()
    body = bytearray()
    for part in json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(',', ':')).iterencode(payload):
        chunk = part.encode('utf-8')
        if len(chunk) > limit - len(body):
            raise ValueError()
        body.extend(chunk)
    return bytes(body)


class ModelCallFailure(RuntimeError):
    """Stable classification only; never retain provider payload/URL/key as text."""

    def __init__(self, code, *, retryable=False, status_code=None, attempted=True):
        self.code, self.retryable, self.status_code, self.attempted = code, retryable, status_code, attempted
        super().__init__(code)


def _configuration(chosen, timeout):
    try:
        if type(chosen) is not dict or type(timeout) not in (int, float) or not .05 <= timeout <= 300:
            raise ValueError()
        base, key, model = chosen['base_url'], chosen['api_key'], chosen['model']
        if (not isinstance(base, str) or '?' in base or '#' in base or not isinstance(key, str) or not key or len(key) > 4096
                or any(c in key for c in ('\r', '\n', '\0')) or not key.isascii()
                or not isinstance(model, str) or not 1 <= len(model) <= 128 or any(c in model for c in ('\r', '\n', '\0'))):
            raise ValueError()
        url = urlsplit(base)
        _ = url.port
        if url.scheme not in ('http', 'https') or not url.hostname or url.username is not None or url.password is not None:
            raise ValueError()
        if any(part in ('.', '..') or not re.fullmatch('[A-Za-z0-9_.-]*', part) for part in url.path.split('/')):
            raise ValueError()
    except (ValueError, TypeError, KeyError, AttributeError):
        raise ModelCallFailure('MODEL_CONFIGURATION_INVALID', attempted=False) from None
    return base, key, model


async def _send_chat_json(chosen, payload, timeout, *, transport=None, circuit_breaker: CircuitBreaker | None = None):
    return await _send_model_json(chosen, payload, timeout, endpoint='chat/completions',
                                  transport=transport, circuit_breaker=circuit_breaker)


async def _send_vision_json(chosen, payload, timeout, *, transport=None, circuit_breaker: CircuitBreaker | None = None):
    return await _send_model_json(chosen, payload, timeout, endpoint='chat/completions',
                                  transport=transport, request_limit=MAX_VISION_REQUEST_BYTES,
                                  circuit_breaker=circuit_breaker)


async def _send_embedding_json(chosen, payload, timeout, *, transport=None, client=None,
                               circuit_breaker: CircuitBreaker | None = None):
    return await _send_model_json(chosen, payload, timeout, endpoint='embeddings',
                                  transport=transport, client=client, circuit_breaker=circuit_breaker)


def _model_client(timeout, *, transport=None):
    return httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False,
                             timeout=httpx.Timeout(min(30, timeout), connect=min(5, timeout)))


def _resolve_circuit_breaker(chosen: dict, base_url: str, explicit: CircuitBreaker | None) -> CircuitBreaker | None:
    if explicit is not None:
        return explicit
    flag = os.getenv('SUPPORTFLOW_AI_CIRCUIT_BREAKER_ENABLED', '').strip().lower()
    if flag in ('1', 'true', 'yes', 'on'):
        provider = chosen.get('provider')
        key = provider if isinstance(provider, str) and provider.strip() else (urlsplit(base_url).hostname or 'default')
        return default_circuit_breaker_registry.get(key)
    return None


async def _send_model_json(chosen, payload, timeout, *, endpoint, transport=None, request_limit=MAX_CHAT_BYTES,
                           client=None, circuit_breaker: CircuitBreaker | None = None):
    if endpoint not in ('chat/completions', 'embeddings') or type(endpoint) is not str:
        raise ModelCallFailure('MODEL_OPERATION_INVALID', attempted=False)
    base, key, model = _configuration(chosen, timeout)
    try:
        if type(payload) is not dict or payload.get('model') != model:
            raise ValueError()
        body = _request_body(payload, request_limit)
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ModelCallFailure('MODEL_REQUEST_INVALID', attempted=False) from None

    active_breaker = _resolve_circuit_breaker(chosen, base, circuit_breaker)
    if active_breaker is not None:
        active_breaker.before_call()

    try:
        # No hidden retry/fallback. Header and body completion share one deadline.
        async with asyncio.timeout(timeout):
            connection = _model_client(timeout, transport=transport) if client is None else nullcontext(client)
            async with connection as session:
                async with session.stream('POST', base.rstrip('/') + '/' + endpoint,
                                          headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json', 'Accept-Encoding': 'identity'}, content=body) as response:
                    status = response.status_code
                    if status == 429:
                        raise ModelCallFailure('MODEL_RATE_LIMITED', retryable=True, status_code=status)
                    if status in (408, 425) or status >= 500:
                        raise ModelCallFailure('MODEL_UNAVAILABLE', retryable=True, status_code=status)
                    if status in (401, 403):
                        raise ModelCallFailure('MODEL_AUTHORIZATION_DENIED', status_code=status)
                    if status in (400, 404, 422):
                        raise ModelCallFailure('MODEL_REQUEST_REJECTED', status_code=status)
                    if status != 200:
                        raise ModelCallFailure('MODEL_PROTOCOL_ERROR', status_code=status)
                    types = response.headers.get_list('content-type')
                    if (len(types) != 1 or types[0].split(';')[0].strip().lower() != 'application/json'
                            or response.headers.get('content-encoding', 'identity').lower() != 'identity'):
                        raise ModelCallFailure('MODEL_PROTOCOL_ERROR', status_code=status)
                    raw = bytearray()
                    async for chunk in response.aiter_raw():
                        if len(raw) + len(chunk) > MAX_CHAT_BYTES:
                            raise ModelCallFailure('MODEL_RESPONSE_TOO_LARGE')
                        raw.extend(chunk)
    except CircuitBreakerOpenError:
        raise
    except (TimeoutError, httpx.TimeoutException):
        if active_breaker is not None:
            active_breaker.record_failure(transient=True, reason='MODEL_TIMEOUT')
        raise ModelCallFailure('MODEL_TIMEOUT', retryable=True) from None
    except httpx.RequestError:
        if active_breaker is not None:
            active_breaker.record_failure(transient=True, reason='MODEL_NETWORK_ERROR')
        raise ModelCallFailure('MODEL_NETWORK_ERROR', retryable=True) from None
    except ModelCallFailure as exc:
        if active_breaker is not None:
            active_breaker.record_failure(transient=bool(exc.retryable), reason=exc.code)
        raise
    except Exception:
        if active_breaker is not None:
            active_breaker.record_failure(transient=False, reason='MODEL_TRANSPORT_ERROR')
        raise ModelCallFailure('MODEL_TRANSPORT_ERROR') from None

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError()
            result[key] = value
        return result

    def constant(_): raise ValueError()
    try:
        value = json.loads(bytes(raw).decode('utf-8'), object_pairs_hook=unique, parse_constant=constant)
        if type(value) is not dict:
            raise ValueError()
        if active_breaker is not None:
            active_breaker.record_success()
        return value
    except (ValueError, UnicodeError, RecursionError):
        if active_breaker is not None:
            active_breaker.record_failure(transient=False, reason='MODEL_RESPONSE_INVALID')
        raise ModelCallFailure('MODEL_RESPONSE_INVALID') from None
