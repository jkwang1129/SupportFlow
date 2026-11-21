"""Tenant-bound durable execution primitives. All writes are fenced, not an Agent Loop."""
from contextlib import contextmanager
from datetime import timedelta
from uuid import UUID, uuid4

from psycopg import sql
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ..core.db import connect
from .types import (RunInput, Limits, Lease, tenant_code, identifier, fingerprint, reference,
                    RuntimeConflict, LostLease, BudgetExceeded, QueueFull, ActionDenied)
from .payloads import SCHEMAS,StepPayload,encode as encode_payload,decode as decode_payload

TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}


class RuntimeStore:
    def __init__(self, connector=None, *, backlog_limit=500):
        if type(backlog_limit) is not int or not 1 <= backlog_limit <= 10000:
            raise ValueError('Invalid tenant backlog limit')
        self.connector = connector or (lambda: connect(connect_timeout=2))
        self.backlog_limit = backlog_limit

    @contextmanager
    def _scope(self, tenant):
        tenant_code(tenant)
        with self.connector() as connection:
            connection.row_factory = dict_row
            with connection.transaction():
                connection.execute("SELECT set_config('app.tenant_code',%s,true),set_config('lock_timeout','2000',true),set_config('statement_timeout','5000',true)", (tenant,))
                yield connection

    def enqueue(self, input: RunInput, limits=Limits()):
        digest = input.hash(limits)
        with self._scope(input.tenant) as connection:
            # A short per-tenant admission lock makes the backlog limit atomic;
            # execution/heartbeats never hold this lock.
            key = int.from_bytes(bytes.fromhex(fingerprint(['admit', input.tenant]))[:8], 'big', signed=True)
            connection.execute('SELECT pg_advisory_xact_lock(%s)', (key,))
            existing = connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND source_event_id=%s',
                                          (input.tenant, input.source_event_id)).fetchone()
            if existing:
                if existing['input_hash'] != digest:
                    raise RuntimeConflict('Input event content changed')
                return existing
            if connection.execute('SELECT 1 FROM agent_run WHERE tenant_code=%s AND session_no=%s AND event_seq>=%s LIMIT 1',
                                  (input.tenant,input.session_no,input.event_seq)).fetchone():
                raise RuntimeConflict('Source events must be admitted in increasing session order')
            pending = connection.execute("SELECT count(*) AS n FROM agent_run WHERE tenant_code=%s AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')", (input.tenant,)).fetchone()['n']
            if pending >= self.backlog_limit:
                raise QueueFull('Tenant runtime backlog is full')
            row = connection.execute("""INSERT INTO agent_run
                (tenant_code,run_id,source_event_id,session_no,event_seq,message_id,principal_ref,trace_id,runtime_version,input_hash,
                 max_steps,max_model_calls,max_tool_calls,max_retries,deadline_at)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,clock_timestamp()+%s*interval '1 second')
                ON CONFLICT DO NOTHING RETURNING *""", (input.tenant,uuid4(),input.source_event_id,input.session_no,input.event_seq,
                    input.message_id,input.principal_ref,input.trace_id,input.runtime_version,digest,limits.max_steps,
                    limits.max_model_calls,limits.max_tool_calls,limits.max_retries,limits.wall_seconds)).fetchone()
            if not row:
                raise RuntimeConflict('Session event sequence already reserved by another event')
            return row

    def get(self, tenant, run_id):
        with self._scope(tenant) as connection:
            return connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s', (tenant,UUID(str(run_id)))).fetchone()

    def steps(self, tenant, run_id):
        with self._scope(tenant) as connection:
            return connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s ORDER BY step_no', (tenant,UUID(str(run_id)))).fetchall()

    @staticmethod
    def _confirmed_reconciliation(connection, run, operation, version, audit_table):
        query = sql.SQL('SELECT * FROM {} WHERE tenant_code=%s AND run_id=%s').format(sql.Identifier(audit_table))
        audit = connection.execute(query, (run['tenant_code'], run['run_id'])).fetchone()
        if audit is None:
            return None
        step = connection.execute(
            'SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s AND step_no=%s FOR SHARE',
            (run['tenant_code'], run['run_id'], audit['step_no']),
        ).fetchone()
        intent = dict(operation=operation, version=version, resource_ref=run['principal_ref'],
                      input_ref=str(audit['snapshot_id']), arguments_sha256=audit['arguments_sha256'])
        expected = intent.copy()
        if operation == 'session.reply':
            expected.update(outcome='reply-committed', business_ref='message:'+str(audit['message_id']),
                            resource_ref=audit['message_no'])
        else:
            if audit['operation'] != operation or audit['schema_version'] != version:
                raise RuntimeConflict('Ticket reconciliation ledger is inconsistent')
            expected.update(outcome='ticket-committed', business_ref='ticket:'+str(audit['ticket_id']),
                            resource_ref=audit['ticket_no'])
        if (run['state'] != 'FAILED' or run['error_code'] != 'SIDE_EFFECT_CONFIRMED'
                or run['owner'] is not None or run['lease_until'] is not None
                or run['fence'] != audit['observed_fence'] + 1 or run['source_event_id'] != audit['source_event_id']
                or run['runtime_version'] != 'runtime-v1' or run['result_ref'] != expected
                or not step or step['kind'] != 'TOOL' or step['effect'] != 'WRITE' or step['state'] != 'SUCCEEDED'
                or step['intent_ref'] != intent or step['intent_hash'] != audit['intent_hash']
                or step['intent_hash'] != fingerprint(dict(kind='TOOL', effect='WRITE', intent=intent))
                or step['result_ref'] != expected):
            raise RuntimeConflict('Reconciliation ledger is inconsistent')
        return audit

    def _read_reconciliation(self, tenant, run_id, operation, version, audit_table):
        with self._scope(tenant) as connection:
            run = connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s FOR SHARE',
                                     (tenant, UUID(str(run_id)))).fetchone()
            if run is None:
                return None
            return self._confirmed_reconciliation(connection, run, operation, version, audit_table)

    def reply_reconciliation(self, tenant, run_id):
        return self._read_reconciliation(tenant, run_id, 'session.reply', 'reply-v1', 'agent_reply_reconciliation')

    def ticket_reconciliation(self, tenant, run_id):
        return self._read_reconciliation(tenant, run_id, 'ticket.create', 'ticket-create-v1', 'agent_ticket_reconciliation')

    def confirm_reply_commit(self, candidate, receipt, operator_ref):
        from .reconciliation import ReplyRecoveryCandidate
        from .result import receipt_reference
        if type(candidate) is not ReplyRecoveryCandidate:
            raise ValueError('Typed reply recovery observation required')
        result = receipt_reference(receipt, candidate.source, candidate.run_id, candidate.snapshot_id, candidate.arguments_sha256)
        facts = dict(message_id=receipt.message_id, message_no=receipt.message_no, message_seq=receipt.sequence)
        return self._confirm_commit(candidate, result, facts, operator_ref, 'agent_reply_reconciliation')

    def confirm_ticket_commit(self, candidate, receipt, operator_ref):
        from .reconciliation import TicketRecoveryCandidate
        from .ticket_creation import receipt_reference
        if type(candidate) is not TicketRecoveryCandidate:
            raise ValueError('Typed ticket recovery observation required')
        result = receipt_reference(receipt, candidate.source, candidate.run_id, candidate.snapshot_id, candidate.arguments_sha256)
        facts = dict(ticket_id=receipt.ticket_id, ticket_no=receipt.ticket_no,
                     operation='ticket.create', schema_version='ticket-create-v1')
        return self._confirm_commit(candidate, result, facts, operator_ref, 'agent_ticket_reconciliation')

    def _confirm_commit(self, candidate, result, facts, operator_ref, audit_table):
        """Record a matched historical receipt without granting a lease or updating budgets."""
        identifier(operator_ref)
        tenant = candidate.source.tenant
        with self._scope(tenant) as connection:
            run = connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s FOR UPDATE',
                                     (tenant, candidate.run_id)).fetchone()
            if not run:
                raise RuntimeConflict('Recovery run is unavailable')
            previous = self._confirmed_reconciliation(connection, run, candidate.operation, candidate.version, audit_table)
            if previous is not None:
                source = RunInput(run['tenant_code'], run['source_event_id'], run['session_no'], run['event_seq'],
                                  run['message_id'], run['principal_ref'], run['trace_id'], run['runtime_version'])
                key = connection.execute('SELECT step_key FROM agent_step WHERE tenant_code=%s AND run_id=%s AND step_no=%s',
                                         (tenant, candidate.run_id, previous['step_no'])).fetchone()['step_key']
                if (previous['observed_fence'] != candidate.fence or previous['step_no'] != candidate.step_no
                        or candidate.source != source or candidate.step_key != key
                        or previous['intent_hash'] != candidate.intent_hash or previous['source_event_id'] != candidate.source.source_event_id
                        or previous['snapshot_id'] != candidate.snapshot_id or previous['arguments_sha256'] != candidate.arguments_sha256
                        or any(previous[name] != value for name, value in facts.items())):
                    raise RuntimeConflict('Recovery proof changed')
                return previous
            steps = connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s ORDER BY step_no FOR UPDATE',
                                       (tenant, candidate.run_id)).fetchall()
            if type(candidate).from_rows(run, steps) != candidate:
                raise RuntimeConflict('Recovery observation is stale')
            step = next(row for row in steps if row['step_no'] == candidate.step_no)
            audit_fields = dict(tenant_code=tenant, run_id=candidate.run_id, step_no=candidate.step_no,
                                observed_fence=candidate.fence, source_event_id=candidate.source.source_event_id,
                                snapshot_id=candidate.snapshot_id, arguments_sha256=candidate.arguments_sha256,
                                intent_hash=candidate.intent_hash, operator_ref=operator_ref, original_error_code='SIDE_EFFECT_UNKNOWN',
                                original_step_result=Jsonb(reference(step['result_ref']))) | facts
            insert = sql.SQL('INSERT INTO {} ({}) VALUES ({}) RETURNING *').format(
                sql.Identifier(audit_table), sql.SQL(',').join(map(sql.Identifier, audit_fields)),
                sql.SQL(',').join(sql.Placeholder() for _ in audit_fields),
            )
            audit = connection.execute(insert, list(audit_fields.values())).fetchone()
            # The audit keeps the original completion times and budgets intact.
            connection.execute("UPDATE agent_step SET state='SUCCEEDED',result_ref=%s WHERE tenant_code=%s AND run_id=%s AND step_no=%s",
                               (Jsonb(result), tenant, candidate.run_id, candidate.step_no))
            connection.execute("""UPDATE agent_run SET error_code='SIDE_EFFECT_CONFIRMED',result_ref=%s,fence=fence+1,
                updated_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s""", (Jsonb(result), tenant, candidate.run_id))
            return audit

    def due_tenants(self, limit=32):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('Invalid queue discovery limit')
        with self.connector() as connection:
            connection.row_factory = dict_row
            connection.execute("SELECT set_config('statement_timeout','5000',true),set_config('lock_timeout','2000',true)")
            return [row['tenant_code'] for row in connection.execute('SELECT * FROM public.supportflow_agent_due_tenants(%s)', (limit,))]

    @staticmethod
    def _unknown(connection, row):
        connection.execute("""UPDATE agent_step SET state='UNKNOWN',completed_at=clock_timestamp(),result_ref='{"error_code":"WORKER_LOST"}'
            WHERE tenant_code=%s AND run_id=%s AND state='RESERVED'""", (row['tenant_code'],row['run_id']))

    @staticmethod
    def _unknown_write(connection, row):
        return connection.execute("""SELECT 1 FROM agent_step WHERE tenant_code=%s AND run_id=%s
            AND effect='WRITE' AND state IN ('RESERVED','UNKNOWN') LIMIT 1""", (row['tenant_code'],row['run_id'])).fetchone() is not None

    @staticmethod
    def _terminal(connection, row, state, error=None, result=None):
        return connection.execute("""UPDATE agent_run SET state=%s,error_code=%s,result_ref=%s,
            owner=NULL,lease_until=NULL,fence=fence+1,updated_at=clock_timestamp(),finished_at=clock_timestamp()
            WHERE tenant_code=%s AND run_id=%s RETURNING *""",
            (state,error,Jsonb(result or {}),row['tenant_code'],row['run_id'])).fetchone()

    @staticmethod
    def _owned(connection, lease):
        row = connection.execute("""SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s
            AND state='RUNNING' AND owner=%s AND fence=%s FOR UPDATE""",
            (lease.tenant,lease.run_id,lease.owner,lease.fence)).fetchone()
        # Time predicates are checked AFTER acquiring the row lock. A SELECT's
        # initial predicate can become stale while waiting for another transaction.
        if not row or not connection.execute('SELECT %s>clock_timestamp() AND %s>clock_timestamp() AS valid',
                                              (row['lease_until'],row['deadline_at'])).fetchone()['valid']:
            raise LostLease('Execution lease is no longer valid')
        return row

    def claim_next(self, tenant, owner, *, lease_seconds=30):
        identifier(owner)
        if type(lease_seconds) is not int or not 1 <= lease_seconds <= 60:
            raise ValueError('Invalid lease duration')
        with self._scope(tenant) as connection:
            candidates = connection.execute("""SELECT r.run_id FROM agent_run r WHERE r.tenant_code=%s AND
                ((r.state IN ('PENDING','RETRY_WAIT') AND r.ready_at<=clock_timestamp())
                  OR (r.state='RUNNING' AND (r.lease_until<=clock_timestamp() OR r.deadline_at<=clock_timestamp())))
                AND NOT EXISTS(SELECT 1 FROM agent_run earlier WHERE earlier.tenant_code=r.tenant_code AND earlier.session_no=r.session_no
                    AND earlier.run_id<>r.run_id AND (earlier.state='RUNNING' OR
                       (earlier.event_seq<r.event_seq AND (earlier.state NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR earlier.error_code='SIDE_EFFECT_UNKNOWN'))))
                ORDER BY r.created_at,r.event_seq,r.run_id LIMIT 32""", (tenant,)).fetchall()
            for candidate in candidates:
                row = connection.execute("""SELECT *,deadline_at<=clock_timestamp() AS timed_out FROM agent_run
                    WHERE tenant_code=%s AND run_id=%s AND ((state IN ('PENDING','RETRY_WAIT') AND ready_at<=clock_timestamp())
                        OR (state='RUNNING' AND (lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp()))) FOR UPDATE SKIP LOCKED""",
                    (tenant,candidate['run_id'])).fetchone()
                if not row:
                    continue
                session_lock = int.from_bytes(bytes.fromhex(fingerprint(['session',tenant,row['session_no']]))[:8], 'big', signed=True)
                if not connection.execute('SELECT pg_try_advisory_xact_lock(%s) AS locked', (session_lock,)).fetchone()['locked']:
                    continue
                if row['state'] == 'RUNNING' or row['timed_out']:
                    self._unknown(connection,row)
                    if self._unknown_write(connection,row):
                        self._terminal(connection,row,'FAILED','SIDE_EFFECT_UNKNOWN')
                        continue
                    if row['timed_out']:
                        self._terminal(connection,row,'FAILED','DEADLINE_EXCEEDED')
                        continue
                    if row['retry_count'] >= row['max_retries']:
                        self._terminal(connection,row,'FAILED','RETRY_EXHAUSTED')
                        continue
                blocked = connection.execute("""SELECT 1 FROM agent_run WHERE tenant_code=%s AND session_no=%s AND run_id<>%s
                    AND (state='RUNNING' OR (event_seq<%s AND (state NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR error_code='SIDE_EFFECT_UNKNOWN'))) LIMIT 1""",
                    (tenant,row['session_no'],row['run_id'],row['event_seq'])).fetchone()
                if blocked:
                    continue
                return connection.execute("""UPDATE agent_run SET state='RUNNING',owner=%s,fence=fence+1,
                    lease_until=least(deadline_at,clock_timestamp()+%s*interval '1 second'),attempt=attempt+1,
                    retry_count=retry_count+%s,updated_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s RETURNING *""",
                    (owner,lease_seconds,1 if row['state']=='RUNNING' else 0,tenant,row['run_id'])).fetchone()
            return None

    def heartbeat(self, lease: Lease, *, lease_seconds=30):
        if type(lease_seconds) is not int or not 1 <= lease_seconds <= 60:
            raise ValueError('Invalid lease duration')
        with self._scope(lease.tenant) as connection:
            self._owned(connection,lease)
            return connection.execute("""UPDATE agent_run SET lease_until=least(deadline_at,clock_timestamp()+%s*interval '1 second'),
                updated_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s RETURNING *""",
                (lease_seconds,lease.tenant,lease.run_id)).fetchone()

    def authorize_reply(self, tenant, run_id, source_event_id, owner, fence, snapshot_id, arguments_hash):
        return self._authorize_action(tenant,run_id,source_event_id,owner,fence,snapshot_id,arguments_hash,'session.reply','reply-v1')

    def authorize_ticket_create(self, tenant, run_id, source_event_id, owner, fence, snapshot_id, arguments_hash):
        return self._authorize_action(tenant,run_id,source_event_id,owner,fence,snapshot_id,arguments_hash,'ticket.create','ticket-create-v1')

    def _authorize_action(self,tenant,run_id,source_event_id,owner,fence,snapshot_id,arguments_hash,operation,version):
        if (operation,version) not in (('session.reply','reply-v1'),('ticket.create','ticket-create-v1')):
            raise ValueError('Unsupported action authority operation')
        identifier(owner)
        if type(fence) is not int or fence<1 or not isinstance(arguments_hash,str) or len(arguments_hash)!=64 or any(c not in '0123456789abcdef' for c in arguments_hash):
            raise ValueError('Invalid action authority references')
        run_id, source_event_id, snapshot_id = (UUID(str(value)) for value in (run_id,source_event_id,snapshot_id))
        with self._scope(tenant) as connection:
            row=connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s FOR SHARE',(tenant,run_id)).fetchone()
            if row is None: return None
            if (row['state']!='RUNNING' or row['cancel_requested'] or row['owner']!=owner or row['fence']!=fence
                    or row['source_event_id']!=source_event_id or row['runtime_version']!='runtime-v1'):
                raise ActionDenied('Action is not authorized')
            expected=dict(operation=operation,resource_ref=row['principal_ref'],input_ref=str(snapshot_id),
                          arguments_sha256=arguments_hash,version=version)
            approval = None
            if operation == 'ticket.create':
                approval = connection.execute("""SELECT * FROM agent_approval
                    WHERE tenant_code=%s AND run_id=%s FOR SHARE""", (tenant, run_id)).fetchone()
            steps=connection.execute("""SELECT step_key,step_no FROM agent_step WHERE tenant_code=%s AND run_id=%s
                 AND fence=%s AND state='RESERVED' AND kind='TOOL' AND effect='WRITE' AND intent_ref=%s
                 ORDER BY step_no LIMIT 2 FOR SHARE""",(tenant,run_id,fence,Jsonb(expected))).fetchall()
            # Database time is checked after ALL row locks, so waiting cannot
            # resurrect execution authority. This operation never charges/reset budgets.
            now=connection.execute('SELECT clock_timestamp() AS now').fetchone()['now']
            if len(steps)!=1 or row['lease_until']<=now or row['deadline_at']<=now:
                raise ActionDenied('Action is not authorized')
            expires = min(row['lease_until'], row['deadline_at'], now + timedelta(seconds=5))
            if approval is not None:
                if (approval['status'] != 'CONSUMED' or approval['snapshot_id'] != snapshot_id
                        or approval['principal_ref'] != row['principal_ref'] or approval['operation'] != operation
                        or approval['schema_version'] != version or approval['arguments_sha256'] != arguments_hash
                        or approval['consumed_step_no'] != steps[0]['step_no'] or approval['expires_at'] <= now):
                    raise ActionDenied('Action approval is not authorized')
                expires = min(expires, approval['expires_at'])
            expires -= timedelta(milliseconds=100)
            if expires<=now: raise ActionDenied('Action authority is too close to expiry')
            step=steps[0]
            return dict(version='action-v1',tenant_code=tenant,run_id=str(run_id),source_event_id=str(source_event_id),
                        owner=owner,fence=fence,step_key=step['step_key'],step_no=step['step_no'],operation=operation,
                        snapshot_id=str(snapshot_id),arguments_sha256=arguments_hash,expires_at_ms=int(expires.timestamp()*1000))

    def reserve_step(self, lease: Lease, step_key, kind, effect, intent_ref):
        identifier(step_key)
        intent_ref = reference(intent_ref)
        if kind not in ('MODEL','TOOL','INTERNAL') or effect not in (('READ','WRITE') if kind=='TOOL' else ('NONE',)):
            raise ValueError('Invalid step kind/effect')
        digest = fingerprint({'kind':kind,'effect':effect,'intent':intent_ref})
        exhausted = False
        with self._scope(lease.tenant) as connection:
            run = self._owned(connection,lease)
            existing = connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s AND step_key=%s',
                                          (lease.tenant,lease.run_id,step_key)).fetchone()
            if existing:
                if existing['intent_hash'] != digest:
                    raise RuntimeConflict('Step intent changed')
                return existing,False
            approval = None
            if kind == 'TOOL' and effect == 'WRITE' and intent_ref.get('operation') == 'ticket.create':
                approval = connection.execute("""SELECT * FROM agent_approval
                    WHERE tenant_code=%s AND run_id=%s FOR UPDATE""", (lease.tenant, lease.run_id)).fetchone()
                if approval is not None:
                    expected_approval = dict(operation=approval['operation'], version=approval['schema_version'],
                        resource_ref=approval['principal_ref'], input_ref=str(approval['snapshot_id']),
                        arguments_sha256=approval['arguments_sha256'])
                    now = connection.execute('SELECT clock_timestamp() AS now').fetchone()['now']
                    if (approval['status'] != 'APPROVED' or intent_ref != expected_approval
                            or approval['principal_ref'] != run['principal_ref']
                            or now >= min(approval['expires_at'], run['lease_until'], run['deadline_at'])):
                        raise ActionDenied('Approval is unavailable for this write')
            if (run['step_count'] >= run['max_steps'] or
                (kind=='MODEL' and run['model_calls'] >= run['max_model_calls']) or
                (kind=='TOOL' and run['tool_calls'] >= run['max_tool_calls'])):
                self._unknown(connection,run)
                self._terminal(connection,run,'FAILED','SIDE_EFFECT_UNKNOWN' if self._unknown_write(connection,run) else 'BUDGET_EXHAUSTED')
                exhausted = True
            else:
                updated = connection.execute("""UPDATE agent_run SET step_count=step_count+1,
                    model_calls=model_calls+%s,tool_calls=tool_calls+%s,updated_at=clock_timestamp()
                    WHERE tenant_code=%s AND run_id=%s RETURNING step_count""",
                    (1 if kind=='MODEL' else 0,1 if kind=='TOOL' else 0,lease.tenant,lease.run_id)).fetchone()
                step = connection.execute("""INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,fence)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *""",
                    (lease.tenant,lease.run_id,updated['step_count'],step_key,kind,effect,digest,Jsonb(intent_ref),lease.fence)).fetchone()
                if approval is not None:
                    outcome = connection.execute("""UPDATE agent_approval SET status='CONSUMED',consumed_step_no=%s,
                        consumed_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s AND expires_at>clock_timestamp()""",
                        (step['step_no'], lease.tenant, lease.run_id))
                    if outcome.rowcount != 1:
                        raise ActionDenied('Approval expired before step reservation')
        # Raise after the terminal transition commits, not inside a rolled-back transaction.
        if exhausted:
            raise BudgetExceeded('Runtime call/step budget exhausted')
        return step,True

    def complete_payload_step(self,lease: Lease,step_key,schema,value):
        identifier(step_key)
        raw,digest=encode_payload(schema,value)
        with self._scope(lease.tenant) as connection:
            self._owned(connection,lease)
            step=connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s AND step_key=%s FOR UPDATE',
                                   (lease.tenant,lease.run_id,step_key)).fetchone()
            if not step or step['fence']!=lease.fence: raise LostLease('Payload step belongs to another attempt')
            if (step['kind'],step['effect'])!=SCHEMAS[schema]: raise RuntimeConflict('Payload schema does not match step kind')
            previous=connection.execute('SELECT * FROM agent_payload WHERE tenant_code=%s AND run_id=%s AND step_no=%s',
                                         (lease.tenant,lease.run_id,step['step_no'])).fetchone()
            self._owned(connection,lease)  # Recheck DB time after all row locks.
            if previous:
                result={'resource_ref':'payload:'+str(previous['payload_id'])}
                if (previous['payload_schema']!=schema or previous['payload_hash']!=digest or previous['payload_json']!=raw
                        or step['state']!='SUCCEEDED' or step['result_ref']!=result):
                    raise RuntimeConflict('Persisted step payload changed')
                return result
            if step['state']!='RESERVED': raise RuntimeConflict('Payload step is not reserved')
            payload_id=uuid4();result={'resource_ref':'payload:'+str(payload_id)}
            connection.execute('''INSERT INTO agent_payload(tenant_code,payload_id,run_id,step_no,fence,payload_schema,payload_json,payload_hash)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s)''',(lease.tenant,payload_id,lease.run_id,step['step_no'],lease.fence,schema,raw,digest))
            connection.execute("""UPDATE agent_step SET state='SUCCEEDED',result_ref=%s,completed_at=clock_timestamp()
                WHERE tenant_code=%s AND run_id=%s AND step_no=%s""",(Jsonb(result),lease.tenant,lease.run_id,step['step_no']))
            self._owned(connection,lease)
            return result

    def payload(self,tenant,run_id,payload_id):
        with self._scope(tenant) as connection:
            row=connection.execute('''SELECT * FROM agent_payload WHERE tenant_code=%s AND run_id=%s AND payload_id=%s
                AND expires_at>clock_timestamp()''',(tenant,UUID(str(run_id)),UUID(str(payload_id)))).fetchone()
            if row is None:return None
            try:value=decode_payload(row['payload_schema'],row['payload_json'],row['payload_hash'])
            except (ValueError,TypeError,UnicodeError,RecursionError):raise RuntimeConflict('Persisted payload is invalid') from None
            return StepPayload(row['payload_id'],row['run_id'],row['step_no'],row['payload_schema'],row['payload_hash'],value,row['created_at'],row['expires_at'])

    def complete_step(self, lease: Lease, step_key, state, result_ref):
        identifier(step_key)
        result_ref = reference(result_ref)
        if state not in ('SUCCEEDED','FAILED','UNKNOWN'):
            raise ValueError('Invalid step result')
        with self._scope(lease.tenant) as connection:
            self._owned(connection,lease)
            step = connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s AND step_key=%s',
                                      (lease.tenant,lease.run_id,step_key)).fetchone()
            if not step or step['fence'] != lease.fence:
                raise LostLease('Step belongs to a different execution attempt')
            if step['state'] != 'RESERVED':
                if step['state'] == state and step['result_ref'] == result_ref:
                    return step
                raise RuntimeConflict('Step result changed')
            return connection.execute("""UPDATE agent_step SET state=%s,result_ref=%s,completed_at=clock_timestamp()
                WHERE tenant_code=%s AND run_id=%s AND step_key=%s RETURNING *""",
                (state,Jsonb(result_ref),lease.tenant,lease.run_id,step_key)).fetchone()

    def finish(self, lease: Lease, result_ref):
        result_ref = reference(result_ref)
        with self._scope(lease.tenant) as connection:
            row = self._owned(connection,lease)
            if self._unknown_write(connection,row):
                self._unknown(connection,row)
                return self._terminal(connection,row,'FAILED','SIDE_EFFECT_UNKNOWN')
            if connection.execute("SELECT 1 FROM agent_step WHERE tenant_code=%s AND run_id=%s AND state='RESERVED' LIMIT 1", (lease.tenant,lease.run_id)).fetchone():
                raise RuntimeConflict('An execution step is still in progress')
            return self._terminal(connection,row,'SUCCEEDED',result=result_ref)

    def retry(self, lease: Lease, error_code, *, delay_seconds=1):
        identifier(error_code)
        if type(delay_seconds) is not int or not 0 <= delay_seconds <= 300:
            raise ValueError('Invalid retry delay')
        with self._scope(lease.tenant) as connection:
            row = self._owned(connection,lease)
            self._unknown(connection,row)
            if self._unknown_write(connection,row):
                return self._terminal(connection,row,'FAILED','SIDE_EFFECT_UNKNOWN')
            if row['retry_count'] >= row['max_retries']:
                return self._terminal(connection,row,'FAILED','RETRY_EXHAUSTED')
            return connection.execute("""UPDATE agent_run SET state='RETRY_WAIT',retry_count=retry_count+1,error_code=%s,
                owner=NULL,lease_until=NULL,fence=fence+1,ready_at=least(deadline_at,clock_timestamp()+%s*interval '1 second'),
                updated_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s RETURNING *""",
                (error_code,delay_seconds,lease.tenant,lease.run_id)).fetchone()

    def fail(self, lease: Lease, error_code):
        identifier(error_code)
        with self._scope(lease.tenant) as connection:
            row = self._owned(connection,lease)
            self._unknown(connection,row)
            return self._terminal(connection,row,'FAILED','SIDE_EFFECT_UNKNOWN' if self._unknown_write(connection,row) else error_code)

    def cancel(self, tenant, run_id):
        with self._scope(tenant) as connection:
            row = connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s FOR UPDATE', (tenant,UUID(str(run_id)))).fetchone()
            if not row or row['state'] in TERMINAL:
                return row
            if row['result_ref'].get('outcome') == 'waiting-approval':
                connection.execute("""UPDATE agent_approval SET status='CANCELLED',closed_by='system:run-cancel',closed_at=clock_timestamp()
                    WHERE tenant_code=%s AND run_id=%s AND status IN ('PENDING','APPROVED')""", (tenant, row['run_id']))
            connection.execute('UPDATE agent_run SET cancel_requested=true WHERE tenant_code=%s AND run_id=%s', (tenant,row['run_id']))
            self._unknown(connection,row)
            unknown = self._unknown_write(connection,row)
            return self._terminal(connection,row,'FAILED' if unknown else 'CANCELLED', 'SIDE_EFFECT_UNKNOWN' if unknown else 'CANCELLED_BY_USER')
