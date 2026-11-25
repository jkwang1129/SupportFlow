"""Disposable PostgreSQL checks for idle-tenant staging retention and permissions."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
from uuid import uuid4

import psycopg
import pytest

from ai.rag.stage_retention import BATCH_SQL, MAINTENANCE_DSN, process_batch


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_KB_STAGE_RETENTION_TEST_DSN'),
                                reason='requires a disposable knowledge staging database')
TENANT = 'IDLE_KB_A'
FOREIGN = 'IDLE_KB_B'


def connection(role='ADMIN'):
    prefix = f'SUPPORTFLOW_KB_STAGE_{role}_TEST_DSN' if role != 'MAINT' else 'SUPPORTFLOW_KB_STAGE_RETENTION_TEST_DSN'
    return psycopg.connect(os.environ[prefix], autocommit=True)


@pytest.fixture(autouse=True)
def reset_staging():
    yield
    with connection() as db:
        db.execute('DELETE FROM kb_chunk_stage WHERE tenant_code = ANY(%s)', ([TENANT, FOREIGN],))
        db.execute('DELETE FROM kb_chunk WHERE tenant_code = ANY(%s)', ([TENANT, FOREIGN],))


def stage(doc, *, tenant=TENANT, age_hours=48):
    with connection() as db:
        db.execute("""INSERT INTO kb_chunk_stage(id,tenant_code,doc_id,attempt_id,chunk_no,content,create_time)
            VALUES (%s,%s,%s,%s,0,'synthetic staged body',statement_timestamp() - %s * interval '1 hour')""",
                   (doc, tenant, doc, uuid4(), age_hours))


def candidates():
    with connection() as db:
        return set(db.execute('SELECT tenant_code,doc_id FROM kb_chunk_stage WHERE tenant_code = ANY(%s)',
                              ([TENANT, FOREIGN],)).fetchall())


def batch(limit=100, *, apply=False):
    return process_batch(os.environ['SUPPORTFLOW_KB_STAGE_RETENTION_TEST_DSN'], TENANT, limit, apply=apply)


def test_idle_tenant_cli_preserves_fresh_foreign_and_active_chunks():
    stage(101)
    stage(102)
    stage(103, age_hours=2)
    stage(101, tenant=FOREIGN)
    with connection() as db:
        db.execute("INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content) VALUES (100901,%s,101,0,'live policy')",
                   (TENANT,))
        live_snapshot = db.execute('SELECT * FROM kb_chunk WHERE id=100901').fetchone()

    def cli(*flags):
        env = {**os.environ, MAINTENANCE_DSN: os.environ['SUPPORTFLOW_KB_STAGE_RETENTION_TEST_DSN']}
        result = subprocess.run([sys.executable, '-m', 'ai.rag.stage_retention', '--tenant', TENANT, *flags],
                                env=env, capture_output=True, text=True, encoding='utf-8', timeout=10)
        assert result.returncode == 0 and not result.stderr and 'synthetic staged body' not in result.stdout
        return json.loads(result.stdout)

    preview = cli()
    assert (preview['mode'], preview['selected'], preview['deleted']) == ('preview', 2, 0)
    assert candidates() == {(TENANT, 101), (TENANT, 102), (TENANT, 103), (FOREIGN, 101)}
    applied, repeated = cli('--apply'), cli('--apply')
    assert (applied['deleted'], repeated['deleted']) == (2, 0)
    assert candidates() == {(TENANT, 103), (FOREIGN, 101)}
    with connection() as db:
        assert db.execute('SELECT * FROM kb_chunk WHERE id=100901').fetchone() == live_snapshot
    if path := os.getenv('SUPPORTFLOW_KB_STAGE_TEST_REPORT'):
        Path(path).write_text(json.dumps({'preview': preview, 'applied': applied, 'repeated': repeated,
                                          'fresh_rows_preserved': 1, 'foreign_rows_preserved': 1,
                                          'active_chunks_unchanged': 1}, indent=2) + '\n', encoding='utf-8')


def test_apply_skips_row_locks_and_respects_the_batch_limit():
    for doc, age in ((201, 48), (202, 36), (203, 26)):
        stage(doc, age_hours=age)
    with connection() as db, db.transaction():
        db.execute('SELECT 1 FROM kb_chunk_stage WHERE doc_id=201 AND tenant_code=%s FOR UPDATE', (TENANT,))
        assert batch(limit=1)['selected'] == 1
        assert batch(limit=1, apply=True)['deleted'] == 1
        assert candidates() == {(TENANT, 201), (TENANT, 203)}
        assert batch(limit=1, apply=True)['deleted'] == 1
        assert batch(limit=1, apply=True)['deleted'] == 0
    assert batch(apply=True)['deleted'] == 1 and not candidates()


def test_two_maintenance_workers_do_not_count_a_row_twice():
    for doc in (301, 302, 303):
        stage(doc)
    with ThreadPoolExecutor(2) as workers:
        results = [workers.submit(batch, 2, apply=True) for _ in range(2)]
        counts = sorted(result.result(timeout=8)['deleted'] for result in results)
    assert counts == [1, 2] and not candidates()


def test_read_only_and_explicit_rollback_preserve_rows():
    stage(401)
    with connection('MAINT') as db:
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction), db.transaction():
            db.execute('SET TRANSACTION READ ONLY')
            db.execute(BATCH_SQL, (TENANT, 1, True))
        with db.transaction(force_rollback=True):
            assert db.execute(BATCH_SQL, (TENANT, 1, True)).fetchone() == (1,)
    assert candidates() == {(TENANT, 401)}


def test_maintenance_expiration_does_not_depend_on_the_session_timezone():
    stage(402, age_hours=23)
    stage(403, age_hours=49)
    with connection('MAINT') as db:
        db.execute("SET TIME ZONE 'Pacific/Kiritimati'")
        assert db.execute(BATCH_SQL, (TENANT, 10, True)).fetchone() == (1,)
    assert candidates() == {(TENANT, 402)}


def test_exec_only_and_invalid_scope_is_rejected():
    with connection('MAINT') as db:
        for statement in ('SELECT content FROM public.kb_chunk_stage', 'DELETE FROM public.kb_chunk_stage',
                          'SELECT content FROM public.kb_document', 'TRUNCATE public.kb_chunk_stage'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                db.execute(statement)
        for scope in ((None, 1, False), ('PLATFORM', 1, False), ('', 1, False), (TENANT, 0, True),
                      (TENANT, 501, True), (TENANT, 1, None), (TENANT, None, False)):
            with pytest.raises(psycopg.errors.InvalidParameterValue):
                db.execute(BATCH_SQL, scope)
    for role in ('KB', 'CUSTOMER', 'PUBLIC'):
        with connection(role) as db, pytest.raises(psycopg.errors.InsufficientPrivilege):
            db.execute(BATCH_SQL, (TENANT, 1, False))
