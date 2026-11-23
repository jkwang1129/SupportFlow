"""Cross-service timelines keep receipt ambiguity and reject mismatched bindings."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from ai.runtime.trace_timeline import correlate
from ai.runtime.types import RuntimeConflict


@pytest.fixture
def matched():
    start = datetime(2026, 11, 23, tzinfo=timezone.utc)
    end = start + timedelta(milliseconds=135)
    event_id, run_id = uuid4(), uuid4()
    delivery = dict(event_id=event_id, run_id=run_id, message_id=7, event_seq=3,
                    trace_id='fixed-request', runtime_version='runtime-v1', state='DELIVERED', attempts=2,
                    error_code=None, created_at=start, ready_at=start, delivered_at=end,
                    principal_ref='private-principal', session_no='private-session')
    run = dict(run_id=run_id, trace_id='fixed-request', runtime_version='runtime-v1', state='SUCCEEDED',
               created_at=start, finished_at=end)
    step = dict(step_no=1, created_at=start, completed_at=end, state='SUCCEEDED', operation='model.chat')
    return delivery, {'delivery': dict(source_event_id=event_id, message_id=7, event_seq=3),
                      'trace': {'run': run, 'steps': [step]}, 'approval': None}


def test_matching_receipt_joins_metadata_and_measured_durations(matched):
    delivery, runtime = matched
    track = correlate('TENANT_A', delivery, runtime)
    assert track['binding_status'] == 'matched' and track['trace_id'] == 'fixed-request'
    assert track['event_id'] == delivery['event_id'] and track['run_id'] == delivery['run_id']
    assert track['customer_delivery']['elapsed_to_receipt_ms'] == 135.0
    assert track['python_runtime']['run']['elapsed_to_finish_ms'] == 135.0
    assert track['python_runtime']['steps'][0]['elapsed_ms'] == 135.0
    assert 'private-' not in repr(track) and 'message_id' not in repr(track) and 'event_seq' not in repr(track)


@pytest.mark.parametrize('field,value', [('event_id', uuid4()), ('run_id', uuid4()), ('message_id', 8),
                                         ('event_seq', 11), ('trace_id', 'other'), ('runtime_version', 'other')])
def test_mismatched_receipt_is_never_rendered(matched, field, value):
    delivery, runtime = matched
    with pytest.raises(RuntimeConflict, match='binding differs'):
        correlate('TENANT_A', delivery | {field: value}, runtime)


def test_missing_receipt_and_missing_runtime_are_distinguishable(matched):
    delivery, runtime = matched
    pending = delivery | {'run_id': None, 'delivered_at': None, 'state': 'PENDING', 'error_code': 'DELIVERY_RETRY'}
    awaiting_receipt = correlate('TENANT_A', pending, runtime)
    assert awaiting_receipt['binding_status'] == 'awaiting_receipt'
    assert awaiting_receipt['customer_delivery']['elapsed_to_receipt_ms'] is None
    outbox_only = correlate('TENANT_A', pending, None)
    assert outbox_only['binding_status'] == 'outbox_only' and outbox_only['python_runtime'] is None
    assert correlate('TENANT_A', delivery, None)['binding_status'] == 'receipt_without_runtime'
    assert correlate('TENANT_A', None, runtime)['binding_status'] == 'runtime_only'
    assert correlate('TENANT_A', None, None) is None


def test_open_or_inverted_intervals_do_not_invent_elapsed_time(matched):
    delivery, runtime = matched
    delivery['delivered_at'] = delivery['created_at'] - timedelta(seconds=1)
    runtime['trace']['run']['finished_at'] = None
    runtime['trace']['steps'][0]['completed_at'] = None
    track = correlate('TENANT_A', delivery, runtime)
    assert track['customer_delivery']['elapsed_to_receipt_ms'] is None
    assert track['python_runtime']['run']['elapsed_to_finish_ms'] is None
    assert track['python_runtime']['steps'][0]['elapsed_ms'] is None
