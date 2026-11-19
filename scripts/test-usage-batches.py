"""Run metering ledger/outbox tests on disposable PG17 with restricted, non-owner identities."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
INIT = ROOT / "backend/deploy/db-init"


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    name = "supportflow-usage-test-" + secrets.token_hex(6)
    admin, billing, customer = (secrets.token_hex(24) for _ in range(3))
    environment = {**os.environ, "POSTGRES_PASSWORD": admin, "PGPASSWORD": admin}
    run(["docker", "run", "--rm", "-d", "--name", name, "-e", "POSTGRES_USER=supportflow",
         "-e", "POSTGRES_PASSWORD", "-p", "127.0.0.1::5432", "--mount",
         f"type=bind,src={INIT / 'db-init.sql'},dst=/docker-entrypoint-initdb.d/10-supportflow.sql,readonly",
         "pgvector/pgvector:pg17"], env=environment, stdout=subprocess.DEVNULL)
    try:
        psql = ["docker", "exec", "-i", "-e", "PGPASSWORD", name, "psql", "-h", "127.0.0.1",
                "-U", "supportflow", "-d", "customer_db", "-wXq", "-v", "ON_ERROR_STOP=1"]
        for _ in range(90):
            ready = subprocess.run([*psql, "-c", "SELECT 1 FROM tenant_rls_status LIMIT 1"], env=environment,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL initialization timed out")
        strict = ROOT / "backend/supportflow-customer-service/src/main/resources/schema/customer_db_runtime_security.sql"
        run(psql, env=environment, input=strict.read_text(encoding="utf-8"), encoding="utf-8")
        for _ in range(2):
            run(psql, env=environment, input=(INIT / "30-usage-batches.sql").read_text(encoding="utf-8"), encoding="utf-8")
        run(psql, env=environment, input=f"""
            CREATE ROLE usage_test_billing LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '{billing}';
            CREATE ROLE usage_test_customer LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '{customer}';
            GRANT supportflow_customer_tenant TO usage_test_customer;
            GRANT CONNECT ON DATABASE customer_db TO usage_test_customer;
            \connect billing_db
            GRANT CONNECT ON DATABASE billing_db TO usage_test_billing;
            GRANT USAGE ON SCHEMA public TO usage_test_billing;
            GRANT SELECT, INSERT, UPDATE ON public.billing_usage_daily TO usage_test_billing;
            GRANT SELECT, INSERT ON public.billing_usage_batch TO usage_test_billing;
        """)
        address = subprocess.check_output(["docker", "port", name, "5432/tcp"], text=True).strip().splitlines()[0]
        env = {**os.environ, "SUPPORTFLOW_USAGE_TEST_BILLING_URL": f"jdbc:postgresql://{address}/billing_db",
               "SUPPORTFLOW_USAGE_TEST_CUSTOMER_URL": f"jdbc:postgresql://{address}/customer_db",
               "SUPPORTFLOW_USAGE_TEST_BILLING_PASSWORD": billing, "SUPPORTFLOW_USAGE_TEST_CUSTOMER_PASSWORD": customer}
        mvn = shutil.which("mvn")
        if not mvn:
            raise RuntimeError("Maven is required")
        run([mvn, "-B", "-f", "backend/pom.xml", "-pl", "supportflow-billing-service,supportflow-customer-service", "-am",
             "test", "-Dtest=BillingUsageBatchPostgresTest,BillingUsageOutboxPostgresTest", "-Dsurefire.failIfNoSpecifiedTests=false"], env=env)
    finally:
        run(["docker", "rm", "--force", "--volumes", name], stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
