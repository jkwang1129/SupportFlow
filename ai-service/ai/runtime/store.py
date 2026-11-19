"""Tenant-bound durable execution primitives. All writes are fenced, not an Agent Loop."""
from contextlib import contextmanager
from uuid import UUID, uuid4

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ..core.db import connect
from .types import (RunInput, Limits, Lease, tenant_code, identifier, fingerprint, reference,
                    RuntimeConflict, LostLease, BudgetExceeded, QueueFull)

TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}


class RuntimeStore:
    def __init__(self, connector=connect, *, backlog_limit=500):
        if type(backlog_limit) is not int or not 1 <= backlog_limit <= 10000:
            raise ValueError('Invalid tenant backlog limit')
        self.connector = connector
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

    def due_tenants(self, limit=32):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('Invalid queue discovery limit')
        with self.connector() as connection:
            connection.row_factory = dict_row
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
        # Raise after the terminal transition commits, not inside a rolled-back transaction.
        if exhausted:
            raise BudgetExceeded('Runtime call/step budget exhausted')
        return step,True

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
            connection.execute('UPDATE agent_run SET cancel_requested=true WHERE tenant_code=%s AND run_id=%s', (tenant,row['run_id']))
            self._unknown(connection,row)
            unknown = self._unknown_write(connection,row)
            return self._terminal(connection,row,'FAILED' if unknown else 'CANCELLED', 'SIDE_EFFECT_UNKNOWN' if unknown else 'CANCELLED_BY_USER')
