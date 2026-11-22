"""Run one tenant's ticket read loop or approved ticket executions."""
import argparse
import logging
import signal
from threading import Event
from uuid import uuid4

import psycopg

from ..config import get_settings
from ..core.llm_chat import provider_conf
from ..core.schema_guard import runtime_missing
from .approvals import TicketApprovals
from .customer_tools import current_message_tool, ticket_status_tool
from .models import ChatProfile, RuntimeChatClient
from .mcp_ticket import McpTicketClient
from .source import SourceClient
from .store import RuntimeStore
from .ticket_loop import TicketReadLoop
from .ticket_creation import TicketCreationClient
from .ticket_write_tool import ticket_create_tool
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
    mcp_secret = settings.runtime_ticket_mcp_secret.get_secret_value()
    mcp = None
    if settings.runtime_ticket_mcp_url or mcp_secret:
        if secret == mcp_secret:
            raise ValueError('MCP and customer credentials must be distinct')
        mcp = McpTicketClient(settings.runtime_ticket_mcp_url, mcp_secret)
    access = ToolExecutor(ToolRegistry([current_message_tool(SourceClient(base, secret))]), frozenset({'session:read'}))
    tools = ToolExecutor(ToolRegistry([ticket_status_tool(TicketClient(base, secret), mcp_client=mcp)]), frozenset({'ticket:read'}))
    store = RuntimeStore()
    loop = TicketReadLoop(access, tools, RuntimeChatClient(profile),
                          canonical_history=settings.runtime_canonical_planner_history,
                          transport_hash=mcp.definition_hash() if mcp is not None else None,
                          approvals=TicketApprovals(store) if settings.runtime_ticket_proposal_enabled else None)
    return RuntimeWorker(store, {'runtime-v1': loop}, owner, tenant=tenant, approval_mode='without_approval')


def build_approval_worker(settings, tenant, *, owner):
    tenant_code(tenant)
    if not settings.runtime_api_enabled or not settings.runtime_ticket_actions_enabled:
        raise ValueError('Runtime and ticket action authority must be explicitly enabled')
    base = settings.customer_runtime_url
    secret = settings.customer_secret.get_secret_value()
    tool = ticket_create_tool(SourceClient(base, secret), TicketCreationClient(base, secret))
    executor = ToolExecutor(ToolRegistry([tool]), frozenset({'ticket:create'}))
    store = RuntimeStore()
    approvals = TicketApprovals(store)
    return RuntimeWorker(store, {'runtime-v1': lambda context: approvals.execute(context, executor)},
                         owner, tenant=tenant, approval_mode='approved_only')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant', required=True, help='Tenant assigned to this worker by the operator')
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--provider', choices=PROVIDERS)
    mode.add_argument('--approved-tickets-only', action='store_true', help='Execute existing approved ticket proposals')
    parser.add_argument('--once', action='store_true', help='Run at most one due task and exit')
    args = parser.parse_args(argv)
    try:
        settings = get_settings()
        owner = 'ticket-worker:' + uuid4().hex
        worker = (build_approval_worker(settings, args.tenant, owner=owner) if args.approved_tickets_only
                  else build_worker(settings, args.tenant, args.provider, owner=owner))
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
