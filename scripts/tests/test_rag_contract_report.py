"""Fault injection for the report gate, not fabricated application evaluation results."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DATASET = json.loads((ROOT / "fixtures/evaluation/rag-abstention-v1.json").read_text(encoding="utf-8"))


class RagContractReportTest(unittest.TestCase):
    def report(self, mutation=None):
        suite = ET.Element("testsuite")
        for case in DATASET["cases"]:
            for engine in ("linear", "langgraph"):
                ET.SubElement(suite, "testcase", {
                    "classname": "tests.test_rag_abstention",
                    "name": f"test_routing_never_generates_after_the_evidence_budget_is_exhausted[{case['id']}-{engine}]",
                    "time": "0.001",
                })
        if mutation:
            mutation(suite)
        with tempfile.TemporaryDirectory(prefix="supportflow-report-test-") as directory:
            directory = Path(directory)
            junit, output = directory / "input.xml", directory / "report.json"
            ET.ElementTree(suite).write(junit, encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "scripts/report-rag-contract.py"),
                                      "--junit", str(junit), "--output", str(output)], cwd=ROOT,
                                     capture_output=True, text=True, encoding="utf-8", timeout=15)
            self.assertIn(process.returncode, (0, 1), process.stderr)
            return process.returncode, json.loads(output.read_text(encoding="utf-8"))

    def test_complete_passing_suite_is_accepted(self):
        code, report = self.report()
        self.assertEqual(0, code)
        self.assertTrue(report["gate_passed"])
        self.assertEqual("NOT_MEASURED", report["provider_tokens"])

    def test_missing_case_is_rejected(self):
        code, report = self.report(lambda suite: suite.remove(suite[-1]))
        self.assertEqual(1, code)
        self.assertFalse(report["gate_passed"])

    def test_duplicate_case_is_rejected(self):
        code, report = self.report(lambda suite: suite.append(ET.fromstring(ET.tostring(suite[0]))))
        self.assertEqual(1, code)
        self.assertFalse(report["gate_passed"])

    def test_failure_is_rejected_with_reason_preserved(self):
        code, report = self.report(lambda suite: ET.SubElement(suite[0], "failure", message="injected wrong routing"))
        self.assertEqual(1, code)
        self.assertEqual("injected wrong routing", report["cases"][0]["failure"])

    def test_skipped_engine_cannot_silently_pass_the_gate(self):
        code, report = self.report(lambda suite: ET.SubElement(suite[0], "skipped", message="engine unavailable"))
        self.assertEqual(1, code)
        self.assertFalse(report["gate_passed"])


if __name__ == "__main__":
    unittest.main()
