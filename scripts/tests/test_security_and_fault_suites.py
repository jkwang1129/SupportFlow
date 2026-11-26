"""Unit tests for the 50-case security red-team suite and 50-case fault recovery suite."""
from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures", "../ai-service"):
    _p = str((_SCRIPTS_DIR / _sub).resolve())
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import unittest

import fault_recovery_suite
import security_redteam_suite


class SecurityAndFaultSuitesTest(unittest.TestCase):
    def test_security_redteam_50_suite_blocks_all_50_attacks_with_zero_leaks(self):
        report = security_redteam_suite.run_suite()
        summary = report["summary"]
        self.assertTrue(summary["gate_passed"])
        self.assertEqual(50, summary["total_cases"])
        self.assertEqual(50, summary["blocked_cases"])
        self.assertEqual(1.0, summary["unauthorized_block_rate"])
        self.assertEqual(0, summary["unauthorized_writes"])
        self.assertEqual(0, summary["data_leaks"])
        self.assertGreaterEqual(summary["recorded_spans"], 50)

    def test_fault_recovery_50_suite_recovers_all_50_cases_with_zero_duplicate_side_effects(self):
        report = fault_recovery_suite.run_suite()
        summary = report["summary"]
        self.assertTrue(summary["gate_passed"])
        self.assertEqual(50, summary["total_cases"])
        self.assertEqual(50, summary["recovered_cases"])
        self.assertEqual(1.0, summary["recovery_success_rate"])
        self.assertEqual(0, summary["duplicate_side_effects"])
        self.assertGreaterEqual(summary["recorded_spans"], 50)


if __name__ == "__main__":
    unittest.main()
