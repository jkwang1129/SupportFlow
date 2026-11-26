"""Contract/adapter tests; durable states and budgets are qualified with real PG separately."""
from dataclasses import replace
from types import SimpleNamespace
from uuid import uuid4

import pytest

from ai.runtime.ticket_creation import arguments_hash
from ai.runtime.ticket_write_tool import TicketCreateArguments, TicketCreateResult, ticket_create_tool
from ai.runtime.source import SourceClient
from ai.runtime.ticket_creation import TicketCreationClient
from ai.runtime.tools import ReadAuthorization, ToolCall, ToolDenied, ToolExecutor, ToolRegistry, WriteTool
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_tools import context as read_context, definition as read_definition

SNAPSHOT = str(uuid4())
TITLE, CONTENT = 'PRIVATE TITLE', 'PRIVATE BODY\n原文 '


def context(previous=()):
    ctx = read_context()
    ctx.store = SimpleNamespace(steps=lambda *_: list(previous))
    return ctx


def proposal(**changes): return ToolCall('create_ticket', 'ticket-create-v1',
                                         dict(title=TITLE, content=CONTENT) | changes)


def definition(**changes):
    def intent(identity, args, snapshot):
        return dict(operation='ticket.create', version='ticket-create-v1', resource_ref=identity.source.principal_ref, input_ref=snapshot,
                    arguments_sha256=arguments_hash(identity.source, identity.run_id, snapshot, args['title'], args['content']))

    def result(intent): return {key: intent[key] for key in ('operation', 'version', 'input_ref', 'arguments_sha256')} | dict(
        outcome='ticket-committed', business_ref='ticket:123', resource_ref='TK_123')
    values = dict(name='create_ticket', version='ticket-create-v1', scope='ticket:create', operation='ticket.create', policy_sha256='a' * 64, arguments=TicketCreateArguments, result=TicketCreateResult,
                  authorize=lambda identity, *_: ReadAuthorization(dict(resource_ref=identity.source.principal_ref, input_ref=SNAPSHOT)), intent=intent,
                  invoke=lambda i, a, intent, *_: result(intent), confirm=lambda i, a, intent, *_: result(intent),
                  verify=lambda i, a, value, intent: all(value[key] == intent[key] for key in ('operation', 'version', 'input_ref', 'arguments_sha256')))
    return WriteTool(**(values | changes))


def executor(tool=None, scopes=frozenset({'ticket:create'})): return ToolExecutor(
    ToolRegistry([tool or definition()]), scopes)


def test_catalog_keeps_reads_and_writes_explicit_and_detached():
    registry = ToolRegistry([read_definition(), definition()])
    catalog = registry.catalog()
    assert [tool['effect'] for tool in catalog] == [
        'READ', 'WRITE'] and catalog[1]['cache_policy'] == 'receipt-only'
    assert catalog[1]['parallel'] is False and set(catalog[1]['arguments']['properties']) == {'title', 'content'}
    catalog[1]['arguments']['properties']['tenant'] = {}
    assert 'tenant' not in registry.catalog()[1]['arguments']['properties']


def test_fresh_write_reserves_exact_business_intent_before_adapter_and_saves_only_references():
    ctx = context()
    called = []

    def invoke(identity, args, intent, lease, deadline):
        assert ctx.steps[-1][1:3] == ('TOOL', 'WRITE') and lease == ctx.lease and 0 < deadline.remaining() <= 8
        assert intent['arguments_sha256'] == arguments_hash(
            identity.source, identity.run_id, SNAPSHOT, TITLE, CONTENT)
        called.append(1)
        return definition().invoke(identity, args, intent, lease, deadline)
    result = executor(definition(invoke=invoke)).execute(ctx, 'model-key', proposal())
    assert result['business_ref'] == 'ticket:123' and called == [1]
    assert [step[1:3] for step in ctx.steps] == [('INTERNAL', 'NONE'), ('TOOL', 'READ'), ('TOOL', 'WRITE')]
    assert TITLE not in str(ctx.steps) and CONTENT not in str(ctx.steps)


