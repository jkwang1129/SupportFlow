"""Fault injection only against the disposable acceptance project's five Java DB services."""
import re
import subprocess

import httpx


def verify_java_readiness(project):
    if not re.fullmatch(r"supportflow-acceptance-[0-9a-f]{6}", project):
        raise ValueError("Expected a disposable acceptance project")

    def sql(database, command):
        return subprocess.check_output([
            "docker", "exec", project + "-postgres-1", "psql", "-U", "supportflow",
            "-d", database, "-XqAt", "-v", "ON_ERROR_STOP=1", "-c", command], text=True).strip()

    cases = [
        (9091, "user_db", "sys_user_role", "id", "supportflow_user"),
        (9092, "tenant_db", "tenant_usage_daily", "tenant_code", "supportflow_tenant"),
        (9093, "customer_db", "session_message", "client_msg_no", "supportflow_customer_tenant"),
        (9093, "customer_db", "kb_index_previous_chunk", "embedding", "supportflow_customer_tenant"),
        (9094, "billing_db", "billing_usage_daily", "stat_date", "supportflow_billing"),
        (9095, "ops_db", "audit_log", "tenant_code", "supportflow_ops"),
    ]
    with httpx.Client(timeout=8, trust_env=False) as client:
        for port, database, table, column, role in cases:
            base = f"http://127.0.0.1:{port}"

            def check(expected):
                ready = client.get(base + "/actuator/health/readiness")
                assert ready.status_code == expected, (database, ready.status_code, ready.text)
                assert ready.json() == {"status": "UP" if expected == 200 else "DOWN"}, ready.text
                live = client.get(base + "/actuator/health/liveness")
                assert live.status_code == 200 and live.json() == {"status": "UP"}, live.text
                if expected == 503:
                    blocked = client.post(base + "/api/readiness-negative-test", json={})
                    assert blocked.status_code == 503, (database, blocked.text)
                    assert blocked.headers["Retry-After"] == "5"
                    assert blocked.json()["message"] == "Service is not ready"
                    assert table not in blocked.text and column not in ready.text
                else:
                    # The gate must reopen after repair, without restarting the service.
                    assert client.post(base + "/api/readiness-negative-test", json={}).status_code != 503

            check(200)
            sql(database, f'ALTER TABLE public."{table}" RENAME COLUMN "{column}" TO readiness_fault_column')
            try:
                check(503)
            finally:
                sql(database, f'ALTER TABLE public."{table}" RENAME COLUMN readiness_fault_column TO "{column}"')
            check(200)
            sql(database, f'ALTER TABLE public."{table}" RENAME TO readiness_fault_table')
            try:
                check(503)
            finally:
                sql(database, f'ALTER TABLE public.readiness_fault_table RENAME TO "{table}"')
            check(200)
            sql(database, f'REVOKE SELECT ON public."{table}" FROM {role}')
            try:
                # Keep INSERT to reproduce metadata visibility without SELECT permission.
                assert sql(database, f"SELECT has_table_privilege('{role}','{table}','INSERT')") == "t"
                check(503)
            finally:
                sql(database, f'GRANT SELECT ON public."{table}" TO {role}')
            check(200)
            print(f"Java readiness {database}.{table}: missing column/table and SELECT revocation blocked; restored; liveness stayed UP", flush=True)
