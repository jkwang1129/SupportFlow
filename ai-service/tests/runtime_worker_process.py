"""Subprocess fault fixture, intentionally killable only on a disposable test DSN."""
import os
from pathlib import Path
import sys
from threading import Event

import psycopg
from psycopg.rows import dict_row

from ai.runtime.store import RuntimeStore
from ai.runtime.worker import RuntimeWorker


def main():
    mode, marker = sys.argv[1:]
    if mode not in ('MODEL', 'WRITE'):
        raise ValueError('Invalid fixture mode')
    connector = lambda: psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, connect_timeout=2)
    store = RuntimeStore(connector)

    def handler(context):
        context.step('prepare', 'INTERNAL', 'NONE', {'operation': 'prepare'}, lambda: {'resource_ref': 'prepared'})

        def interrupted_call():
            Path(marker).write_text('reserved', encoding='utf-8')
            Event().wait()  # Parent kills this process while the step is RESERVED.

        return context.step('external', 'MODEL' if mode == 'MODEL' else 'TOOL',
                            'NONE' if mode == 'MODEL' else 'WRITE', {'operation': 'external'}, interrupted_call)

    RuntimeWorker(store, {'runtime-v1': handler}, 'killed-process', lease_seconds=1).run_once('TENANT_A')


if __name__ == '__main__':
    main()
