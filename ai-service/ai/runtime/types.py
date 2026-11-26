"""Immutable runtime references and limits. No user JWT, API key or prompt payload."""
from dataclasses import asdict, dataclass
import hashlib
import json
import re
from uuid import UUID


def tenant_code(value):
    if not isinstance(value, str) or value.upper() == 'PLATFORM' or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,15}', value):
        raise ValueError('A trusted business tenant is required')
    return value


def identifier(value, limit=64):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,' + str(limit - 1) + '}', value):
        raise ValueError('Invalid runtime reference')
    return value


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def reference(value):
    allowed = {'operation', 'resource_ref', 'input_ref', 'version', 'model',
               'arguments_sha256', 'business_ref', 'outcome', 'error_code'}
    if not isinstance(value, dict) or set(value) - allowed:
        raise ValueError('Only runtime reference fields are permitted')
    for item in value.values():
        identifier(item, 128)
    return dict(value)


@dataclass(frozen=True)
class Limits:
    max_steps: int = 12
    max_model_calls: int = 4
    max_tool_calls: int = 8
    max_retries: int = 2
    wall_seconds: int = 120

    def __post_init__(self):
        for field, upper, lower in [('max_steps', 100, 1), ('max_model_calls', 100, 0), ('max_tool_calls', 100, 0), ('max_retries', 10, 0), ('wall_seconds', 3600, 1)]:
            value = getattr(self, field)
            if type(value) is not int or not lower <= value <= upper:
                raise ValueError('Invalid runtime limit')


@dataclass(frozen=True)
class RunInput:
    tenant: str
    source_event_id: UUID
    session_no: str
    event_seq: int
    message_id: int
    principal_ref: str
    trace_id: str
    runtime_version: str = 'runtime-v1'

    def __post_init__(self):
        tenant_code(self.tenant)
        object.__setattr__(self, 'source_event_id', UUID(str(self.source_event_id)))
        for name, maximum in [('session_no', 64), ('principal_ref', 96), ('trace_id', 64), ('runtime_version', 32)]:
            identifier(getattr(self, name), maximum)
        for value in (self.event_seq, self.message_id):
            if type(value) is not int or not 1 <= value <= 9223372036854775807:
                raise ValueError('A positive business event/message sequence is required')

    def hash(self, limits):
        data = asdict(self)
        data['source_event_id'] = str(self.source_event_id)
        return fingerprint({'input': data, 'limits': asdict(limits)})

    @classmethod
    def from_run(cls, run):
        return cls(run['tenant_code'], run['source_event_id'], run['session_no'],
                   run['event_seq'], run['message_id'], run['principal_ref'], run['trace_id'], run['runtime_version'])


@dataclass(frozen=True)
class Lease:
    tenant: str
    run_id: UUID
    owner: str
    fence: int

    @classmethod
    def from_run(cls, run):
        return cls(run['tenant_code'], run['run_id'], run['owner'], run['fence'])


class RuntimeConflict(RuntimeError):
    pass


class ActionDenied(RuntimeConflict):
    pass


class LostLease(RuntimeError):
    pass


class BudgetExceeded(RuntimeError):
    pass


class ModelCapacityBusy(RuntimeError):
    pass


class ModelTokenQuotaExceeded(RuntimeError):
    pass


class ModelCostRejected(RuntimeError):
    def __init__(self, code):
        self.code = identifier(code)
        super().__init__(self.code)


class QueueFull(RuntimeError):
    pass
