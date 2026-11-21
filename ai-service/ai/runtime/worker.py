"""Single-slot durable worker. Business handlers are explicitly registered trusted code."""
from collections.abc import Callable, Mapping
from threading import Event, Lock, Thread
from types import MappingProxyType
from uuid import UUID

import psycopg

from .store import RuntimeStore
from .types import Lease, LostLease, BudgetExceeded, RuntimeConflict, identifier, fingerprint, reference
from .payloads import SCHEMAS,encode as encode_payload


class TransientFailure(Exception):
    """A classified retryable failure; never pass provider exception text as its code."""
    def __init__(self, code, *, delay_seconds=1):
        self.code = identifier(code)
        if type(delay_seconds) is not int or not 0 <= delay_seconds <= 300:
            raise ValueError('Invalid retry delay')
        self.delay_seconds = delay_seconds
        super().__init__(self.code)


class PermanentFailure(Exception):
    def __init__(self, code):
        self.code = identifier(code)
        super().__init__(self.code)


class ApprovalPending(Exception):
    """The approval request and release of the current lease have committed."""


class ExecutionContext:
    def __init__(self, store, run, lost, lease_seconds):
        self.store = store
        self.run = MappingProxyType(dict(run))
        self.lease = Lease.from_run(run)
        self.lost = lost
        self.lease_seconds = lease_seconds

    def check_active(self):
        if self.lost.is_set():
            raise LostLease('Worker execution authority was lost')
        # Revalidate at every external boundary, not only on heartbeat ticks.
        self.store.heartbeat(self.lease, lease_seconds=self.lease_seconds)

    def step(self, key, kind, effect, intent_ref, execute: Callable):
        """Return durable reference results, never persist arbitrary callable output.

        A logical key can be retried after UNKNOWN only for MODEL/READ/INTERNAL.
        Each external attempt reserves a new fenced step and consumes shared budget.
        The caller must use business references to reload any larger result payload.
        """
        return self._step(key,kind,effect,intent_ref,execute)

    def payload_step(self,key,kind,effect,intent_ref,schema,execute: Callable):
        """Reuse an atomically persisted model/read/internal value after restart.

        Values are untrusted data; callers must reauthorize business resources
        before using them. WRITE outcomes use business receipts, never this API.
        """
        if not isinstance(schema,str) or SCHEMAS.get(schema)!=(kind,effect):
            raise ValueError('Payload schema does not match execution kind')
        return self._step(key,kind,effect,intent_ref,execute,payload_schema=schema)

    def _value(self,result,step_no,schema):
        if schema is None:return result
        try:
            if set(result)!={'resource_ref'} or not result['resource_ref'].startswith('payload:'):raise ValueError()
            raw=result['resource_ref'].removeprefix('payload:');payload_id=UUID(raw)
            if str(payload_id)!=raw:raise ValueError()
        except (ValueError,TypeError,KeyError,AttributeError):raise PermanentFailure('PAYLOAD_REFERENCE_INVALID') from None
        try:payload=self.store.payload(self.lease.tenant,self.lease.run_id,payload_id)
        except RuntimeConflict:raise PermanentFailure('PAYLOAD_INVALID') from None
        if payload is None:raise PermanentFailure('PAYLOAD_UNAVAILABLE')
        if payload.step_no!=step_no:raise PermanentFailure('PAYLOAD_REFERENCE_INVALID')
        if payload.schema!=schema:raise PermanentFailure('PAYLOAD_SCHEMA_MISMATCH')
        self.check_active()
        return payload.value

    def _step(self,key,kind,effect,intent_ref,execute,payload_schema=None):
        identifier(key)
        intent_ref = reference(intent_ref)
        self.check_active()
        prefix = 's' + fingerprint(key)[:40] + ':'
        digest = fingerprint({'kind': kind, 'effect': effect, 'intent': intent_ref})
        previous = [step for step in self.store.steps(self.lease.tenant, self.lease.run_id)
                    if step['step_key'].startswith(prefix)]
        if any(step['intent_hash'] != digest for step in previous):
            raise RuntimeConflict('Logical step intent changed across attempts')
        for step in reversed(previous):
            if step['state'] == 'SUCCEEDED':
                return self._value(step['result_ref'],step['step_no'],payload_schema)
            if step['effect'] == 'WRITE' and step['state'] in ('RESERVED', 'UNKNOWN'):
                raise PermanentFailure('SIDE_EFFECT_UNKNOWN')
        physical_key = prefix + str(self.lease.fence)
        step, created = self.store.reserve_step(self.lease, physical_key, kind, effect, intent_ref)
        if not created:
            # Includes recursive/re-entrant use of the same key in a live attempt.
            # It must not trigger the same physical call twice.
            raise RuntimeConflict('Step attempt has already been reserved')
        try:
            self.check_active()
            output=execute()
            if payload_schema is None:result=reference(output)
            else:
                try:encode_payload(payload_schema,output)
                except ValueError:raise PermanentFailure('PAYLOAD_FORMAT_INVALID') from None
        except LostLease:
            raise
        except Exception:
            # An exception/timeout does not prove that an external effect did not
            # happen. Preserve uncertainty; retry() refuses unknown WRITE effects.
            self.store.complete_step(self.lease, physical_key, 'UNKNOWN', {'error_code': 'CALL_FAILED'})
            raise
        self.check_active()
        if payload_schema is None:self.store.complete_step(self.lease, physical_key, 'SUCCEEDED', result)
        else:
            # This commit can succeed even if its acknowledgement is lost. It
            # is outside the execution-exception handler: never downgrade an
            # already atomically SUCCEEDED step back to UNKNOWN.
            result=self.store.complete_payload_step(self.lease,physical_key,payload_schema,output)
        return self._value(result,step['step_no'],payload_schema)


