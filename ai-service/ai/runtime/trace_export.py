"""Export metadata for a single Runtime task using the restricted operator database account."""
import argparse
from datetime import datetime
import json
import sys
from uuid import UUID

import psycopg

from ..core.schema_guard import runtime_missing
from .store import RuntimeStore
from .types import RuntimeConflict, tenant_code


def _metadata(value):
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError('Unexpected trace metadata')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True)
    parser.add_argument('--run', required=True, type=UUID)
    args = parser.parse_args(argv)
    try:
        tenant = tenant_code(args.tenant)
        if runtime_missing():
            print('Runtime trace schema or role is unavailable', file=sys.stderr)
            return 1
        snapshot = RuntimeStore().trace_snapshot(tenant, args.run)
        if snapshot is None:
            print('Runtime trace not found', file=sys.stderr)
            return 1
        print(json.dumps(snapshot, default=_metadata))
        return 0
    except (psycopg.Error, RuntimeConflict, ValueError) as failure:
        print('Runtime trace unavailable: ' + type(failure).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
