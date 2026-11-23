"""Load and validate the versioned, synthetic RAG quality comparison fixture."""
from collections import Counter
from datetime import date
from hashlib import sha256
import json
from pathlib import Path
import re
import sys


FIXTURE = Path(__file__).resolve().parents[1] / 'fixtures/evaluation/rag-quality-v1.json'
IDENTIFIER = re.compile(r'[a-z0-9]+(?:-[a-z0-9]+)*')
TENANT = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,15}')
OUTCOMES = {'answer', 'abstain', 'handoff'}
CATEGORIES = {'policy', 'multi_source', 'context', 'tenant_scope', 'lifecycle', 'unsupported',
              'conflict', 'manual_handoff', 'untrusted_document'}


class DatasetError(ValueError):
    """The benchmark fixture or its annotations disagree with the contract."""


def require(condition, message):
    if not condition:
        raise DatasetError(message)


def check_keys(record, keys, label):
    require(isinstance(record, dict) and record.keys() == keys, f'{label}: unexpected or missing fields')


def check_text(value, label):
    require(isinstance(value, str) and value.strip(), f'{label}: non-empty text required')


def check_choice(value, choices, label):
    require(isinstance(value, str) and value in choices, f'{label}: unsupported value')


def check_date(value, label):
    try:
        parsed = date.fromisoformat(value)
    except (TypeError, ValueError):
        raise DatasetError(f'{label}: ISO calendar date required') from None
    require(parsed.isoformat() == value, f'{label}: ISO calendar date required')


def unique_list(values, matcher, label):
    require(isinstance(values, list) and all(isinstance(value, str) and matcher(value) for value in values),
            f'{label}: invalid entries')
    require(len(set(values)) == len(values), f'{label}: duplicate entries')
    return set(values)


def index_records(records, keys, label):
    require(isinstance(records, list) and records, f'{label}: non-empty list required')
    by_id = {}
    for row in records:
        check_keys(row, keys, label)
        identifier = row['id']
        require(isinstance(identifier, str) and IDENTIFIER.fullmatch(identifier), f'{label}: invalid record ID')
        require(identifier not in by_id, f'{label}: duplicate ID {identifier}')
        by_id[identifier] = row
    return by_id


def eligible_policies(documents, tenant, scope, as_of):
    return {
        doc['id'] for doc in documents.values()
        if doc['tenant'] == tenant and (scope is None or doc['id'] in scope)
        and doc['publication'] == 'published' and doc['index_status'] == 'ready'
        and doc['valid_from'] <= as_of and (doc['valid_until'] is None or as_of < doc['valid_until'])
        and doc['kind'] == 'policy'
    }


def validate_case(case, documents, tenants, as_of):
    label = case['id']
    check_choice(case['category'], CATEGORIES, label)
    check_choice(case['tenant'], tenants, label)
    check_text(case['question'], label)
    scope = case['document_scope']
    if scope is not None:
        scope = unique_list(scope, documents.__contains__, f'{label} scope')

    history = case['history']
    require(isinstance(history, list) and len(history) % 2 == 0, f'{label}: history requires complete turns')
    for index, message in enumerate(history):
        check_keys(message, {'role', 'content'}, f'{label} history')
        require(message['role'] == ('user' if index % 2 == 0 else 'assistant'), f'{label}: invalid history role')
        check_text(message['content'], f'{label} history')

    expected = case['expected']
    check_keys(expected, {'outcome', 'relevant_document_ids', 'facts', 'forbidden_markers', 'rationale'}, f'{label} expected')
    check_text(expected['rationale'], f'{label} rationale')
    outcome = expected['outcome']
    check_choice(outcome, OUTCOMES, label)
    eligible = eligible_policies(documents, case['tenant'], scope, as_of)
    relevant = unique_list(expected['relevant_document_ids'], documents.__contains__, f'{label} relevance')
    require(relevant <= eligible, f'{label}: relevance includes ineligible evidence')
    require(outcome == 'abstain' or relevant, f'{label}: outcome requires policy evidence')

    facts = expected['facts']
    require(isinstance(facts, list) and bool(facts) == (outcome == 'answer'), f'{label}: outcome disagrees with facts')
    statements = set()
    for fact in facts:
        check_keys(fact, {'statement', 'evidence'}, f'{label} fact')
        statement, evidence = fact['statement'], fact['evidence']
        check_text(statement, f'{label} fact')
        require(statement not in statements, f'{label}: duplicate fact')
        statements.add(statement)
        require(isinstance(evidence, list) and evidence, f'{label}: fact lacks supporting evidence')
        sources = set()
        for reference in evidence:
            check_keys(reference, {'document_id', 'quote'}, f'{label} evidence')
            check_choice(reference['document_id'], relevant, f'{label} evidence document')
            check_text(reference['quote'], f'{label} evidence quote')
            document_id, quote = reference['document_id'], reference['quote']
            require(quote in documents[document_id]['text'], f'{label}: evidence quote absent from source')
            require((document_id, quote) not in sources, f'{label}: duplicate evidence quote')
            sources.add((document_id, quote))

    markers = unique_list(expected['forbidden_markers'], str.strip, f'{label} forbidden markers')
    for marker in markers:
        marker_sources = {doc_id for doc_id, doc in documents.items() if marker in doc['text']}
        require(marker_sources and not (marker_sources & eligible), f'{label}: forbidden marker conflicts with policy evidence')
        require(marker not in case['question'] and all(marker not in msg['content'] for msg in history),
                f'{label}: forbidden marker is exposed in the input')