@pytest.mark.parametrize('patch', [dict(customer_id=1), dict(tenant_code='TENANT_B'), dict(priority=1), dict(assignee_id=1), dict(owner='forged'), dict(snapshot_id=SNAPSHOT),
                                   dict(title=' '), dict(content='\0'), dict(title=7), dict(content='中' * 2731), dict(title='🙂' * 129)])
def test_invalid_model_arguments_do_not_authorize_or_reserve(patch):
    ctx = context()
    call = proposal()
    call.arguments.update(patch)
    with pytest.raises(PermanentFailure, match='TOOL_ARGUMENTS_INVALID'):
        executor().execute(ctx, 'model-key', call)
    assert ctx.steps == []


def test_scope_unknown_tool_and_version_are_rejected_before_steps():
    ctx = context()
    with pytest.raises(PermanentFailure, match='TOOL_SCOPE_DENIED'):
        executor(scopes=frozenset()).execute(ctx, 'k', proposal())
    for call, code in ((ToolCall('shell', 'v1', {}), 'TOOL_UNKNOWN'), (ToolCall('create_ticket', 'v2', {}), 'TOOL_VERSION_MISMATCH')):
        with pytest.raises(PermanentFailure, match=code):
            executor().execute(ctx, 'k', call)
    assert ctx.steps == []


@pytest.mark.parametrize('grant', [True, None, ReadAuthorization(dict(resource_ref='foreign', input_ref=SNAPSHOT))])
def test_source_authority_must_be_a_bound_reference_grant(grant):
    ctx = context()
    with pytest.raises(PermanentFailure, match='TOOL_RESOURCE_DENIED'):
        executor(definition(authorize=lambda *_: grant, invoke=lambda *_: pytest.fail())).execute(ctx, 'k', proposal())
    assert not any(step[2] == 'WRITE' for step in ctx.steps)


def test_resource_denial_prevents_write_and_discards_private_details():
    def denied(*_): raise ToolDenied('private source error')
    with pytest.raises(PermanentFailure, match='^TOOL_RESOURCE_DENIED$'):
        executor(definition(authorize=denied, invoke=lambda *_: pytest.fail())).execute(context(), 'k', proposal())


def test_mutating_authorizer_cannot_change_the_approved_arguments():
    def authorize(identity, args, deadline): args['title'] = 'changed'; return ReadAuthorization(
        dict(resource_ref=identity.source.principal_ref, input_ref=SNAPSHOT))

    def invoke(i, args, intent, lease, d): assert args['title'] == TITLE; return definition().invoke(
        i, args, intent, lease, d)
    executor(definition(authorize=authorize, invoke=invoke)).execute(context(), 'k', proposal())


@pytest.mark.parametrize('patch', [dict(operation='session.reply'), dict(version='reply-v1'), dict(resource_ref='foreign'), dict(input_ref='other'), dict(arguments_sha256='A' * 64), dict(extra='secret')])
def test_adapter_cannot_substitute_the_reserved_business_intent(patch):
    original = definition()
    with pytest.raises(PermanentFailure, match='TOOL_WRITE_INTENT_INVALID'):
        executor(replace(original, intent=lambda *args: original.intent(*args) | patch,
                 invoke=lambda *_: pytest.fail())).execute(context(), 'k', proposal())


@pytest.mark.parametrize('patch', [dict(arguments_sha256='b' * 64), dict(input_ref=str(uuid4())), dict(operation='session.reply'), dict(business_ref='ticket:0'),
                                   dict(business_ref='ticket:9223372036854775808'), dict(resource_ref='../other'), dict(title='PRIVATE')])
def test_wrong_or_raw_write_results_are_not_accepted(patch):
    original = definition()
    with pytest.raises(PermanentFailure, match='TOOL_RESULT_INVALID'):
        executor(replace(original, invoke=lambda *args: original.invoke(*args) | patch)
                 ).execute(context(), 'k', proposal())


def test_transient_write_uses_one_accounted_receipt_read_and_no_second_post():
    ctx = context()
    calls = []
    original = definition()
    def invoke(*_): calls.append('POST'); raise TransientFailure('TICKET_CREATE_TIMEOUT')
    def confirm(*args): calls.append('GET'); return original.confirm(*args)
    value = executor(replace(original, invoke=invoke, confirm=confirm)).execute(ctx, 'k', proposal())
    assert value['outcome'] == 'ticket-committed' and calls == ['POST', 'GET']
    assert [step[1:3] for step in ctx.steps] == [
        ('INTERNAL', 'NONE'), ('TOOL', 'READ'), ('TOOL', 'WRITE'), ('TOOL', 'READ')]


