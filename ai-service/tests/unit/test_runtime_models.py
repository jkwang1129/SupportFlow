import copy
from dataclasses import replace

import httpx
import pytest

from ai.core import llm_chat
from ai.runtime.models import ChatProfile, RuntimeChatClient
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_customer_tools import source_context
from test_chat_transport import response


MESSAGES = [{'role': 'system', 'content': 'Host planner policy'},
            {'role': 'user', 'content': 'synthetic private input'}]


def profile(**kwargs):
    return ChatProfile(**(dict(tenant='TENANT_A', profile_ref='planner-1', provider='fixture', model='fixture-chat',
                               base_url='http://model.test/v1', api_key='synthetic-private-model-credential') | kwargs))


def answer():
    return {'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': '{"action":"query"}'}}],
            'usage': {'prompt_tokens': 13, 'completion_tokens': 7, 'total_tokens': 20}}


def client(server, **kwargs): return RuntimeChatClient(profile(**kwargs), transport=httpx.MockTransport(server))


def test_one_reserved_model_uses_fixed_profile_and_preserves_provider_usage_in_result(monkeypatch):
    monkeypatch.setattr(llm_chat, 'record_chat_usage', lambda **_: pytest.fail('second usage ledger write'))
    calls = []
    def server(request): calls.append(request); return response(answer())
    ctx = source_context()
    value = client(server).complete(ctx, 'plan-1', MESSAGES)
    assert len(calls) == 1 and (value.input_tokens, value.output_tokens) == (13, 7)
    assert value.content == '{"action":"query"}' and value.profile_ref == 'planner-1'
    key, kind, effect, intent = ctx.steps[0]
    assert (kind, effect) == ('MODEL', 'NONE') and intent['operation'] == 'model.chat'
    assert intent['arguments_sha256'] == value.request_hash and 'synthetic private input' not in str(intent)
    assert profile().api_key not in repr(profile()) + str(intent) + repr(value)
    assert value.content not in repr(value)


def test_key_rotation_does_not_reinterpret_model_policy_but_endpoint_model_and_tenant_changes_do():
    base = profile()
    assert replace(base, api_key='rotated-private-model-credential').definition_hash() == base.definition_hash()
    for item in [replace(base, base_url='http://another-model.test/v1'), replace(base, model='other-model'),
                 replace(base, tenant='TENANT_B'), replace(base, prompt_version='planner-v2')]:
        assert item.definition_hash() != base.definition_hash()


@pytest.mark.parametrize('field,value', [('tenant', 'PLATFORM'), ('profile_ref', '../planner'), ('api_key', 'short'),
                                         ('api_key', 'long-private-key\r\n'), ('base_url',
                                                                               'http://user:pass@model.test/v1'), ('base_url', 'http://model.test/v1?x=1'),
                                         ('max_output_tokens', True), ('max_output_tokens',
                                                                       0), ('max_output_tokens', 4097), ('timeout_seconds', True),
                                         ('timeout_seconds', 31), ('prompt_version', 'bad version')])
def test_invalid_host_profiles_fail_before_worker_use(field, value):
    with pytest.raises(ValueError):
        profile(**{field: value})


def test_model_profile_cannot_change_tenant_or_replace_a_trusted_lease():
    ctx = source_context()
    api = client(lambda _: pytest.fail('wrong profile reached HTTP'), tenant='TENANT_B')
    with pytest.raises(PermanentFailure, match='MODEL_PROFILE_DENIED'):
        api.complete(ctx, 'plan-1', MESSAGES)
    ctx.lease = replace(ctx.lease, run_id=replace_run_id())
    with pytest.raises(PermanentFailure, match='MODEL_PROFILE_DENIED'):
        client(lambda _: pytest.fail()).complete(ctx, 'plan-1', MESSAGES)
    assert ctx.steps == []


def replace_run_id():
    from uuid import uuid4
    return uuid4()


