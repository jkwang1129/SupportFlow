"""Read PostgreSQL structural metadata; a baseline is data, never executable SQL."""
import json


def capture_baseline(connection):
    tables = {}
    for table, column, data_type, not_null in connection.execute("""
        SELECT c.relname,a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
        WHERE n.nspname='public' AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
          AND c.relname<>'supportflow_schema_history'
        ORDER BY c.relname,a.attnum
        """):
        tables.setdefault(table, {"columns": {}, "indexes": {}, "constraints": {}})["columns"][column] = {
            "type": data_type, "not_null": not_null}
    for table, index, definition, valid in connection.execute("""
        SELECT t.relname,i.relname,pg_catalog.pg_get_indexdef(i.oid),x.indisvalid
        FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class t ON t.oid=x.indrelid
        JOIN pg_catalog.pg_class i ON i.oid=x.indexrelid
        JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
        WHERE n.nspname='public' ORDER BY t.relname,i.relname
        """):
        if table in tables:
            tables[table]["indexes"][index] = {"definition": definition, "valid": valid}
    for table, name, definition in connection.execute("""
        SELECT t.relname,c.conname,pg_catalog.pg_get_constraintdef(c.oid)
        FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
        JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
        WHERE n.nspname='public' ORDER BY t.relname,c.conname
        """):
        if table in tables:
            tables[table]["constraints"][name] = definition
    return {"format": 1, "database": connection.info.dbname, "tables": tables}


def validate_baseline(connection, content):
    """Adopt an initialized database without re-running non-idempotent CREATE TABLE.

    Required baseline columns/types/nullability/indexes/constraints must match.
    Additional objects from subsequent migrations are allowed. RLS and privileges
    are deliberately not baseline evidence; the security migrations verify those.
    """
    expected = json.loads(content)
    if expected.get("format") != 1 or expected.get("database") != connection.info.dbname or not expected.get("tables"):
        raise ValueError("Invalid baseline contract")
    actual = capture_baseline(connection)
    for table, contract in expected["tables"].items():
        observed = actual["tables"].get(table)
        if observed is None:
            raise ValueError(f"Baseline table missing: {table}")
        for group in ("columns", "indexes", "constraints"):
            for name, required in contract[group].items():
                if observed[group].get(name) != required:
                    raise ValueError(f"Baseline {group} mismatch: {table}.{name}")