@pytest.mark.parametrize('down', [False, True])
def test_absent_or_failed_receipt_never_causes_another_post(down):
    calls = []
    def invoke(*_): calls.append('POST'); raise TransientFailure('TICKET_CREATE_TIMEOUT')

    def confirm(*_):
        calls.append('GET')
        if down:
            raise TransientFailure('TICKET_CREATE_UNAVAILABLE')
        return None
    with pytest.raises(TransientFailure):
        executor(definition(invoke=invoke, confirm=confirm)).execute(context(), 'k', proposal())
    assert calls == ['POST', 'GET']


def prior(ctx, tool):
    from ai.runtime.tools import ToolIdentity
    identity = ToolIdentity.from_context(ctx)
    args = proposal().arguments
    intent = tool.intent(identity, args, SNAPSHOT)
    return dict(kind='TOOL', effect='WRITE', state='SUCCEEDED', intent_ref=intent, result_ref=tool.invoke(identity, args, intent, ctx.lease, None))


def test_successful_write_is_confirmed_without_current_source_or_post():
    ctx = context()
    tool = definition()
    old = prior(ctx, tool)
    ctx.store.steps = lambda *_: [old]
    calls = []
    def confirm(*args): calls.append('GET'); return tool.confirm(*args)
    value = executor(replace(tool, authorize=lambda *_: pytest.fail(),
                     invoke=lambda *_: pytest.fail(), confirm=confirm)).execute(ctx, 'different-key', proposal())
    assert value == old['result_ref'] and calls == ['GET'] and [s[1:3]
                                                                for s in ctx.steps] == [('INTERNAL', 'NONE'), ('TOOL', 'READ')]


@pytest.mark.parametrize('state', ['RESERVED', 'UNKNOWN', 'FAILED'])
def test_uncertain_or_failed_existing_write_is_never_replayed(state):
    ctx = context()
    tool = definition()
    old = prior(ctx, tool)
    old['state'] = state
    ctx.store.steps = lambda *_: [old]
    with pytest.raises(PermanentFailure, match='SIDE_EFFECT_UNKNOWN'):
        executor(replace(tool, authorize=lambda *_: pytest.fail(), invoke=lambda *_: pytest.fail(),
                 confirm=lambda *_: pytest.fail())).execute(ctx, 'k', proposal())


def test_changed_parameters_cannot_adopt_a_previous_success_even_under_another_call_key():
    ctx = context()
    tool = definition()
    old = prior(ctx, tool)
    ctx.store.steps = lambda *_: [old]
    with pytest.raises(PermanentFailure, match='TOOL_WRITE_CHECKPOINT_INVALID'):
        executor(replace(tool, authorize=lambda *_: pytest.fail(), invoke=lambda *_: pytest.fail(),
                 confirm=lambda *_: pytest.fail())).execute(ctx, 'new-key', proposal(title='changed'))


def test_missing_or_changed_committed_receipt_never_reexecutes_source_or_post():
    ctx = context()
    tool = definition()
    old = prior(ctx, tool)
    ctx.store.steps = lambda *_: [old]
    with pytest.raises(PermanentFailure, match='TOOL_WRITE_CHECKPOINT_INVALID'):
        executor(replace(tool, authorize=lambda *_: pytest.fail(), invoke=lambda *_: pytest.fail(),
                 confirm=lambda *_: None)).execute(ctx, 'k', proposal())


def test_ticket_adapter_requires_same_fixed_customer_origin_and_pair():
    key = 'synthetic-pair-' + '0' * 40
    for source, client in ((None, TicketCreationClient('http://customer.test', key)),
                           (SourceClient('http://other.test', key), TicketCreationClient('http://customer.test', key)),
                           (SourceClient('http://customer.test', key), TicketCreationClient('http://customer.test', key + 'x'))):
        with pytest.raises(ValueError):
            ticket_create_tool(source, client)
