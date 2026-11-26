"""Initialize only absent application databases using the existing canonical SQL.

    CREATE DATABASE is not transactional. Each database's baseline DDL and seed
    data are transactional; a failure never drops an existing or created database.
"""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import hashlib

import psycopg
from psycopg import sql

from migration_engine import ROOT, DATABASES, MigrationError, database_section, migrate_databases

SOURCE = ROOT / "backend/deploy/db-init/db-init.sql"
INIT_LOCK = int.from_bytes(hashlib.sha256(b"supportflow:database-init:v1").digest()[:8], "big", signed=True)


def baseline_sections(text):
    lines = text.replace("\r\n", "\n").splitlines()
    connections = [line.strip().removeprefix("\\connect ")
                   for line in lines if line.strip().startswith("\\connect ")]
    if len(connections) != len(DATABASES) or set(connections) != DATABASES:
        raise MigrationError("Canonical initialization must declare each of the six databases once")
    first = next(i for i, line in enumerate(lines) if line.strip().startswith("\\connect "))
    # Database creation is controlled below with quoted identifiers. The old
    # psql preamble (including gexec) is never passed to the SQL driver.
    body = "\n".join(lines[first:])
    return {database: database_section(body, database) for database in connections}


def initialize_databases(environment, *, source=SOURCE):
    sections = baseline_sections(source.read_text(encoding="utf-8"))
    password = environment.get("SUPPORTFLOW_DB_PASSWORD", "")
    if not password:
        raise MigrationError("Separate initialization administrator credentials are required")
    options = dict(host=environment.get("SUPPORTFLOW_DB_HOST", "127.0.0.1"),
                   port=environment.get("SUPPORTFLOW_DB_PORT", "5432"),
                   user=environment.get("SUPPORTFLOW_MIGRATION_DB_USER", "supportflow"),
                   password=password, connect_timeout=5, autocommit=True)
    with psycopg.connect(**options, dbname="postgres") as administrator:
        can_create = administrator.execute(
            "SELECT rolsuper OR rolcreatedb FROM pg_roles WHERE rolname=current_user").fetchone()
        if not can_create or not can_create[0]:
            raise MigrationError("A separate CREATEDB initialization administrator is required")
        if not administrator.execute("SELECT pg_try_advisory_lock(%s)", (INIT_LOCK,)).fetchone()[0]:
            raise MigrationError("Another initialization owns the cluster initialization lock")
        try:
            existing = administrator.execute(
                "SELECT datname FROM pg_database WHERE datname=ANY(%s)", (sorted(DATABASES),)).fetchall()
            if existing:
                raise MigrationError(
                    "Application databases already exist (possibly partial); init refuses to overwrite. Use migrate/verify or inspect recovery state")
            for database in sections:
                administrator.execute(
                    sql.SQL("CREATE DATABASE {} WITH ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(database)))
            completed = []
            for database, content in sections.items():
                try:
                    with psycopg.connect(**options, dbname=database) as connection:
                        with connection.transaction():
                            connection.execute(
                                "SELECT set_config('lock_timeout','2000',true),set_config('statement_timeout','30000',true)")
                            connection.execute(content)
                    completed.append(database)
                except psycopg.Error as failure:
                    raise MigrationError(
                        f"Initialization failed for {database}; SQLSTATE={failure.sqlstate}; completed={','.join(completed)}. Current database baseline rolled back; databases were not removed") from None
            applied = migrate_databases(environment)
            verified = migrate_databases(environment, verify=True)
            return {"initialized": completed, "migrations": applied, "verification": verified,
                    "runtime_roles": "NOT_PROVISIONED_BY_INIT"}
        finally:
            administrator.execute("SELECT pg_advisory_unlock(%s)", (INIT_LOCK,))
