"""Deterministic tool contract, authority and failure boundaries. No model/network."""
from dataclasses import asdict, replace
import json
from types import SimpleNamespace
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field
import pytest

from ai.runtime import tools
from ai.runtime.tools import (ReadTool, ToolCall, ToolDenied, ToolExecutor, ToolModel,
                              ToolRegistry, ToolUnavailable)
from ai.runtime.types import Lease, RunInput, fingerprint
from ai.runtime.worker import PermanentFailure, TransientFailure


class Arguments(ToolModel):
    ticket_no: str = Field(min_length=1, max_length=64)
    limit: int = Field(default=5, ge=1, le=5)


class Result(ToolModel):
    ticket_no: str
    status: Literal['OPEN', 'CLOSED']
    summary: str = Field(max_length=256)


ANSWER = {'ticket_no': 'T-1', 'status': 'OPEN', 'summary': 'synthetic issue'}


def definition(**kwargs):
    values = dict(name='get_ticket_status', version='ticket-v1', scope='ticket:read',
                  arguments=Arguments, result=Result, authorize=lambda *_: True,
                  invoke=lambda *_: dict(ANSWER),
                  verify=lambda identity, args, result, grant: args['ticket_no'] == result['ticket_no'])
    return ReadTool(**(values | kwargs))


def call(**arguments):
    return ToolCall('get_ticket_status', 'ticket-v1', {'ticket_no': 'T-1'} | arguments)


def context():
    source = RunInput('TENANT_A', uuid4(), 'SESSION1', 1, 1, 'customer-1', 'trace-1')
    run_id = uuid4()
    row = asdict(source)
    row['tenant_code'] = row.pop('tenant')
    row['run_id'] = run_id
    ctx = SimpleNamespace(run=row, lease=Lease('TENANT_A', run_id, 'worker', 1), steps=[])
    def step(key, kind, effect, intent, execute):
        ctx.steps.append((key, kind, effect, intent))
        return execute()
    ctx.step = step
    ctx.payload_step = lambda key, kind, effect, intent, schema, execute: step(key, kind, effect, intent, execute)
    return ctx


def executor(tool=None, scopes=frozenset({'ticket:read'})):
    return ToolExecutor(ToolRegistry([tool or definition()]), scopes)


def test_success_uses_only_trusted_identity_and_records_digests_not_arguments():
    seen = []
    def invoke(identity, args, deadline):
        assert 0 < deadline.remaining() <= 3
        seen.append((identity, args))
        return dict(ANSWER)
    ctx = context()
    assert executor(definition(invoke=invoke)).execute(ctx, 'call-1', call()) == ANSWER
    identity, args = seen[0]
    assert identity.source.tenant == 'TENANT_A' and identity.source.principal_ref == 'customer-1'
    assert identity.run_id == ctx.lease.run_id and args == {'ticket_no': 'T-1', 'limit': 5}
    assert len(ctx.steps) == 2 and all(x[1:3] == ('TOOL', 'READ') for x in ctx.steps)
    assert ctx.steps[1][3]['arguments_sha256'] == fingerprint(args)
    assert 'synthetic issue' not in str(ctx.steps) and 'T-1' not in str(ctx.steps)


@pytest.mark.parametrize('raw', [
    '', b'', b'[]', b'null', b'{', b'\xff', b' ' * 8193,
    b'{"name":"get_ticket_status","name":"get_ticket_status","version":"ticket-v1","arguments":{}}',
    b'{"name":"get_ticket_status","version":"ticket-v1","arguments":{"ticket_no":"T-1","ticket_no":"T-2"}}',
    b'{"name":"get_ticket_status","version":"ticket-v1","arguments":{},"tenant":"TENANT_B"}',
    b'{"name":true,"version":"ticket-v1","arguments":{}}',
    b'{"name":"get_ticket_status","version":1,"arguments":{}}',
    b'{"name":"get_ticket_status","version":"ticket-v1","arguments":[]}',
    b'{"name":"get_ticket_status","version":"ticket-v1","arguments":{"x":NaN}}',
    br'{"name":"get_ticket_status","version":"ticket-v1","arguments":{"x":"\u0000"}}',
])
def test_raw_proposals_are_bounded_closed_and_duplicate_safe(raw):
    with pytest.raises(PermanentFailure, match='^TOOL_CALL_INVALID$'):
        ToolCall.parse(raw)


