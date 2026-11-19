"""Run AI with isolated runtime DB identities and the existing local provider configuration."""
import os
import subprocess
import sys
from local_env import ROOT, load, ai_database_urls, runtime_environment

if __name__ == "__main__":
    load()
    ai_database_urls()
    raise SystemExit(subprocess.call([sys.executable, "-m", "ai"], cwd=ROOT / "ai-service", env=runtime_environment('ai')))
