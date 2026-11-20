"""Explicit trusted-host recovery of a past reply commit. Never executes a WRITE.

FAILED remains FAILED; a matching immutable business receipt may clear only the
unknown-effect hold. This is not a model tool, public route or automatic poller.
"""
import asyncio
from dataclasses import dataclass
import re
from uuid import UUID

from .result import ResultClient,receipt_reference
from .types import RunInput,RuntimeConflict,identifier,fingerprint,reference


@dataclass(frozen=True)
class ReplyRecoveryCandidate:
    source: RunInput
    run_id: UUID
    fence: int
    step_no: int
    step_key: str
    intent_hash: str
    snapshot_id: UUID
    arguments_sha256: str

    def __post_init__(self):
        if not isinstance(self.source,RunInput) or self.source.runtime_version!='runtime-v1':raise ValueError('Invalid recovery source')
        if any(not isinstance(value,UUID) for value in (self.run_id,self.snapshot_id)):raise ValueError('Typed recovery UUID required')
        if any(type(value) is not int or not 1<=value<=9223372036854775807 for value in (self.fence,self.step_no)):
            raise ValueError('Invalid recovery fence/step')
        for digest in (self.intent_hash,self.arguments_sha256):
            if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):raise ValueError('Invalid recovery digest')
        identifier(self.step_key)

    @classmethod
    def from_rows(cls,run,steps):
        if (not run or run['state']!='FAILED' or run['error_code']!='SIDE_EFFECT_UNKNOWN'
                or run['runtime_version']!='runtime-v1' or run['owner'] is not None or run['lease_until'] is not None):
            raise RuntimeConflict('Reply recovery requires a held terminal run')
        writes=[s for s in steps if s['effect']=='WRITE']
        if len(writes)!=1:raise RuntimeConflict('Reply recovery requires exactly one write')
        step=writes[0];intent=step['intent_ref']
        try:
            if (step['tenant_code']!=run['tenant_code'] or step['run_id']!=run['run_id']
                    or step['state']!='UNKNOWN' or step['kind']!='TOOL' or step['fence']>run['fence']
                    or type(intent) is not dict or set(intent)!={'operation','version','resource_ref','input_ref','arguments_sha256'}
                    or intent['operation']!='session.reply' or intent['version']!='reply-v1'
                    or intent['resource_ref']!=run['principal_ref']
                    or not isinstance(intent['arguments_sha256'],str) or not re.fullmatch('[0-9a-f]{64}',intent['arguments_sha256'])
                    or step['intent_hash']!=fingerprint(dict(kind='TOOL',effect='WRITE',intent=intent))):raise ValueError()
            snapshot=UUID(intent['input_ref'])
            if str(snapshot)!=intent['input_ref']:raise ValueError()
            identifier(step['step_key']);reference(step['result_ref'])
            source=RunInput(run['tenant_code'],run['source_event_id'],run['session_no'],run['event_seq'],run['message_id'],
                            run['principal_ref'],run['trace_id'],run['runtime_version'])
            if type(run['fence']) is not int or run['fence']<1:raise ValueError()
        except (ValueError,TypeError,KeyError,AttributeError):
            raise RuntimeConflict('Invalid reply recovery checkpoint') from None
        return cls(source,run['run_id'],run['fence'],step['step_no'],step['step_key'],step['intent_hash'],snapshot,intent['arguments_sha256'])


class ReplyReconciler:
    def __init__(self,store,results: ResultClient,operator_ref):
        if not isinstance(results,ResultClient):raise ValueError('Configured result SDK required')
        self.store,self.results,self.operator_ref=store,results,identifier(operator_ref)

    def reconcile(self,tenant,run_id):
        # DB atomic confirmation also handles concurrent same-proof operations.
        # A completed reconciliation is immutable and does not require a new GET.
        previous=self.store.reply_reconciliation(tenant,run_id)
        if previous is not None:return previous
        run=self.store.get(tenant,run_id)
        try:candidate=ReplyRecoveryCandidate.from_rows(run,self.store.steps(tenant,run_id))
        except RuntimeConflict:
            previous=self.store.reply_reconciliation(tenant,run_id)
            if previous is not None:return previous
            raise
        receipt=asyncio.run(self.results.receipt(candidate.source,candidate.run_id,
                          expected_snapshot_id=candidate.snapshot_id,expected_hash=candidate.arguments_sha256))
        if receipt is None:return None  # Absence is not proof of no effect.
        receipt_reference(receipt,candidate.source,candidate.run_id,candidate.snapshot_id,candidate.arguments_sha256)
        return self.store.confirm_reply_commit(candidate,receipt,self.operator_ref)
