"""Opt-in service entry point for the existing read-only TicketReadLoop."""
import argparse
import logging
import signal
from threading import Event
from uuid import uuid4

import psycopg

from ..config import get_settings
from ..core.llm_chat import provider_conf
from ..core.schema_guard import runtime_missing
from .customer_tools import current_message_tool, ticket_status_tool
from .models import ChatProfile, RuntimeChatClient
from .source import SourceClient
from .store import RuntimeStore
from .ticket_loop import TicketReadLoop
from .tickets import TicketClient
from .tools import ToolExecutor, ToolRegistry
from .types import tenant_code
from .worker import RuntimeWorker

PROVIDERS = ('qwen', 'deepseek')
logger = logging.getLogger(__name__)


def build_worker(settings, tenant, provider, *, owner):
    tenant_code(tenant)
    if not settings.runtime_api_enabled:
        raise ValueError('Runtime receiver must be explicitly enabled')
    if provider not in PROVIDERS:
        raise ValueError('An explicit supported provider is required')
    config = provider_conf(provider, getattr(settings, provider + '_api_key'),
                           getattr(settings, provider + '_base_url'), getattr(settings, provider + '_model'))
    profile = ChatProfile(tenant=tenant, profile_ref='ticket-read-' + provider + '-v1',
                          prompt_version='ticket-plan-v1', **config)
    base = settings.customer_runtime_url
    secret = settings.customer_secret.get_secret_value()
    access = ToolExecutor(ToolRegistry([current_message_tool(SourceClient(base, secret))]), frozenset({'session:read'}))
    tools = ToolExecutor(ToolRegistry([ticket_status_tool(TicketClient(base, secret))]), frozenset({'ticket:read'}))
    loop = TicketReadLoop(access, tools, RuntimeChatClient(profile))
    return RuntimeWorker(RuntimeStore(), {'runtime-v1': loop}, owner, tenant=tenant)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True, help='Tenant assigned to this worker by the operator')
    parser.add_argument('--provider', required=True, choices=PROVIDERS)
    parser.add_argument('--once', action='store_true', help='Run at most one due task and exit')
    args = parser.parse_args(argv)
    try:
        worker = build_worker(get_settings(), args.tenant, args.provider, owner='ticket-worker:' + uuid4().hex)
        if runtime_missing():
            logger.error('Ticket worker schema or role is unavailable')
            return 1
        if args.once:
            worker.run_once(args.tenant)
            return 0
        stop = Event()

        def stop_polling(*_):
            stop.set()

        signal.signal(signal.SIGTERM, stop_polling)
        signal.signal(signal.SIGINT, stop_polling)
        worker.run_forever(stop, poll_seconds=1)
        return 0
    except (ValueError, RuntimeError, psycopg.Error) as failure:
        logger.error('Ticket worker stopped errorType=%s', type(failure).__name__)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
