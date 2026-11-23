"""The boundary gate requires one passing result from the correct integration report."""
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_boundary_report as boundary


class AgentBoundaryReportTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.contract = boundary.load_contract()
        self.selected = self.contract['cases'][0]

    def write(self, omit=None, status=None, duplicate=False, wrong_module=False):
        for report in boundary.REPORTS:
            root = ElementTree.Element('testsuite')
            for case in self.contract['cases']:
                if case['report'] != report or (omit and case == self.selected):
                    continue
                module = f"tests.{case['module']}"
                if wrong_module and case == self.selected:
                    module = f'{module}_other'
                node = ElementTree.SubElement(root, 'testcase', classname=module, name=case['test'], time='0.01')
                if status and case == self.selected:
                    ElementTree.SubElement(node, status, message='synthetic failure detail')
                if duplicate and case == self.selected:
                    ElementTree.SubElement(root, 'testcase', node.attrib)
            ElementTree.ElementTree(root).write(self.directory / report, encoding='utf-8')

    def evaluate(self):
        return boundary.evaluate(self.contract, self.directory)

    def test_expected_cases_pass_when_both_reports_contain_unambiguous_results(self):
        self.write()
        result = self.evaluate()
        self.assertTrue(result['gate_passed'])
        self.assertEqual(10, result['required'])
        self.assertEqual(10, result['passed'])
        self.assertTrue(all(report['sha256'] for report in result['reports'].values()))

    def test_selected_skips_or_failures_do_not_count_and_do_not_copy_diagnostics(self):
        for status in ('skipped', 'error', 'failure'):
            with self.subTest(status=status):
                self.write(status=status)
                result = self.evaluate()
                self.assertFalse(result['gate_passed'])
                self.assertEqual(9, result['passed'])
                self.assertEqual(status, result['cases'][0]['status'])
                self.assertNotIn('synthetic failure detail', repr(result))

    def test_missing_result_or_name_collision_from_other_module_cannot_pass(self):
        for options in ({'omit': True}, {'wrong_module': True}):
            with self.subTest(options=options):
                self.write(**options)
                result = self.evaluate()
                self.assertFalse(result['gate_passed'])
                self.assertEqual('not_executed', result['cases'][0]['status'])

    def test_duplicate_results_fail_even_when_both_pass(self):
        self.write(duplicate=True)
        result = self.evaluate()
        self.assertFalse(result['gate_passed'])
        self.assertEqual('duplicate', result['cases'][0]['status'])

    def test_corrupt_or_missing_source_report_fails(self):
        self.write()
        path = self.directory / self.selected['report']
        path.write_text('<testsuite>', encoding='utf-8')
        result = self.evaluate()
        self.assertFalse(result['gate_passed'])
        self.assertEqual('invalid_xml', result['cases'][0]['status'])
        path.unlink()
        result = self.evaluate()
        self.assertFalse(result['gate_passed'])
        self.assertEqual('missing', result['cases'][0]['status'])

    def test_changed_case_matrix_cannot_pass_the_frozen_digest(self):
        fixture = self.directory / boundary.FIXTURE.name
        fixture.write_text('{}', encoding='utf-8')
        fixture.with_suffix('.sha256').write_bytes(boundary.FIXTURE.with_suffix('.sha256').read_bytes())
        with self.assertRaisesRegex(ValueError, 'frozen version'):
            boundary.load_contract(fixture)
