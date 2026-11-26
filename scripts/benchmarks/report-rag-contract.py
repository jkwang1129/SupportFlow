"""Convert actual pytest JUnit results into a versioned, scoped RAG contract report.

This does not estimate model accuracy, tokens, cost or production latency.
"""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "fixtures/evaluation/rag-abstention-v1.json"
EXTRACTIVE_DATASET = ROOT / "fixtures/evaluation/rag-extractive-v1.json"
EXTRACTIVE_TEST = "test_rag_extractive_support_compares_paired_outputs"
CITATION_CASES = {
    f"test_invalid_citation_withholds_generated_answer[{marker}-{engine}]"
    for marker in ("two_digit", "three_digit", "unicode_digits", "oversized")
    for engine in ("linear", "langgraph")
} | {"test_generated_in_range_citation_is_preserved", "test_no_model_excerpt_keeps_literal_document_number_and_original_source"}
CITATION_TESTS = ("test_invalid_citation_withholds_generated_answer[", "test_generated_in_range_citation_is_preserved",
                  "test_no_model_excerpt_keeps_literal_document_number_and_original_source")


def read_cases(path, module="test_rag_abstention", prefixes=()):
    rows = []
    for case in ET.parse(path).iter("testcase"):
        name = case.get("name", "")
        if module not in case.get("classname", "") or (prefixes and not name.startswith(prefixes)):
            continue
        status = "passed"
        detail = None
        for tag in ("failure", "error", "skipped"):
            node = case.find(tag)
            if node is not None:
                status, detail = tag, node.get("message")
                break
        rows.append({"case": name, "status": status,
                     "duration_seconds": float(case.get("time", 0)), "failure": detail})
    return rows


def counts(rows):
    return {"total": len(rows), **{status: sum(row["status"] == status for row in rows)
                                   for status in ("passed", "failure", "error", "skipped")}}


def passed(rows, expected):
    actual = [row["case"] for row in rows]
    return set(actual) == expected and len(actual) == len(expected) and all(row["status"] == "passed" for row in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-citations", action="store_true")
    parser.add_argument("--require-extractive", action="store_true")
    args = parser.parse_args()
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    rows = read_cases(args.junit)
    expected = {f"test_routing_never_generates_after_the_evidence_budget_is_exhausted[{case['id']}-{engine}]"
                for case in dataset["cases"] for engine in ("linear", "langgraph")}
    gate = passed(rows, expected)
    report = {
        "schema_version": 1,
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree_changes": subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT, text=True, encoding="utf-8").splitlines(),
        "environment": {"os": platform.platform(), "report_python": platform.python_version()},
        "code_sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in (
            "ai-service/ai/rag/graph.py", "ai-service/tests/unit/test_rag_abstention.py")},
        "dataset_version": dataset["version"], "dataset_sha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
        "dataset_cases": len(dataset["cases"]), "engines": ["linear", "langgraph"],
        "mode": dataset["mode"], "scope": dataset["scope"],
        "measurement": "Executed pytest contracts, not task/answer accuracy; testcase durations include test overhead",
        "junit_source": str(args.junit), "counts": counts(rows), "cases": rows, "gate_passed": gate,
        "answer_accuracy": "NOT_MEASURED", "groundedness": "NOT_MEASURED", "recall_at_5": "NOT_MEASURED",
        "provider_tokens": "NOT_MEASURED", "provider_cost": "NOT_MEASURED", "production_latency": "NOT_MEASURED",
    }
    for package in ("pytest", "langgraph"):
        try:
            report["environment"][package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            report["environment"][package] = "NOT_INSTALLED_IN_REPORT_ENVIRONMENT"
    if args.baseline:
        report["baseline"] = {"junit_source": str(args.baseline), "counts": counts(read_cases(args.baseline))}
    if args.require_citations:
        citations = read_cases(args.junit, "test_rag_failure_modes", CITATION_TESTS)
        citation_gate = passed(citations, CITATION_CASES)
        report["citation_gate"] = {"counts": counts(citations), "cases": citations, "gate_passed": citation_gate}
        for path in ("ai-service/ai/services/rag.py", "ai-service/tests/unit/test_rag_failure_modes.py"):
            report["code_sha256"][path] = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        gate = gate and citation_gate
    if args.require_extractive:
        fixture = json.loads(EXTRACTIVE_DATASET.read_text(encoding="utf-8"))
        extractive = read_cases(args.junit, "test_rag_extractive", (EXTRACTIVE_TEST + "[",))
        expected = {f"{EXTRACTIVE_TEST}[{case['id']}-{engine}]"
                    for case in fixture["cases"] for engine in ("linear", "langgraph")}
        extractive_gate = passed(extractive, expected)
        report["extractive_gate"] = {"dataset_version": fixture["version"],
                                     "dataset_sha256": hashlib.sha256(EXTRACTIVE_DATASET.read_bytes()).hexdigest(),
                                     "counts": counts(extractive), "cases": extractive, "gate_passed": extractive_gate}
        gate = gate and extractive_gate
    report["gate_passed"] = gate
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {"counts": report["counts"], "gate_passed": gate, "output": str(args.output)}
    if args.require_citations:
        summary["citation_counts"] = report["citation_gate"]["counts"]
    if args.require_extractive:
        summary["extractive_counts"] = report["extractive_gate"]["counts"]
    print(json.dumps(summary))
    return 0 if gate else 1


if __name__ == "__main__":
    sys.exit(main())
