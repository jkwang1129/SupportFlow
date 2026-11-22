"""Exact estimates for observed usage under an explicit, tenant-bound price book."""

from dataclasses import dataclass
from collections.abc import Mapping
from hashlib import sha256
import json
import re
from types import MappingProxyType

from .types import identifier, tenant_code

MAX_PRICE_BOOK_BYTES = 32768
MAX_RATE_MICROS = 1_000_000_000_000
PICOS_PER_UNIT = 10**12


def _unique_object(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError('Duplicate price book field')
    return result


@dataclass(frozen=True)
class PriceBook:
    tenant: str
    version: str
    currency: str
    digest: str
    rates: Mapping[str, tuple[int, int | None]]

    def estimate_picos(self, profile_hash, input_tokens, output_tokens):
        rate = self.rates.get(profile_hash)
        if rate is None or (output_tokens is not None and rate[1] is None):
            return None
        input_rate, output_rate = rate
        return input_tokens * input_rate + (output_tokens or 0) * (output_rate or 0)


def load_price_book(path):
    with path.open('rb') as file:
        raw = file.read(MAX_PRICE_BOOK_BYTES + 1)
    if len(raw) > MAX_PRICE_BOOK_BYTES:
        raise ValueError('Price book exceeds limit')
    book = json.loads(raw, object_pairs_hook=_unique_object)
    if (type(book) is not dict or set(book) != {'schema_version', 'tenant', 'version', 'currency', 'rates'}
            or book['schema_version'] != 'runtime-price-book-v1'):
        raise ValueError('Invalid price book')
    tenant, version = tenant_code(book['tenant']), identifier(book['version'], 32)
    currency, rates = book['currency'], book['rates']
    if (type(currency) is not str or re.fullmatch(r'[A-Z]{3}', currency) is None
            or type(rates) is not dict or not 1 <= len(rates) <= 64):
        raise ValueError('Invalid price book currency or rates')

    entries = {}
    for profile_hash, rate in rates.items():
        if (re.fullmatch(r'[0-9a-f]{64}', profile_hash) is None or type(rate) is not dict
                or set(rate) not in ({'input_micros_per_million'}, {'input_micros_per_million', 'output_micros_per_million'})
                or any(type(value) is not int or not 0 <= value <= MAX_RATE_MICROS for value in rate.values())):
            raise ValueError('Invalid price book rate')
        entries[profile_hash] = (rate['input_micros_per_million'], rate.get('output_micros_per_million'))
    return PriceBook(tenant, version, currency, sha256(raw).hexdigest(), MappingProxyType(entries))


def format_amount(picos):
    whole, fraction = divmod(picos, PICOS_PER_UNIT)
    return f'{whole}.{fraction:012d}'
