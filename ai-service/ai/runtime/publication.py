"""Publish only a host-verified report through the existing atomic reply protocol.

Receipt recovery proves a DB commit, not exactly-once external channel delivery.
Unconfirmed writes/cancelled leases retain the original UNKNOWN hold policy.
"""
import asyncio
import re
from uuid import UUID

from .reply import arguments_hash
from .result import ResultClient,receipt_reference
from .ticket_loop import TicketReadLoop
from .tools import ToolIdentity
from .worker import PermanentFailure,TransientFailure


def _snapshot(value):
    try:
        snapshot=UUID(value)
        if str(snapshot)!=value:raise ValueError()
        return snapshot
    except (ValueError,TypeError,AttributeError):raise PermanentFailure('REPLY_CHECKPOINT_INVALID') from None


class TicketReplyHandler:
    def __init__(self,loop: TicketReadLoop,result_client: ResultClient,*,receipt_client=None):
        if not isinstance(loop,TicketReadLoop) or not isinstance(result_client,ResultClient):
            raise ValueError('Explicit verified-report loop and result SDK required')
        if receipt_client is not None and not isinstance(receipt_client,ResultClient):
            raise ValueError('Configured receipt SDK required')
        self.loop,self.results,self.receipts=loop,result_client,receipt_client or result_client

    def _lookup(self,context,source,snapshot,digest,key):
        def read():
            receipt=asyncio.run(self.receipts.receipt(source,context.lease.run_id,expected_snapshot_id=snapshot,expected_hash=digest))
            return {'outcome':'not-observed'} if receipt is None else receipt_reference(receipt,source,context.lease.run_id,snapshot,digest)
        return context.step(key,'TOOL','READ',dict(operation='session.reply.receipt',version='reply-v1',
            resource_ref=source.principal_ref,input_ref=str(snapshot),arguments_sha256=digest),read)

    def __call__(self,context):
        context.check_active()
        identity=ToolIdentity.from_context(context);source=identity.source
        if self.loop.model.profile.tenant!=source.tenant:raise PermanentFailure('MODEL_PROFILE_DENIED')
        # A committed write is a past business fact. Confirm it without rebuilding
        # model context or trying to send a potentially changed report a second time.
        completed=[step for step in context.store.steps(source.tenant,identity.run_id)
                   if step['kind']=='TOOL' and step['effect']=='WRITE' and step['state']=='SUCCEEDED']
        if completed:
            if len(completed)!=1:raise PermanentFailure('REPLY_CHECKPOINT_INVALID')
            previous=completed[0];intent=previous['intent_ref']
            if (type(intent) is not dict or set(intent)!={'operation','version','resource_ref','input_ref','arguments_sha256'}
                    or intent['operation']!='session.reply' or intent['version']!='reply-v1' or intent['resource_ref']!=source.principal_ref
                    or not isinstance(intent['arguments_sha256'],str)
                    or not re.fullmatch('[0-9a-f]{64}',intent['arguments_sha256'])):
                raise PermanentFailure('REPLY_CHECKPOINT_INVALID')
            snapshot=_snapshot(intent['input_ref'])
            found=self._lookup(context,source,snapshot,intent['arguments_sha256'],'committed-reply-check')
            if found.get('outcome')!='reply-committed' or found!=previous['result_ref']:
                raise PermanentFailure('REPLY_CHECKPOINT_INVALID')
            return found

        prepared=self.loop.prepare(context)
        snapshot=_snapshot(prepared.value['source_snapshot_id']);text=prepared.value['text']
        digest=arguments_hash(source,identity.run_id,snapshot,text)
        intent=dict(operation='session.reply',version='reply-v1',resource_ref=source.principal_ref,
                    input_ref=str(snapshot),arguments_sha256=digest)
        def publish():
            try:
                receipt=asyncio.run(self.results.reply(source,context.lease,snapshot,text))
                return receipt_reference(receipt,source,identity.run_id,snapshot,digest)
            except TransientFailure:
                # A timeout/5xx can follow an actual commit. One separately
                # reserved READ may prove it; absence is never proof of no effect.
                found=self._lookup(context,source,snapshot,digest,'uncertain-reply-check')
                if found.get('outcome')=='reply-committed':return found
                raise TransientFailure('REPLY_COMMIT_UNCONFIRMED') from None
        return context.step('report-publication','TOOL','WRITE',intent,publish)
