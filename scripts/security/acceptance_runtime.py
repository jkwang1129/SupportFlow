"""Launch local acceptance services and keep their data separate from all other projects."""
import contextlib
import json
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root / 'scripts'))
from local_env import runtime_environment
logs = root / ".local" / "security-services"
logs.mkdir(parents=True, exist_ok=True)
project = "supportflow-acceptance-" + secrets.token_hex(3)
compose = ["docker", "compose", "--project-name", project]
(logs / '.project-name').write_text(project, encoding='utf-8')
legacy = '--legacy' in sys.argv
if legacy:
    empty = logs / 'skip-init.sql'
    empty.write_text('-- Bootstrap baseline only; migration will follow in place.\n', encoding='utf-8')
    override = logs / 'legacy-compose.json'
    override.write_text(json.dumps({'services': {'postgres': {'volumes': [
        {'type':'bind','source':str(empty),'target':'/docker-entrypoint-initdb.d/25-runtime-security.sql','read_only':True},
        {'type':'bind','source':str(empty),'target':'/docker-entrypoint-initdb.d/40-runtime-roles.sql','read_only':True},
    ]}}}), encoding='utf-8')
    compose += ['-f', str(root / 'compose.yaml'), '-f', str(override)]
password = secrets.token_urlsafe(32)
storage_key = secrets.token_urlsafe(32)
env = dict({k:v for k,v in os.environ.items() if not k.startswith("SUPPORTFLOW_")}, JAVA_HOME=os.environ["JAVA_HOME"],
           SUPPORTFLOW_DB_HOST="127.0.0.1", SUPPORTFLOW_DB_PORT="15432", SUPPORTFLOW_DB_USER="supportflow",
           SUPPORTFLOW_DB_PASSWORD=password, SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(48),
           SUPPORTFLOW_GATEWAY_PORT="19090", SUPPORTFLOW_RUSTFS_PORT="19000", SUPPORTFLOW_RUSTFS_SECRET_KEY=storage_key,
           SUPPORTFLOW_AI_DATABASE_URL=f"postgresql://supportflow:{password}@127.0.0.1:15432/ai_db",
           SUPPORTFLOW_AI_KB_DATABASE_URL=f"postgresql://supportflow:{password}@127.0.0.1:15432/customer_db",
           SUPPORTFLOW_CAPTCHA_DEBUG="true", SUPPORTFLOW_REGISTER_CODE_DEBUG="true", PYTHONUTF8="1",
           VITE_PROXY_TARGET="http://127.0.0.1:19090", SUPPORTFLOW_API_BASE="http://127.0.0.1:19090/api", NO_RESTART="1")
for role in ("USER", "TENANT", "CUSTOMER", "BILLING", "OPS", "AI", "KB", "CUSTOMER_PLATFORM"):
    env[f"SUPPORTFLOW_{role}_DB_PASSWORD"] = secrets.token_urlsafe(32)
env["SUPPORTFLOW_AI_DATABASE_URL"] = f"postgresql://supportflow_ai:{env['SUPPORTFLOW_AI_DB_PASSWORD']}@127.0.0.1:15432/ai_db"
env["SUPPORTFLOW_AI_KB_DATABASE_URL"] = f"postgresql://supportflow_kb:{env['SUPPORTFLOW_KB_DB_PASSWORD']}@127.0.0.1:15432/customer_db"

env.update({f"SUPPORTFLOW_AI_{role}_SECRET": secrets.token_urlsafe(48) for role in ("GATEWAY","CUSTOMER","OPS","TENANT")})
env.update({f"SUPPORTFLOW_CUSTOMER_{role}_SECRET": secrets.token_urlsafe(48) for role in ("REALTIME","OPS","TENANT")})
env["SUPPORTFLOW_BILLING_CUSTOMER_SECRET"] = secrets.token_urlsafe(48)
env["SUPPORTFLOW_USAGE_REPORT_MS"] = "1000"
env["SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS"] = "1000"
env.update(PYTHONPATH=str(root / "ai-service"), SUPPORTFLOW_AI_LLM_DEFAULT_PROVIDER="mock", SUPPORTFLOW_AI_LOG_LEVEL="INFO")
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
children = []


