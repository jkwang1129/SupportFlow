"""A disposable PostgreSQL fixture; never connects to the user's Compose data volumes."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import subprocess
import time

import psycopg

ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def disposable_postgres(*, initialize=True):
    name = "supportflow-schema-test-" + secrets.token_hex(6)
    password = secrets.token_hex(24)
    args = ["docker", "run", "--rm", "-d", "--name", name, "-e", "POSTGRES_USER=supportflow",
            "-e", "POSTGRES_PASSWORD", "-p", "127.0.0.1::5432"]
    if initialize:
        args += ["--mount",
                 f"type=bind,src={ROOT/'backend/deploy/db-init/db-init.sql'},dst=/docker-entrypoint-initdb.d/10-init.sql,readonly"]
    subprocess.run([*args, "pgvector/pgvector:pg17"], env={**os.environ, "POSTGRES_PASSWORD": password},
                   check=True, stdout=subprocess.DEVNULL)
    try:
        address = subprocess.check_output(["docker", "port", name, "5432/tcp"], text=True).strip().splitlines()[0]
        environment = {**os.environ, "SUPPORTFLOW_DB_HOST": "127.0.0.1", "SUPPORTFLOW_DB_PORT": address.rsplit(":", 1)[1],
                       "SUPPORTFLOW_DB_PASSWORD": password, "SUPPORTFLOW_MIGRATION_DB_USER": "supportflow",
                       "SUPPORTFLOW_TEST_PG_CONTAINER": name}

        def connect(database, *, readonly=False):
            return psycopg.connect(host="127.0.0.1", port=environment["SUPPORTFLOW_DB_PORT"], dbname=database,
                                   user="supportflow", password=password, autocommit=True, connect_timeout=2,
                                   options="-c default_transaction_read_only=on" if readonly else "")
        for _ in range(90):
            try:
                with connect("user_db" if initialize else "postgres") as connection:
                    connection.execute("SELECT 1 FROM sys_user" if initialize else "SELECT 1")
                break
            except psycopg.Error:
                time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL did not initialize")
        yield connect, environment
    finally:
        subprocess.run(["docker", "rm", "--force", "--volumes", name], check=True, stdout=subprocess.DEVNULL)
