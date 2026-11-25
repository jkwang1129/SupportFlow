"""Controlled playback of model decisions and verified read projections."""
import json
import socket

import psycopg
import pytest

from ai.runtime.ticket_loop import LOOP_VERSION, planner_policy
from ai.runtime.ticket_prompts import CANARY_PROMPT, STABLE_PROMPT, ticket_prompt
from ai.runtime.ticket_replay import SUITE_VERSION, TAPE_VERSION, VERSIONED_TAPE, compare_suite, main, replay_tape
from ai.runtime.types import fingerprint


CATALOG = [{'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'scope': 'ticket:read', 'effect': 'READ'}]
QUESTION = '请查看合成工单 TK-A1'
FACT = {'ticket_no': 'TK-A1', 'status': 2, 'updated_at': '2026-11-23T10:11:12'}
QUERY = {'action': 'tool', 'call': {'name': 'get_ticket_status', 'version': 'ticket-status-v2',
                                    'arguments': {'ticket_no': 'TK-A1'}}}


def pack(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


def start(events, expected, *, canonical=False):
    return dict(schema_version=TAPE_VERSION, loop_version=LOOP_VERSION,
                policy_sha256=fingerprint(planner_policy(CATALOG)), catalog=CATALOG,
                source_text=QUESTION, max_turns=4, canonical_history=canonical, events=events, expected=expected)


def signature(messages=()):
    return fingerprint([{'role': 'system', 'content': planner_policy(CATALOG)},
                        {'role': 'user', 'content': pack({'kind': 'untrusted_user_input', 'text': QUESTION})},
                        *messages])


def outcome(*, name=None, error=None, status=None, models=1, reads=0):
    return dict(outcome=name, error_code=error, ticket_status=status, model_calls=models, ticket_reads=reads)


def normal(canonical=False):
    raw = json.dumps(QUERY, indent=2)
    followup = signature([{'role': 'assistant', 'content': pack(QUERY) if canonical else raw},
                          {'role': 'user', 'content': pack({'kind': 'untrusted_tool_result', 'slot': 'tool-0',
                                                           'name': 'get_ticket_status', 'value': FACT})}])
    return start([{'kind': 'model', 'input_sha256': signature(), 'reply': raw},
                  {'kind': 'ticket_read', 'slot': 'tool-0', 'ticket_no': 'TK-A1', 'result': FACT.copy()},
                  {'kind': 'model', 'input_sha256': followup, 'reply': '{"action":"report","evidence":"tool-0"}'},
                  {'kind': 'ticket_read', 'slot': 'tool-0', 'ticket_no': 'TK-A1', 'result': FACT | {'status': 4}}],
                 outcome(name='ticket-status-ready', status=4, models=2, reads=2), canonical=canonical)


def suite(tape):
    return {'schema_version': SUITE_VERSION, 'cases': {'fixture': tape}}


@pytest.mark.parametrize('version, digest', [
    (STABLE_PROMPT, '153fb1f7eb7e7c918a936c1a8454519be39a28572c57e8b540e15e0217104115'),
    (CANARY_PROMPT, '723ddbdcce5959cd79afcd166d8d70b536ebf9619ac7053abbdb14a2ebfc72f6'),
])
def test_published_prompt_content_is_frozen_and_replay_retains_version(version, digest):
    rendered = planner_policy(CATALOG, version)
    assert fingerprint(rendered) == digest
    messages = [{'role': 'system', 'content': rendered},
                {'role': 'user', 'content': pack({'kind': 'untrusted_user_input', 'text': QUESTION})}]
    tape = start([{'kind': 'model', 'input_sha256': fingerprint(messages), 'reply': '{"action":"clarify"}'}],
                 outcome(name='clarification-ready'))
    tape.update(schema_version=VERSIONED_TAPE, prompt_version=version, policy_sha256=digest)
    result = replay_tape(tape)
    assert result['passed'] and result['prompt_version'] == version
    assert result['observed']['model_calls'] == 1 and result['observed']['ticket_reads'] == 0
    assert ticket_prompt(CANARY_PROMPT).rollback_to == STABLE_PROMPT


@pytest.mark.parametrize('version, issue', [('missing', 'PROMPT_OR_CATALOG_INVALID'), (CANARY_PROMPT, 'POLICY_CHANGED')])
def test_replay_never_substitutes_a_different_prompt_for_the_recorded_policy(version, issue):
    tape = normal() | {'schema_version': VERSIONED_TAPE, 'prompt_version': version}
    result = compare_suite(suite(tape))
    assert not result['passed'] and result['cases']['fixture']['issue'] == issue


@pytest.mark.parametrize('catalog', [[], [CATALOG[0] | {'effect': 'WRITE'}], [CATALOG[0] | {'note': '密' * 6000}]])
def test_prompt_catalog_only_accepts_bounded_read_capability(catalog):
    with pytest.raises(ValueError):
        ticket_prompt(CANARY_PROMPT).render(catalog)


@pytest.mark.parametrize('canonical', [False, True])
def test_actual_planner_rereads_the_ticket_before_reporting_without_network_or_db(canonical, monkeypatch):
    def denied(*args, **kwargs):
        pytest.fail('recorded replay must not call an external adapter')

    monkeypatch.setattr(socket, 'socket', denied)
    monkeypatch.setattr(psycopg, 'connect', denied)
    result = replay_tape(normal(canonical))
    assert result['passed'] and result['observed']['ticket_status'] == 4
    assert (result['observed']['model_calls'], result['observed']['ticket_reads']) == (2, 2)


def test_read_revocation_is_replayed_as_failure_without_using_the_earlier_observation():
    tape = normal()
    tape['events'][-1].pop('result')
    tape['events'][-1]['error_code'] = 'TICKET_REVOKED'
    tape['expected'] = outcome(error='TICKET_REVOKED', models=2, reads=2)
    assert replay_tape(tape)['passed']


@pytest.mark.parametrize('reply,error', [
    ('{"action":"report","evidence":"tool-0"}', 'REPORT_EVIDENCE_INVALID'),
    ('{"action":"tool","call":{"name":"create_ticket","version":"ticket-create-v1","arguments":{}}}', 'TOOL_UNKNOWN'),
    ('{"action":"tool","call":{"name":"get_ticket_status","version":"v1","arguments":{}}}', 'TOOL_VERSION_MISMATCH'),
    ('{"action":"tool","call":{"name":"get_ticket_status","version":"ticket-status-v2","arguments":{}}}', 'TOOL_ARGUMENTS_INVALID'),
])
def test_unauthorized_or_unobserved_decisions_cannot_consume_a_recorded_ticket_read(reply, error):
    tape = start([{'kind': 'model', 'input_sha256': signature(), 'reply': reply}], outcome(error=error))
    assert replay_tape(tape)['observed'] == outcome(error=error)


def test_recorded_timeout_ends_the_current_attempt_without_retrying():
    tape = start([{'kind': 'model', 'input_sha256': signature(), 'error_code': 'MODEL_TIMEOUT'}],
                 outcome(error='MODEL_TIMEOUT'))
    assert replay_tape(tape)['passed']


@pytest.mark.parametrize('change,expected_issue', [
    ('order', 'RECORDED_CALL_ORDER_CHANGED'), ('input', 'RECORDED_CALL_INPUT_CHANGED'),
    ('missing', 'MISSING_RECORDED_CALL'), ('extra', 'UNUSED_RECORDED_CALLS'),
    ('tool_result', 'INVALID_RECORDED_TICKET_RESULT'), ('policy', 'POLICY_CHANGED'),
    ('version', 'LOOP_VERSION_CHANGED'), ('catalog', 'INVALID_REPLAY_CATALOG'),
])
def test_tape_drift_is_visible_to_the_gate(change, expected_issue):
    tape = normal()
    if change == 'order':
        tape['events'][1], tape['events'][2] = tape['events'][2], tape['events'][1]
    elif change == 'input':
        tape['events'][0]['input_sha256'] = '0' * 64
    elif change == 'missing':
        tape['events'].pop()
    elif change == 'extra':
        tape['events'].append(tape['events'][0])
    elif change == 'tool_result':
        tape['events'][1]['result']['status'] = True
    elif change == 'policy':
        tape['policy_sha256'] = '0' * 64
    elif change == 'version':
        tape['loop_version'] = 'old-loop'
    elif change == 'catalog':
        tape['catalog'] = [CATALOG[0] | {'effect': 'WRITE'}]
    report = compare_suite(suite(tape))
    assert not report['passed'] and report['cases']['fixture']['issue'] == expected_issue


def test_cli_fails_if_actual_read_outcome_differs_and_only_returns_projected_metadata(tmp_path, capsys):
    tape = normal()
    tape['expected']['ticket_status'] = 2
    path = tmp_path / 'tape.json'
    path.write_text(pack(suite(tape)), encoding='utf-8')
    assert main(['--suite', str(path)]) == 1
    report = capsys.readouterr().out
    assert QUESTION not in report and 'TK-A1' not in report
    assert json.loads(report)['cases']['fixture']['differences'] == ['ticket_status']
