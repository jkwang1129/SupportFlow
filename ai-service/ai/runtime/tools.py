"""Closed, host-registered tools. Model proposals never grant authority.

Adapters must bound their own I/O with Deadline.remaining(); this synchronous
executor does not pretend it can forcibly terminate arbitrary Python code.
"""
from dataclasses import dataclass, field
import json
import re
import time
from types import MappingProxyType
from uuid import UUID, uuid4
from collections.abc import Callable

from pydantic import BaseModel, ConfigDict, ValidationError

from .payloads import encode
from .types import RunInput, fingerprint, identifier, reference
from .worker import PermanentFailure, TransientFailure


class ToolModel(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True, frozen=True, hide_input_in_errors=True)


@dataclass(frozen=True)
class ToolCall:
    name: str
    version: str
    arguments: dict = field(repr=False)

    @classmethod
    def parse(cls, raw):
        """Only the model proposal, with no tenant/principal/endpoint overrides."""
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('Duplicate tool field')
                result[key] = value
            return result

        try:
            if not isinstance(raw, bytes) or len(raw) > 8192:
                raise ValueError()
            value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique)
            if type(value) is not dict or set(value) != {'name', 'version', 'arguments'}:
                raise ValueError()
            return cls(**value)
        except (ValueError, TypeError, RecursionError):
            raise PermanentFailure('TOOL_CALL_INVALID') from None

    def __post_init__(self):
        if not isinstance(self.name, str) or not re.fullmatch(r'[a-z][a-z0-9_]{0,47}', self.name):
            raise ValueError('Invalid registered tool name')
        identifier(self.version, 32)
        raw, _ = encode('tool-output-v1', self.arguments)
        if len(raw.encode()) > 8192:
            raise ValueError('Tool arguments exceed limits')
        # Detach a caller-owned mutable dictionary before validation/execution.
        object.__setattr__(self, 'arguments', json.loads(raw))


@dataclass(frozen=True)
class ToolIdentity:
    source: RunInput
    run_id: UUID

    @classmethod
    def from_context(cls, context):
        run, lease = context.run, context.lease
        source = RunInput(
            run['tenant_code'], run['source_event_id'], run['session_no'],
            run['event_seq'], run['message_id'], run['principal_ref'], run['trace_id'], run['runtime_version'])
        if source.tenant != lease.tenant or run['run_id'] != lease.run_id:
            raise PermanentFailure('TOOL_IDENTITY_INVALID')
        return cls(source, lease.run_id)


class ToolDenied(Exception):
    """Adapter's current resource authorization denied. Details are discarded."""


class ToolUnavailable(Exception):
    """Explicit read-only transient classification, no automatic local retry."""
    def __init__(self, code='TOOL_UNAVAILABLE'):
        if code not in {'TOOL_UNAVAILABLE', 'TOOL_RATE_LIMITED'}:
            raise ValueError('Invalid tool failure classification')
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class ReadAuthorization:
    """Fresh resource/version/hash references from trusted authorization code."""
    references: dict = field(repr=False)

    def __post_init__(self):
        object.__setattr__(self, 'references', MappingProxyType(reference(dict(self.references))))


@dataclass(frozen=True)
class Deadline:
    expires: float

    def remaining(self):
        seconds = self.expires - time.monotonic()
        if seconds <= 0:
            raise TimeoutError()
        return seconds


def _closed_schema(model, *, arguments=False):
    if not isinstance(model, type) or not issubclass(model, ToolModel):
        raise ValueError('Tool contracts require strict ToolModel types')
    if model.model_config.get('extra') != 'forbid' or model.model_config.get('strict') is not True:
        raise ValueError('Tool contracts must reject unknown fields and coercion')
    schema = model.model_json_schema()
    reserved = {'tenant', 'tenant_code', 'principal_ref', 'run_id', 'owner', 'fence',
                'scopes', 'url', 'base_url', 'headers', 'authorization', 'token', 'secret'}
    pending = [schema]
    while pending:
        item = pending.pop()
        if isinstance(item, dict):
            if item.get('type') == 'object':
                if item.get('additionalProperties') is not False:
                    raise ValueError('Nested tool contracts must also be closed')
                if arguments and reserved.intersection(item.get('properties', {})):
                    raise ValueError('Tool arguments cannot override execution authority')
            pending.extend(item.values())
        elif isinstance(item, list):
            pending.extend(item)
    return schema


