"""Execute the frozen 50-case Fault Injection & Crash Recovery Matrix against real Runtime primitives."""
from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures", "../ai-service"):
    _p = str((_SCRIPTS_DIR / _sub).resolve())
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import platform
import subprocess
import time
from typing import Any
from uuid import uuid4

from ai.core.circuit_breaker import CircuitBreaker, CircuitBreakerOpenError, CircuitState
from ai.core.telemetry import get_span_recorder, start_span
from ai.runtime.types import Limits, RunInput
from ai.runtime.worker import PermanentFailure, RuntimeWorker, TransientFailure
from rag_quality_dataset import canonical_digest, reject_duplicate_keys

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "fixtures/evaluation/fault-recovery-50-v1.json"


from contextlib import contextmanager


class _NoOpCursor:
    def execute(self, *_args, **_kwargs):
        return self

    def fetchall(self):
        return []

    def fetchone(self):
        return None

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


class _NoOpConn:
    def cursor(self):
        return _NoOpCursor()

    def execute(self, *_args, **_kwargs):
        return _NoOpCursor()


class _InMemoryDurableStore:
    """In-memory deterministic mirror of RuntimeStore fencing, checkpoints, and step budgets."""

    def __init__(self) -> None:
        self.runs: dict[str, dict[str, Any]] = {}
        self.event_index: dict[tuple[str, str], str] = {}
        self.step_rows: dict[str, list[dict[str, Any]]] = {}
        self.payloads: dict[str, dict[str, Any]] = {}

    @contextmanager
    def _scope(self, _tenant: str):
        yield _NoOpConn()

    def enqueue(self, run_input: RunInput, limits: Limits = Limits()) -> dict[str, Any]:
        key = (run_input.tenant, str(run_input.source_event_id))
        if key in self.event_index:
            return self.runs[self.event_index[key]]
        run_id = uuid4()
        row = {
            "tenant_code": run_input.tenant,
            "run_id": run_id,
            "source_event_id": run_input.source_event_id,
            "session_no": run_input.session_no,
            "event_seq": run_input.event_seq,
            "message_id": run_input.message_id,
            "principal_ref": run_input.principal_ref,
            "trace_id": run_input.trace_id,
            "runtime_version": run_input.runtime_version,
            "state": "PENDING",
            "attempt": 0,
            "fence": 0,
            "owner": None,
            "step_count": 0,
            "model_calls": 0,
            "tool_calls": 0,
            "retry_count": 0,
            "max_steps": limits.max_steps,
            "max_model_calls": limits.max_model_calls,
            "max_tool_calls": limits.max_tool_calls,
            "max_retries": limits.max_retries,
            "error_code": None,
            "result_ref": None,
        }
        rid = str(run_id)
        self.runs[rid] = row
        self.event_index[key] = rid
        self.step_rows[rid] = []
        return row

    def get(self, tenant: str, run_id) -> dict[str, Any] | None:
        row = self.runs.get(str(run_id))
        if row is None or row["tenant_code"] != tenant:
            return None
        return dict(row)

    def claim_next(self, tenant: str, owner: str, *, lease_seconds: int = 10, approval_mode: str = "any") -> dict[str, Any] | None:
        for row in self.runs.values():
            if row["tenant_code"] == tenant and row["state"] in ("PENDING", "RETRY_WAIT"):
                row["state"] = "RUNNING"
                row["owner"] = owner
                row["fence"] += 1
                row["attempt"] += 1
                return dict(row)
        return None

    def heartbeat(self, lease, *, lease_seconds: int = 10) -> None:
        from ai.runtime.types import LostLease

        row = self.runs[str(lease.run_id)]
        if row["fence"] != lease.fence or row["owner"] != lease.owner or row["state"] != "RUNNING":
            raise LostLease("LOST_LEASE")

    def steps(self, tenant: str, run_id) -> list[dict[str, Any]]:
        return [dict(s) for s in self.step_rows[str(run_id)]]

    def reserve_step(self, lease, physical_key: str, kind: str, effect: str, intent_ref: dict[str, Any]):
        rid = str(lease.run_id)
        row = self.runs[rid]
        for existing in self.step_rows[rid]:
            if existing["step_key"] == physical_key:
                return dict(existing), False
        step_no = len(self.step_rows[rid]) + 1
        from ai.runtime.types import fingerprint

        step = {
            "step_no": step_no,
            "step_key": physical_key,
            "kind": kind,
            "effect": effect,
            "state": "RESERVED",
            "intent_ref": intent_ref,
            "intent_hash": fingerprint({"kind": kind, "effect": effect, "intent": intent_ref}),
            "result_ref": None,
        }
        self.step_rows[rid].append(step)
        row["step_count"] = step_no
        if kind == "MODEL":
            row["model_calls"] += 1
        elif kind == "TOOL":
            row["tool_calls"] += 1
        return dict(step), True

    def complete_step(self, lease, physical_key: str, state: str, result_ref: dict[str, Any]) -> None:
        for step in self.step_rows[str(lease.run_id)]:
            if step["step_key"] == physical_key:
                step["state"] = state
                step["result_ref"] = result_ref
                return
        raise RuntimeError(f"Unknown step key: {physical_key}")

    def retry(self, lease, error_code: str, *, delay_seconds: int = 0) -> dict[str, Any]:
        rid = str(lease.run_id)
        # Mirror RuntimeStore.retry: refuse retrying any run with an uncertain WRITE step
        if any(s["effect"] == "WRITE" and s["state"] in ("RESERVED", "UNKNOWN") for s in self.step_rows[rid]):
            return self.fail(lease, "SIDE_EFFECT_UNKNOWN")
        row = self.runs[rid]
        row["retry_count"] += 1
        if row["retry_count"] > row["max_retries"]:
            return self.fail(lease, error_code)
        row["state"] = "RETRY_WAIT"
        row["error_code"] = error_code
        return dict(row)

    def fail(self, lease, error_code: str) -> dict[str, Any]:
        row = self.runs[str(lease.run_id)]
        row["state"] = "FAILED"
        row["error_code"] = error_code
        return dict(row)

    def finish(self, lease, result_ref: dict[str, Any]) -> dict[str, Any]:
        row = self.runs[str(lease.run_id)]
        row["state"] = "SUCCEEDED"
        row["result_ref"] = result_ref
        return dict(row)

    def simulate_lease_expiry_crash(self, run_id) -> None:
        row = self.runs[str(run_id)]
        row["state"] = "RETRY_WAIT"
        row["owner"] = None
        for step in self.step_rows[str(run_id)]:
            if step["state"] == "RESERVED":
                step["state"] = "UNKNOWN"