def launch(args, cwd, name):
    with (logs / (name + ".log")).open("wb") as output:
        proc = subprocess.Popen(args, cwd=cwd, env=runtime_environment(name,env), stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    children.append(proc)
    return proc


stop = logs / "stop"
restart = logs / "restart-ai"
pause = logs / "pause-ai"
for marker in (stop, restart, pause):
    marker.unlink(missing_ok=True)
with (logs / "compose.log").open("wb") as output:
    try:
        subprocess.run([*compose, "up", "-d", "--wait", "postgres", "rustfs"], cwd=root, env=env,
                       stdout=output, stderr=subprocess.STDOUT, timeout=150, check=True)
        print("Acceptance database and attachment storage ready", flush=True)
        if legacy:
            commands = [*compose, 'exec', '-T', '-e', 'PGPASSWORD']
            for role in ('USER','TENANT','CUSTOMER','BILLING','OPS','AI','KB','CUSTOMER_PLATFORM'):
                commands += ['-e', f'SUPPORTFLOW_{role}_DB_PASSWORD']
            commands += ['postgres','psql','-U','supportflow','-d','customer_db','-wXq','-v','ON_ERROR_STOP=1']
            sql = """INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,biz_type,creator)
                VALUES (90001,'PLATFORM','OLD_LICENSE','old.png','unchanged/old.png',2,'99');
                CREATE POLICY old_permissive ON file_meta USING(true) WITH CHECK(true);
            """
            sql += (root / 'backend/supportflow-customer-service/src/main/resources/schema/customer_db_runtime_security.sql').read_text(encoding='utf-8')
            sql += (root / 'backend/deploy/db-init/40-runtime-roles.sql').read_text(encoding='utf-8')
            subprocess.run(commands, cwd=root, env={**env,'PGPASSWORD':password}, input=sql,
                           text=True, encoding='utf-8', stdout=output, stderr=subprocess.STDOUT, timeout=60, check=True)
            print('Populated legacy database upgraded in place', flush=True)
        admin_env = dict(env, PYTHONPATH=str(root / "scripts"))
        admin_password = secrets.token_urlsafe(20) + "X9"
        subprocess.run([sys.executable, "-c", "import os, sys, runpy; from unittest.mock import patch; "
                        "sys.argv=['create-platform-admin.py','--email','acceptance-admin@example.test']; "
                        "patch('getpass.getpass', return_value=os.environ['ACCEPTANCE_ADMIN_PASSWORD']).start(); "
                        "runpy.run_path('scripts/create-platform-admin.py',run_name='__main__')"], cwd=root,
                       env={**admin_env, "ACCEPTANCE_ADMIN_PASSWORD": admin_password}, stdout=output,
                       stderr=subprocess.STDOUT, check=True, timeout=20)
        (logs / ".test-admin").write_text(admin_password, encoding="utf-8")
        java = Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        for module in ("gateway", "user-service", "tenant-service", "customer-service", "billing-service",
                       "ops-service", "realtime-service"):
            name = "supportflow-" + module
            jar, = (root / "backend" / name / "target").glob("*.jar")
            launch([str(java), "-Xms32m", "-Xmx384m", "-jar", str(jar)], root, module)
        print("Java services launched", flush=True)
        ai = launch([sys.executable, "-m", "ai"], logs, "ai")
        deadline = time.monotonic() + 1200
        while not stop.exists() and time.monotonic() < deadline:
            if any(p.poll() is not None for p in children):
                raise RuntimeError("A service exited; inspect its local log.")
            time.sleep(1)
    finally:
        for proc in reversed(children):
            if proc.poll() is None:
                proc.terminate()
        for proc in reversed(children):
            with contextlib.suppress(subprocess.TimeoutExpired):
                proc.wait(timeout=10)
        (logs / ".test-admin").unlink(missing_ok=True)
        subprocess.run([*compose, "--profile", "attachments", "down", "--remove-orphans", "-v"],
                       cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=90, check=True)
