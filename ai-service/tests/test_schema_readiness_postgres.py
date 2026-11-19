"""Disposable database fault injection; probes always connect with production runtime identities."""
import os

import psycopg
import pytest

from ai.config import Settings
from ai.core import db, schema_guard


pytestmark = pytest.mark.skipif(not os.getenv("SUPPORTFLOW_SCHEMA_TEST_AI_URL"), reason="requires isolated schema/role fixture")


@pytest.fixture(autouse=True)
def settings(monkeypatch):
    settings = Settings(_env_file=None, database_url=os.environ["SUPPORTFLOW_SCHEMA_TEST_AI_URL"],
                        kb_database_url=os.environ["SUPPORTFLOW_SCHEMA_TEST_KB_URL"])
    monkeypatch.setattr(db,"get_settings",lambda:settings)
    monkeypatch.setattr(schema_guard,"get_settings",lambda:settings)
    return settings


def test_latest_baseline_is_ready_under_actual_non_owner_ai_and_kb_identities(settings):
    for dsn in (settings.database_url,settings.kb_database_url):
        with psycopg.connect(dsn) as connection:
            assert connection.execute("SELECT NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole) FROM pg_roles WHERE rolname=current_user").fetchone() == (True,)
    assert schema_guard.check() == {"ready":True,"missing":[],"unavailable":False}


def test_missing_column_is_not_ready_and_renaming_it_back_restores_readiness():
    with psycopg.connect(os.environ["SUPPORTFLOW_SCHEMA_TEST_ADMIN_URL"],autocommit=True) as admin:
        admin.execute("ALTER TABLE bot_setting RENAME COLUMN brand_name TO readiness_hidden_brand")
        try:
            result=schema_guard.check()
            assert not result["ready"]
            assert "ai_db.bot_setting.brand_name" in result["missing"]
        finally:
            admin.execute("ALTER TABLE bot_setting RENAME COLUMN readiness_hidden_brand TO brand_name")
    assert schema_guard.is_ready()


def test_missing_table_is_not_ready_and_no_runtime_auto_repair_is_attempted(monkeypatch):
    monkeypatch.setenv("SUPPORTFLOW_AI_AUTO_MIGRATE","true")
    with psycopg.connect(os.environ["SUPPORTFLOW_SCHEMA_TEST_ADMIN_URL"],autocommit=True) as admin:
        admin.execute("ALTER TABLE bot_intent_sample RENAME TO readiness_hidden_sample")
        try:
            assert not schema_guard.ensure_schema()
            assert admin.execute("SELECT to_regclass('public.bot_intent_sample')").fetchone() == (None,)
        finally:
            admin.execute("ALTER TABLE readiness_hidden_sample RENAME TO bot_intent_sample")
    assert schema_guard.is_ready()


def test_select_revocation_cannot_be_hidden_by_remaining_insert_update_permissions():
    with psycopg.connect(os.environ["SUPPORTFLOW_SCHEMA_TEST_ADMIN_URL"],autocommit=True) as admin:
        admin.execute("REVOKE SELECT ON bot_intent_sample FROM supportflow_ai")
        try:
            assert not schema_guard.is_ready()
        finally:
            admin.execute("GRANT SELECT ON bot_intent_sample TO supportflow_ai")
    assert schema_guard.is_ready()