def test_parse_detaches_model_proposal_and_hides_arguments_in_repr():
    args = {'ticket_no': 'private-customer-text'}
    proposal = ToolCall('get_ticket_status', 'ticket-v1', args)
    args['tenant'] = 'TENANT_B'
    assert proposal.arguments == {'ticket_no': 'private-customer-text'}
    assert 'private-customer-text' not in repr(proposal)
    raw = json.dumps({'name': 'get_ticket_status', 'version': 'ticket-v1', 'arguments': {'ticket_no': 'T-1'}}).encode()
    assert ToolCall.parse(raw) == call()


@pytest.mark.parametrize('arguments', [
    {}, {'ticket_no': 1}, {'ticket_no': ''}, {'ticket_no': 'x' * 65},
    {'ticket_no': 'T-1', 'limit': True}, {'ticket_no': 'T-1', 'limit': '2'},
    {'ticket_no': 'T-1', 'limit': 0}, {'ticket_no': 'T-1', 'limit': 6},
    {'ticket_no': 'T-1', 'tenant_code': 'TENANT_B'},
    {'ticket_no': 'T-1', 'url': 'http://169.254.169.254/'},
    {'ticket_no': 'T-1', 'scopes': ['ticket:write']},
])
def test_invalid_arguments_never_authorize_reserve_or_invoke(arguments):
    ctx = context()
    with pytest.raises(PermanentFailure, match='^TOOL_ARGUMENTS_INVALID$'):
        executor().execute(ctx, 'call-1', ToolCall('get_ticket_status', 'ticket-v1', arguments))
    assert ctx.steps == []


def test_mutated_proposal_is_checked_again_before_execution():
    proposal = call()
    proposal.arguments['ticket_no'] = 'x' * 9000
    ctx = context()
    with pytest.raises(PermanentFailure, match='TOOL_ARGUMENTS_INVALID'):
        executor().execute(ctx, 'call-1', proposal)
    assert ctx.steps == []


@pytest.mark.parametrize('proposal,code', [
    (ToolCall('shell', 'v1', {}), 'TOOL_UNKNOWN'),
    (ToolCall('get_ticket_status', 'v2', {}), 'TOOL_VERSION_MISMATCH'),
    ({'name': 'get_ticket_status'}, 'TOOL_CALL_INVALID'),
])
def test_unknown_tools_and_versions_are_not_dynamic_imports(proposal, code):
    ctx = context()
    with pytest.raises(PermanentFailure, match=code):
        executor().execute(ctx, 'call-1', proposal)
    assert ctx.steps == []


def test_scope_policy_and_lease_binding_fail_before_any_adapter():
    ctx = context()
    with pytest.raises(PermanentFailure, match='TOOL_SCOPE_DENIED'):
        executor(scopes=frozenset()).execute(ctx, 'call-1', call())
    ctx.lease = replace(ctx.lease, tenant='TENANT_B')
    with pytest.raises(PermanentFailure, match='TOOL_IDENTITY_INVALID'):
        executor().execute(ctx, 'call-1', call())
    assert ctx.steps == []


@pytest.mark.parametrize('permission', [False, None, 1, 'true'])
def test_only_explicit_current_authorization_allows_invocation(permission):
    tool = definition(authorize=lambda *_: permission, invoke=lambda *_: pytest.fail('unauthorized call'))
    ctx = context()
    with pytest.raises(PermanentFailure, match='TOOL_RESOURCE_DENIED'):
        executor(tool).execute(ctx, 'call-1', call())
    assert len(ctx.steps) == 1


@pytest.mark.parametrize('output', [
    None, [], Result(**ANSWER), ANSWER | {'secret': 'private-data'},
    ANSWER | {'status': 'HALLUCINATED'}, ANSWER | {'summary': 'x' * 257},
    ANSWER | {'ticket_no': True}, ANSWER | {'summary': '\0'},
    ANSWER | {'ticket_no': 'OTHER-CUSTOMER'},
])
def test_invalid_or_extra_output_is_not_returned_or_persisted_as_success(output):
    with pytest.raises(PermanentFailure, match='TOOL_RESULT_INVALID'):
        executor(definition(invoke=lambda *_: output)).execute(context(), 'call-1', call())


