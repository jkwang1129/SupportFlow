"""Read-only AI/KB schema checks. Runtime identities never execute DDL.

Initialization/upgrade instructions live in backend/deploy/db-init/README.md.
Legacy AUTO_MIGRATE/SCHEMA_DIR values deliberately cannot enable runtime DDL.
"""
from __future__ import annotations

import logging
import psycopg
from psycopg.rows import dict_row
from ..config import get_settings
from .db import connect

logger = logging.getLogger(__name__)
AI_SOURCE = "backend/deploy/db-init/ai_db.sql"
KB_SOURCE = "backend/deploy/db-init/customer_db.sql"
REQUIRED_TABLES = dict.fromkeys(("bot_intent", "bot_model", "bot_setting", "bot_dialogue",
                                "bot_version", "training_job", "ai_usage_record", "bot_intent_sample"), AI_SOURCE)
REQUIRED_COLUMNS = {f"{table}.{column}": AI_SOURCE for table in REQUIRED_TABLES for column in ("id", "tenant_code")}
REQUIRED_COLUMNS.update({name: AI_SOURCE for name in (
    "bot_intent.escalate", "bot_setting.brand_name", "bot_setting.avatar_url",
    "bot_setting.reception_enabled", "bot_setting.transfer_on_anger", "bot_setting.transfer_after_unresolved",
    "bot_setting.transfer_keywords", "bot_setting.transfer_message", "bot_setting.model_key",
    "bot_model.provider", "bot_model.model_key", "bot_dialogue.slots", "bot_dialogue.session_no",
    "ai_usage_record.request_id", "ai_usage_record.cost_amount", "ai_usage_record.stat_date")})
KB_COLUMNS = {
    "kb_chunk": ("id", "tenant_code", "doc_id", "chunk_no", "content", "char_count", "token_count", "embedding", "embedding_model"),
    "kb_document": ("id", "tenant_code", "title", "status", "index_status", "is_deleted"),
}
RUNTIME_SOURCE = 'backend/deploy/migrations/sql/ai_db_002_agent_runtime.sql'
PAYLOAD_SOURCE = 'backend/deploy/migrations/sql/ai_db_003_agent_payload.sql'
RECONCILIATION_SOURCE = 'backend/deploy/migrations/sql/ai_db_004_reply_reconciliation.sql'
RUNTIME_COLUMNS = {
    'agent_run': ('tenant_code run_id source_event_id session_no event_seq message_id principal_ref trace_id runtime_version input_hash '
                  'state max_steps max_model_calls max_tool_calls max_retries step_count model_calls tool_calls retry_count attempt fence '
                  'owner lease_until ready_at deadline_at cancel_requested error_code result_ref created_at updated_at finished_at').split(),
    'agent_step': ('tenant_code run_id step_no step_key kind effect intent_hash intent_ref state fence result_ref created_at completed_at').split(),
    'agent_payload': ('tenant_code payload_id run_id step_no fence payload_schema payload_json payload_hash created_at expires_at').split(),
    'agent_reply_reconciliation': ('tenant_code run_id step_no observed_fence source_event_id snapshot_id arguments_sha256 intent_hash '
                                  'message_id message_no message_seq operator_ref original_error_code original_step_result confirmed_at').split(),
}


def _runtime_source(table):
    return {'agent_payload': PAYLOAD_SOURCE, 'agent_reply_reconciliation': RECONCILIATION_SOURCE}.get(table, RUNTIME_SOURCE)


def _kb_connect(**kwargs):
    return psycopg.connect(get_settings().kb_database_url, row_factory=dict_row, **kwargs)


def _columns(connector):
    # Metadata requires no tenant row access. Each connection/query has a bounded timeout.
    with connector(connect_timeout=2, options="-c statement_timeout=2000") as connection:
        return {f"{row['table_name']}.{row['column_name']}" for row in connection.execute(
            "SELECT table_name,column_name,pg_catalog.has_column_privilege(current_user, "
            "format('%I.%I',table_schema,table_name),column_name,'SELECT') AS readable "
            "FROM information_schema.columns WHERE table_schema='public'"
        ).fetchall() if row["readable"]}


