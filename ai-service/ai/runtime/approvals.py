"""Trusted control plane for pausing and deciding one internal ticket approval."""
from datetime import timedelta
from uuid import UUID, uuid4

from psycopg.types.json import Jsonb

from .ticket_creation import arguments_hash
from .tools import ToolCall, ToolExecutor, ToolIdentity, WriteTool
from .types import RunInput, RuntimeConflict, fingerprint, identifier
from .worker import ApprovalPending

_OPERATION = 'ticket.create'
_VERSION = 'ticket-create-v1'
_TOOL = 'create_ticket'
_PURPOSE = 'customer-support-ticket'
_CHECKPOINT = 'ticket-approval-plan'


class TicketApprovals:
    """Call from a host with a resolved tenant, current source and trusted reviewer identity."""

    def __init__(self, store):
        self.store = store

    def pause(self, context, snapshot_id, title, content, *, wait_seconds=60):
        if not isinstance(snapshot_id, UUID) or type(wait_seconds) is not int or not 1 <= wait_seconds <= 3600:
            raise ValueError('A typed source snapshot and bounded wait are required')
        identity = ToolIdentity.from_context(context)
        lease = context.lease
        digest = arguments_hash(identity.source, identity.run_id, snapshot_id, title, content)
        # Reject proposals that fit the business API but exceed the tool's
        # serialized argument budget before asking a human to approve them.
        ToolCall(_TOOL, _VERSION, dict(title=title, content=content))
        intent = dict(operation=_OPERATION, version=_VERSION, resource_ref=identity.source.principal_ref,
                      input_ref=str(snapshot_id), arguments_sha256=digest)
        expected = dict(title=title, content=content, snapshot_id=str(snapshot_id), arguments_sha256=digest)
        # Persist original arguments with the existing atomic payload/step contract.
        # A crash before the pause transaction can re-use this successful step.
        checked = context.payload_step(_CHECKPOINT, 'INTERNAL', 'NONE', intent, 'working-state-v1', lambda: expected)
        if checked != expected:
            raise RuntimeConflict('Approval checkpoint changed')
        with self.store._scope(lease.tenant) as connection:
            run = self.store._owned(connection, lease)
            if self._approval(connection, lease.tenant, lease.run_id) is not None:
                raise RuntimeConflict('This task already has an approval')
            if connection.execute("SELECT 1 FROM agent_step WHERE tenant_code=%s AND run_id=%s AND (effect='WRITE' OR state='RESERVED') LIMIT 1",
                                  (lease.tenant, lease.run_id)).fetchone():
                raise RuntimeConflict('Approval must precede all writes and wait for active steps')
            expected_hash = fingerprint(dict(kind='INTERNAL', effect='NONE', intent=intent))
            step_prefix = 's' + fingerprint(_CHECKPOINT)[:40] + ':'
            checkpoint = connection.execute("""SELECT s.result_ref FROM agent_step s JOIN agent_payload p
                ON p.tenant_code=s.tenant_code AND p.run_id=s.run_id AND p.step_no=s.step_no
                WHERE s.tenant_code=%s AND s.run_id=%s AND left(s.step_key,%s)=%s AND s.state='SUCCEEDED'
                    AND s.intent_hash=%s AND p.payload_schema='working-state-v1' AND p.expires_at>clock_timestamp()
                    AND s.result_ref->>'resource_ref'='payload:'||p.payload_id::text""",
                (lease.tenant, lease.run_id, len(step_prefix), step_prefix, expected_hash)).fetchone()
            if checkpoint is None:
                raise RuntimeConflict('Approval checkpoint is unavailable')
            now = self._clock(connection)
            expires = min(run['deadline_at'], now + timedelta(seconds=wait_seconds))
            if expires <= now:
                raise RuntimeConflict('Approval window expired')
            approval_id = uuid4()
            connection.execute("""INSERT INTO agent_approval(tenant_code,run_id,approval_id,principal_ref,snapshot_id,
                tool_name,operation,schema_version,purpose,arguments_sha256,requested_at,expires_at)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                (lease.tenant, lease.run_id, approval_id, run['principal_ref'], snapshot_id,
                 _TOOL, _OPERATION, _VERSION, _PURPOSE, digest, now, expires))
            refs = dict(outcome='waiting-approval', operation=_OPERATION, version=_VERSION,
                        input_ref=checkpoint['result_ref']['resource_ref'], resource_ref=str(approval_id), arguments_sha256=digest)
            connection.execute("""UPDATE agent_run SET state='WAITING_APPROVAL',owner=NULL,lease_until=NULL,
                fence=fence+1,error_code=NULL,result_ref=%s,updated_at=%s WHERE tenant_code=%s AND run_id=%s""",
                (Jsonb(refs), now, lease.tenant, lease.run_id))
        raise ApprovalPending()

    def get(self, tenant, run_id):
        with self.store._scope(tenant) as connection:
            return connection.execute('SELECT * FROM agent_approval WHERE tenant_code=%s AND run_id=%s',
                                      (tenant, UUID(str(run_id)))).fetchone()

    def preview(self, tenant, run_id, source_event_id):
        """Read the exact proposal from a live approval without advancing the Run."""
        with self.store._scope(tenant) as connection:
            run = connection.execute("""SELECT * FROM agent_run
                WHERE tenant_code=%s AND run_id=%s AND source_event_id=%s FOR SHARE""",
                (tenant, run_id, source_event_id)).fetchone()
            if run is None:
                return None
            row = connection.execute('SELECT * FROM agent_approval WHERE tenant_code=%s AND run_id=%s',
                                     (tenant, run_id)).fetchone()
            if row is None or row['principal_ref'] != run['principal_ref']:
                return None
            if not (row['status'] == 'PENDING' and run['state'] == 'WAITING_APPROVAL'
                    or row['status'] == 'APPROVED' and run['state'] in ('PENDING', 'RETRY_WAIT', 'RUNNING')):
                return None
            now = self._clock(connection)
            if min(row['expires_at'], run['deadline_at']) <= now:
                return None
            args = self._checkpoint(connection, RunInput.from_run(run), run, row)
            return dict(approval_id=row['approval_id'], status=row['status'], expires_at=row['expires_at'],
                        arguments_sha256=args['arguments_sha256'], title=args['title'], content=args['content'])

    def active_reference(self, tenant, session_no):
        """Locate the one live approval for an already authorized session."""
        identifier(session_no)
        with self.store._scope(tenant) as connection:
            rows = connection.execute("""SELECT r.run_id,r.source_event_id,a.status,a.expires_at
                FROM agent_run r JOIN agent_approval a ON a.tenant_code=r.tenant_code AND a.run_id=r.run_id
                WHERE r.tenant_code=%s AND r.session_no=%s AND a.principal_ref=r.principal_ref
                  AND r.deadline_at>clock_timestamp() AND a.expires_at>clock_timestamp()
                  AND ((r.state='WAITING_APPROVAL' AND a.status='PENDING')
                    OR (r.state IN ('PENDING','RETRY_WAIT','RUNNING') AND a.status='APPROVED'))
                LIMIT 2""", (tenant, session_no)).fetchall()
        if len(rows) > 1:
            raise RuntimeConflict('Multiple live approvals for the same session')
        return rows[0] if rows else None

    def latest_reviewed_progress(self, tenant, session_no):
        """Project only progress and its authorization references for a previously approved Run."""
        identifier(session_no)
        with self.store._scope(tenant) as connection:
            return connection.execute("""SELECT r.tenant_code,r.session_no,r.source_event_id,r.run_id,
                                                a.snapshot_id,r.state,r.attempt,r.step_count
                FROM agent_run r JOIN agent_approval a ON a.tenant_code=r.tenant_code AND a.run_id=r.run_id
                WHERE r.tenant_code=%s AND r.session_no=%s AND a.principal_ref=r.principal_ref
                  AND r.runtime_version='runtime-v1' AND a.decided_at IS NOT NULL
                  AND ((r.state IN ('RUNNING','RETRY_WAIT') AND a.status='CONSUMED')
                    OR (r.state IN ('SUCCEEDED','FAILED','CANCELLED')
                       AND a.status IN ('APPROVED','CONSUMED','REVOKED','EXPIRED','CANCELLED')))
                ORDER BY r.event_seq DESC LIMIT 1""", (tenant, session_no)).fetchone()

    def decide(self, tenant, run_id, approval_id, reviewer, *, approve, expected=None):
        if type(approve) is not bool:
            raise ValueError('Approval decision must be explicit')
        identifier(reviewer)
        target = 'APPROVED' if approve else 'DENIED'
        with self.store._scope(tenant) as connection:
            run, row = self._locked(connection, tenant, run_id, approval_id, expected)
            if row['status'] == 'DENIED' and not approve:
                return row
            now = self._clock(connection)
            if row['status'] == 'APPROVED' and approve:
                if now >= row['expires_at'] and run['state'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                    return self._close(connection, run, row, 'EXPIRED', 'system:approval-expiry', now)
                return row
            if row['status'] != 'PENDING' or run['state'] != 'WAITING_APPROVAL':
                raise RuntimeConflict('Approval has already changed')
            if row['expires_at'] <= now or run['deadline_at'] <= now:
                return self._close(connection, run, row, 'EXPIRED', 'system:approval-expiry', now)
            updated = connection.execute("""UPDATE agent_approval SET status=%s,decided_by=%s,decided_at=%s
                WHERE tenant_code=%s AND run_id=%s RETURNING *""", (target, reviewer, now, tenant, row['run_id'])).fetchone()
            if approve:
                connection.execute("""UPDATE agent_run SET state='PENDING',ready_at=%s,updated_at=%s
                    WHERE tenant_code=%s AND run_id=%s""", (now, now, tenant, row['run_id']))
            else:
                self.store._terminal(connection, run, 'FAILED', 'APPROVAL_DENIED', run['result_ref'])
            return updated

    def approved_arguments(self, context):
        """Load the exact approved checkpoint for a currently owned resumed task."""
        identity = ToolIdentity.from_context(context)
        lease = context.lease
        with self.store._scope(lease.tenant) as connection:
            run = self.store._owned(connection, lease)
            row = self._approval(connection, lease.tenant, lease.run_id)
            if row is None or row['principal_ref'] != run['principal_ref']:
                raise RuntimeConflict('Live approval is unavailable')
            if row['status'] == 'CONSUMED':
                step = connection.execute("""SELECT kind,effect,state,intent_ref FROM agent_step
                    WHERE tenant_code=%s AND run_id=%s AND step_no=%s FOR SHARE""",
                    (lease.tenant, lease.run_id, row['consumed_step_no'])).fetchone()
                expected = dict(operation=_OPERATION, version=_VERSION, resource_ref=run['principal_ref'],
                                input_ref=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256'])
                if step != dict(kind='TOOL', effect='WRITE', state='SUCCEEDED', intent_ref=expected):
                    raise RuntimeConflict('Consumed approval has no verified successful write')
            elif row['status'] != 'APPROVED' or row['expires_at'] <= self._clock(connection):
                raise RuntimeConflict('Live approval is unavailable')
            args = self._checkpoint(connection, identity.source, run, row)
        context.check_active()
        return args

    def _checkpoint(self, connection, source, run, row):
        refs = run['result_ref']
        if (refs.get('outcome') != 'waiting-approval' or refs.get('resource_ref') != str(row['approval_id'])
                or refs.get('arguments_sha256') != row['arguments_sha256']
                or refs.get('operation') != _OPERATION or refs.get('version') != _VERSION):
            raise RuntimeConflict('Approval checkpoint reference changed')
        try:
            payload_ref = refs['input_ref']
            payload_id = UUID(payload_ref.removeprefix('payload:'))
            if payload_ref != 'payload:'+str(payload_id):
                raise ValueError()
        except (KeyError, TypeError, ValueError, AttributeError):
            raise RuntimeConflict('Approval checkpoint reference is invalid') from None
        payload = self.store._payload(connection, source.tenant, run['run_id'], payload_id)
        if payload is None or payload.schema != 'working-state-v1':
            raise RuntimeConflict('Approval checkpoint is unavailable')
        value = payload.value
        try:
            if set(value) != {'title', 'content', 'snapshot_id', 'arguments_sha256'}:
                raise ValueError()
            digest = arguments_hash(source, run['run_id'], row['snapshot_id'], value['title'], value['content'])
            if digest != row['arguments_sha256'] or value['arguments_sha256'] != digest or value['snapshot_id'] != str(row['snapshot_id']):
                raise ValueError()
        except (TypeError, ValueError):
            raise RuntimeConflict('Approval checkpoint arguments changed') from None
        return dict(snapshot_id=row['snapshot_id'], title=value['title'], content=value['content'], arguments_sha256=digest)

    def execute(self, context, executor):
        """Resume via the same registered ticket adapter and its current source check."""
        if not isinstance(executor, ToolExecutor):
            raise ValueError('Configured tool executor required')
        args = self.approved_arguments(context)
        call = ToolCall(_TOOL, _VERSION, dict(title=args['title'], content=args['content']))
        tool = executor.registry.resolve(call)
        if not isinstance(tool, WriteTool) or tool.operation != _OPERATION:
            raise RuntimeConflict('Approved write tool does not match')
        return executor.execute(context, 'approved-ticket-create', call)

    def revoke(self, tenant, run_id, approval_id, reviewer, *, expected=None):
        identifier(reviewer)
        with self.store._scope(tenant) as connection:
            run, row = self._locked(connection, tenant, run_id, approval_id, expected)
            if row['status'] == 'REVOKED':
                return row
            if row['status'] != 'APPROVED' or run['state'] not in ('PENDING', 'RETRY_WAIT', 'RUNNING'):
                raise RuntimeConflict('Only a live approved request can be revoked')
            now = self._clock(connection)
            if row['expires_at'] <= now:
                return self._close(connection, run, row, 'EXPIRED', 'system:approval-expiry', now)
            return self._close(connection, run, row, 'REVOKED', reviewer, now)

    def expire(self, tenant, run_id, approval_id):
        with self.store._scope(tenant) as connection:
            run, row = self._locked(connection, tenant, run_id, approval_id)
            now = self._clock(connection)
            if row['status'] not in ('PENDING', 'APPROVED') or now < row['expires_at']:
                return row
            return self._close(connection, run, row, 'EXPIRED', 'system:approval-expiry', now)

    def expire_due(self, tenant, *, limit=16):
        """Settle a bounded batch before claiming the tenant's next task."""
        if type(limit) is not int or not 1 <= limit <= 32:
            raise ValueError('Approval expiry batch must be bounded')
        with self.store._scope(tenant) as connection:
            runs = connection.execute("""SELECT r.* FROM agent_approval a JOIN agent_run r
                ON r.tenant_code=a.tenant_code AND r.run_id=a.run_id
                WHERE r.tenant_code=%s AND a.status IN ('PENDING','APPROVED') AND a.expires_at<=clock_timestamp()
                    AND r.state IN ('WAITING_APPROVAL','PENDING','RETRY_WAIT','RUNNING')
                    AND NOT EXISTS(SELECT 1 FROM agent_step s WHERE s.tenant_code=r.tenant_code AND s.run_id=r.run_id
                        AND s.effect='WRITE' AND s.state IN ('RESERVED','UNKNOWN'))
                ORDER BY a.expires_at,r.run_id LIMIT %s FOR UPDATE OF r SKIP LOCKED""", (tenant, limit)).fetchall()
            closed = 0
            for run in runs:
                approval = self._approval(connection, tenant, run['run_id'])
                now = self._clock(connection)
                if (approval is not None and approval['status'] in ('PENDING', 'APPROVED')
                        and approval['expires_at'] <= now and not self.store._unknown_write(connection, run)):
                    self._close(connection, run, approval, 'EXPIRED', 'system:approval-expiry', now)
                    closed += 1
            return closed

    @staticmethod
    def _approval(connection, tenant, run_id):
        return connection.execute('SELECT * FROM agent_approval WHERE tenant_code=%s AND run_id=%s FOR UPDATE',
                                  (tenant, run_id)).fetchone()

    def _locked(self, connection, tenant, run_id, approval_id, expected=None):
        run_id, approval_id = UUID(str(run_id)), UUID(str(approval_id))
        run = connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s FOR UPDATE',
                                 (tenant, run_id)).fetchone()
        row = None if run is None else self._approval(connection, tenant, run_id)
        if row is None or row['approval_id'] != approval_id or row['principal_ref'] != run['principal_ref']:
            raise RuntimeConflict('Approval is unavailable')
        if expected is not None and expected != (run['source_event_id'], row['arguments_sha256']):
            raise RuntimeConflict('Reviewed proposal does not match the approval')
        return run, row

    @staticmethod
    def _clock(connection):
        return connection.execute('SELECT clock_timestamp() AS now').fetchone()['now']

    def _close(self, connection, run, approval, status, actor, now):
        if run['state'] not in ('WAITING_APPROVAL', 'PENDING', 'RETRY_WAIT', 'RUNNING'):
            raise RuntimeConflict('Approval task is already terminal')
        if self.store._unknown_write(connection, run):
            raise RuntimeConflict('An uncertain write must be reconciled separately')
        updated = connection.execute("""UPDATE agent_approval SET status=%s,closed_by=%s,closed_at=%s
            WHERE tenant_code=%s AND run_id=%s RETURNING *""",
            (status, actor, now, approval['tenant_code'], approval['run_id'])).fetchone()
        self.store._unknown(connection, run)
        self.store._terminal(connection, run, 'FAILED', 'APPROVAL_'+status, run['result_ref'])
        return updated
