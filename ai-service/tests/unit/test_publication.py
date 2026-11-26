import json
from types import SimpleNamespace

import httpx
import pytest

from ai.runtime.publication import TicketReplyHandler
from ai.runtime.result import ResultClient
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_ticket_loop import fixture, QUERY, REPORT
from test_runtime_source import INPUT, RUN, KEY
from test_chat_transport import response


def receipt(body):
    return {'code': 0, 'message': 'ok', 'data': dict(schema_version='reply-v1', operation='session.reply', tenant_code=INPUT.tenant,
                                                     source_event_id=str(INPUT.source_event_id), run_id=str(RUN), snapshot_id=body['snapshot_id'], arguments_sha256=body['arguments_sha256'],
                                                     message_id='123', message_no='BOT_MSG_1', seq='2')}


def setup(mode='normal'):
    loop, ctx, reads, models, reports, state = fixture([QUERY, REPORT])
    ctx.check_active = lambda: None
    ctx.store = SimpleNamespace(steps=lambda *_: [])
    calls = []
    posted = []

    def server(request):
        calls.append(request)
        if request.method == 'POST':
            posted.append(json.loads(request.content))
            if mode in ('lost_ack', 'absent'):
                raise httpx.ReadTimeout('synthetic committed reply timeout')
            if mode == 'denied':
                return response(b'private auth detail', status=401)
        if request.method == 'GET' and mode == 'absent':
            return response({'code': 40403, 'message': 'none', 'data': None}, status=404)
        return response(receipt(posted[0]))
    publisher = TicketReplyHandler(loop, ResultClient(
        'http://customer.test', KEY, transport=httpx.MockTransport(server)))
    return publisher, ctx, calls, posted, reads, models, state


def test_only_host_verified_template_is_sent_and_write_intent_matches_sdk_body():
    publisher, ctx, calls, posted, reads, models, state = setup()
    result = publisher(ctx)
    assert result['outcome'] == 'reply-committed' and result['business_ref'] == 'message:123'
    assert posted[0]['content'] == '工单 TK-1 当前状态：处理中。' and posted[0]['version'] == 'reply-v1'
    assert posted[0]['owner'] == ctx.lease.owner and posted[0]['fence'] == ctx.lease.fence
    assert [request.method for request in calls] == ['POST']
    write = next(step for step in ctx.steps if step[1:3] == ('TOOL', 'WRITE'))
    assert write[3]['arguments_sha256'] == posted[0]['arguments_sha256'] and write[3]['input_ref'] == posted[0]['snapshot_id']
    assert '工单 TK-1' not in str(result) + str(write[3]) and len(models) == 2


def test_transient_post_failure_queries_receipt_once_without_blind_write_retry():
    publisher, ctx, calls, posted, reads, models, state = setup('lost_ack')
    assert publisher(ctx)['outcome'] == 'reply-committed'
    assert [request.method for request in calls] == ['POST', 'GET'] and len(posted) == 1
    assert any(step[1:3] == ('TOOL', 'READ') and step[3]['operation']
               == 'session.reply.receipt' for step in ctx.steps)


def test_receipt_absence_does_not_become_proof_that_retrying_a_write_is_safe():
    publisher, ctx, calls, posted, reads, models, state = setup('absent')
    with pytest.raises(TransientFailure, match='REPLY_COMMIT_UNCONFIRMED'):
        publisher(ctx)
    assert [request.method for request in calls] == ['POST', 'GET'] and len(posted) == 1


def test_permanent_write_denial_has_no_automatic_post_retry():
    publisher, ctx, calls, posted, reads, models, state = setup('denied')
    with pytest.raises(PermanentFailure, match='REPLY_AUTHORIZATION_DENIED'):
        publisher(ctx)
    assert len(calls) == len(posted) == 1


def test_successful_write_checkpoint_is_confirmed_without_regenerating_changed_source_report():
    publisher, ctx, calls, posted, reads, models, state = setup()
    committed = publisher(ctx)
    intent = next(step[3] for step in ctx.steps if step[1:3] == ('TOOL', 'WRITE'))
    ctx.store.steps = lambda *_: [dict(kind='TOOL', effect='WRITE', state='SUCCEEDED',
                                       intent_ref=intent, result_ref=committed)]
    read_count = len(reads)
    state['owner'] = False
    assert publisher(ctx) == committed
    assert len(models) == 2 and len(reads) == read_count and [
        request.method for request in calls] == ['POST', 'GET']


@pytest.mark.parametrize('fault', ['resource_ref', 'input_ref', 'arguments_sha256', 'result_ref'])
def test_bad_committed_checkpoint_never_triggers_a_new_write(fault):
    publisher, ctx, calls, posted, reads, models, state = setup()
    committed = publisher(ctx)
    intent = dict(next(step[3] for step in ctx.steps if step[1:3] == ('TOOL', 'WRITE')))
    old = dict(committed)
    if fault == 'result_ref':
        old['business_ref'] = 'message:999'
    elif fault == 'resource_ref':
        intent[fault] = 'other-principal'
    elif fault == 'input_ref':
        intent[fault] = 'not-a-uuid'
    else:
        intent[fault] = 'not-a-digest'
    ctx.store.steps = lambda *_: [dict(kind='TOOL', effect='WRITE',
                                       state='SUCCEEDED', intent_ref=intent, result_ref=old)]
    with pytest.raises(PermanentFailure, match='REPLY_CHECKPOINT_INVALID'):
        publisher(ctx)
    assert len(posted) == 1 and len(models) == 2


def test_prepared_report_hides_body_and_cannot_be_mutated_by_publisher():
    loop, ctx, reads, models, reports, state = fixture([QUERY, REPORT])
    prepared = loop.prepare(ctx)
    assert '工单 TK-1' not in repr(prepared)
    with pytest.raises(TypeError):
        prepared.value['text'] = 'unverified text'
    with pytest.raises(TypeError):
        prepared.references['outcome'] = 'fake action'
