"""Fresh init, refusal to overwrite, advisory lock and partial rollback on isolated PostgreSQL."""
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

from psycopg import sql

from database_init import baseline_sections, initialize_databases, INIT_LOCK, SOURCE
from isolated_postgres import disposable_postgres
from migration_engine import DATABASES, MigrationError, migrate_databases

ROOT = Path(__file__).resolve().parents[1]


def main():
    valid = "\n".join("\\connect " + database + "\nSELECT 1;" for database in sorted(DATABASES))
    assert set(baseline_sections(valid)) == DATABASES
    for bad in ("SELECT 1;", "\\connect untrusted\nSELECT 1;", valid + "\n\\include other.sql"):
        try:
            baseline_sections(bad)
        except MigrationError:
            pass
        else:
            raise AssertionError("Invalid initialization source accepted")
    with disposable_postgres(initialize=False) as (connect, environment):
        with connect("postgres") as administrator:
            administrator.execute("SELECT pg_advisory_lock(%s)", (INIT_LOCK,))
            try:
                try:
                    initialize_databases(environment)
                except MigrationError as failure:
                    assert "lock" in str(failure)
                else:
                    raise AssertionError("Concurrent init bypassed advisory lock")
            finally:
                administrator.execute("SELECT pg_advisory_unlock(%s)", (INIT_LOCK,))
            assert administrator.execute("SELECT count(*) FROM pg_database WHERE datname=ANY(%s)", (sorted(DATABASES),)).fetchone()[0] == 0
            password = secrets.token_hex(24)
            administrator.execute(sql.SQL("CREATE ROLE init_test_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB PASSWORD {}").format(sql.Literal(password)))
            try:
                initialize_databases({**environment, "SUPPORTFLOW_MIGRATION_DB_USER": "init_test_runtime", "SUPPORTFLOW_DB_PASSWORD": password})
            except MigrationError as failure:
                assert "administrator" in str(failure)
                assert password not in str(failure)
            else:
                raise AssertionError("Runtime role initialized databases")
            # Unrelated data is outside the six-database init scope.
            administrator.execute("CREATE TABLE public.init_scope_sentinel(id int); INSERT INTO public.init_scope_sentinel VALUES(73)")
        init = subprocess.run([sys.executable, "scripts/db-migrate.py", "init"], cwd=ROOT, env=environment,
                              text=True, capture_output=True)
        assert init.returncode == 0, init.stderr
        report = json.loads(init.stdout)
        assert set(report["initialized"]) == DATABASES
        verified_tables = sum(row["schema_verified"]["tables"] for row in report["verification"])
        assert verified_tables == 100
        assert report["runtime_roles"] == "NOT_PROVISIONED_BY_INIT"
        with connect("postgres") as connection:
            assert connection.execute("SELECT id FROM public.init_scope_sentinel").fetchall() == [(73,)]
        with connect("user_db") as connection:
            seed_count = connection.execute("SELECT count(*) FROM sys_role").fetchone()[0]
            assert seed_count > 0
        repeated = subprocess.run([sys.executable, "scripts/db-migrate.py", "init"], cwd=ROOT, env=environment,
                                  text=True, capture_output=True)
        assert repeated.returncode == 1 and "refuses to overwrite" in repeated.stderr, repeated.stderr
        assert environment["SUPPORTFLOW_DB_PASSWORD"] not in repeated.stderr
        with connect("user_db") as connection:
            assert connection.execute("SELECT count(*) FROM sys_role").fetchone()[0] == seed_count
        assert all(not row["applied"] for row in migrate_databases(environment))
        assert len(migrate_databases(environment, verify=True)) == 6
        print(f"Fresh CLI init: six databases / {verified_tables} tables verified; migration repeat no-op; existing databases refused; unrelated sentinel and seed data preserved", flush=True)
        print("Runtime CREATEDB denial, cluster lock contention and malformed source cases passed", flush=True)
    with disposable_postgres(initialize=False) as (connect, environment):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "faulty-init.sql"
            original = SOURCE.read_text(encoding="utf-8")
            source.write_text(original.replace("\\connect customer_db", "SELECT 1/0;\n\\connect customer_db", 1), encoding="utf-8")
            try:
                initialize_databases(environment, source=source)
            except MigrationError as failure:
                assert "billing_db" in str(failure) and "completed=ai_db" in str(failure)
                assert "22012" in str(failure)
                assert environment["SUPPORTFLOW_DB_PASSWORD"] not in str(failure)
            else:
                raise AssertionError("Initialization did not roll back a failed baseline")
        with connect("billing_db") as connection:
            assert connection.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'").fetchone()[0] == 0
        with connect("ai_db") as connection:
            assert connection.execute("SELECT to_regclass('public.bot_intent')").fetchone()[0] == "bot_intent"
        with connect("postgres") as connection:
            assert connection.execute("SELECT count(*) FROM pg_database WHERE datname=ANY(%s)", (sorted(DATABASES),)).fetchone()[0] == 6
        try:
            initialize_databases(environment)
        except MigrationError as failure:
            assert "refuses to overwrite" in str(failure)
        else:
            raise AssertionError("Partial init state was silently overwritten")
        print("Injected billing baseline failure: failed database DDL rolled back; completed AI database and six created databases preserved; partial init refused", flush=True)


if __name__ == "__main__":
    main()
