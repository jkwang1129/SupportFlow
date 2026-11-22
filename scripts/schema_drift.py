"""Read-only verification of all registered application schema objects against reviewed desired state."""
import json
from pathlib import Path
import re

from schema_contract import capture_baseline

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "backend/deploy/migrations/current-schema.json"

# PostgreSQL 17 can move the cast in a deparsed string-enum comparison
# from the whole array to each literal on pg_dump/pg_restore round trips.
_ENUM_ARRAY = re.compile(
    r"(?P<operator>\b(?:ANY|ALL)) \(\(ARRAY\["
    r"(?P<literals>'[A-Za-z0-9_-]+'::character varying(?:, '[A-Za-z0-9_-]+'::character varying)*)"
    r"\]\)::text\[\]\)|'(?:[^']|'')*'"
)


def _restored_definition(required):
    def cast_elements(match):
        if match['operator'] is None:
            return match[0]  # Keep unrelated SQL string literals opaque.
        elements = ', '.join(f'({literal})::text' for literal in match['literals'].split(', '))
        return f"{match['operator']} (ARRAY[{elements}])"

    if isinstance(required, dict):
        return required | {'definition': _ENUM_ARRAY.sub(cast_elements, required['definition'])}
    return _ENUM_ARRAY.sub(cast_elements, required)


class SchemaDriftError(RuntimeError):
    pass


def capture_schema(connection):
    schema = capture_baseline(connection)
    for table, column, default, identity, generated in connection.execute("""
        SELECT c.relname,a.attname,pg_get_expr(d.adbin,d.adrelid),a.attidentity,a.attgenerated
        FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
        WHERE n.nspname='public' AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
        """):
        if table in schema["tables"]:
            schema["tables"][table]["columns"][column].update(default=default, identity=identity, generated=generated)
    for table, enabled, forced in connection.execute("""
        SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity FROM pg_class c
        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p')
        """):
        if table in schema["tables"]:
            schema["tables"][table]["rls"] = {"enabled": enabled, "forced": forced, "policies": {}}
    for table, name, command, permissive, roles, using, check in connection.execute("""
        SELECT c.relname,p.polname,p.polcmd,p.polpermissive,
               ARRAY(SELECT CASE WHEN role=0 THEN 'PUBLIC' ELSE pg_get_userbyid(role) END
                     FROM unnest(p.polroles) role ORDER BY role),
               pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid)
        FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
        """):
        if table in schema["tables"]:
            schema["tables"][table]["rls"]["policies"][name] = {
                "command": command, "permissive": permissive, "roles": sorted(roles), "using": using, "check": check}
    schema["views"] = dict(connection.execute("""
        SELECT c.relname,pg_get_viewdef(c.oid,true) FROM pg_class c
        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='v'
        ORDER BY c.relname
        """).fetchall())
    schema["functions"] = dict(connection.execute("""
        SELECT p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.proname LIKE 'supportflow\\_%' ORDER BY p.proname,p.oid
        """).fetchall())
    return schema


def assert_schema(actual, expected):
    """Verify the registered public schema, excluding migration history and extension functions.

    Unreviewed policies/objects must not silently become the desired state. A
    deliberate schema change requires a new migration and reviewed current
    contract. Independent privilege and cross-tenant tests remain necessary.
    """
    if actual["database"] != expected["database"]:
        raise SchemaDriftError("Schema contract targets another database")
    if set(actual["tables"]) != set(expected["tables"]):
        raise SchemaDriftError("Application table set differs from the registered schema")
    for table, contract in expected["tables"].items():
        observed = actual["tables"].get(table)
        if observed is None:
            raise SchemaDriftError(f"Required table missing: {table}")
        for group in ("columns", "indexes", "constraints"):
            if set(observed[group]) != set(contract[group]):
                raise SchemaDriftError(f"Schema {group} set drift: {table}")
            for name, required in contract[group].items():
                found = observed[group][name]
                if found == required or group in ('indexes', 'constraints') and found == _restored_definition(required):
                    continue
                raise SchemaDriftError(f"Schema {group} drift: {table}.{name}")
        for flag in ("enabled", "forced"):
            if observed["rls"][flag] != contract["rls"][flag]:
                raise SchemaDriftError(f"Schema RLS {flag} drift: {table}")
        for policy, required in contract["rls"]["policies"].items():
            if observed["rls"]["policies"].get(policy) != required:
                raise SchemaDriftError(f"Schema RLS policy drift: {table}.{policy}")
        if set(observed["rls"]["policies"]) != set(contract["rls"]["policies"]):
            raise SchemaDriftError(f"Unreviewed RLS policy set: {table}")
    for group in ("views", "functions"):
        if set(actual[group]) != set(expected[group]):
            raise SchemaDriftError(f"Schema {group} set drift")
        for name, required in expected[group].items():
            if actual[group].get(name) != required:
                raise SchemaDriftError(f"Schema {group} drift: {name}")
    return {"tables": len(expected["tables"]), "views": len(expected["views"]),
            "functions": len(expected["functions"])}


def verify_schema(connection, plans, path=CONTRACT):
    contract = json.loads(path.read_text(encoding="utf-8"))
    versions = {database: [{"version": migration.version, "checksum": migration.checksum} for migration in plan]
                for database, plan in plans.items()}
    if contract.get("format") != 1 or contract.get("migrations") != versions:
        raise SchemaDriftError("Current schema contract does not match the migration manifest")
    expected = contract["databases"].get(connection.info.dbname)
    if not expected:
        raise SchemaDriftError("Current schema contract is absent for this database")
    return assert_schema(capture_schema(connection), expected)
