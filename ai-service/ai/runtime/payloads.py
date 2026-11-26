"""Bounded immutable JSON payloads. Integrity is not semantic trust or action authorization."""
from dataclasses import dataclass, field
from datetime import datetime
import hashlib
import json
import math
from uuid import UUID

SCHEMAS = {'model-output-v1': ('MODEL', 'NONE'), 'tool-output-v1': ('TOOL', 'READ'),
           'working-state-v1': ('INTERNAL', 'NONE')}
MAX_BYTES = 65536


@dataclass(frozen=True)
class StepPayload:
    payload_id: UUID
    run_id: UUID
    step_no: int
    schema: str
    payload_hash: str
    value: dict = field(repr=False)
    created_at: datetime
    expires_at: datetime


def encode(schema, value):
    if not isinstance(schema, str) or schema not in SCHEMAS or type(value) is not dict:
        raise ValueError('Invalid runtime payload')
    pending = [(value, 0)]
    seen = units = 0
    while pending:
        item, depth = pending.pop()
        seen += 1
        if depth > 32 or seen > 4096:
            raise ValueError('Runtime payload structure exceeds limits')
        if type(item) in (dict, list):
            if seen + len(pending) + len(item) > 4096:
                raise ValueError('Runtime payload structure exceeds limits')
            if type(item) is dict:
                for key in item:
                    if type(key) is not str or '\0' in key:
                        raise ValueError('Invalid runtime payload key')
                    units += len(key)
                children = item.values()
            else:
                children = item
            pending.extend((child, depth + 1) for child in children)
        elif type(item) is str:
            if '\0' in item:
                raise ValueError('Invalid runtime payload text')
            units += len(item)
        elif type(item) is int:
            if not -9223372036854775808 <= item <= 9223372036854775807:
                raise ValueError('Runtime payload integer exceeds limits')
        elif type(item) is float:
            if not math.isfinite(item):
                raise ValueError('Invalid runtime payload number')
        elif item is not None and type(item) is not bool:
            raise ValueError('Only JSON payload values are allowed')
        if units > MAX_BYTES:
            raise ValueError('Runtime payload exceeds byte limit')
    body = bytearray()
    try:
        for part in json.JSONEncoder(ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(',', ':')).iterencode(value):
            chunk = part.encode('utf-8')
            if len(chunk) > MAX_BYTES - len(body):
                raise ValueError('Runtime payload exceeds byte limit')
            body.extend(chunk)
    except UnicodeError:
        raise ValueError('Invalid runtime payload text') from None
    return body.decode(), hashlib.sha256(body).hexdigest()


def decode(schema, raw, digest):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate runtime payload key')
            result[key] = value
        return result
    if not isinstance(raw, str) or len(raw.encode()) > MAX_BYTES or hashlib.sha256(raw.encode()).hexdigest() != digest:
        raise ValueError('Invalid runtime payload integrity')
    value = json.loads(raw, object_pairs_hook=unique)
    encode(schema, value)  # Same structural bounds apply to data loaded after restart.
    return value