@dataclass(frozen=True)
class ReadTool:
    name: str
    version: str
    scope: str
    arguments: type[ToolModel]
    result: type[ToolModel]
    authorize: Callable = field(repr=False)
    invoke: Callable = field(repr=False)
    verify: Callable = field(repr=False)
    timeout_seconds: float = 3
    cache_policy: str = 'immutable'

    def __post_init__(self):
        if not isinstance(self.name, str) or not re.fullmatch(r'[a-z][a-z0-9_]{0,47}', self.name):
            raise ValueError('Invalid registered tool name')
        identifier(self.version, 32)
        identifier(self.scope, 64)
        _closed_schema(self.arguments, arguments=True)
        _closed_schema(self.result)
        if not all(callable(callback) for callback in (self.authorize, self.invoke, self.verify)):
            raise ValueError('Current resource authorization, adapter and result binding are required')
        if type(self.timeout_seconds) not in (int, float) or not .05 <= self.timeout_seconds <= 10:
            raise ValueError('Invalid tool deadline')
        if self.cache_policy not in ('immutable','current-version'):
            raise ValueError('Invalid tool cache policy')


@dataclass(frozen=True)
class WriteTool:
    """One fixed operation per Run, reference-only completion and receipt confirmation."""
    name: str
    version: str
    scope: str
    operation: str
    policy_sha256: str
    arguments: type[ToolModel]
    result: type[ToolModel]
    authorize: Callable = field(repr=False)
    intent: Callable = field(repr=False)
    invoke: Callable = field(repr=False)
    confirm: Callable = field(repr=False)
    verify: Callable = field(repr=False)
    timeout_seconds: float = 8
    cache_policy: str = field(default='receipt-only',init=False)

    def __post_init__(self):
        if not isinstance(self.name,str) or not re.fullmatch(r'[a-z][a-z0-9_]{0,47}',self.name):raise ValueError('Invalid registered tool name')
        identifier(self.version,32);identifier(self.scope,64);identifier(self.operation,64)
        if type(self.policy_sha256) is not str or not re.fullmatch(r'[0-9a-f]{64}',self.policy_sha256):raise ValueError('Stable write policy hash required')
        _closed_schema(self.arguments,arguments=True);_closed_schema(self.result)
        if not all(callable(c) for c in (self.authorize,self.intent,self.invoke,self.confirm,self.verify)):raise ValueError('Write authority, intent, receipt and verification callbacks required')
        if type(self.timeout_seconds) not in (int,float) or not .05<=self.timeout_seconds<=10:raise ValueError('Invalid tool deadline')


class ToolRegistry:
    def __init__(self, tools):
        registered = {}
        for tool in tools:
            if not isinstance(tool, (ReadTool,WriteTool)) or tool.name in registered:
                raise ValueError('Unique host-registered tools are required')
            registered[tool.name] = tool
        if not 1 <= len(registered) <= 32:
            raise ValueError('Tool registry exceeds limits')
        self._tools = MappingProxyType(registered)

    def resolve(self, call):
        if not isinstance(call, ToolCall):
            raise PermanentFailure('TOOL_CALL_INVALID')
        tool = self._tools.get(call.name)
        if tool is None:
            raise PermanentFailure('TOOL_UNKNOWN')
        if tool.version != call.version:
            raise PermanentFailure('TOOL_VERSION_MISMATCH')
        return tool

    def catalog(self):
        # Fresh schemas: model/catalog consumers cannot mutate registered code.
        return [{'name': t.name, 'version': t.version, 'scope': t.scope,
                 'effect': 'WRITE' if isinstance(t,WriteTool) else 'READ', 'parallel': False, 'timeout_seconds': t.timeout_seconds,
                 'cache_policy': t.cache_policy,
                 'arguments': _closed_schema(t.arguments, arguments=True),
                 'result': _closed_schema(t.result)} for t in self._tools.values()]


