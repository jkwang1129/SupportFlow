"""Look up Runtime tasks or export one task's metadata using the restricted database role."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
from uuid import UUID

import psycopg

from ..core.schema_guard import runtime_missing
from .store import RuntimeStore
from .types import RuntimeConflict, identifier, tenant_code
from .usage_pricing import load_price_book


def _metadata(value):
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError('Unexpected trace metadata')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--run', type=UUID, help='Export one task and its ordered steps')
    target.add_argument('--trace-id', help='Find up to 20 newest tasks for one exact request ID')
    parser.add_argument('--model-usage', action='store_true', help='Export observed provider usage for one task')
    parser.add_argument('--price-book', type=Path, help='Estimate the priced portion of observed usage using an explicit price book')
    args = parser.parse_args(argv)
    if args.model_usage and args.run is None:
        parser.error('--model-usage requires --run')
    if args.price_book is not None and not args.model_usage:
        parser.error('--price-book requires --model-usage')
    try:
        tenant = tenant_code(args.tenant)
        trace_id = identifier(args.trace_id) if args.trace_id is not None else None
        price_book = load_price_book(args.price_book) if args.price_book is not None else None
        if runtime_missing():
            print('Runtime trace schema or role is unavailable', file=sys.stderr)
            return 1
        store = RuntimeStore()
        if trace_id is not None:
            snapshot = store.lookup_trace(tenant, trace_id)
        elif args.model_usage:
            snapshot = store.model_usage(tenant, args.run, price_book=price_book)
        else:
            snapshot = store.trace_snapshot(tenant, args.run)
        if snapshot is None:
            print('Runtime trace not found', file=sys.stderr)
            return 1
        print(json.dumps(snapshot, default=_metadata))
        return 0
    except (psycopg.Error, RuntimeConflict, OSError, ValueError, RecursionError) as failure:
        print('Runtime trace unavailable: ' + type(failure).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
