"""Verify order-based fusion, relevance denominators and full-leg scope checks."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
from math import log2
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rag_retrieval_benchmark as benchmark


def hit(chunk, score):
    return {'id': chunk, 'doc_id': chunk, 'score': score}


class RetrievalBenchmarkTest(unittest.TestCase):
    def test_rrf_uses_rank_and_does_not_count_duplicates_within_a_leg(self):
        keyword = [hit(2, .2), hit(2, .2), hit(1, .01)]
        dense = [hit(3, .9), hit(1, .8)]
        blended = benchmark.reciprocal_rank_fusion(keyword, dense)
        self.assertEqual([1, 2, 3], [row['id'] for row in blended])
        self.assertAlmostEqual(1 / 63 + 1 / 62, blended[0]['score'])
        self.assertAlmostEqual(1 / 61, blended[1]['score'])

    def test_rank_metrics_deduplicate_documents_and_exclude_unlabeled_questions(self):
        actual = benchmark.ranking_metrics(['irrelevant', 'doc-a', 'doc-a', 'irrelevant', 'doc-b', 'doc-c'],
                                           {'doc-a', 'doc-b', 'doc-c'})
        self.assertEqual(2 / 3, actual['recall_at_5'])
        self.assertEqual(.5, actual['mrr_at_5'])
        self.assertAlmostEqual((1 / log2(3) + 1 / log2(6)) / (1 + 1 / log2(3) + .5), actual['ndcg_at_5'])
        self.assertIsNone(benchmark.ranking_metrics(['doc-a'], set()))

    def test_scope_violation_below_top_five_still_fails_full_leg_check(self):
        matches = [hit(rank, 1 / rank) for rank in range(1, 7)]
        case = {'id': 'synthetic', 'category': 'tenant_scope', 'expected': {'relevant_document_ids': ['d1']}}
        keys = {rank: f'd{rank}' for rank in range(1, 6)}
        scope = {'tenant_code': 'TEST_A', 'top_k': 10, 'published_only': True, 'doc_ids': None}
        with (patch.object(benchmark.store, 'keyword_search', return_value=matches) as keyword,
              patch.object(benchmark.store, 'search', return_value=matches[:5]) as dense):
            result = benchmark.measure_case(case, 'synthetic query', scope, [
                                            1], keys, set(keys.values()), dense_first=False)
        keyword.assert_called_once_with(query='synthetic query', **scope)
        dense.assert_called_once_with(vector=[1], **scope)
        self.assertEqual([None], result['violations']['keyword'])
        self.assertEqual([], result['violations']['rrf'])
        self.assertEqual(5, len(result['profiles']['keyword']['hits']))
