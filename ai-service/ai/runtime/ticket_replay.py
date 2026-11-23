"""Replay an offline tape through the read-only ticket planning flow."""
import argparse
import json
from pathlib import Path
import re
import sys

from pydantic import ValidationError

from .customer_tools import TicketArguments
from .ticket_loop import LOOP_VERSION, STATUS_NAMES, TICKET_READ, planner_policy, run_read_plan
from .tickets import ticket_timestamp
from .trace_replay import _json_document
from .types import fingerprint, identifier
from .worker import PermanentFailure

TAPE_VERSION = 'ticket-read-tape-v1'
SUITE_VERSION = 'ticket-read-replay-suite-v1'
_SHA = re.compile(r'[0-9a-f]{64}')
_EXPECTED = {'outcome', 'error_code', 'ticket_status', 'model_calls', 'ticket_reads'}
_RECORDED_FAILURES = {
    'model': {'MODEL_TIMEOUT', 'MODEL_UNAVAILABLE', 'MODEL_RATE_LIMITED', 'MODEL_OUTPUT_INVALID'},
    'ticket_read': {'TICKET_REVOKED', 'TICKET_SOURCE_REVOKED', 'TICKET_SOURCE_CHANGED', 'TICKET_SOURCE_EXPIRED',
                    'TICKET_PAYLOAD_INVALID', 'TOOL_UNAVAILABLE', 'TOOL_DENIED'},
}


class TapeMismatch(ValueError):
    pass


def _require(condition, code):
    if not condition:
        raise TapeMismatch(code)


def _digest(value):
    return isinstance(value, str) and _SHA.fullmatch(value) is not None


class RecordedCalls:
    def __init__(self, events):
        self.events = events
        self.cursor = self.model_calls = self.ticket_reads = 0

    def _next(self, kind, binding):
        _require(self.cursor < len(self.events), 'MISSING_RECORDED_CALL')
        event = self.events[self.cursor]
        _require(isinstance(event, dict) and event.get('kind') == kind, 'RECORDED_CALL_ORDER_CHANGED')
        _require(all(event.get(key) == value for key, value in binding.items()), 'RECORDED_CALL_INPUT_CHANGED')
        self.cursor += 1
        return event

    @staticmethod
    def _result(event, binding_fields, name):
        if event.keys() == binding_fields | {'kind', 'error_code'}:
            code = event['error_code']
            _require(isinstance(code, str) and code in _RECORDED_FAILURES[event['kind']], 'INVALID_RECORDED_ERROR')
            raise PermanentFailure(code)
        _require(event.keys() == binding_fields | {'kind', name}, 'INVALID_RECORDED_RESULT')
        return event[name]

    def plan(self, _turn, messages):
        event = self._next('model', {'input_sha256': fingerprint(messages)})
        self.model_calls += 1
        reply = self._result(event, {'input_sha256'}, 'reply')
        _require(isinstance(reply, str) and 0 < len(reply.encode('utf-8')) <= 8192 and '\0' not in reply,
                 'INVALID_RECORDED_MODEL_REPLY')
        return reply

    def read(self, slot, call):
        if call.name != TICKET_READ[0]:
            raise PermanentFailure('TOOL_UNKNOWN')
        if call.version != TICKET_READ[1]:
            raise PermanentFailure('TOOL_VERSION_MISMATCH')
        try:
            ticket = TicketArguments.model_validate(call.arguments).ticket_no
        except ValidationError:
            raise PermanentFailure('TOOL_ARGUMENTS_INVALID') from None
        event = self._next('ticket_read', {'slot': slot, 'ticket_no': ticket})
        self.ticket_reads += 1
        result = self._result(event, {'slot', 'ticket_no'}, 'result')
        _require(isinstance(result, dict) and result.keys() == {'ticket_no', 'status', 'updated_at'}
                 and result['ticket_no'] == ticket and type(result['status']) is int and result['status'] in STATUS_NAMES,
                 'INVALID_RECORDED_TICKET_RESULT')
        try:
            ticket_timestamp(result['updated_at'])
        except ValueError:
            raise TapeMismatch('INVALID_RECORDED_TICKET_RESULT') from None
        return result