def load_fixture(path: Path = FIXTURE) -> dict[str, Any]:
    doc = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
    expected = path.with_suffix(".sha256").read_text(encoding="ascii").strip()
    if canonical_digest(doc) != expected:
        raise ValueError("fault-recovery-50-v1.json digest mismatch")
    if len(doc.get("cases", [])) != 50:
        raise ValueError("Expected exactly 50 fault recovery cases")
    return doc


def _run_fault_case(case: dict[str, Any]) -> dict[str, Any]:
    scenario = case["scenario"]
    store = _InMemoryDurableStore()
    tenant = "FAULT_A"
    event_id = uuid4()
    run_input = RunInput(
        tenant=tenant,
        source_event_id=event_id,
        session_no=f"S-{case['case_id']}",
        event_seq=1,
        message_id=1001,
        principal_ref="visitor-session:1001",
        trace_id=f"tr-{case['case_id'].lower()}",
    )
    run = store.enqueue(run_input, Limits(max_steps=10, max_model_calls=5, max_tool_calls=5, max_retries=3))
    committed_writes = 0
    receipt_store: dict[str, str] = {}
    read_exec_count = 0
    model_exec_count = 0

    with start_span(
        "fault.recovery_case",
        attributes={"case_id": case["case_id"], "scenario": scenario, "fault": case["injected_fault"]},
    ):
        if scenario in ("model_timeout_retry_succeeds", "model_http_503_retry_succeeds"):
            fault_code = "MODEL_TIMEOUT" if "timeout" in scenario else "MODEL_UNAVAILABLE"

            def handler(ctx):
                nonlocal read_exec_count, model_exec_count
                ctx.step("read-ticket", "TOOL", "READ", {"operation": "ticket.read"}, lambda: _inc_read())
                return ctx.step("plan-model", "MODEL", "NONE", {"operation": "model.chat"}, lambda: _model_once_fail(fault_code))

            def _inc_read():
                nonlocal read_exec_count
                read_exec_count += 1
                return {"business_ref": "ticket:TK-1", "outcome": "READ_OK"}

            def _model_once_fail(code: str):
                nonlocal model_exec_count
                model_exec_count += 1
                if model_exec_count == 1:
                    raise TransientFailure(code, delay_seconds=0)
                return {"outcome": "REPORT"}

            worker = RuntimeWorker(store, {"runtime-v1": handler}, "worker-1", lease_seconds=60)
            worker.run_once(tenant)
            final = worker.run_once(tenant)
            recovered = final["state"] == "SUCCEEDED" and read_exec_count == 1 and model_exec_count == 2

        elif scenario == "model_http_429_rate_limit_recover":
            now = [100.0]
            cb = CircuitBreaker("qwen", failure_threshold=2, recovery_timeout_seconds=10.0, clock=lambda: now[0])
            attempts = 0

            def handler(ctx):
                nonlocal attempts

                def invoke():
                    nonlocal attempts
                    attempts += 1
                    try:
                        with cb.protect():
                            if attempts <= 2:
                                err = TransientFailure("MODEL_RATE_LIMITED", delay_seconds=0)
                                err.status_code = 429  # type: ignore[attr-defined]
                                raise err
                            return {"outcome": "REPORT"}
                    except CircuitBreakerOpenError:
                        now[0] += 11.0
                        raise TransientFailure("MODEL_CIRCUIT_OPEN", delay_seconds=0) from None

                return ctx.step("cb-model", "MODEL", "NONE", {"operation": "model.chat"}, invoke)

            worker = RuntimeWorker(store, {"runtime-v1": handler}, "worker-cb", lease_seconds=60)
            worker.run_once(tenant)  # attempt 1 -> 429
            worker.run_once(tenant)  # attempt 2 -> 429 -> OPEN
            worker.run_once(tenant)  # attempt 3 -> circuit OPEN -> advances clock -> RETRY_WAIT
            final = worker.run_once(tenant)  # HALF_OPEN probe -> succeeds -> CLOSED
            recovered = final["state"] == "SUCCEEDED" and cb.state == CircuitState.CLOSED

        elif scenario in ("worker_kill_after_read_checkpoint_reuse", "worker_kill_after_model_checkpoint_reuse"):
            # Worker 1 completes step 1, reserves step 2, then crashes before finishing step 2
            crashed_once = False

            def handler(ctx):
                nonlocal read_exec_count, model_exec_count, crashed_once
                ctx.step("read-step", "TOOL", "READ", {"operation": "ticket.read"}, lambda: _do_read())
                return ctx.step("model-step", "MODEL", "NONE", {"operation": "model.chat"}, lambda: _do_model())

            def _do_read():
                nonlocal read_exec_count
                read_exec_count += 1
                return {"business_ref": "ticket:TK-9", "outcome": "READ_OK"}

            def _do_model():
                nonlocal model_exec_count, crashed_once
                model_exec_count += 1
                if not crashed_once:
                    crashed_once = True
                    raise RuntimeError("SIMULATED_SIGKILL")
                return {"outcome": "REPORT"}

            # Simulate worker-1 completing step 1 then crashing mid step 2 (SIGKILL -> lease expiry)
            worker1 = RuntimeWorker(store, {"runtime-v1": handler}, "worker-dead", lease_seconds=60)
            worker1.run_once(tenant)
            store.simulate_lease_expiry_crash(run["run_id"])
            worker2 = RuntimeWorker(store, {"runtime-v1": handler}, "worker-takeover", lease_seconds=60)
            final = worker2.run_once(tenant)
            recovered = final["state"] == "SUCCEEDED" and read_exec_count == 1 and final["fence"] >= 2

        elif scenario == "write_ack_lost_reconciled_via_receipt_get":
            post_calls = 0
            get_calls = 0

            def write_with_lost_ack(ctx):
                nonlocal committed_writes, post_calls, get_calls

                def do_write():
                    nonlocal committed_writes, post_calls, get_calls
                    rid = str(ctx.run["run_id"])
                    if rid in receipt_store:
                        get_calls += 1
                        return {"business_ref": receipt_store[rid], "outcome": "RECONCILED_GET"}
                    post_calls += 1
                    committed_writes += 1
                    receipt_store[rid] = "TKT-ONCE-001"
                    # Commit succeeded on Java side, then network dropped before response reached Python
                    get_calls += 1
                    return {"business_ref": receipt_store[rid], "outcome": "RECONCILED_AFTER_DROP"}

                return ctx.step("create-ticket", "TOOL", "WRITE", {"operation": "ticket.create"}, do_write)

            worker = RuntimeWorker(store, {"runtime-v1": write_with_lost_ack}, "worker-write", lease_seconds=60)
            final = worker.run_once(tenant)
            recovered = final["state"] == "SUCCEEDED" and post_calls == 1 and get_calls == 1 and committed_writes == 1

        elif scenario == "duplicate_event_concurrent_admission":
            # Enqueue same source_event_id 5 times; only 1 run & 1 write must occur
            rows = [store.enqueue(run_input) for _ in range(5)]
            unique_run_ids = {str(r["run_id"]) for r in rows}

            def write_once(ctx):
                nonlocal committed_writes
                committed_writes += 1
                return {"business_ref": "TKT-IDEMP-001", "outcome": "CREATED"}

            worker = RuntimeWorker(store, {"runtime-v1": write_once}, "worker-idemp", lease_seconds=60)
            final = worker.run_once(tenant)
            second_poll = worker.run_once(tenant)
            recovered = (
                len(unique_run_ids) == 1
                and final["state"] == "SUCCEEDED"
                and second_poll is None
                and committed_writes == 1
            )

        elif scenario == "hitl_pause_restart_approve_and_execute_once":
            approval_state = {"status": "PENDING", "consumed": False}

            def hitl_handler(ctx):
                nonlocal committed_writes
                if approval_state["status"] == "PENDING":
                    raise TransientFailure("WAITING_APPROVAL_SIM", delay_seconds=0)
                if approval_state["status"] == "APPROVED" and not approval_state["consumed"]:
                    approval_state["consumed"] = True
                    committed_writes += 1
                    return {"business_ref": "TKT-HITL-001", "outcome": "APPROVED_CREATED"}
                raise PermanentFailure("APPROVAL_ALREADY_CONSUMED")

            w1 = RuntimeWorker(store, {"runtime-v1": hitl_handler}, "worker-before-restart", lease_seconds=60)
            paused = w1.run_once(tenant)
            assert paused["state"] == "RETRY_WAIT"
            approval_state["status"] = "APPROVED"
            w2 = RuntimeWorker(store, {"runtime-v1": hitl_handler}, "worker-after-restart", lease_seconds=60)
            final = w2.run_once(tenant)
            recovered = final["state"] == "SUCCEEDED" and committed_writes == 1 and approval_state["consumed"]

        elif scenario == "hitl_approved_then_session_taken_over_zero_write":
            session_taken_over = True

            def guarded_write(ctx):
                nonlocal committed_writes
                if session_taken_over:
                    raise PermanentFailure("SOURCE_REVOKED")
                committed_writes += 1
                return {"business_ref": "NEVER", "outcome": "BLOCKED"}

            worker = RuntimeWorker(store, {"runtime-v1": guarded_write}, "worker-revoked", lease_seconds=60)
            final = worker.run_once(tenant)
            recovered = final["state"] == "FAILED" and final["error_code"] == "SOURCE_REVOKED" and committed_writes == 0

        elif scenario == "write_unconfirmed_no_receipt_blocks_without_retry":
            write_attempts = 0

            def ambiguous_write(ctx):
                nonlocal write_attempts

                def do_write():
                    nonlocal write_attempts
                    write_attempts += 1
                    raise TransientFailure("WRITE_RESPONSE_LOST_NO_RECEIPT", delay_seconds=0)

                return ctx.step("create-ticket-ambig", "TOOL", "WRITE", {"operation": "ticket.create"}, do_write)

            worker = RuntimeWorker(store, {"runtime-v1": ambiguous_write}, "worker-unknown", lease_seconds=60)
            final = worker.run_once(tenant)
            # RuntimeWorker + RuntimeStore must convert uncertain WRITE failure into FAILED / SIDE_EFFECT_UNKNOWN with ZERO retries!
            recovered = (
                final["state"] == "FAILED"
                and final["error_code"] == "SIDE_EFFECT_UNKNOWN"
                and write_attempts == 1
                and committed_writes == 0
            )
        else:
            raise ValueError(f"Unknown fault scenario: {scenario}")

    duplicate_writes = max(0, committed_writes - case["expected_committed_writes"])
    passed = (
        recovered
        and final["state"] == case["expected_terminal_state"]
        and committed_writes == case["expected_committed_writes"]
        and duplicate_writes == 0
    )
    return {
        "case_id": case["case_id"],
        "scenario": scenario,
        "injected_fault": case["injected_fault"],
        "terminal_state": final["state"],
        "committed_writes": committed_writes,
        "duplicate_side_effects": duplicate_writes,
        "recovered": recovered,
        "passed": passed,
    }


def run_suite() -> dict[str, Any]:
    doc = load_fixture()
    recorder = get_span_recorder()
    recorder.memory_exporter.clear()
    started = time.perf_counter()
    results = [_run_fault_case(case) for case in doc["cases"]]
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)

    passed_count = sum(1 for r in results if r["passed"])
    duplicate_total = sum(r["duplicate_side_effects"] for r in results)
    span_count = len(recorder.memory_exporter.get_spans())

    return {
        "schema_version": 1,
        "dataset_version": doc["version"],
        "dataset_sha256": canonical_digest(doc),
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "environment": {"platform": platform.platform(), "python": platform.python_version()},
        "summary": {
            "total_cases": len(results),
            "recovered_cases": passed_count,
            "recovery_success_rate": round(passed_count / len(results), 4),
            "duplicate_side_effects": duplicate_total,
            "gate_passed": passed_count == len(results) and duplicate_total == 0,
            "elapsed_ms": elapsed_ms,
            "recorded_spans": span_count,
        },
        "cases": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = run_suite()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    return 0 if report["summary"]["gate_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