def validate_dataset(dataset):
    check_keys(dataset, {'version', 'corpus_version', 'mode', 'as_of', 'documents', 'cases'}, 'dataset')
    require(dataset['version'] == FIXTURE.stem and dataset['mode'] == 'synthetic', 'dataset: unsupported version or mode')
    check_text(dataset['corpus_version'], 'corpus version')
    require(IDENTIFIER.fullmatch(dataset['corpus_version']), 'dataset: invalid corpus version')
    check_date(dataset['as_of'], 'as_of')

    documents = index_records(dataset['documents'], {'id', 'title', 'tenant', 'kind', 'publication', 'index_status',
                                                    'valid_from', 'valid_until', 'text'}, 'documents')
    tenants = set()
    for label, doc in documents.items():
        check_text(doc['tenant'], label)
        require(TENANT.fullmatch(doc['tenant']) and doc['tenant'].upper() != 'PLATFORM', f'{label}: invalid tenant')
        tenants.add(doc['tenant'])
        check_text(doc['title'], label)
        check_text(doc['text'], label)
        check_choice(doc['kind'], {'policy', 'community_note'}, label)
        check_choice(doc['publication'], {'published', 'draft', 'withdrawn'}, label)
        check_choice(doc['index_status'], {'ready', 'failed'}, label)
        check_date(doc['valid_from'], label)
        if doc['valid_until'] is not None:
            check_date(doc['valid_until'], label)
            require(doc['valid_from'] < doc['valid_until'], f'{label}: invalid validity interval')

    cases = index_records(dataset['cases'], {'id', 'category', 'tenant', 'document_scope', 'history', 'question', 'expected'}, 'cases')
    for case in cases.values():
        validate_case(case, documents, tenants, dataset['as_of'])
    return {
        'dataset_version': dataset['version'],
        'corpus_version': dataset['corpus_version'],
        'mode': dataset['mode'],
        'as_of': dataset['as_of'],
        'document_count': len(documents),
        'case_count': len(cases),
        'outcomes': dict(Counter(case['expected']['outcome'] for case in cases.values())),
        'category_counts': dict(Counter(case['category'] for case in cases.values())),
        'tenant_counts': dict(Counter(case['tenant'] for case in cases.values())),
        'recall_eligible_cases': sum(bool(case['expected']['relevant_document_ids']) for case in cases.values()),
    }


def canonical_digest(value):
    content = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return sha256(content).hexdigest()


def reject_duplicate_keys(pairs):
    value = dict(pairs)
    require(len(pairs) == len(value), 'JSON contains duplicate keys')
    return value


def load_fixture(path=FIXTURE):
    dataset = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=reject_duplicate_keys)
    summary = validate_dataset(dataset)
    digest = canonical_digest(dataset)
    expected_digest = path.with_suffix('.sha256').read_text(encoding='ascii').strip()
    require(digest == expected_digest, 'dataset SHA-256 differs from the frozen version')
    return dataset, summary | {'dataset_sha256': digest, 'corpus_sha256': canonical_digest(dataset['documents'])}


def main():
    try:
        _, summary = load_fixture()
        print(json.dumps(summary, sort_keys=True))
    except (OSError, ValueError) as error:
        print(f'RAG quality dataset invalid. {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
