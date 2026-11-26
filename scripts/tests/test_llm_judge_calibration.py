"""Unit tests for LLM-as-a-Judge evaluation and human calibration metrics."""
from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import unittest

from llm_judge_calibration import (
    compute_calibration_metrics,
    deterministic_rubric_judge,
    parse_judge_json,
)


class LlmJudgeCalibrationTest(unittest.TestCase):
    def test_deterministic_judge_scores_complete_partial_and_wrong_decisions(self):
        full = deterministic_rubric_judge(
            "GK-001",
            required_facts=["168小时", "买家承担"],
            expected_decision="answer",
            candidate_answer="签收次日起满168小时内可退，非质量问题运费由买家承担。",
            candidate_decision="answer",
        )
        self.assertTrue(full.passed)
        self.assertEqual(5, full.score)

        partial = deterministic_rubric_judge(
            "GK-001",
            required_facts=["168小时", "买家承担"],
            expected_decision="answer",
            candidate_answer="签收次日起满168小时内可退。",
            candidate_decision="answer",
        )
        self.assertFalse(partial.passed)
        self.assertEqual(3, partial.score)

        mismatch = deterministic_rubric_judge(
            "GA-001",
            required_facts=[],
            expected_decision="handoff",
            candidate_answer="已直接为您重开跨月发票。",
            candidate_decision="answer",
        )
        self.assertFalse(mismatch.passed)
        self.assertEqual(1, mismatch.score)

    def test_parse_judge_json_handles_fenced_blocks_and_bounds(self):
        score, passed, grounded, reason = parse_judge_json(
            '```json\n{"score": 5, "pass": true, "grounded": true, "reason": "完全符合官方制度"}\n```'
        )
        self.assertEqual(5, score)
        self.assertTrue(passed)
        self.assertTrue(grounded)
        self.assertEqual("完全符合官方制度", reason)

        with self.assertRaises(ValueError):
            parse_judge_json('{"score": 6, "pass": true}')

    def test_calibration_metrics_compute_agreement_and_cohens_kappa(self):
        human = [True, True, True, True, False, False, False, False]
        judge_perfect = [True, True, True, True, False, False, False, False]
        perfect = compute_calibration_metrics(human, judge_perfect)
        self.assertEqual(1.0, perfect["exact_agreement_rate"])
        self.assertEqual(1.0, perfect["cohens_kappa"])

        judge_one_diff = [True, True, True, False, False, False, False, False]
        high = compute_calibration_metrics(human, judge_one_diff)
        self.assertEqual(0.875, high["exact_agreement_rate"])
        self.assertGreater(high["cohens_kappa"], 0.7)


if __name__ == "__main__":
    unittest.main()
