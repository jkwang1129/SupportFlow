"""Offline trace review and failure-gate tests."""
import json

import pytest

from ai.runtime.trace_replay import compare_suite, main, replay


def sample(state, error=None, *steps):
    return {'schema_version': 'runtime-trace-v1', 'run': {'state': state, 'error_code': error,
            'step_count': len(steps), 'model_calls': 0, 'tool_calls': len(steps), 'retry_count': 0},
            'steps': [{'step_no': index, 'kind': 'TOOL', 'effect': effect, 'state': phase}
                      for index, (effect, phase) in enumerate(steps, start=1)]}


@pytest.mark.parametrize('state,error,effect,step_state', [
    ('SUCCEEDED', None, 'READ', 'SUCCEEDED'),
    ('FAILED', 'SIDE_EFFECT_UNKNOWN', 'WRITE', 'UNKNOWN'),
    ('FAILED', 'SIDE_EFFECT_CONFIRMED', 'WRITE', 'SUCCEEDED'),
    ('RUNNING', None, 'WRITE', 'UNKNOWN'),
])
def test_valid_metadata_replays_in_order_without_execution(state, error, effect, step_state):
    trace = sample(state, error, (effect, step_state))
    result = replay(trace)
    assert not result['issues']
    assert result['unknown_writes'] == int(effect == 'WRITE' and step_state == 'UNKNOWN')
    assert result['successful_writes'] == int(effect == 'WRITE' and step_state == 'SUCCEEDED')
    assert result['state'] == state and trace['run']['state'] == state


@pytest.mark.parametrize('trace,issue', [
    (sample('SUCCEEDED', None, ('WRITE', 'UNKNOWN')), 'UNKNOWN_WRITE_WITHOUT_HOLD'),
    (sample('CANCELLED', None, ('WRITE', 'UNKNOWN')), 'UNKNOWN_WRITE_WITHOUT_HOLD'),
    (sample('FAILED', 'SIDE_EFFECT_UNKNOWN'), 'HOLD_WITHOUT_UNKNOWN_WRITE'),
    (sample('FAILED', 'SIDE_EFFECT_CONFIRMED', ('READ', 'SUCCEEDED')), 'MISSING_CONFIRMED_WRITE'),
    (sample('WAITING_APPROVAL', None, ('READ', 'RESERVED')), 'CLOSED_RUN_HAS_RESERVED_STEPS'),
])
def test_failure_conditions_cannot_pass_replay(trace, issue):
    assert replay(trace)['issues'] == [issue]


def test_nonsequential_trace_or_wrong_schema_is_refused():
    trace = sample('SUCCEEDED', None, ('READ', 'SUCCEEDED'))
    trace['steps'][0]['step_no'] = 2
    with pytest.raises(ValueError, match='sequence'):
        replay(trace)
    with pytest.raises(ValueError, match='Unsupported trace schema'):
        replay(trace | {'schema_version': 'other'})


def test_cli_reports_exact_baseline_diff_and_rejects_oversize(tmp_path, capsys):
    source, expectation = tmp_path/'trace.json', tmp_path/'expected.json'
    source.write_text(json.dumps(sample('SUCCEEDED', None, ('READ', 'SUCCEEDED'))), encoding='utf-8')
    expectation.write_text(json.dumps({'state': 'SUCCEEDED', 'tool_calls': 1}), encoding='utf-8')
    args = ['--trace', str(source), '--expect', str(expectation)]
    assert main(args) == 0 and json.loads(capsys.readouterr().out)['passed']
    expectation.write_text(json.dumps({'state': 'SUCCEEDED', 'tool_calls': 2}), encoding='utf-8')
    assert main(args) == 1
    assert json.loads(capsys.readouterr().out)['expectation_differences'] == ['tool_calls']
    source.write_bytes(b' '*65537)
    assert main(args) == 2
    assert capsys.readouterr().err.strip() == 'Invalid Runtime trace or expectation'


def test_independent_step_totals_cannot_be_hidden_by_a_matching_baseline():
    trace = sample('SUCCEEDED', None, ('READ', 'SUCCEEDED'))
    trace['steps'][0].update(kind='MODEL', effect='NONE')
    assert replay(trace)['issues'] == ['MODEL_COUNT_MISMATCH', 'TOOL_COUNT_MISMATCH']


def test_suite_fails_when_expected_cases_are_missing_or_replaced():
    trace = sample('SUCCEEDED', None, ('READ', 'SUCCEEDED'))
    expected = {'state': 'SUCCEEDED'}
    baseline = {'schema_version': 'runtime-trace-baseline-v1', 'cases': {'normal': expected, 'lost-ack': expected}}
    suite = {'schema_version': 'runtime-trace-suite-v1', 'cases': {'normal': trace, 'extra': trace}}
    outcome = compare_suite(suite, baseline)
    assert outcome['cases']['normal']['passed'] is True
    assert outcome['missing_cases'] == ['lost-ack'] and outcome['unexpected_cases'] == ['extra']
    assert outcome['passed'] is False


def test_suite_requires_a_baseline_and_rejects_duplicate_case_labels(tmp_path, capsys):
    suite = tmp_path/'suite.json'
    baseline = tmp_path/'baseline.json'
    suite.write_text('{"schema_version":"runtime-trace-suite-v1","cases":{"normal":{},"normal":{}}}', encoding='utf-8')
    baseline.write_text(json.dumps({'schema_version': 'runtime-trace-baseline-v1', 'cases': {'normal': {'state': 'SUCCEEDED'}}}),
                        encoding='utf-8')
    args = ['--suite', str(suite)]
    assert main(args) == 2
    assert main(args+['--expect', str(baseline)]) == 2
    assert capsys.readouterr().out == ''