@pytest.mark.parametrize('error,kind,code', [
    (ToolDenied('private upstream detail'), PermanentFailure, 'TOOL_RESOURCE_DENIED'),
    (TimeoutError('private timeout'), TransientFailure, 'TOOL_TIMEOUT'),
    (ToolUnavailable(), TransientFailure, 'TOOL_UNAVAILABLE'),
    (ToolUnavailable('TOOL_RATE_LIMITED'), TransientFailure, 'TOOL_RATE_LIMITED'),
    (RuntimeError('private token'), PermanentFailure, 'TOOL_EXECUTION_ERROR'),
])
def test_errors_are_classified_without_exception_text_or_implicit_retries(error, kind, code):
    attempts = []
    def invoke(*_):
        attempts.append(1)
        raise error
    with pytest.raises(kind, match='^' + code + '$'):
        executor(definition(invoke=invoke)).execute(context(), 'call-1', call())
    assert attempts == [1]


def test_late_adapter_output_is_rejected_without_claiming_forced_thread_termination(monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(tools.time, 'monotonic', lambda: clock[0])
    def invoke(*_):
        clock[0] += 3.1
        return dict(ANSWER)
    with pytest.raises(TransientFailure, match='TOOL_TIMEOUT'):
        executor(definition(invoke=invoke)).execute(context(), 'call-1', call())


def test_authorizer_cannot_mutate_the_arguments_later_executed():
    def authorize(identity, args, deadline):
        args['ticket_no'] = 'OTHER-CUSTOMER'
        return True
    def invoke(identity, args, deadline):
        assert args['ticket_no'] == 'T-1'
        return dict(ANSWER)
    assert executor(definition(authorize=authorize, invoke=invoke)).execute(context(), 'call-1', call()) == ANSWER


def test_verifier_failure_is_closed_and_its_mutations_cannot_change_the_result():
    def verify(identity, args, result, grant):
        result['summary'] = 'modified'
        return True
    assert executor(definition(verify=verify)).execute(context(), 'call-1', call()) == ANSWER
    def broken(*_):
        raise RuntimeError('private verifier detail')
    for verifier in (broken, lambda *_: False, lambda *_: 1):
        with pytest.raises(PermanentFailure, match='^TOOL_RESULT_INVALID$'):
            executor(definition(verify=verifier)).execute(context(), 'call-1', call())


def test_catalog_is_closed_serial_and_detached_from_registration():
    registry = ToolRegistry([definition()])
    first = registry.catalog()[0]
    assert first['effect'] == 'READ' and first['parallel'] is False
    assert first['arguments']['additionalProperties'] is False
    first['arguments']['properties']['tenant'] = {'type': 'string'}
    assert 'tenant' not in registry.catalog()[0]['arguments']['properties']
    with pytest.raises(ValueError):
        ToolRegistry([definition(), definition()])
    with pytest.raises(ValueError):
        ToolRegistry([])
    with pytest.raises(ValueError):
        ToolRegistry([object()])  # No generic WRITE/dynamic server registration.


@pytest.mark.parametrize('field,value', [('timeout_seconds', True), ('timeout_seconds', float('nan')),
                                      ('timeout_seconds', 0), ('timeout_seconds', 11), ('authorize', None), ('verify', None)])
def test_invalid_host_registration_is_rejected(field, value):
    with pytest.raises(ValueError):
        definition(**{field: value})


def test_permissive_nested_or_authority_contracts_cannot_be_registered():
    class Authority(ToolModel):
        tenant_code: str
    class Unbounded(ToolModel):
        data: dict
    class Child(BaseModel):
        value: str
    class Nested(ToolModel):
        child: Child
    class Loose(ToolModel):
        model_config = ConfigDict(extra='allow', strict=True)
    for contract in (Authority, Unbounded, Nested, Loose, BaseModel):
        with pytest.raises(ValueError):
            definition(arguments=contract)
    with pytest.raises(ValueError):
        ToolExecutor(ToolRegistry([definition()]), {'ticket:read'})
