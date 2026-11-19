"""Upgrade an existing local Compose database in place; never remove data volumes."""
import os
import subprocess
from local_env import ROOT, load


def main():
    load()
    # Run all migrations through psql with ON_ERROR_STOP; secrets are environment-only.
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
    content = "\n".join([
        "\\connect customer_db",
        (ROOT / "backend/deploy/db-init/channel-tenant-lookup.sql").read_text(encoding="utf-8"),
        (ROOT / "backend/deploy/db-init/public-bot-avatar.sql").read_text(encoding="utf-8"),
        (ROOT / "backend/supportflow-customer-service/src/main/resources/schema/customer_db_runtime_security.sql").read_text(encoding="utf-8"),
        (ROOT / "backend/deploy/db-init/30-usage-batches.sql").read_text(encoding="utf-8"),
        (ROOT / "backend/deploy/db-init/40-runtime-roles.sql").read_text(encoding="utf-8"),
    ])
    subprocess.run(commands, cwd=ROOT, env=environment, input=content, text=True, encoding="utf-8", check=True)
    print("Security upgrade completed in place. Start applications with the runtime identities.")


if __name__ == "__main__":
    main()
