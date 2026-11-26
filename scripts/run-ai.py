"""Run AI with isolated runtime DB identities and the existing local provider configuration."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parent  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import os
import subprocess
import sys
from local_env import ROOT, load, ai_database_urls, runtime_environment

if __name__ == "__main__":
    load()
    ai_database_urls()
    raise SystemExit(subprocess.call([sys.executable, "-m", "ai"],
                     cwd=ROOT / "ai-service", env=runtime_environment('ai')))
