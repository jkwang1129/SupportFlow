"""Exact operation binding on real restricted Run/Step authority; no business writes."""
import json
import os

from fastapi.testclient import TestClient
import pytest

from ai.runtime.types import ActionDenied
from runtime_api_support import build_app,encode,signed,PATH,KEYS
from ai.runtime.authority import sign
from test_runtime_store_postgres import store,expire
from test_runtime_authority_postgres import setup,authorize

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def values(store,**kwargs):return setup(store,intent_change=dict(operation='ticket.create',version='ticket-create-v1') | kwargs)


def ticket_authorize(store,items,**changes):
    source,row,lease,snapshot,digest,_=items
    return store.authorize_ticket_create(**(dict(tenant=source.tenant,run_id=row['run_id'],source_event_id=source.source_event_id,
        owner=lease.owner,fence=lease.fence,snapshot_id=snapshot,arguments_hash=digest) | changes))


def test_ticket_grant_is_bound_to_exact_write_and_does_not_change_budgets_or_steps(store):
    items=values(store);source,row,lease,snapshot,digest,_=items
    before=store.get(source.tenant,row['run_id']);steps=store.steps(source.tenant,row['run_id'])
    value=ticket_authorize(store,items)
    assert value['operation']=='ticket.create' and value['version']=='action-v1' and value['snapshot_id']==str(snapshot)
    assert store.get(source.tenant,row['run_id'])==before and store.steps(source.tenant,row['run_id'])==steps
    with pytest.raises(ActionDenied):authorize(store,items)
    assert ticket_authorize(store,items,tenant='TENANT_B') is None


def test_unapproved_write_cannot_obtain_notice_authority(store):
    items=values(store)
    assert ticket_authorize(store,items)['version']=='action-v1'
    with pytest.raises(ActionDenied):
        ticket_authorize(store,items,require_approval=True)


@pytest.mark.parametrize('change',[dict(operation='session.reply',version='reply-v1'),dict(operation='session.reply'),
    dict(version='reply-v1'),dict(business_ref='extra'),dict(resource_ref='visitor-session:8')])
def test_reply_or_mismatched_intent_never_grants_ticket_authority(store,change):
    items=values(store,**change)
    with pytest.raises(ActionDenied):ticket_authorize(store,items)


@pytest.mark.parametrize('field,value',[('owner','other-worker'),('fence',99),('arguments_hash','b'*64)])
def test_ticket_permission_checks_current_owner_fence_and_exact_arguments(store,field,value):
    items=values(store)
    with pytest.raises(ActionDenied):ticket_authorize(store,items,**{field:value})


@pytest.mark.parametrize('transition',['cancel','lease','deadline'])
def test_cancelled_or_expired_ticket_execution_cannot_obtain_new_permit(store,transition):
    items=values(store);lease=items[2]
    if transition=='cancel':store.cancel(lease.tenant,lease.run_id)
    else:expire(lease,deadline=transition=='deadline')
    with pytest.raises(ActionDenied):ticket_authorize(store,items)


@pytest.mark.parametrize('state',['SUCCEEDED','FAILED','UNKNOWN'])
def test_nonreserved_ticket_write_has_no_new_execution_authority(store,state):
    items=values(store);store.complete_step(items[2],'reply-1',state,{})
    with pytest.raises(ActionDenied):ticket_authorize(store,items)


def test_authenticated_ticket_endpoint_keeps_reply_denial_and_cancellation_with_real_store(store,monkeypatch):
    items=values(store);source,row,lease,snapshot,digest,_=items
    body=dict(tenant_code=source.tenant,source_event_id=str(source.source_event_id),owner=lease.owner,fence=lease.fence,
        snapshot_id=str(snapshot),arguments_sha256=digest,operation='ticket.create',version='ticket-create-v1')
    path=PATH+'/'+str(row['run_id'])+'/authorize'
    with TestClient(build_app(monkeypatch,store=store,ticket_enabled=True)) as client:
        raw=encode(body);result=client.post(path,content=raw,headers=signed('POST',path,raw))
        assert result.status_code==200;grant=result.json();signature=grant.pop('signature');assert sign(grant,KEYS['customer'])['signature']==signature
        raw=encode(body | dict(operation='session.reply',version='reply-v1'))
        assert client.post(path,content=raw,headers=signed('POST',path,raw)).status_code==409
        store.cancel(source.tenant,row['run_id']);raw=encode(body)
        assert client.post(path,content=raw,headers=signed('POST',path,raw)).status_code==409
