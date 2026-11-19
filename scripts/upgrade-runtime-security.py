"""Upgrade an existing local Compose database in place; never remove data volumes."""
import os
import subprocess
from local_env import ROOT, load
from migration_engine import migrate_databases


def main():
    load()
    # Apply versioned structural SQL, then provision runtime credentials separately.
    environment = dict(os.environ, PGPASSWORD=os.environ["SUPPORTFLOW_DB_PASSWORD"])
    roles = ("USER", "TENANT", "CUSTOMER", "BILLING", "OPS", "AI", "KB", "CUSTOMER_PLATFORM")
    keys = [f"SUPPORTFLOW_{role}_DB_PASSWORD" for role in roles]
    passwords = [environment.get(key, "") for key in keys]
    if any(len(p) < 24 for p in passwords) or len(set(passwords + [environment["PGPASSWORD"]])) != 9:
        raise SystemExit("Configure eight distinct runtime passwords, separate from the administrator, of at least 24 characters")
    commands = ["docker", "compose", "exec", "-T", "-e", "PGPASSWORD"]
    for key in keys:
        commands.extend(["-e", key])
    commands += ["postgres", "psql", "-U", "supportflow", "-d", "postgres", "-wXq", "-v", "ON_ERROR_STOP=1"]
    # Input rather than mounted script paths also works for containers created before
    # the new mounts were added. Existing data and object keys are preserved.
    migrate_databases(environment)
    content = (ROOT / "backend/deploy/db-init/40-runtime-roles.sql").read_text(encoding="utf-8")
    subprocess.run(commands, cwd=ROOT, env=environment, input=content, text=True, encoding="utf-8", check=True)
    migrate_databases(environment, verify=True)
    print("Security upgrade completed in place. Start applications with the runtime identities.")


if __name__ == "__main__":
    main()
