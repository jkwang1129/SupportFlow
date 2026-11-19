"""Fail startup if a runtime DSN has migration privileges or owns protected tables."""
import psycopg
from ..config import get_settings


def require_runtime_roles():
    settings = get_settings()
    if not settings.enforce_runtime_db_roles:
        return
    for dsn, table in ((settings.database_url, "bot_dialogue"), (settings.kb_database_url, "kb_chunk")):
        with psycopg.connect(dsn, connect_timeout=5) as conn:
            row = conn.execute("SELECT rolsuper,rolbypassrls,rolcreatedb,rolcreaterole, "
                               "pg_has_role(current_user,c.relowner,'MEMBER') "
                               "FROM pg_roles r,pg_class c WHERE r.rolname=current_user AND c.oid=%s::regclass", (table,)).fetchone()
            if not row or any(row):
                raise RuntimeError("AI runtime database identities must be non-owner, NOSUPERUSER and NOBYPASSRLS")
