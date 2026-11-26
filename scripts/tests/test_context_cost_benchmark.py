"""Tests for the 16-case multi-turn Context Cost Benchmark (offline stub + concurrent live Qwen hook)."""

from __future__ import annotations

import json
from pathlib import Path
import threading

from scripts.benchmarks.context_cost_benchmark import (
    build_benchmark_cases,
    deterministic_stub_responder,
    main,
    run_benchmark,
)


def test_benchmark_dataset_has_16_multi_turn_cases_with_12_to_24_turns() -> None:
    cases = build_benchmark_cases()
    assert len(cases) == 16
    for case in cases:
        assert 12 <= len(case.history) <= 24
        assert len(case.tool_results) >= 3
        assert case.expected_slots["order_no"].startswith("ORD-")
        assert case.expected_slots["ticket_no"].startswith("TKT-")
        assert case.expected_slots["customer_no"].startswith("CUST-")
        assert "****" in case.expected_slots["phone_masked"]


def test_offline_context_cost_benchmark_meets_reduction_and_retention_targets(tmp_path: Path) -> None:
    out_file = tmp_path / "context-cost-benchmark.json"
    rc = main(["--output", str(out_file)])
    assert rc == 0
    assert out_file.exists()

    report = json.loads(out_file.read_text(encoding="utf-8"))
    assert report["case_count"] == 16
    assert report["mode"] == "deterministic_stub"

    comp = report["compressed_budgeted"]
    diff = report["comparison"]

    # Must retain 100% of sticky slots across all 16 multi-turn sessions
    assert comp["sticky_slot_retention_rate"] == 1.0
    assert comp["all_cases_within_budget"] is True
    assert comp["task_resolution_accuracy"] == 1.0

    # Realistic ~30% - 55% input & total token / cost reduction
    assert 30.0 <= diff["input_token_reduction_pct"] <= 55.0
    assert 30.0 <= diff["total_token_reduction_pct"] <= 55.0
    assert 30.0 <= diff["estimated_cost_reduction_pct"] <= 55.0


def test_live_qwen_concurrent_executor_mode_with_fake_provider() -> None:
    lock = threading.Lock()
    seen_threads: set[int] = set()

    class FakeQwenGeneration:
        def configuration(self) -> dict[str, object]:
            return {"model": "qwen-plus"}

        async def generate(self, payload: dict, receipt: dict, *, scene: str = "") -> dict:
            with lock:
                seen_threads.add(threading.get_ident())
            stub = deterministic_stub_responder(payload["messages"])
            prompt_chars = sum(len(str(m.get("content") or "")) for m in payload["messages"])
            prompt_toks = max(50, int(prompt_chars * 0.55))
            receipt.update(
                response_text=stub["reply_text"],
                usage={
                    "prompt_tokens": prompt_toks,
                    "completion_tokens": 64,
                    "total_tokens": prompt_toks + 64,
                },
                latency_ms=12.5,
            )
            return {"id": "fake-qwen", "scene": scene}

    report = run_benchmark(
        live_qwen=True,
        max_workers=4,
        qwen_factory=FakeQwenGeneration,
    )
    assert report["mode"] == "live_qwen"
    assert report["case_count"] == 16
    assert report["compressed_budgeted"]["sticky_slot_retention_rate"] == 1.0
    assert report["compressed_budgeted"]["task_resolution_accuracy"] == 1.0
    assert len(seen_threads) >= 1
