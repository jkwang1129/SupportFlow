"""Apply an explicit case-by-case semantic review to a frozen RAG result, offline."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import argparse
from hashlib import sha256
import json
from pathlib import Path
import re

from rag_quality_dataset import COMPARISONS, SUITES, load_suite, reject_duplicate_keys

DECISIONS = {'answer', 'abstain', 'handoff', 'error'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def citation_indexes(row):
    displayed = {citation['index'] for citation in row['citations']}
    mentioned = {int(index) for index in re.findall(r'\[([0-9]{1,9})\]', row['answer'] or '')}
    return displayed | mentioned


def observations(dataset, raw):
    comparison = raw.get('configuration', {}).get('comparison', 'rewrite')
    require(comparison in COMPARISONS, 'unknown comparison in raw report')
    cases = {case['id']: case for case in dataset['cases']}
    indexed = {}
    for case in raw['cases']:
        for row in case['observations']:
            key = (case['id'], row['profile'])
            require(key not in indexed, f'duplicate result {key}')
            indexed[key] = row
    require(indexed.keys() == {(case_id, profile) for case_id in cases for profile in COMPARISONS[comparison]},
            'benchmark must contain both profiles for every frozen case')
    return cases, indexed


def template(cases, indexed, source_sha256):
    return {'schema_version': 1, 'raw_sha256': source_sha256, 'reviewer': '', 'judgments': [
        {'case_id': case, 'profile': profile, 'decision': None,
         'facts': [None for _ in cases[case]['expected']['facts']], 'material_error': None,
         'supported_citations': [], 'reason': ''}
        for case, profile in indexed
    ]}


def fraction(hits, total):
    return {'matched': hits, 'total': total, 'rate': round(hits / total, 6) if total else None}


def summarize(rows):
    answers = [row for row in rows if row['expected_decision'] == 'answer']
    abstentions = [row for row in rows if row['expected_decision'] == 'abstain']
    tp = sum(row['expected_decision'] == row['semantic_decision'] == 'handoff' for row in rows)
    fp = sum(row['expected_decision'] != 'handoff' and row['semantic_decision'] == 'handoff' for row in rows)
    fn = sum(row['expected_decision'] == 'handoff' and row['semantic_decision'] != 'handoff' for row in rows)
    tn = len(rows) - tp - fp - fn
    refs = sum(row['citations']['total'] for row in rows)
    return {
        'answer_correctness': fraction(sum(row['correct'] for row in answers), len(answers)),
        'fact_coverage': fraction(sum(row['facts']['matched'] for row in answers), sum(row['facts']['total'] for row in answers)),
        'overall_semantic_correctness': fraction(sum(row['correct'] for row in rows), len(rows)),
        'citation_semantic_support': fraction(sum(row['citations']['matched'] for row in rows), refs),
        'answer_case_supported_citation_coverage': fraction(sum(row['citations']['matched'] > 0 for row in answers), len(answers)),
        'semantic_abstention_rate': fraction(sum(row['semantic_decision'] == 'abstain' for row in rows), len(rows)),
        'semantic_abstention_matches': fraction(sum(row['correct'] for row in abstentions), len(abstentions)),
        'handoff_recommendation': {'tp': tp, 'fp': fp, 'fn': fn, 'tn': tn,
                                   'precision': fraction(tp, tp + fp), 'recall': fraction(tp, tp + fn),
                                   'accuracy': fraction(tp + tn, len(rows))},
        'handoff_execution_accuracy': None,
        'calibration': {
            'structured_decision_disagreements': [row['case_id'] for row in rows if row['decision_disagrees']],
            'structurally_valid_but_unsupported_citations': sum(len(row['unsupported_valid_citations']) for row in rows),
        },
    }


def apply_review(cases, indexed, review, source_sha256):
    require(review.get('schema_version') == 1 and source_sha256 == review.get('raw_sha256'),
            'review must match this unmodified raw report')
    require(isinstance(review.get('reviewer'), str) and review['reviewer'].strip(), 'reviewer is required')
    rows = []
    seen = set()
    for item in review['judgments']:
        key = (item['case_id'], item['profile'])
        require(key in indexed and key not in seen, f'unknown or duplicate judgment {key}')
        seen.add(key)
        case, observation = cases[key[0]], indexed[key]
        expected = case['expected']
        decision = item['decision']
        require(decision in DECISIONS and (decision == 'error') ==
                bool(observation['error']), f'{key}: invalid decision')
        facts = item['facts']
        require(isinstance(facts, list) and len(facts) == len(expected['facts']) and all(type(value) is bool for value in facts),
                f'{key}: every labeled fact must be judged')
        require(decision != 'error' or not any(facts), f'{key}: failed execution cannot satisfy facts')
        require(type(item['material_error']) is bool, f'{key}: material_error must be true or false')
        require(isinstance(item['reason'], str) and item['reason'].strip(), f'{key}: reason required')
        indexes = citation_indexes(observation)
        structurally_valid = {pair['index'] for pair in observation['scores']['citation_pairs'] if pair['valid']}
        supported = item['supported_citations']
        require(isinstance(supported, list) and all(type(index) is int for index in supported) and
                len(supported) == len(set(supported)) and set(supported) <= structurally_valid & indexes,
                f'{key}: supported citations must be distinct, displayed, and structurally valid')
        require(decision != 'error' or not supported, f'{key}: failed execution cannot have supported citations')
        rows.append({
            'case_id': key[0], 'profile': key[1], 'expected_decision': expected['outcome'], 'semantic_decision': decision,
            'correct': decision == expected['outcome'] and all(facts) and not item['material_error'],
            'facts': fraction(sum(facts), len(facts)), 'citations': fraction(len(supported), len(indexes)),
            'reason': item['reason'], 'unsupported_valid_citations': sorted(structurally_valid - set(supported)),
            'decision_disagrees': decision != ('handoff' if observation.get('handoff_recommended') else observation['decision'] or 'error'),
        })
    require(seen == indexed.keys(), 'review must cover every paired observation')
    profiles = sorted({profile for _, profile in indexed})
    return {'schema_version': 1, 'raw_sha256': source_sha256, 'reviewer': review['reviewer'], 'rows': rows,
            'metrics': {profile: summarize([row for row in rows if row['profile'] == profile])
                        for profile in profiles}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', required=True, type=Path)
    parser.add_argument('--review', type=Path, help='When omitted, create an empty review template')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--suite', choices=SUITES, default='baseline')
    args = parser.parse_args()
    try:
        content = args.raw.read_bytes()
        raw = json.loads(content, object_pairs_hook=reject_duplicate_keys)
        dataset, fixture = load_suite(args.suite)
        require(raw['fixture']['dataset_sha256'] == fixture['dataset_sha256'],
                'raw report references a different fixture')
        cases, indexed = observations(dataset, raw)
        source_sha256 = sha256(content).hexdigest()
        if args.review:
            review = json.loads(args.review.read_bytes(), object_pairs_hook=reject_duplicate_keys)
            result = apply_review(cases, indexed, review, source_sha256)
            result['review_sha256'] = sha256(args.review.read_bytes()).hexdigest()
        else:
            result = template(cases, indexed, source_sha256)
        with args.output.open('x', encoding='utf-8') as output:
            output.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    except (ValueError, OSError, TypeError, KeyError) as error:
        parser.error(str(error))
    if args.review:
        print(json.dumps(result['metrics'], sort_keys=True))


if __name__ == '__main__':
    main()
