"""Generate once or test four baseline contracts using disposable canonical PostgreSQL 17."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

import psycopg

from migration_engine import load_plan, run_plan, MigrationError, Migration
from schema_contract import capture_baseline, validate_baseline

ROOT = Path(__file__).resolve().parents[1]
TARGETS = ("ai_db", "user_db", "tenant_db", "ops_db")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-new-contracts", action="store_true",
                        help="Create missing contracts only; never overwrite an adopted baseline")
    args = parser.parse_args()
    name = "supportflow-baselines-test-" + secrets.token_hex(6)
    password = secrets.token_hex(24)
    source = ROOT / "backend/deploy/db-init/db-init.sql"
    subprocess.run(["docker", "run", "--rm", "-d", "--name", name, "-e", "POSTGRES_USER=supportflow",
                    "-e", "POSTGRES_PASSWORD", "-p", "127.0.0.1::5432", "--mount",
                    f"type=bind,src={source},dst=/docker-entrypoint-initdb.d/10-init.sql,readonly",
                    "pgvector/pgvector:pg17"], env={**os.environ, "POSTGRES_PASSWORD": password},
                   check=True, stdout=subprocess.DEVNULL)
    try:
        address = subprocess.check_output(["docker", "port", name, "5432/tcp"], text=True).strip().splitlines()[0]
        def connect(database):
            return psycopg.connect(f"postgresql://supportflow:{password}@{address}/{database}",
                                   autocommit=True, connect_timeout=2)
        for _ in range(90):
            try:
                with connect("user_db") as connection:
                    connection.execute("SELECT 1 FROM sys_user")
                break
            except psycopg.Error:
                time.sleep(1)
        else:
            raise RuntimeError("Baseline fixture did not initialize")
        directory = ROOT / "backend/deploy/migrations/baselines"
        if args.write_new_contracts:
            directory.mkdir(parents=True, exist_ok=True)
            for database in TARGETS:
                with connect(database) as connection:
                    content = capture_baseline(connection)
                content["source"] = {"path": source.relative_to(ROOT).as_posix(),
                                     "sha256": hashlib.sha256(source.read_text(encoding="utf-8").encode()).hexdigest()}
                with (directory / (database + ".json")).open("x", encoding="utf-8") as output:
                    json.dump(content, output, ensure_ascii=False, indent=2)
                    output.write("\n")
                print(f"Created immutable baseline contract: {database}, {len(content['tables'])} tables", flush=True)
            return
        for database in TARGETS:
            plan = [load_plan()[database][0]]
            assert plan[0].kind == "baseline"
            with connect(database) as connection:
                validate_baseline(connection, plan[0].content)
                # Existing seed data must survive adoption; use deterministic row counts
                # for every baseline table in this synthetic fixture.
                tables = capture_baseline(connection)["tables"]
                from psycopg import sql
                counts = {table: connection.execute(sql.SQL("SELECT count(*) FROM public.{}").format(sql.Identifier(table))).fetchone()[0]
                          for table in tables}
                assert run_plan(connection, plan)["applied"] == ["001"]
                assert run_plan(connection, plan)["applied"] == []
                assert run_plan(connection, plan, verify=True)["verified"] == 1
                for table, count in counts.items():
                    assert connection.execute(sql.SQL("SELECT count(*) FROM public.{}").format(sql.Identifier(table))).fetchone()[0] == count
                print(f"Baseline {database}: adoption/repeat/verify passed, {len(tables)} table row counts preserved", flush=True)

                # A new environment with an incompatible table/column/type/index
                # cannot be silently marked as an adopted baseline.
                connection.execute("DELETE FROM supportflow_schema_history WHERE version='001'")
                table, contract = next((table, contract) for table, contract in tables.items()
                                       if any(value["type"].startswith("character varying") for value in contract["columns"].values())
                                       and contract["indexes"])
                column = next(column for column, value in contract["columns"].items()
                              if value["type"].startswith("character varying"))
                index = next(iter(contract["indexes"]))
                mutations = [
                    sql.SQL("ALTER TABLE public.{} RENAME TO baseline_fault_table").format(sql.Identifier(table)),
                    sql.SQL("ALTER TABLE public.{} RENAME COLUMN {} TO baseline_fault_column").format(sql.Identifier(table), sql.Identifier(column)),
                    sql.SQL("ALTER TABLE public.{} ALTER COLUMN {} TYPE text").format(sql.Identifier(table), sql.Identifier(column)),
                    sql.SQL("ALTER INDEX public.{} RENAME TO baseline_fault_index").format(sql.Identifier(index)),
                ]
                for mutation in mutations:
                    # The test's explicit outer transaction is only for reversible fault
                    # injection; run_plan correctly refuses such a wrapper.
                    try:
                        with connection.transaction():
                            connection.execute(mutation)
                            try:
                                validate_baseline(connection, plan[0].content)
                            except ValueError:
                                pass
                            else:
                                raise AssertionError("Faulty baseline was accepted")
                            raise InjectedRollback()
                    except InjectedRollback:
                        pass
                # Runner-level failed adoption must not persist a history receipt.
                broken = json.loads(plan[0].content)
                broken["tables"]["missing_baseline_table"] = {"columns": {"id": {"type": "bigint", "not_null": True}}, "indexes": {}, "constraints": {}}
                denied = Migration(database, "001", "incompatible baseline", plan[0].source, json.dumps(broken), "baseline")
                try:
                    run_plan(connection, [denied])
                except MigrationError:
                    pass
                else:
                    raise AssertionError("Runner adopted an incompatible baseline")
                assert connection.execute("SELECT count(*) FROM supportflow_schema_history").fetchone()[0] == 0
                assert run_plan(connection, plan)["applied"] == ["001"]
                print(f"Baseline {database}: missing table/column, type/index drift and incompatible adoption rejected", flush=True)
    finally:
        subprocess.run(["docker", "rm", "--force", "--volumes", name], check=True, stdout=subprocess.DEVNULL)


class InjectedRollback(Exception):
    pass


if __name__ == "__main__":
    main()
