"""Disposable read-Loop process-death fixture. Never a production worker launcher."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[2]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import json
import os
from pathlib import Path
import sys
import time

import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'ai-service'))


def main():
    if os.environ.get('SUPPORTFLOW_LOOP_PROCESS_FIXTURE') != '1' or len(sys.argv) != 3 or sys.argv[1] not in ('crash', 'resume'):
        raise SystemExit('This launcher requires the disposable Loop fixture')
    from ai.runtime.customer_tools import current_message_tool, ticket_status_tool
    from ai.runtime.models import ChatProfile, RuntimeChatClient
    from ai.runtime.source import SourceClient
    from ai.runtime.store import RuntimeStore
    from ai.runtime.ticket_loop import TicketReadLoop
    from ai.runtime.tickets import TicketClient
    from ai.runtime.tools import ToolExecutor, ToolRegistry
    from ai.runtime.worker import RuntimeWorker
    mode, tenant = sys.argv[1:]

    class Store(RuntimeStore):
        def complete_payload_step(self, *args):
            value = super().complete_payload_step(*args)
            if mode == 'crash' and args[2] == 'model-output-v1':
                print('MODEL_CHECKPOINT_COMMITTED', flush=True)
                os._exit(23)
            return value
    store = Store(lambda: psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row))
    base, secret = os.environ['SUPPORTFLOW_LOOP_TEST_CUSTOMER_URL'], os.environ['SUPPORTFLOW_LOOP_TEST_CUSTOMER_KEY']
    access = ToolExecutor(ToolRegistry([current_message_tool(
        SourceClient(base, secret))]), frozenset({'session:read'}))
    tools = ToolExecutor(ToolRegistry(
        [ticket_status_tool(TicketClient(base, secret))]), frozenset({'ticket:read'}))
    profile = ChatProfile(tenant, 'loop-planner-v1', 'fixture', 'fixture-chat', os.environ['SUPPORTFLOW_LOOP_TEST_MODEL_URL'],
                          os.environ['SUPPORTFLOW_LOOP_TEST_MODEL_KEY'], prompt_version='ticket-plan-v1')
    loop = TicketReadLoop(access, tools, RuntimeChatClient(profile))
    worker = RuntimeWorker(store, {'runtime-v1': loop}, 'loop-' + mode + '-process', lease_seconds=3)
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        result = worker.run_once(tenant)
        if result is not None:
            print(json.dumps({key: result[key] for key in ('state', 'model_calls',
                  'tool_calls', 'step_count', 'retry_count', 'attempt')}), flush=True)
            return
        time.sleep(.05)
    raise SystemExit('Disposable Loop lease takeover did not become due')


if __name__ == '__main__':
    main()
