"""Check an exported Runtime trace offline, without invoking tools or models."""
import argparse
import json
from pathlib import Path
import sys

from .types import identifier


_RUN_STATES = {'PENDING', 'RUNNING', 'RETRY_WAIT', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'}
_STEP_STATES = {'RESERVED', 'SUCCEEDED', 'FAILED', 'UNKNOWN'}
_MAX_DOCUMENT_BYTES = 65536


def replay(trace):
    """Fold the recorded steps into a compact result and check known invariants."""
    if not isinstance(trace, dict) or trace.get('schema_version') != 'runtime-trace-v1':
        raise ValueError('Unsupported trace schema')
    run, steps = trace.get('run'), trace.get('steps')
    if not isinstance(run, dict) or not isinstance(steps, list) or len(steps) > 100:
        raise ValueError('Invalid trace shape')
    counts = {key: run.get(key) for key in ('step_count', 'model_calls', 'tool_calls', 'retry_count')}
    if any(type(value) is not int or not 0 <= value <= 100 for value in counts.values()):
        raise ValueError('Invalid Run counters')
    state, error = run.get('state'), run.get('error_code')
    if not isinstance(state, str) or state not in _RUN_STATES or error is not None and not isinstance(error, str):
        raise ValueError('Invalid Run outcome')

    unknown_writes = successful_writes = active_steps = model_calls = tool_calls = 0
    for index, step in enumerate(steps, start=1):
        if not isinstance(step, dict) or type(step.get('step_no')) is not int or step['step_no'] != index:
            raise ValueError('Trace step sequence is invalid')
        kind, effect, step_state = (step.get(name) for name in ('kind', 'effect', 'state'))
        if (not all(isinstance(value, str) for value in (kind, effect, step_state))
                or step_state not in _STEP_STATES
                or (kind, effect) not in {('INTERNAL', 'NONE'), ('MODEL', 'NONE'), ('TOOL', 'READ'), ('TOOL', 'WRITE')}):
            raise ValueError('Trace step contract is invalid')
        active_steps += step_state == 'RESERVED'
        model_calls += kind == 'MODEL'
        tool_calls += kind == 'TOOL'
        unknown_writes += effect == 'WRITE' and step_state == 'UNKNOWN'
        successful_writes += effect == 'WRITE' and step_state == 'SUCCEEDED'

    issues = []
    if len(steps) != counts['step_count']:
        issues.append('STEP_COUNT_MISMATCH')
    if model_calls != counts['model_calls']:
        issues.append('MODEL_COUNT_MISMATCH')
    if tool_calls != counts['tool_calls']:
        issues.append('TOOL_COUNT_MISMATCH')
    if (unknown_writes and state in ('SUCCEEDED', 'FAILED', 'CANCELLED')
            and (state, error) != ('FAILED', 'SIDE_EFFECT_UNKNOWN')):
        issues.append('UNKNOWN_WRITE_WITHOUT_HOLD')
    if error == 'SIDE_EFFECT_UNKNOWN' and not unknown_writes:
        issues.append('HOLD_WITHOUT_UNKNOWN_WRITE')
    if error == 'SIDE_EFFECT_CONFIRMED' and (state != 'FAILED' or not successful_writes):
        issues.append('MISSING_CONFIRMED_WRITE')
    if state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'WAITING_APPROVAL') and active_steps:
        issues.append('CLOSED_RUN_HAS_RESERVED_STEPS')
    return {'state': state, 'error_code': error, **counts, 'unknown_writes': unknown_writes,
            'successful_writes': successful_writes, 'issues': issues}


def compare(trace, expected):
    report = replay(trace)
    if not isinstance(expected, dict) or not expected or set(expected) - (set(report) - {'issues'}):
        raise ValueError('Invalid trace expectation')
    differences = [key for key, value in expected.items()
                   if type(report[key]) is not type(value) or report[key] != value]
    return report | {'expectation_differences': differences, 'passed': not report['issues'] and not differences}


def compare_suite(suite, baseline):
    for document, version in ((suite, 'runtime-trace-suite-v1'), (baseline, 'runtime-trace-baseline-v1')):
        if (not isinstance(document, dict) or set(document) != {'schema_version', 'cases'}
                or document['schema_version'] != version or not isinstance(document['cases'], dict)
                or not 1 <= len(document['cases']) <= 32):
            raise ValueError('Invalid trace suite or baseline')
        for name in document['cases']:
            identifier(name)
    observed, expected = suite['cases'], baseline['cases']
    missing = sorted(expected.keys() - observed.keys())
    unexpected = sorted(observed.keys() - expected.keys())
    reports = {name: compare(observed[name], expected[name]) for name in sorted(expected.keys() & observed.keys())}
    return {'schema_version': 'runtime-trace-suite-result-v1', 'cases': reports,
            'missing_cases': missing, 'unexpected_cases': unexpected,
            'passed': not missing and not unexpected and all(case['passed'] for case in reports.values())}


def _json_document(path):
    with path.open('rb') as file:
        raw = file.read(_MAX_DOCUMENT_BYTES + 1)
    if len(raw) > _MAX_DOCUMENT_BYTES:
        raise ValueError('Trace document exceeds its byte limit')
    def unique(pairs):
        result = dict(pairs)
        if len(result) != len(pairs):
            raise ValueError('Duplicate trace field')
        return result

    return json.loads(raw, object_pairs_hook=unique)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--trace', type=Path)
    mode.add_argument('--suite', type=Path)
    parser.add_argument('--expect', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.suite is not None:
            if args.expect is None:
                raise ValueError('A suite requires an independent baseline')
            report = compare_suite(_json_document(args.suite), _json_document(args.expect))
        elif args.expect is not None:
            report = compare(_json_document(args.trace), _json_document(args.expect))
        else:
            report = replay(_json_document(args.trace))
            report.update(expectation_differences=[], passed=not report['issues'])
        print(json.dumps(report))
        return 0 if report['passed'] else 1
    except (OSError, ValueError, UnicodeError, RecursionError):
        print('Invalid Runtime trace or expectation', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