def _missing() -> dict[str, str]:
    columns = _columns(connect)
    tables = {name.split(".", 1)[0] for name in columns}
    missing = {f"ai_db.{table}": source for table, source in REQUIRED_TABLES.items() if table not in tables}
    missing.update({f"ai_db.{column}": source for column, source in REQUIRED_COLUMNS.items() if column not in columns})
    if get_settings().runtime_api_enabled:
        missing.update(runtime_missing(columns))
    kb = _columns(_kb_connect)
    missing.update({f"customer_db.{table}.{column}": KB_SOURCE
                    for table, names in KB_COLUMNS.items() for column in names if f"{table}.{column}" not in kb})
    return missing


def runtime_missing(columns=None):
    """Opt-in runtime metadata/privilege checks, no business rows and no DDL."""
    columns = _columns(connect) if columns is None else columns
    missing = {f'ai_db.{table}.{column}': _runtime_source(table) for table, names in RUNTIME_COLUMNS.items()
               for column in names if f'{table}.{column}' not in columns}
    if missing:
        return missing
    with connect(connect_timeout=2, options='-c statement_timeout=2000') as connection:
        for row in connection.execute("""
            SELECT c.relname, c.relrowsecurity AND c.relforcerowsecurity
              AND NOT pg_has_role(current_user,c.relowner,'MEMBER')
              AND has_table_privilege(current_user,c.oid,'SELECT')
              AND has_table_privilege(current_user,c.oid,'INSERT')
              AND CASE WHEN c.relname IN ('agent_payload','agent_reply_reconciliation') THEN NOT has_table_privilege(current_user,c.oid,'UPDATE')
                  ELSE has_table_privilege(current_user,c.oid,'UPDATE') END
              AND NOT has_table_privilege(current_user,c.oid,'DELETE')
              AND NOT has_table_privilege(current_user,c.oid,'TRUNCATE') AS usable
              FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname='public' AND c.relname IN ('agent_run','agent_step','agent_payload','agent_reply_reconciliation')
            """).fetchall():
            if not row['usable']:
                missing[f"ai_db.{row['relname']}.runtime_permissions"] = _runtime_source(row['relname'])
        role = connection.execute("""SELECT NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)
              AND pg_has_role(current_user,'supportflow_agent_runtime','MEMBER') AS usable
              FROM pg_roles WHERE rolname=current_user""").fetchone()
        function = connection.execute("""SELECT CASE WHEN to_regprocedure('public.supportflow_agent_due_tenants(integer)') IS NULL
              THEN false ELSE has_function_privilege(current_user,'public.supportflow_agent_due_tenants(integer)','EXECUTE') END AS usable""").fetchone()
        if not role['usable'] or not function['usable']:
            missing['ai_db.runtime_capability'] = RUNTIME_SOURCE
    return missing


def check() -> dict:
    try:
        missing = _missing()
        return {"ready": not missing, "missing": sorted(missing), "unavailable": False}
    except Exception as error:
        logger.warning("Schema metadata unavailable errorType=%s", type(error).__name__)
        return {"ready": False, "missing": [], "unavailable": True}


def is_ready() -> bool:
    return bool(check()["ready"])


def ensure_schema() -> bool:
    """Startup diagnostic only; the business gate/probe performs read-only checks too."""
    result = check()
    if result["ready"]:
        logger.info("AI and KB schema metadata checks passed")
    else:
        logger.error("Schema not ready missing=%s unavailable=%s; inspect backend/deploy/db-init/README.md",
                     result["missing"], result["unavailable"])
    return bool(result["ready"])


def describe() -> str:
    result = check()
    if result["ready"]:
        return "OK"
    if result["unavailable"]:
        return "数据库结构检查不可用（连接或权限异常）"
    return "缺少：" + "、".join(result["missing"]) + "；见 backend/deploy/db-init/README.md"


__all__ = ["ensure_schema", "describe", "check", "is_ready"]
