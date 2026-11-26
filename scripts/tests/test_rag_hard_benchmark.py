"""Unit tests for the high-discrimination RAG hard benchmark dataset and metrics."""

from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import json
from math import log2
from pathlib import Path
import tempfile
import unittest

from rag_hard_benchmark import (
    BenchmarkDatasetError,
    FIXTURE_PATH,
    allowed_published_docs,
    canonical_digest,
    load_hard_fixture,
    percentile,
    ranking_metrics,
    summarize_retrieval_profile,
)


class RagHardBenchmarkTest(unittest.TestCase):
    def test_frozen_fixture_has_24_docs_multi_chunk_corpus_and_36_questions(self):
        dataset, summary = load_hard_fixture(FIXTURE_PATH)
        self.assertEqual(summary["document_count"], 24)
        self.assertGreaterEqual(summary["chunk_count"], 44)
        self.assertLessEqual(summary["chunk_count"], 54)
        self.assertEqual(summary["question_count"], 36)
        self.assertEqual(set(summary["tenant_documents"].keys()), {"HARD_A", "HARD_B"})
        self.assertEqual(set(summary["tenant_questions"].keys()), {"HARD_A", "HARD_B"})
        self.assertTrue({"official", "reference", "community"} <= set(summary["authority_counts"].keys()))
        self.assertEqual(len(summary["dataset_sha256"]), 64)
        self.assertTrue(any(d["publication"] == "draft" or d["index_status"] == "failed" for d in dataset["documents"]))

    def test_tampered_sha256_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            copy_json = Path(tmp) / "rag-hard-benchmark-v1.json"
            copy_sha = Path(tmp) / "rag-hard-benchmark-v1.sha256"
            copy_json.write_text(FIXTURE_PATH.read_text(encoding="utf-8"), encoding="utf-8")
            copy_sha.write_text("0" * 64 + "\n", encoding="ascii")
            with self.assertRaises(BenchmarkDatasetError):
                load_hard_fixture(copy_json)

    def test_ranking_metrics_recall_mrr_and_ndcg_at_5(self):
        metrics = ranking_metrics(["d-wrong", "d-rel-1", "d-rel-1", "d-rel-2", "d-other"], {"d-rel-1", "d-rel-2"})
        self.assertIsNotNone(metrics)
        assert metrics is not None
        self.assertAlmostEqual(metrics["recall_at_5"], 1.0)
        self.assertAlmostEqual(metrics["mrr_at_5"], 0.5)
        expected_dcg = (1.0 / log2(3)) + (1.0 / log2(5))
        expected_idcg = (1.0 / log2(2)) + (1.0 / log2(3))
        self.assertAlmostEqual(metrics["ndcg_at_5"], round(expected_dcg / expected_idcg, 6))

    def test_allowed_published_docs_excludes_cross_tenant_draft_and_failed_index(self):
        docs = [
            {"id": "a-pub", "tenant": "HARD_A", "publication": "published", "index_status": "ready"},
            {"id": "a-draft", "tenant": "HARD_A", "publication": "draft", "index_status": "ready"},
            {"id": "a-fail", "tenant": "HARD_A", "publication": "published", "index_status": "failed"},
            {"id": "b-pub", "tenant": "HARD_B", "publication": "published", "index_status": "ready"},
        ]
        allowed = allowed_published_docs({"tenant": "HARD_A", "document_scope": None}, docs)
        self.assertEqual(allowed, {"a-pub"})

    def test_summarize_profile_aggregates_leakage_and_latency_percentiles(self):
        cases = [
            {
                "leakage": {"hybrid_rerank": {"cross_tenant": [], "unpublished": []}},
                "profiles": {
                    "hybrid_rerank": {
                        "latency_ms": 12.4,
                        "metrics": {"recall_at_5": 1.0, "mrr_at_5": 1.0, "ndcg_at_5": 1.0},
                    }
                },
            },
            {
                "leakage": {"hybrid_rerank": {"cross_tenant": [], "unpublished": []}},
                "profiles": {
                    "hybrid_rerank": {
                        "latency_ms": 28.6,
                        "metrics": {"recall_at_5": 0.0, "mrr_at_5": 0.0, "ndcg_at_5": 0.0},
                    }
                },
            },
        ]
        summary = summarize_retrieval_profile(cases, "hybrid_rerank")
        self.assertEqual(summary["recall_at_5"], 0.5)
        self.assertEqual(summary["cross_tenant_leakage"], 0)
        self.assertEqual(summary["unpublished_leakage"], 0)
        self.assertEqual(summary["latency_ms"]["p50"], 12.4)
        self.assertEqual(summary["latency_ms"]["p95"], 28.6)
        self.assertEqual(percentile([5.0, 10.0, 20.0, 40.0], 0.5), 10.0)


if __name__ == "__main__":
    unittest.main()
