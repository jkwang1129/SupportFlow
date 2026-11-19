"""Small PostgreSQL SQL migration runner; no runtime DDL and no password in history.

Existing SQL files remain the authority. Only their declared database section is
executed; psql includes/variables and transaction control beyond one outer pair
are rejected. Provisioning credentials/roles is deliberately a separate step.
"""
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re

import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[1]
DATABASES = {"customer_db", "billing_db", "ai_db", "tenant_db", "user_db", "ops_db"}
RUNTIME_ROLES = [f"supportflow_{role}" for role in (
    "user", "tenant", "customer", "billing", "ops", "ai", "kb", "customer_platform",
    "customer_tenant", "kb_tenant", "platform_access")]


class MigrationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Migration:
    database: str
    version: str
    name: str
    source: str
    content: str
    kind: str = "sql"

    @property
    def checksum(self):
        return hashlib.sha256(self.content.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def database_section(text, target):
    """Parse our limited psql control surface, not arbitrary external psql scripts."""
    current = target
    sections = {target: []}
    for line in text.replace("\r\n", "\n").splitlines():
        stripped = line.strip()
        if stripped == "\\set ON_ERROR_STOP on":
            continue
        if stripped.startswith("\\connect "):
            current = stripped.removeprefix("\\connect ")
            if current not in DATABASES:
                raise MigrationError("Unexpected migration database")
            sections.setdefault(current, [])
        elif stripped.startswith("\\"):
            raise MigrationError("Unsupported psql command in migration")
        else:
            sections.setdefault(current, []).append(line)
    result = sections.get(target, [])
    active = [i for i, line in enumerate(result) if line.strip() and not line.lstrip().startswith("--")]
    if not active:
        raise MigrationError("Migration contains no SQL for its database")
    controls = [i for i in active if result[i].strip().upper() in {"BEGIN;", "COMMIT;", "ROLLBACK;"}]
    if controls:
        if controls != [active[0], active[-1]] or result[active[0]].strip().upper() != "BEGIN;" or result[active[-1]].strip().upper() != "COMMIT;":
            raise MigrationError("Only a single outer transaction is supported")
        result = [line for i, line in enumerate(result) if i not in controls]
    return "\n".join(result).strip() + "\n"


def load_plan(path=None):
    manifest = json.loads((path or ROOT / "backend/deploy/migrations/manifest.json").read_text(encoding="utf-8"))
    if manifest["format"] != 1:
        raise MigrationError("Unsupported migration manifest")
    plans = {}
    for item in manifest["migrations"]:
        database = item["database"]
        if database not in DATABASES or not re.fullmatch(r"[0-9]{3}", item["version"]):
            raise MigrationError("Invalid database or version in manifest")
        source = (ROOT / item["source"]).resolve()
        kind = item.get("kind", "sql")
        if kind not in {"sql", "baseline"}:
            raise MigrationError("Unsupported migration kind")
        suffix = ".json" if kind == "baseline" else ".sql"
        if ROOT not in source.parents or ROOT / "backend" not in source.parents or source.suffix != suffix:
            raise MigrationError("Migration source must be a repository backend SQL file")
        if kind == "baseline" and ROOT / "backend/deploy/migrations/baselines" not in source.parents:
            raise MigrationError("Baseline source must be a registered contract")
        source_content = source.read_text(encoding="utf-8")
        migration = Migration(database, item["version"], item["name"], item["source"],
                              source_content if kind == "baseline" else database_section(source_content, database), kind)
        plans.setdefault(database, []).append(migration)
    for plan in plans.values():
        versions = [migration.version for migration in plan]
        if versions != sorted(set(versions)):
            raise MigrationError("Migration versions must be unique and ordered")
    return plans


def run_plan(connection, migrations, *, verify=False):
    """One transaction per migration, session advisory lock for the entire database plan.

    Applied SQL and its checksum receipt commit atomically. An interrupted multi-
    database deployment can rerun completed databases and resume pending ones.
    """
    if not connection.autocommit or connection.info.transaction_status != psycopg.pq.TransactionStatus.IDLE:
        raise MigrationError("Use an autocommit administrator connection; the runner owns transactions")
    database = connection.info.dbname
    if database not in DATABASES:
        raise MigrationError("Only registered business databases can be migrated")
    if any(migration.database != database for migration in migrations):
        raise MigrationError("Migration connection targets the wrong database")
    versions = [migration.version for migration in migrations]
    if versions != sorted(set(versions)):
        raise MigrationError("Duplicate or unordered migrations")
    lock = int.from_bytes(hashlib.sha256(("supportflow:migrations:" + database).encode()).digest()[:8], "big", signed=True)
    if not connection.execute("SELECT pg_try_advisory_lock(%s)", (lock,)).fetchone()[0]:
        raise MigrationError("Another migration or verification owns this database lock")
    try:
        exists = connection.execute("SELECT to_regclass('public.supportflow_schema_history')").fetchone()[0]
        if not verify and not connection.execute("SELECT has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]:
            raise MigrationError("A separate schema administrator is required")
        if not exists:
            if verify:
                raise MigrationError("Schema history is absent; migrate before verify")
            if not connection.execute("SELECT has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]:
                raise MigrationError("A separate schema administrator is required")
            with connection.transaction():
                connection.execute("""CREATE TABLE public.supportflow_schema_history (
                    version varchar(3) PRIMARY KEY, name text NOT NULL, checksum char(64) NOT NULL,
                    source text NOT NULL, installed_by text NOT NULL DEFAULT current_user,
                    installed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
                connection.execute("REVOKE ALL ON public.supportflow_schema_history FROM PUBLIC")
        if not verify:
            existing_roles=connection.execute("SELECT rolname FROM pg_roles WHERE rolname=ANY(%s)",(RUNTIME_ROLES,)).fetchall()
            for (role,) in existing_roles:
                connection.execute(sql.SQL("REVOKE ALL ON public.supportflow_schema_history FROM {}").format(sql.Identifier(role)))
            own_role="supportflow_"+database.removesuffix("_db")
            if (own_role,) in existing_roles:
                connection.execute(sql.SQL("GRANT SELECT ON public.supportflow_schema_history TO {}").format(sql.Identifier(own_role)))
        applied = dict(connection.execute("SELECT version,checksum FROM public.supportflow_schema_history").fetchall())
        if set(applied) - set(versions):
            raise MigrationError("Database has unknown newer migrations; refusing downgrade")
        if sorted(applied) != versions[:len(applied)]:
            raise MigrationError("Schema history is not a contiguous migration prefix")
        for migration in migrations:
            if migration.version in applied and applied[migration.version] != migration.checksum:
                raise MigrationError(f"Checksum mismatch for {database}/{migration.version}")
        pending = [migration for migration in migrations if migration.version not in applied]
        if verify and pending:
            raise MigrationError("Database has pending migrations")
        for migration in pending:
            try:
                with connection.transaction():
                    connection.execute("SELECT set_config('lock_timeout','2000',true),set_config('statement_timeout','30000',true)")
                    if migration.kind == "sql":
                        connection.execute(migration.content)
                    elif migration.kind == "baseline":
                        from schema_contract import validate_baseline
                        try:
                            validate_baseline(connection, migration.content)
                        except (ValueError, KeyError, TypeError) as failure:
                            raise MigrationError(f"Baseline {database}/{migration.version} rejected: {failure}") from None
                    else:
                        raise MigrationError("Unsupported migration kind")
                    connection.execute("""INSERT INTO public.supportflow_schema_history(version,name,checksum,source)
                        VALUES(%s,%s,%s,%s)""", (migration.version,migration.name,migration.checksum,migration.source))
            except psycopg.Error as error:
                raise MigrationError(f"Migration {database}/{migration.version} failed; SQLSTATE={error.sqlstate}") from None
        return {"database": database, "applied": [migration.version for migration in pending], "verified": len(migrations)}
    finally:
        connection.execute("SELECT pg_advisory_unlock(%s)", (lock,))


def migrate_databases(environment, *, verify=False, database=None):
    plans=load_plan()
    if database is not None:
        if database not in plans:
            raise MigrationError("No incremental version plan is registered for that database")
        plans={database:plans[database]}
    password=environment.get("SUPPORTFLOW_DB_PASSWORD","")
    if not password:
        raise MigrationError("Separate migration administrator credentials are required")
    results=[]
    for target,plan in plans.items():
        with psycopg.connect(host=environment.get("SUPPORTFLOW_DB_HOST","127.0.0.1"),
                             port=environment.get("SUPPORTFLOW_DB_PORT","5432"),dbname=target,
                             user=environment.get("SUPPORTFLOW_MIGRATION_DB_USER","supportflow"),
                             password=password,connect_timeout=5,autocommit=True) as connection:
            results.append(run_plan(connection,plan,verify=verify))
    return results
