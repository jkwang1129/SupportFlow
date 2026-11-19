"""Load the ignored local runtime configuration without executing shell expressions."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load():
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.startswith("SUPPORTFLOW_") and key.replace("_", "").isalnum():
                os.environ.setdefault(key, value.strip())


def ai_database_urls():
    from urllib.parse import quote
    host = os.getenv("SUPPORTFLOW_DB_HOST", "127.0.0.1")
    port = os.getenv("SUPPORTFLOW_DB_PORT", "5432")
    for kind, name, database in (("AI", "supportflow_ai", "ai_db"), ("KB", "supportflow_kb", "customer_db")):
        password = os.environ.get(f"SUPPORTFLOW_{kind}_DB_PASSWORD", "")
        if not password:
            raise SystemExit("Run scripts/setup-local-env.py first")
        key = "SUPPORTFLOW_AI_DATABASE_URL" if kind == "AI" else "SUPPORTFLOW_AI_KB_DATABASE_URL"
        os.environ[key] = f"postgresql://{name}:{quote(password, safe='')}@{host}:{port}/{database}"


def runtime_environment(service, source=None):
    """Do not pass bootstrap/other services' credentials into a runtime process."""
    source = dict(os.environ if source is None else source)
    own = service.removeprefix('supportflow-').removesuffix('-service').upper()
    databases = {'AI', 'KB'} if own == 'AI' else {own}
    if own == 'CUSTOMER':
        databases.add('CUSTOMER_PLATFORM')
    ai_callers = {'GATEWAY', 'CUSTOMER', 'OPS', 'TENANT'} if own == 'AI' else {own}
    customer_callers = {'REALTIME', 'OPS', 'TENANT'} if own == 'CUSTOMER' else {own}
    for key in list(source):
        if not key.startswith('SUPPORTFLOW_'):
            continue
        if key.endswith('_DB_PASSWORD') and key not in {f'SUPPORTFLOW_{d}_DB_PASSWORD' for d in databases}:
            source.pop(key)
        elif key == 'SUPPORTFLOW_DB_USER' or ('_MIGRATION_' in key and own != 'CUSTOMER'):
            source.pop(key)
        elif key.startswith('SUPPORTFLOW_AI_') and key.endswith('_SECRET') and key not in {f'SUPPORTFLOW_AI_{c}_SECRET' for c in ai_callers}:
            source.pop(key)
        elif key.startswith('SUPPORTFLOW_CUSTOMER_') and key.endswith('_SECRET') and key not in {f'SUPPORTFLOW_CUSTOMER_{c}_SECRET' for c in customer_callers}:
            source.pop(key)
        elif key in ('SUPPORTFLOW_AI_DATABASE_URL', 'SUPPORTFLOW_AI_KB_DATABASE_URL') and own != 'AI':
            source.pop(key)
        elif key == 'SUPPORTFLOW_JWT_SECRET' and own == 'AI':
            source.pop(key)
    return source
