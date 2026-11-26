"""Exercise the budget policy through the running Ops and AI services."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

from datetime import date
import re
import subprocess

import httpx


def verify_cost_guard(project):
    if not re.fullmatch(r"supportflow-acceptance-[0-9a-f]{6}", project):
        raise ValueError("Expected a disposable acceptance project")

    def sql(database, command):
        return subprocess.check_output([
            "docker", "exec", project + "-postgres-1", "psql", "-U", "supportflow",
            "-d", database, "-XqAt", "-v", "ON_ERROR_STOP=1", "-c", command,
        ], text=True).strip()

    cases = []
    with httpx.Client(timeout=8, trust_env=False) as client:
        def check(name, status, tenant="GUARD_FIXTURE", mode=None, used=None):
            params = {"tenantCode": tenant} if tenant is not None else {}
            response = client.get("http://127.0.0.1:9095/api/ops/internal/observability/cost-guard",
                                  params=params)
            assert response.status_code == status, (name, response.status_code, response.text)
            assert response.headers.get("cache-control") == "no-store", name
            body = response.json()
            observation = {"case": name, "http_status": status, "code": body["code"]}
            if status == 200:
                assert body["code"] == 0 and body["data"]["mode"] == mode, (name, body)
                assert body["data"]["used"] == used, (name, body)
                observation.update(mode=mode, used_cost=body["data"]["used"])
            else:
                assert body["data"] is None and body["code"] == status * 100, (name, body)
            cases.append(observation)
            return body

        check("missing_tenant", 400, tenant=None)
        check("platform_tenant", 400, tenant="PLATFORM")
        check("invalid_tenant", 400, tenant="GUARD+FIXTURE")
        check("no_budget", 200, mode=1, used=0)
        sql("ops_db", """
            INSERT INTO tenant_cost_budget (id, tenant_code, monthly_budget, over_action)
            VALUES (912001, 'GUARD_FIXTURE', 100, 3)
        """)
        check("zero_usage", 200, mode=1, used=0)
        today = date.today().isoformat()
        sql("ai_db", f"""
            INSERT INTO ai_usage_record (id, tenant_code, model_key, scene, cost_amount, stat_date)
            VALUES (912001, 'GUARD_FIXTURE', 'test-fixture', 1, 99.95, DATE '{today}'),
                   (912002, 'GUARD_OTHER', 'test-fixture', 1, 200, DATE '{today}')
        """)
        check("below_limit_without_rounding_up_or_other_tenant", 200, mode=1, used=99.95)
        sql("ai_db", "UPDATE ai_usage_record SET cost_amount=100 WHERE id=912001")
        check("pause_at_limit", 200, mode=3, used=100)
        sql("ops_db", "UPDATE tenant_cost_budget SET over_action=2 WHERE id=912001")
        check("changed_policy_degrades_without_ops_cache", 200, mode=2, used=100)
        sql("ops_db", "UPDATE tenant_cost_budget SET over_action=1 WHERE id=912001")
        alert = check("only_alert_at_limit", 200, mode=1, used=100)
        assert "仅告警" in alert["data"]["reason"], alert

        sql("ai_db", "REVOKE SELECT ON ai_usage_record FROM supportflow_ai")
        try:
            assert sql("ai_db", "SELECT has_table_privilege('supportflow_ai','ai_usage_record','SELECT')") == "f"
            check("usage_query_unavailable", 503)
            check("absent_budget_needs_no_usage_query", 200, tenant="GUARD_OTHER", mode=1, used=0)
        finally:
            sql("ai_db", "GRANT SELECT ON ai_usage_record TO supportflow_ai")
        check("usage_query_recovered", 200, mode=1, used=100)

        # Readiness checks the ID and tenant columns. Break the budget query after that check.
        sql("ops_db", "ALTER TABLE tenant_cost_budget RENAME COLUMN monthly_budget TO guard_fault_budget")
        try:
            ready = client.get("http://127.0.0.1:9095/actuator/health/readiness")
            assert ready.status_code == 200, ready.text
            check("budget_query_unavailable", 503)
        finally:
            sql("ops_db", "ALTER TABLE tenant_cost_budget RENAME COLUMN guard_fault_budget TO monthly_budget")
        check("budget_query_recovered", 200, mode=1, used=100)

    print(f"Legacy AI cost guard: {len(cases)} Ops / AI / PostgreSQL HTTP checks passed", flush=True)
    return cases
