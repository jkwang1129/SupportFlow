"""Semantic review must bind to the exact raw run and preserve its denominators."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rag_quality_review import apply_review, observations, template


class SemanticReviewTest(unittest.TestCase):
    def setUp(self):
        dataset = {'cases': [
            {'id': 'answer', 'expected': {'outcome': 'answer', 'facts': ['Fact A', 'Fact B']}},
            {'id': 'handoff', 'expected': {'outcome': 'handoff', 'facts': []}},
        ]}
        self.raw = {'cases': [{'id': case['id'], 'observations': [
            {'profile': arm, 'answer': 'Answer [1]', 'citations': [{'index': 1}], 'decision': 'answer',
             'error': None, 'scores': {'citation_pairs': [{'index': 1, 'valid': True}]}}
            for arm in ('single_pass', 'agentic')]}
            for case in dataset['cases']]}
        self.cases, self.indexed = observations(dataset, self.raw)
        self.review = template(self.cases, self.indexed, 'fixture-source-hash')
        self.review['reviewer'] = 'fixture reviewer'
        for item in self.review['judgments']:
            if item['case_id'] == 'answer':
                item.update(decision='answer', facts=[True, False], supported_citations=[], material_error=False, reason='missing condition')
            else:
                item.update(decision='handoff', facts=[], supported_citations=[1], material_error=False, reason='requires human review')

    def evaluate(self):
        return apply_review(self.cases, self.indexed, self.review, 'fixture-source-hash')

    def test_semantic_accuracy_and_handoff_are_measured_independently_of_structure(self):
        values = self.evaluate()['metrics']['agentic']
        self.assertEqual({'matched': 0, 'total': 1, 'rate': 0}, values['answer_correctness'])
        self.assertEqual({'matched': 1, 'total': 2, 'rate': 0.5}, values['fact_coverage'])
        self.assertEqual(1, values['calibration']['structurally_valid_but_unsupported_citations'])
        self.assertEqual(['handoff'], values['calibration']['structured_decision_disagreements'])
        self.assertEqual(1, values['handoff_recommendation']['tp'])
        self.assertIsNone(values['handoff_execution_accuracy'])

    def test_wrong_source_digest_is_rejected(self):
        self.review['raw_sha256'] = 'edited-results'
        with self.assertRaisesRegex(ValueError, 'unmodified raw report'):
            self.evaluate()

    def test_missing_or_duplicate_judgments_are_rejected(self):
        original = deepcopy(self.review['judgments'])
        self.review['judgments'].pop()
        with self.assertRaisesRegex(ValueError, 'cover every'):
            self.evaluate()
        self.review['judgments'] = original + original[:1]
        with self.assertRaisesRegex(ValueError, 'duplicate judgment'):
            self.evaluate()

    def test_incomplete_fact_assessments_and_invalid_references_are_rejected(self):
        self.review['judgments'][0]['facts'] = [True]
        with self.assertRaisesRegex(ValueError, 'every labeled fact'):
            self.evaluate()
        self.review['judgments'][0].update(facts=[True, False], supported_citations=[2])
        with self.assertRaisesRegex(ValueError, 'structurally valid'):
            self.evaluate()

    def test_error_cannot_receive_factual_credit(self):
        self.indexed[('answer', 'single_pass')]['error'] = 'MODEL_TIMEOUT'
        self.review['judgments'][0]['decision'] = 'error'
        with self.assertRaisesRegex(ValueError, 'failed execution cannot satisfy facts'):
            self.evaluate()
