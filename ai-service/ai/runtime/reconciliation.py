"""Explicit trusted-host recovery of past business commits through read-only receipts.

FAILED remains FAILED; a matching immutable business receipt may clear only the
unknown-effect hold. This is not a model tool, public route or automatic poller.
"""
import asyncio
from dataclasses import dataclass
import re
from typing import ClassVar
from uuid import UUID

from .result import ResultClient
from .ticket_creation import TicketCreationClient
from .types import RunInput,RuntimeConflict,identifier,fingerprint,reference


@dataclass(frozen=True)
class _RecoveryCandidate:
    operation: ClassVar[str]
    version: ClassVar[str]
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
            raise RuntimeConflict('Recovery requires a held terminal run')
        writes=[s for s in steps if s['effect']=='WRITE']
        if len(writes)!=1:raise RuntimeConflict('Recovery requires exactly one write')
        step=writes[0];intent=step['intent_ref']
        try:
            if (step['tenant_code']!=run['tenant_code'] or step['run_id']!=run['run_id']
                    or step['state']!='UNKNOWN' or step['kind']!='TOOL' or step['fence']>run['fence']
                    or type(intent) is not dict or set(intent)!={'operation','version','resource_ref','input_ref','arguments_sha256'}
                    or intent['operation']!=cls.operation or intent['version']!=cls.version
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
            raise RuntimeConflict('Invalid recovery checkpoint') from None
        return cls(source,run['run_id'],run['fence'],step['step_no'],step['step_key'],step['intent_hash'],snapshot,intent['arguments_sha256'])


@dataclass(frozen=True)
class ReplyRecoveryCandidate(_RecoveryCandidate):
    operation = 'session.reply'
    version = 'reply-v1'


@dataclass(frozen=True)
class TicketRecoveryCandidate(_RecoveryCandidate):
    operation = 'ticket.create'
    version = 'ticket-create-v1'


def _reconcile(store, results, operator_ref, tenant, run_id, candidate_type, read_audit, confirm):
    previous = read_audit(tenant, run_id)
    if previous is not None:
        return previous
    run = store.get(tenant, run_id)
    try:
        candidate = candidate_type.from_rows(run, store.steps(tenant, run_id))
    except RuntimeConflict:
        previous = read_audit(tenant, run_id)
        if previous is not None:
            return previous
        raise
    receipt = asyncio.run(results.receipt(candidate.source, candidate.run_id,
                          expected_snapshot_id=candidate.snapshot_id, expected_hash=candidate.arguments_sha256))
    if receipt is None:
        return None  # Absence is not proof of no effect.
    return confirm(candidate, receipt, operator_ref)


class ReplyReconciler:
    def __init__(self, store, results: ResultClient, operator_ref):
        if not isinstance(results, ResultClient):
            raise ValueError('Configured result SDK required')
        self.store, self.results, self.operator_ref = store, results, identifier(operator_ref)

    def reconcile(self, tenant, run_id):
        return _reconcile(self.store, self.results, self.operator_ref, tenant, run_id,
                          ReplyRecoveryCandidate, self.store.reply_reconciliation, self.store.confirm_reply_commit)


class TicketReconciler:
    def __init__(self, store, tickets: TicketCreationClient, operator_ref):
        if not isinstance(tickets, TicketCreationClient):
            raise ValueError('Configured ticket SDK required')
        self.store, self.tickets, self.operator_ref = store, tickets, identifier(operator_ref)

    def reconcile(self, tenant, run_id):
        return _reconcile(self.store, self.tickets, self.operator_ref, tenant, run_id,
                          TicketRecoveryCandidate, self.store.ticket_reconciliation, self.store.confirm_ticket_commit)
