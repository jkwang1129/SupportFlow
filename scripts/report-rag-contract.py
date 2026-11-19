"""Convert actual pytest JUnit results into a versioned, scoped RAG contract report.

This does not estimate model accuracy, tokens, cost or production latency.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "fixtures/evaluation/rag-abstention-v1.json"


def read_cases(path):
    rows = []
    for case in ET.parse(path).iter("testcase"):
        if "test_rag_abstention" not in case.get("classname", ""):
            continue
        status = "passed"
        detail = None
        for tag in ("failure", "error", "skipped"):
            node = case.find(tag)
            if node is not None:
                status, detail = tag, node.get("message")
                break
        rows.append({"case": case.get("name"), "status": status,
                     "duration_seconds": float(case.get("time", 0)), "failure": detail})
    return rows


def counts(rows):
    return {"total": len(rows), **{status: sum(row["status"] == status for row in rows)
                                  for status in ("passed", "failure", "error", "skipped")}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    rows = read_cases(args.junit)
    expected = {f"test_routing_never_generates_after_the_evidence_budget_is_exhausted[{case['id']}-{engine}]"
                for case in dataset["cases"] for engine in ("linear", "langgraph")}
    actual = [row["case"] for row in rows]
    gate = set(actual) == expected and len(actual) == len(expected) and all(row["status"] == "passed" for row in rows)
    report = {
        "schema_version": 1,
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree_changes": subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT, text=True, encoding="utf-8").splitlines(),
        "environment": {"os": platform.platform(), "report_python": platform.python_version()},
        "code_sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in (
            "ai-service/ai/rag/graph.py", "ai-service/tests/test_rag_abstention.py")},
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
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"counts": report["counts"], "gate_passed": gate, "output": str(args.output)}, ensure_ascii=False))
    return 0 if gate else 1


if __name__ == "__main__":
    sys.exit(main())