def replay_tape(tape):
    fields = {'schema_version', 'loop_version', 'policy_sha256', 'catalog', 'source_text', 'max_turns',
              'canonical_history', 'events', 'expected'}
    _require(isinstance(tape, dict) and tape.keys() == fields and tape['schema_version'] == TAPE_VERSION, 'INVALID_TAPE')
    _require(identifier(tape['loop_version']) == LOOP_VERSION, 'LOOP_VERSION_CHANGED')
    text, turns, catalog, events, expected = (tape[key] for key in ('source_text', 'max_turns', 'catalog', 'events', 'expected'))
    _require(isinstance(text, str) and text.strip() and len(text.encode('utf-8')) <= 8192 and '\0' not in text,
             'INVALID_TAPE_SOURCE')
    _require(type(turns) is int and 1 <= turns <= 4 and type(tape['canonical_history']) is bool, 'INVALID_TAPE_LIMITS')
    _require(isinstance(catalog, list) and len(catalog) == 1 and isinstance(catalog[0], dict)
             and (catalog[0].get('name'), catalog[0].get('version')) == TICKET_READ
             and catalog[0].get('effect') == 'READ' and catalog[0].get('scope') == 'ticket:read', 'INVALID_REPLAY_CATALOG')
    _require(isinstance(events, list) and 1 <= len(events) <= 2 * turns and isinstance(expected, dict)
             and expected.keys() == _EXPECTED, 'INVALID_TAPE_EVENTS_OR_EXPECTATION')
    policy = planner_policy(catalog)
    _require(_digest(tape['policy_sha256']) and tape['policy_sha256'] == fingerprint(policy), 'POLICY_CHANGED')

    calls = RecordedCalls(events)
    outcome, error, status = None, None, None
    try:
        ticket = run_read_plan(text, policy, calls.plan, calls.read, max_turns=turns,
                               canonical_history=tape['canonical_history'])
        outcome = 'clarification-ready' if ticket is None else 'ticket-status-ready'
        status = ticket['status'] if ticket is not None else None
    except PermanentFailure as failure:
        error = failure.code
    _require(calls.cursor == len(events), 'UNUSED_RECORDED_CALLS')
    result = {'outcome': outcome, 'error_code': error, 'ticket_status': status,
              'model_calls': calls.model_calls, 'ticket_reads': calls.ticket_reads}
    differences = sorted(key for key in expected if type(expected[key]) is not type(result[key]) or expected[key] != result[key])
    return {'passed': not differences, 'loop_version': LOOP_VERSION, 'policy_sha256': fingerprint(policy),
            'observed': result, 'differences': differences}


def compare_suite(suite):
    _require(isinstance(suite, dict) and suite.keys() == {'schema_version', 'cases'} and suite['schema_version'] == SUITE_VERSION
             and isinstance(suite['cases'], dict) and 1 <= len(suite['cases']) <= 16, 'INVALID_REPLAY_SUITE')
    cases = {}
    for name, tape in suite['cases'].items():
        identifier(name)
        try:
            cases[name] = replay_tape(tape)
        except TapeMismatch as failure:
            cases[name] = {'passed': False, 'issue': str(failure)}
        except (ValueError, UnicodeError, RecursionError):
            cases[name] = {'passed': False, 'issue': 'INVALID_REPLAY_TAPE'}
    return {'schema_version': 'ticket-read-replay-result-v1', 'mode': 'recorded-read-only', 'loop_version': LOOP_VERSION,
            'cases': cases, 'passed': all(case['passed'] for case in cases.values())}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(report := compare_suite(_json_document(args.suite)), sort_keys=True))
        return 0 if report['passed'] else 1
    except (OSError, ValueError, UnicodeError, RecursionError):
        print('Invalid ticket replay document', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
