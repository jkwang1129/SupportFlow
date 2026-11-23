"""Verify fixture integrity and reject regressions in source or outcome annotations."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rag_quality_dataset import DatasetError, FIXTURE, load_fixture, validate_dataset


class RagQualityDatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset, cls.summary = load_fixture()

    def setUp(self):
        self.data = deepcopy(self.dataset)

    def case(self, case_id):
        return next(case for case in self.data['cases'] if case['id'] == case_id)

    def test_frozen_fixture_has_all_expected_outcomes_and_partial_evidence(self):
        self.assertEqual({'answer': 13, 'abstain': 10, 'handoff': 2}, self.summary['outcomes'])
        self.assertEqual(16, self.summary['recall_eligible_cases'])
        self.assertEqual({'EVAL_A', 'EVAL_B'}, self.summary['tenant_counts'].keys())
        partial = self.case('relevant-but-insufficient')['expected']
        self.assertEqual('abstain', partial['outcome'])
        self.assertEqual(['shipping-standard'], partial['relevant_document_ids'])
        self.assertEqual([], partial['facts'])

    def test_duplicate_document_and_case_ids_are_rejected(self):
        for key in ('documents', 'cases'):
            with self.subTest(key=key):
                data = deepcopy(self.dataset)
                data[key].append(deepcopy(data[key][0]))
                with self.assertRaisesRegex(DatasetError, 'duplicate ID'):
                    validate_dataset(data)

    def test_unavailable_sources_cannot_be_labeled_relevant(self):
        for case_id, document_id in (
                ('foreign-tenant-only', 'warranty-eval-b'), ('expired-source-only', 'returns-expired'),
                ('draft-policy-only', 'refund-draft'), ('withdrawn-policy-only', 'pickup-withdrawn'),
                ('failed-index-only', 'maintenance-unindexed'), ('untrusted-note-only', 'untrusted-note'),
                ('excluded-by-document-scope', 'returns-current'), ('explicit-empty-scope', 'returns-current')):
            with self.subTest(case=case_id):
                data = deepcopy(self.dataset)
                case = next(case for case in data['cases'] if case['id'] == case_id)
                case['expected']['relevant_document_ids'].append(document_id)
                with self.assertRaisesRegex(DatasetError, 'ineligible evidence'):
                    validate_dataset(data)

    def test_answer_without_evidence_is_rejected(self):
        self.case('return-window')['expected']['facts'][0]['evidence'] = []
        with self.assertRaisesRegex(DatasetError, 'lacks supporting evidence'):
            validate_dataset(self.data)

    def test_publication_end_date_is_excluded_and_start_date_is_included(self):
        returns = next(doc for doc in self.data['documents'] if doc['id'] == 'returns-current')
        returns['valid_until'] = self.data['as_of']
        with self.assertRaisesRegex(DatasetError, 'ineligible evidence'):
            validate_dataset(self.data)
        returns['valid_until'] = None
        returns['valid_from'] = self.data['as_of']
        self.assertEqual(25, validate_dataset(self.data)['case_count'])

    def test_misquoted_passage_is_rejected(self):
        self.case('return-window')['expected']['facts'][0]['evidence'][0]['quote'] = '三天内可发起退货。'
        with self.assertRaisesRegex(DatasetError, 'quote absent'):
            validate_dataset(self.data)

    def test_handoff_cannot_be_annotated_as_having_answer_facts(self):
        self.case('conflicting-repair-policy')['expected']['facts'] = self.case('return-window')['expected']['facts']
        with self.assertRaisesRegex(DatasetError, 'outcome disagrees with facts'):
            validate_dataset(self.data)

    def test_labeled_leakage_marker_cannot_appear_in_question(self):
        self.case('foreign-tenant-only')['question'] += ' 蓝鹭售后码'
        with self.assertRaisesRegex(DatasetError, 'forbidden marker is exposed'):
            validate_dataset(self.data)

    def test_canonical_digest_is_stable_across_whitespace_and_detects_label_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / FIXTURE.name
            path.with_suffix('.sha256').write_bytes(FIXTURE.with_suffix('.sha256').read_bytes())
            path.write_text(json.dumps(self.data, sort_keys=True, indent=4), encoding='utf-8', newline='\r\n')
            _, reformatted = load_fixture(path)
            self.assertEqual(self.summary['dataset_sha256'], reformatted['dataset_sha256'])
            self.case('return-window')['expected']['rationale'] += ' 标注发生变化。'
            path.write_text(json.dumps(self.data), encoding='utf-8')
            with self.assertRaisesRegex(DatasetError, 'SHA-256 differs'):
                load_fixture(path)

    def test_conflicting_duplicate_json_key_is_rejected_before_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / FIXTURE.name
            path.write_text('{"mode":"synthetic","mode":"ambiguous"}', encoding='utf-8')
            with self.assertRaisesRegex(DatasetError, 'duplicate keys'):
                load_fixture(path)
