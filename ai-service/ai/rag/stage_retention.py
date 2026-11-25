"""Preview or remove one tenant's expired knowledge index staging rows."""
import argparse
import json
import os
import sys

import psycopg

from ..runtime.types import tenant_code


MAINTENANCE_DSN = 'SUPPORTFLOW_KB_STAGE_MAINTENANCE_DSN'
BATCH_SQL = 'SELECT public.supportflow_expired_kb_stage_batch(%s,%s,%s)'


def process_batch(dsn, tenant, limit, *, apply=False):
    tenant = tenant_code(tenant)
    if type(limit) is not int or not 1 <= limit <= 500:
        raise ValueError('Invalid KB stage retention batch limit')
    with psycopg.connect(dsn, autocommit=True, connect_timeout=2,
                         options='-c statement_timeout=5000 -c lock_timeout=2000 -c search_path=pg_catalog') as connection:
        with connection.transaction():
            if not apply:
                connection.execute('SET TRANSACTION READ ONLY')
            count = connection.execute(BATCH_SQL, (tenant, limit, apply)).fetchone()[0]
    return {'schema_version': 'kb-stage-retention-v1', 'tenant': tenant, 'batch_limit': limit,
            'mode': 'apply' if apply else 'preview', 'selected': count, 'deleted': count if apply else 0}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True)
    parser.add_argument('--limit', type=int, default=100)
    parser.add_argument('--apply', action='store_true', help='Delete one committed batch; the default is read-only preview')
    args = parser.parse_args(argv)
    dsn = os.environ.get(MAINTENANCE_DSN)
    if not dsn:
        parser.error(f'Set {MAINTENANCE_DSN} for the maintenance session')
    try:
        print(json.dumps(process_batch(dsn, args.tenant, args.limit, apply=args.apply)))
        return 0
    except (psycopg.Error, OSError, ValueError) as error:
        print('KB stage retention unavailable: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
