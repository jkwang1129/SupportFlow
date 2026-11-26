"""Require the named agent boundary checks to actually pass on disposable PostgreSQL."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import argparse
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree

from rag_quality_dataset import canonical_digest, reject_duplicate_keys

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'fixtures/evaluation/agent-boundaries-v1.json'
REPORTS = {'runtime-store.xml', 'knowledge-isolation.xml'}


def load_contract(path=FIXTURE):
    document = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
    if canonical_digest(document) != path.with_suffix('.sha256').read_text(encoding='ascii').strip():
        raise ValueError('boundary matrix differs from the frozen version')
    cases = document['cases']
    if (not cases or {row['report'] for row in cases} != REPORTS or len({row['id'] for row in cases}) != len(cases)
            or len({(row['report'], row['module'], row['test']) for row in cases}) != len(cases)):
        raise ValueError('boundary matrix has missing or duplicate evidence identifiers')
    return document


def read_report(path):
    try:
        raw = path.read_bytes()
    except OSError:
        return {}, {'status': 'missing', 'sha256': None}
    source = {'status': 'available', 'sha256': sha256(raw).hexdigest()}
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError:
        return {}, source | {'status': 'invalid_xml'}
    entries = defaultdict(list)
    for case in root.iter('testcase'):
        cname = case.get('classname') or ''
        mod = cname.rsplit('.', 1)[-1] if cname.startswith('tests.') else cname
        key = (mod, case.get('name'))
        status = next((tag for tag in ('error', 'failure', 'skipped') if case.find(tag) is not None), 'passed')
        entries[key].append({'status': status, 'duration_seconds': case.get('time')})
    return entries, source


def evaluate(contract, directory):
    read = {name: read_report(directory / name) for name in sorted(REPORTS)}
    results = []
    for entry in contract['cases']:
        indexed, source = read[entry['report']]
        matches = indexed.get((entry['module'], entry['test']), [])
        result = {'status': source['status'], 'duration_seconds': None}
        if source['status'] == 'available':
            result = matches[0] if len(matches) == 1 else result | {
                'status': 'duplicate' if matches else 'not_executed'}
        results.append(entry | result)
    passed = sum(row['status'] == 'passed' for row in results)
    return {
        'schema_version': 1, 'contract_version': contract['version'], 'contract_sha256': canonical_digest(contract),
        'gate_passed': passed == len(results), 'passed': passed, 'required': len(results),
        'reports': {name: source for name, (_, source) in read.items()}, 'cases': results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        contract = load_contract()
        report = evaluate(contract, args.reports)
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))
    report['head'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'required': report['required'],
                      'failed': [row['id'] for row in report['cases'] if row['status'] != 'passed']}))
    return 0 if report['gate_passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
