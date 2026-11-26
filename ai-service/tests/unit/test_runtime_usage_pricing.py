"""Pricing is exact and requires an explicit, bounded snapshot."""
from hashlib import sha256
import json

import pytest

from ai.runtime.usage_pricing import MAX_PRICE_BOOK_BYTES, format_amount, load_price_book


def _example():
    return {'schema_version': 'runtime-price-book-v1', 'tenant': 'TENANT_A', 'version': 'test-v1', 'currency': 'CNY',
            'rates': {'a' * 64: {'input_micros_per_million': 1},
                      'b' * 64: {'input_micros_per_million': 0, 'output_micros_per_million': 0}}}


def test_price_book_uses_exact_integer_arithmetic_and_separates_unknown_from_zero(tmp_path):
    path = tmp_path / 'prices.json'
    raw = json.dumps(_example()).encode()
    path.write_bytes(raw)
    book = load_price_book(path)
    assert (book.tenant, book.version, book.currency, book.digest) == (
        'TENANT_A', 'test-v1', 'CNY', sha256(raw).hexdigest())
    assert format_amount(book.estimate_picos('a' * 64, 1, None)) == '0.000000000001'
    assert book.estimate_picos('a' * 64, 1, 0) is None
    assert book.estimate_picos('c' * 64, 5, None) is None
    assert format_amount(book.estimate_picos('b' * 64, 12, 40)) == '0.000000000000'


@pytest.mark.parametrize('rate', [True, 1.2, -1, 1_000_000_000_001])
def test_price_book_rejects_invalid_rates(tmp_path, rate):
    path = tmp_path / 'prices.json'
    body = _example()
    body['rates']['a' * 64]['input_micros_per_million'] = rate
    path.write_text(json.dumps(body), encoding='utf-8')
    with pytest.raises(ValueError, match='Invalid price book rate'):
        load_price_book(path)


@pytest.mark.parametrize('body', [b'{"version":"first","version":"second"}', b' ' * (MAX_PRICE_BOOK_BYTES + 1)])
def test_price_book_rejects_ambiguous_or_excessive_input(tmp_path, body):
    path = tmp_path / 'prices.json'
    path.write_bytes(body)
    with pytest.raises(ValueError):
        load_price_book(path)