@pytest.mark.parametrize('messages', [[], {}, [{'role': 'user', 'content': 'x'}],
                                      [{'role': 'system', 'content': 'host'}, {
                                          'role': 'system', 'content': 'untrusted override'}],
                                      [{'role': 'system', 'content': 'host'}, {'role': 'tool', 'content': 'x'}],
                                      [{'role': 'system', 'content': 'host', 'tenant': 'TENANT_B'}], [
                                          {'role': 'system', 'content': None}],
                                      [{'role': 'system', 'content': ' '}], [
                                          {'role': 'system', 'content': 'x' * 16385}],
                                      [{'role': 'system', 'content': 'host'}] +
                                      [{'role': 'user', 'content': 'x'}] * 32,
                                      [{'role': 'system', 'content': 'host'}] + [{'role': 'user', 'content': 'x' * 16000}] * 3])
def test_message_context_has_closed_roles_shape_and_byte_budget_before_model_reservation(messages):
    ctx = source_context()
    with pytest.raises(PermanentFailure, match='MODEL_CONTEXT_INVALID'):
        client(lambda _: pytest.fail('invalid context reached model')).complete(ctx, 'plan-1', messages)
    assert ctx.steps == []


@pytest.mark.parametrize('field,value', [('choices', []), ('choices', [{}, {}]),
                                         ('index', True), ('finish_reason', 'length'), ('role',
                                                                                        'user'), ('tool_calls', [{'name': 'shell'}]),
                                         ('refusal', 'cannot comply'), ('content', None), ('content',
                                                                                           ' '), ('content', 'x' * 8193), ('content', 'a\0b'),
                                         ('content', '\ud800'), ('content', 'synthetic-private-model-credential'),
                                         ('usage', []), ('usage', {'prompt_tokens': True, 'completion_tokens': 1}),
                                         ('usage', {'prompt_tokens': 1, 'completion_tokens': '2'}), ('usage', {'prompt_tokens': -1, 'completion_tokens': 1})])
def test_unusable_output_or_usage_never_becomes_a_successful_model_checkpoint(field, value):
    data = answer()
    if field in ('choices', 'usage'):
        data[field] = value
    elif field in ('index', 'finish_reason'):
        data['choices'][0][field] = value
    else:
        data['choices'][0]['message'][field] = value
    with pytest.raises(PermanentFailure, match='MODEL_OUTPUT_INVALID'):
        client(lambda _: response(data)).complete(source_context(), 'plan-1', MESSAGES)


@pytest.mark.parametrize('usage', [None, {}, {'prompt_tokens': 10}])
def test_missing_usage_stays_unknown_instead_of_being_fabricated_as_zero(usage):
    data = answer()
    data['usage'] = usage
    value = client(lambda _: response(data)).complete(source_context(), 'plan-1', MESSAGES)
    assert value.input_tokens is None and value.output_tokens is None


@pytest.mark.parametrize('status,kind,code', [(429, TransientFailure, 'MODEL_RATE_LIMITED'), (503, TransientFailure, 'MODEL_UNAVAILABLE'),
                                              (401, PermanentFailure, 'MODEL_AUTHORIZATION_DENIED'), (400, PermanentFailure, 'MODEL_REQUEST_REJECTED')])
def test_transport_classification_becomes_runtime_failure_without_local_retry(status, kind, code):
    calls = []
    def server(_): calls.append(1); return response(b'private upstream', status=status)
    with pytest.raises(kind, match='^' + code + '$'):
        client(server).complete(source_context(), 'plan-1', MESSAGES)
    assert calls == [1]


@pytest.mark.parametrize('field,value', [('schema_version', 'future-v2'), ('profile_ref', 'other-profile'),
                                         ('profile_hash', '0' * 64), ('request_hash',
                                                                      '0' * 64), ('prompt_version', 'other-prompt'),
                                         ('usage', {'available': True, 'input_tokens': True, 'output_tokens': 1}), ('content', 'synthetic-private-model-credential')])
def test_cached_output_is_checked_again_against_current_profile_request_and_usage(field, value):
    ctx = source_context()
    original = ctx.payload_step
    cached = []
    calls = []

    def payload(*args):
        if not cached:
            cached.append(original(*args))
        result = copy.deepcopy(cached[0])
        result[field] = value
        return result
    ctx.payload_step = payload
    def server(_): calls.append(1); return response(answer())
    with pytest.raises(PermanentFailure, match='MODEL_CHECKPOINT_INVALID'):
        client(server).complete(ctx, 'plan-1', MESSAGES)
    assert calls == [1]
