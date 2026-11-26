"""Run the seven packaged Java services from a single terminal."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parent  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import os
from pathlib import Path
import shutil
import subprocess
import time
from local_env import load, runtime_environment


ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    "supportflow-user-service",
    "supportflow-tenant-service",
    "supportflow-customer-service",
    "supportflow-billing-service",
    "supportflow-ops-service",
    "supportflow-realtime-service",
    "supportflow-gateway",
)


def main() -> None:
    load()
    java_home = os.environ.get("JAVA_HOME")
    java_name = "java.exe" if os.name == "nt" else "java"
    java = str(Path(java_home) / "bin" / java_name) if java_home else shutil.which(java_name)
    if not java or not Path(java).is_file():
        raise SystemExit("Set JAVA_HOME to a JDK 25 installation.")
    if len(os.environ.get("SUPPORTFLOW_JWT_SECRET", "").encode()) < 32:
        raise SystemExit("Set a SUPPORTFLOW_JWT_SECRET of at least 32 bytes.")

    jars = {}
    for module in MODULES:
        target = ROOT / "backend" / module / "target"
        matches = sorted(target.glob(f"{module}-*.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one {module} JAR. Run mvn -f backend/pom.xml clean package.")
        jars[module] = matches[0]

    logs = ROOT / ".local" / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    children = {}
    try:
        for module, jar in jars.items():
            log_path = logs / f"{module}.log"
            with log_path.open("wb") as log:
                children[module] = subprocess.Popen(
                    [java, "-jar", str(jar)], cwd=ROOT, env=runtime_environment(module), stdout=log, stderr=subprocess.STDOUT)
            print(f"Started {module}. Log: {log_path}", flush=True)
        while True:
            for module, child in children.items():
                if child.poll() is not None:
                    raise SystemExit(
                        f"{module} exited with code {child.returncode}. Check {logs / (module + '.log')}.")
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for child in children.values():
            if child.poll() is None:
                child.terminate()
        for child in children.values():
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()


if __name__ == "__main__":
    main()