class RuntimeWorker:
    """One claimed task at a time, one heartbeat thread, no unbounded executor queue.

    Multiple independent worker processes coordinate through PG leases. Handlers
    require bounded external I/O; Python cannot forcibly cancel a blocked thread.
    The FastAPI app does not auto-start this worker before business integration.
    """
    def __init__(self, store: RuntimeStore, handlers: Mapping[str, Callable], owner, *, lease_seconds=30):
        self.store = store
        self.owner = identifier(owner)
        if type(lease_seconds) is not int or not 1 <= lease_seconds <= 60:
            raise ValueError('Invalid lease duration')
        if not handlers or any(not callable(handler) for handler in handlers.values()):
            raise ValueError('Explicit trusted runtime handlers are required')
        for version in handlers:
            identifier(version, 32)
        self.handlers = MappingProxyType(dict(handlers))
        self.lease_seconds = lease_seconds
        self._slot = Lock()
        self._last_tenant = None

    def run_once(self, tenant):
        if not self._slot.acquire(blocking=False):
            return None
        stopped, lost = Event(), Event()
        heartbeat = None
        try:
            run = self.store.claim_next(tenant, self.owner, lease_seconds=self.lease_seconds)
            if run is None:
                return None
            lease = Lease.from_run(run)

            def renew():
                while not stopped.wait(self.lease_seconds / 3):
                    try:
                        self.store.heartbeat(lease, lease_seconds=self.lease_seconds)
                    except Exception:
                        # Do not log DSNs/provider payloads. Stop trusting this
                        # worker even when the cause is a transient DB outage.
                        lost.set()
                        return

            heartbeat = Thread(target=renew, name='agent-lease-heartbeat', daemon=True)
            heartbeat.start()
            context = ExecutionContext(self.store, run, lost, self.lease_seconds)
            handler = self.handlers.get(run['runtime_version'])
            try:
                if handler is None:
                    raise PermanentFailure('UNSUPPORTED_RUNTIME_VERSION')
                context.check_active()
                result = reference(handler(context))
                context.check_active()
                return self.store.finish(lease, result)
            except (LostLease, BudgetExceeded, ApprovalPending):
                # A budget failure has already committed its terminal state;
                # lost leases belong to another worker/cancellation/deadline.
                return self.store.get(tenant, run['run_id'])
            except (psycopg.OperationalError,psycopg.InterfaceError):
                # No DB/provider detail enters persistence. retry() retains all
                # counters, reuses committed steps, and holds uncertain WRITE.
                # If the DB is still unavailable, propagate rather than invent
                # a successful transition; lease takeover remains authoritative.
                return self._transition(context,'retry','RUNTIME_PERSISTENCE_UNAVAILABLE',delay_seconds=0)
            except TransientFailure as failure:
                return self._transition(context, 'retry', failure.code, delay_seconds=failure.delay_seconds)
            except PermanentFailure as failure:
                return self._transition(context, 'fail', failure.code)
            except Exception:
                # Unexpected programming/validation errors are permanent, not an
                # unbounded automatic retry. Never persist exception text.
                return self._transition(context, 'fail', 'EXECUTION_ERROR')
        finally:
            stopped.set()
            if heartbeat is not None:
                # Keep the slot occupied until the bounded DB heartbeat exits;
                # do not leak a fresh daemon thread on each connection failure.
                heartbeat.join()
            self._slot.release()

    def _transition(self, context, operation, code, **kwargs):
        try:
            context.check_active()
            return getattr(self.store, operation)(context.lease, code, **kwargs)
        except LostLease:
            return self.store.get(context.lease.tenant, context.lease.run_id)

    def tick(self):
        tenants = self.store.due_tenants()
        if not tenants:
            return None
        # Local round-robin only; this does not claim cross-process tenant quotas.
        if self._last_tenant in tenants:
            index = tenants.index(self._last_tenant) + 1
            tenants = tenants[index:] + tenants[:index]
        for tenant in tenants:
            result = self.run_once(tenant)
            if result is not None:
                self._last_tenant = tenant
                return result
        return None

    def run_forever(self, stop: Event, *, poll_seconds=0.25):
        if not isinstance(poll_seconds, (int, float)) or isinstance(poll_seconds, bool) or not 0.05 <= poll_seconds <= 5:
            raise ValueError('Invalid polling interval')
        while not stop.is_set():
            if self.tick() is None:
                stop.wait(poll_seconds)
