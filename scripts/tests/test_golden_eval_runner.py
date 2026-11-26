"""Unit tests for the 200-case Golden Dataset evaluation runner."""
from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures", "../ai-service"):
    _p = str((_SCRIPTS_DIR / _sub).resolve())
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import unittest

import golden_eval_runner


class GoldenEvalRunnerTest(unittest.TestCase):
    def test_offline_200_case_golden_eval_meets_all_quality_and_trace_gates(self):
        report = golden_eval_runner.run_evaluation(live_qwen=False)
        summary = report["summary"]
        self.assertEqual(summary["total_cases"], 200)
        self.assertGreaterEqual(summary["task_success_rate"], 0.90)
        self.assertGreaterEqual(summary["tool_call_success_rate"], 0.95)
        self.assertEqual(summary["trace_node_coverage"]["coverage_rate"], 1.0)
        self.assertEqual(summary["trace_node_coverage"]["missing_nodes"], [])
        self.assertEqual(summary["human_judge_calibration"]["sample_count"], 40)
        self.assertGreaterEqual(summary["human_judge_calibration"]["exact_agreement_rate"], 0.85)
        self.assertEqual(summary["by_category"]["security_injection"]["pass_rate"], 1.0)
        self.assertEqual(summary["by_category"]["fault_recovery"]["pass_rate"], 1.0)


if __name__ == "__main__":
    unittest.main()