class ToolExecutor:
    def __init__(self, registry, allowed_scopes):
        if not isinstance(registry, ToolRegistry) or not isinstance(allowed_scopes, frozenset):
            raise ValueError('Host-owned registry and immutable scope policy required')
        for scope in allowed_scopes:
            identifier(scope, 64)
        self.registry, self.allowed_scopes = registry, allowed_scopes

    @staticmethod
    def _call(tool, callback, identity, arguments):
        deadline = Deadline(time.monotonic() + tool.timeout_seconds)
        try:
            result = callback(identity, arguments, deadline)
            deadline.remaining()
            return result
        except ToolDenied:
            raise PermanentFailure('TOOL_RESOURCE_DENIED') from None
        except TimeoutError:
            raise TransientFailure('TOOL_TIMEOUT') from None
        except ToolUnavailable as error:
            raise TransientFailure(error.code) from None
        except (PermanentFailure, TransientFailure):
            # Existing bounded SDKs already expose stable classified failures.
            raise
        except Exception:
            raise PermanentFailure('TOOL_EXECUTION_ERROR') from None

    @staticmethod
    def _validate(model, value, code):
        try:
            raw, _ = encode('tool-output-v1', value)
            # Pure JSON only; no provider objects, model instances or custom mappings.
            checked = model.model_validate(json.loads(raw), strict=True)
            result = checked.model_dump(mode='json')
            encode('tool-output-v1', result)
            return result
        except (ValueError, TypeError, ValidationError):
            raise PermanentFailure(code) from None

    def execute(self, context, key, call):
        identifier(key)
        tool = self.registry.resolve(call)
        if tool.scope not in self.allowed_scopes:
            raise PermanentFailure('TOOL_SCOPE_DENIED')
        identity = ToolIdentity.from_context(context)
        try:
            proposal = ToolCall(call.name, call.version, call.arguments)
        except ValueError:
            raise PermanentFailure('TOOL_ARGUMENTS_INVALID') from None
        arguments = self._validate(tool.arguments, proposal.arguments, 'TOOL_ARGUMENTS_INVALID')
        if isinstance(tool,WriteTool):return self._execute_write(context,tool,identity,arguments)
        intent = {'operation': tool.name, 'version': tool.version,
                  'resource_ref': identity.source.principal_ref, 'arguments_sha256': fingerprint(arguments)}

        if tool.cache_policy=='current-version':
            # A stable anchor forbids changing tool/arguments across resume.
            # Only the separately authorized *resource version* may change.
            context.step('bind:'+fingerprint(key)[:40],'INTERNAL','NONE',intent,lambda:{'outcome':'bound'})

        def authorize():
            grant = self._call(tool, tool.authorize, identity, json.loads(json.dumps(arguments)))
            if grant is True:
                return {'outcome': 'authorized'}
            if not isinstance(grant, ReadAuthorization):
                raise PermanentFailure('TOOL_RESOURCE_DENIED')
            return dict(grant.references) | {'outcome': 'authorized'}

        # A fresh current-ACL check is itself an accounted read step, even when
        # a prior tool result is reusable. Never cache permission across resumes.
        grant = ReadAuthorization(context.step('auth:' + uuid4().hex, 'TOOL', 'READ', intent, authorize))
        if tool.cache_policy=='current-version':
            digest=grant.references.get('arguments_sha256')
            if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest) or not grant.references.get('resource_ref'):
                raise PermanentFailure('TOOL_AUTHORIZATION_INVALID')
            key='read:'+fingerprint({'logical_key':key,'authorization':dict(grant.references)})[:48]

        def verified(output):
            result = self._validate(tool.result, output, 'TOOL_RESULT_INVALID')
            try:
                # A pure host verifier binds returned resources/facts to the
                # request. JSON schema alone cannot prove that correspondence.
                valid = tool.verify(identity, json.loads(json.dumps(arguments)), json.loads(json.dumps(result)), grant)
            except Exception:
                raise PermanentFailure('TOOL_RESULT_INVALID') from None
            if valid is not True:
                raise PermanentFailure('TOOL_RESULT_INVALID')
            return result

        def invoke():
            output = self._call(tool, tool.invoke, identity, json.loads(json.dumps(arguments)))
            return verified(output)

        result = context.payload_step(key, 'TOOL', 'READ', intent, 'tool-output-v1', invoke)
        return verified(result)

    def _execute_write(self,context,tool,identity,arguments):
        # The business operation, not a model-chosen logical key, owns one intent
        # per Run. Neither another call key nor another alias authorizes a new body.
        context.step('bind-write:'+fingerprint(tool.operation)[:40],'INTERNAL','NONE',
            dict(operation=tool.operation,version=tool.version,resource_ref=identity.source.principal_ref,
                 input_ref=tool.name,arguments_sha256=fingerprint({'arguments':arguments,'policy':tool.policy_sha256})),lambda:{'outcome':'bound'})
        writes=[step for step in context.store.steps(identity.source.tenant,identity.run_id) if step['effect']=='WRITE']
        if any(step['state'] in ('RESERVED','UNKNOWN') for step in writes):raise PermanentFailure('SIDE_EFFECT_UNKNOWN')
        previous=[]
        for step in writes:
            if step['kind']!='TOOL' or type(step['intent_ref']) is not dict:raise PermanentFailure('TOOL_WRITE_CHECKPOINT_INVALID')
            if step['intent_ref'].get('operation')==tool.operation:previous.append(step)
        if len(previous)>1:raise PermanentFailure('TOOL_WRITE_CHECKPOINT_INVALID')

        def intent_for(input_ref):
            try:
                intent=reference(tool.intent(identity,json.loads(json.dumps(arguments)),input_ref))
                if (set(intent)!={'operation','version','resource_ref','input_ref','arguments_sha256'}
                        or intent['operation']!=tool.operation or intent['version']!=tool.version
                        or intent['resource_ref']!=identity.source.principal_ref or intent['input_ref']!=input_ref
                        or not re.fullmatch(r'[0-9a-f]{64}',intent['arguments_sha256'])):raise ValueError()
                return intent
            except Exception:raise PermanentFailure('TOOL_WRITE_INTENT_INVALID') from None

        def verified(value,intent):
            result=self._validate(tool.result,value,'TOOL_RESULT_INVALID')
            try:
                reference(result)
                if tool.verify(identity,json.loads(json.dumps(arguments)),json.loads(json.dumps(result)),dict(intent)) is not True:raise ValueError()
            except Exception:raise PermanentFailure('TOOL_RESULT_INVALID') from None
            return result

        def confirm(intent):
            def read():
                value=self._call(tool,lambda i,a,d:tool.confirm(i,a,dict(intent),d),identity,json.loads(json.dumps(arguments)))
                return {'outcome':'not-observed'} if value is None else verified(value,intent)
            return context.step('write-receipt:'+uuid4().hex,'TOOL','READ',intent,read)

        if previous:
            prior=previous[0]
            if prior['state']!='SUCCEEDED':raise PermanentFailure('SIDE_EFFECT_UNKNOWN')
            intent=intent_for(prior['intent_ref'].get('input_ref'))
            if intent!=prior['intent_ref']:raise PermanentFailure('TOOL_WRITE_CHECKPOINT_INVALID')
            expected=verified(prior['result_ref'],intent)
            found=confirm(intent)
            if found!=expected:raise PermanentFailure('TOOL_WRITE_CHECKPOINT_INVALID')
            return found

        def authorize():
            grant=self._call(tool,tool.authorize,identity,json.loads(json.dumps(arguments)))
            if not isinstance(grant,ReadAuthorization) or grant.references.get('resource_ref')!=identity.source.principal_ref:
                raise PermanentFailure('TOOL_RESOURCE_DENIED')
            return dict(grant.references)
        grant=context.step('auth:'+uuid4().hex,'TOOL','READ',
            dict(operation=tool.name,version=tool.version,resource_ref=identity.source.principal_ref,arguments_sha256=fingerprint(arguments)),authorize)
        intent=intent_for(grant.get('input_ref'))
        def invoke():
            try:
                result=self._call(tool,lambda i,a,d:tool.invoke(i,a,dict(intent),context.lease,d),identity,json.loads(json.dumps(arguments)))
                return verified(result,intent)
            except TransientFailure:
                found=confirm(intent)
                if found.get('outcome')!='not-observed':return found
                raise TransientFailure('TOOL_WRITE_UNCONFIRMED') from None
        return context.step('write:'+fingerprint(tool.operation)[:40],'TOOL','WRITE',intent,invoke)
