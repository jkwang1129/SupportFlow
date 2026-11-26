"""Create missing local runtime secrets; preserve existing values and AI provider keys."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parent  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main():
    path = ROOT / ".env"
    original = path.read_text(encoding="utf-8") if path.exists() else ""
    names = {line.partition("=")[0] for line in original.splitlines() if "=" in line}
    required = ["SUPPORTFLOW_DB_PASSWORD", "SUPPORTFLOW_JWT_SECRET", "SUPPORTFLOW_RUSTFS_SECRET_KEY"]
    required += [f"SUPPORTFLOW_{role}_DB_PASSWORD" for role in (
        "USER", "TENANT", "CUSTOMER", "BILLING", "OPS", "AI", "KB", "CUSTOMER_PLATFORM")]
    required += [f"SUPPORTFLOW_AI_{role}_SECRET" for role in ("GATEWAY", "CUSTOMER", "OPS", "TENANT")]
    required += [f"SUPPORTFLOW_CUSTOMER_{role}_SECRET" for role in ("REALTIME", "OPS", "TENANT")]
    required += ["SUPPORTFLOW_BILLING_CUSTOMER_SECRET"]
    additions = [f"{key}={secrets.token_urlsafe(48)}" for key in required if key not in names]
    if additions:
        with path.open("a", encoding="utf-8") as f:
            f.write(("\n" if original and not original.endswith("\n") else "") + "\n".join(additions) + "\n")
    print("Local runtime configuration ready; secret values are not displayed. Existing databases require the documented upgrade.")


if __name__ == "__main__":
    main()
