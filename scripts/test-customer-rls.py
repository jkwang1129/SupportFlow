"""Run JDBC regressions against a fresh, disposable PostgreSQL 17 customer_db."""

import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
DB_INIT = ROOT / "backend" / "deploy" / "db-init"


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-ai", action="store_true", help="also run the AI knowledge diagnostics DB test (requires pytest and AI dependencies)")
    args = parser.parse_args()
    name = "supportflow-rls-test-" + secrets.token_hex(6)
    admin_password, app_password, kb_password = (secrets.token_hex(24) for _ in range(3))
    run(
        [
            "docker", "run", "--rm", "-d", "--name", name,
            "-e", "POSTGRES_USER=supportflow",
            "-e", "POSTGRES_PASSWORD",
            "-p", "127.0.0.1::5432",
            "--mount", f"type=bind,src={DB_INIT / 'db-init.sql'},dst=/docker-entrypoint-initdb.d/10-supportflow.sql,readonly",
            "pgvector/pgvector:pg17",
        ],
        env={**os.environ, "POSTGRES_PASSWORD": admin_password},
        stdout=subprocess.DEVNULL,
    )
    try:
        db_env = {**os.environ, "PGPASSWORD": admin_password}
        psql = [
            "docker", "exec", "-i", "-e", "PGPASSWORD", name, "psql", "-h", "127.0.0.1",
            "-U", "supportflow", "-d", "customer_db", "-wXqAt", "-v", "ON_ERROR_STOP=1",
        ]
        for _ in range(90):
            ready = subprocess.run(
                [*psql, "-c", "SELECT 1 FROM tenant_rls_status LIMIT 1"],
                env=db_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL did not initialize in 90 seconds")

        metadata = run(
            [*psql, "-c", "SELECT rolsuper, rolbypassrls, pg_get_userbyid(relowner) "
             "FROM pg_roles, pg_class WHERE rolname = current_user AND pg_class.oid = 'public.kb_chunk'::regclass"],
            env=db_env, capture_output=True,
        ).stdout.strip()
        superuser, bypass, owner = metadata.split("|")
        if superuser != "t" or owner != "supportflow":
            raise RuntimeError(f"Unexpected bootstrap role/table ownership: {metadata}")
        print(f"Baseline: supportflow rolsuper={superuser}, rolbypassrls={bypass}; kb_chunk owner={owner}.", flush=True)

        staged = (ROOT / "backend/supportflow-customer-service/src/main/resources/schema/customer_db_runtime_security.sql").read_text(encoding="utf-8")
        for _ in range(2):
            run(psql, input=staged, env=db_env, encoding="utf-8")
        run(psql, input=(DB_INIT / "channel-tenant-lookup.sql").read_text(encoding="utf-8"),
            env=db_env, encoding="utf-8")
        run(psql, input=(DB_INIT / "public-bot-avatar.sql").read_text(encoding="utf-8"),
            env=db_env, encoding="utf-8")
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_005_agent_event_outbox.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_006_agent_source_snapshot.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_007_agent_result_receipt.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_008_kb_publication_read.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_009_ticket_create_receipt.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_010_kb_chunk_stage.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, input=(ROOT / 'backend/deploy/migrations/sql/customer_db_011_kb_stage_retention.sql').read_text(encoding='utf-8'),
            env=db_env, encoding='utf-8')
        run(psql, env=db_env, input=f"""
            CREATE ROLE rls_test_java LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '{app_password}';
            GRANT supportflow_customer_tenant TO rls_test_java;
            CREATE ROLE rls_test_kb LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '{kb_password}';
            GRANT supportflow_kb_tenant TO rls_test_kb;
            INSERT INTO file_meta(id, tenant_code, file_no, file_name, object_key, biz_type, is_deleted) VALUES
                (900011, 'TENANT_A', 'AVATAR', 'avatar.png', 'bot/avatar/TENANT_A/avatar.png', 1, false),
                (900012, 'TENANT_A', 'CHAT', 'chat.png', 'chat/TENANT_A/S1/chat.png', 5, false),
                (900013, 'TENANT_A', 'LICENSE', 'license.png', 'enterprise/license.png', 2, false),
                (900014, 'TENANT_A', 'DELETED', 'deleted.png', 'bot/avatar/TENANT_A/deleted.png', 1, true),
                (900015, 'TENANT_A', 'BADPATH', 'bad.png', 'private/bad.png', 1, false);
            INSERT INTO channel(id, tenant_code, channel_id, channel_type, name, status) VALUES
                (900001, 'TENANT_A', 'TEST_A', 1, 'A channel', 1),
                (900002, 'TENANT_B', 'TEST_B', 1, 'B channel', 1),
                (900003, 'TENANT_A', 'TEST_WX', 2, 'WeChat fixture', 1);
            INSERT INTO channel_wechat_config(id, tenant_code, channel_id, app_id, app_secret, token) VALUES
                (900001, 'TENANT_A', 900003, 'fixture-wechat-app', 'fixture-app-secret', 'fixture-token');
            INSERT INTO channel_key(id, tenant_code, channel_id, app_key, key_type, expire_time) VALUES
                (900001, 'TENANT_A', 900001, 'test-active-A', 1, NULL),
                (900002, 'TENANT_B', 900002, 'test-expired-B', 1, CURRENT_TIMESTAMP - INTERVAL '1 day'),
                (900003, 'TENANT_A', 900001, 'test-ambiguous', 1, NULL),
                (900004, 'TENANT_B', 900002, 'test-ambiguous', 1, NULL);
            INSERT INTO kb_document(id, tenant_code, doc_no, title, status) VALUES
                (1, 'TENANT_A', 'A', 'Delivery A', 3),
                (2, 'TENANT_B', 'B', 'Delivery B', 3);
            INSERT INTO kb_chunk(id, tenant_code, doc_id, chunk_no, content, embedding_model) VALUES
                (1, 'TENANT_A', 1, 0, 'delivery A', 'local-hash'),
                (2, 'TENANT_B', 2, 0, 'delivery B', 'test-embedding'),
                (3, 'PLATFORM', 3, 0, 'platform', 'test-embedding');
        """)

        host = run(["docker", "port", name, "5432/tcp"], capture_output=True).stdout.strip()
        subprocess_env = {
            **os.environ,
            "SUPPORTFLOW_RLS_TEST_URL": f"jdbc:postgresql://{host}/customer_db",
            "SUPPORTFLOW_RLS_TEST_APP_PASSWORD": app_password,
            "SUPPORTFLOW_RLS_TEST_KB_PASSWORD": kb_password,
            "SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD": admin_password,
        }
        mvn = shutil.which("mvn")
        if not mvn:
            raise RuntimeError("Maven was not found on PATH")
        run(
            [mvn, "-B", "-f", "backend/pom.xml", "-pl", "supportflow-customer-service", "-am", "test",
             "-Dtest=TenantRlsDataSourceTest,TenantRlsPostgresTest,SessionMessagePostgresTest,SessionClaimPostgresTest,AgentEventOutboxPostgresTest,AgentEventDispatcherPostgresTest,AgentSourceSnapshotsPostgresTest,RuntimeSourcePostgresTest,AgentResultRepliesPostgresTest,AgentTicketCreationsPostgresTest,RuntimeTicketCreatePostgresTest,RuntimeReplyPostgresTest,RuntimeTicketPostgresTest,RuntimeKnowledgePostgresTest,AgentTicketApprovalPostgresTest,AgentShadowAdmissionPostgresTest,KbDocumentLifecyclePostgresTest", "-Dsurefire.failIfNoSpecifiedTests=false"],
            env=subprocess_env,
        )
        if args.with_ai:
            report = ROOT / '.local/verification/knowledge-isolation.xml'
            report.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(
                [sys.executable, "-m", "pytest", "-q", "tests/test_kb_console_status.py", "tests/test_rag_document_scope_postgres.py",
                 "tests/test_rag_published_policy_postgres.py", f"--junitxml={report}"],
                cwd=ROOT / "ai-service", check=True,
                env={
                    **os.environ,
                    "SUPPORTFLOW_KB_TEST_DSN": f"postgresql://rls_test_kb:{kb_password}@{host}/customer_db",
                    "SUPPORTFLOW_KB_ADMIN_TEST_DSN": f"postgresql://supportflow:{admin_password}@{host}/customer_db",
                },
            )
    finally:
        run(["docker", "rm", "--force", "--volumes", name], stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    sys.exit(main())
