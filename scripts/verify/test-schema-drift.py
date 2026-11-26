"""Reproduce strict schema drift detection using only disposable PostgreSQL 17 data."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import argparse
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan, migrate_databases, Migration
from schema_drift import capture_schema, verify_schema, assert_schema, CONTRACT, SchemaDriftError

ROOT = Path(__file__).resolve().parents[2]


class RollbackFault(Exception):
    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-new-contract", action="store_true")
    parser.add_argument("--output", type=Path, default=CONTRACT,
                        help="New contract candidate path; existing files are never overwritten")
    args = parser.parse_args()
    plans = load_plan()
    with disposable_postgres() as (connect, environment):
        for database, plan in plans.items():
            with connect(database) as connection:
                run_plan(connection, plan)
        if args.write_new_contract:
            contract = {"format": 1, "migrations": {
                database: [{"version": m.version, "checksum": m.checksum} for m in plan]
                for database, plan in plans.items()}, "databases": {}}
            for database in plans:
                with connect(database) as connection:
                    contract["databases"][database] = capture_schema(connection)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as output:
                json.dump(contract, output, ensure_ascii=False, indent=2, sort_keys=True)
                output.write("\n")
            print("Created reviewed current schema contract from isolated canonical initialization and migrations", flush=True)
            return
        total = 0
        for database in plans:
            with connect(database, readonly=True) as connection:
                result = verify_schema(connection, plans)
                total += result["tables"]
                assert connection.execute("SHOW transaction_read_only").fetchone()[0] == "on"
                print(f"Read-only {database}: {result}", flush=True)
        changed_plan = {database: list(plan) for database, plan in plans.items()}
        next_version = f"{int(plans['customer_db'][-1].version)+1:03d}"
        changed_plan["customer_db"].append(Migration("customer_db", next_version,
                                           "not reviewed", "fixture.sql", "SELECT 1;"))
        with connect("customer_db", readonly=True) as connection:
            try:
                verify_schema(connection, changed_plan)
            except SchemaDriftError as failure:
                assert "manifest" in str(failure)
            else:
                raise AssertionError("A stale contract accepted an unknown migration plan")
        mutations = [
            "ALTER TABLE public.session RENAME TO schema_fault_session",
            "ALTER TABLE public.session RENAME COLUMN last_msg_seq TO schema_fault_column",
            "ALTER TABLE public.session ALTER COLUMN last_msg_seq TYPE integer",
            "ALTER TABLE public.session ALTER COLUMN last_msg_seq DROP DEFAULT",
            "ALTER TABLE public.session ALTER COLUMN last_msg_seq DROP NOT NULL",
            "ALTER INDEX public.idx_session_tenant_status_time RENAME TO schema_fault_index",
            """DROP INDEX public.agent_event_outbox_pending;
               CREATE INDEX agent_event_outbox_pending ON public.agent_event_outbox (tenant_code,ready_at,event_seq)
               WHERE state::text = ANY (ARRAY[('PENDING'::character varying)::text, ('HELD'::character varying)::text])""",
            """ALTER TABLE public.agent_event_outbox DROP CONSTRAINT agent_event_outbox_state_check;
               ALTER TABLE public.agent_event_outbox ADD CONSTRAINT agent_event_outbox_state_check
               CHECK (state::text = ANY (ARRAY[('PENDING'::character varying)::text, ('SENDING'::character varying)::text,
                                             ('DELIVERED'::character varying)::text, ('WRONG'::character varying)::text]))""",
            "ALTER TABLE public.session DROP CONSTRAINT uk_tenant_session_no",
            "ALTER TABLE public.session NO FORCE ROW LEVEL SECURITY",
            "ALTER TABLE public.session DISABLE ROW LEVEL SECURITY",
            "DROP POLICY supportflow_tenant_scope ON public.session",
            "ALTER POLICY supportflow_tenant_scope ON public.session USING(true) WITH CHECK(true)",
            "CREATE POLICY schema_fault_policy ON public.session USING(true) WITH CHECK(true)",
            "ALTER FUNCTION public.supportflow_channel_tenant(text) SECURITY INVOKER",
            "CREATE TABLE public.schema_fault_table(id bigint)",
        ]
        with connect("customer_db") as connection:
            for mutation in mutations:
                try:
                    with connection.transaction():
                        connection.execute(mutation)
                        try:
                            verify_schema(connection, plans)
                        except SchemaDriftError:
                            pass
                        else:
                            raise AssertionError("Schema drift was accepted: " + mutation)
                        raise RollbackFault()
                except RollbackFault:
                    pass
            verify_schema(connection, plans)
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        # Index validity, views and unknown function sets have deterministic
        # metadata-negative coverage without manually corrupting PG catalogs.
        original = contract["databases"]["customer_db"]
        cases = []
        invalid_index = deepcopy(original)
        index = next(iter(invalid_index["tables"]["session"]["indexes"]))
        invalid_index["tables"]["session"]["indexes"][index]["valid"] = False
        cases.append(invalid_index)
        extra_view = deepcopy(original)
        extra_view["views"]["unreviewed_view"] = "SELECT 1"
        cases.append(extra_view)
        extra_function = deepcopy(original)
        extra_function["functions"]["supportflow_unreviewed()"] = "unreviewed"
        cases.append(extra_function)
        for changed in cases:
            try:
                assert_schema(changed, original)
            except SchemaDriftError:
                pass
            else:
                raise AssertionError("Invalid catalog metadata was accepted")
        with connect("customer_db") as connection:
            connection.execute("ALTER TABLE public.session NO FORCE ROW LEVEL SECURITY")
            try:
                rejected = subprocess.run([sys.executable, "scripts/db-migrate.py", "verify", "--database", "customer_db"],
                                          cwd=ROOT, env=environment, text=True, capture_output=True)
                assert rejected.returncode == 1 and "RLS forced drift" in rejected.stderr, rejected.stderr
                assert environment["SUPPORTFLOW_DB_PASSWORD"] not in rejected.stderr
            finally:
                connection.execute("ALTER TABLE public.session FORCE ROW LEVEL SECURITY")
        verified = migrate_databases(environment, verify=True)
        assert len(verified) == 6 and all(row["schema_verified"]["tables"] for row in verified)
        print(f"Strict schema: {total} tables across six databases; {len(mutations)} SQL faults and {len(cases)} metadata faults rejected; CLI fail/repair verified", flush=True)


if __name__ == "__main__":
    main()
