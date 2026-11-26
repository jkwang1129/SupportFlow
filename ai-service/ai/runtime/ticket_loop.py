"""Bounded read loop and optional explicit visitor ticket proposal.

Structured JSON decisions are an internal protocol, not a claim of native
provider tool_calls/MCP support. Only explicitly registered READ tools can run.
"""
import json
from dataclasses import dataclass, field
from types import MappingProxyType
from uuid import UUID

from .approvals import TicketApprovals
from .models import RuntimeChatClient
from .ticket_prompts import STABLE_PROMPT, TICKET_READ, ticket_prompt
from .tools import ToolCall, ToolExecutor
from .types import fingerprint, reference
from .worker import PermanentFailure


LOOP_VERSION = 'ticket-loop-v1'
PROPOSAL_VERSION = 'explicit-ticket-proposal-v1'
STATUS_NAMES = {1: '待处理', 2: '处理中', 3: '待客户确认', 4: '已解决', 5: '已关闭'}


@dataclass(frozen=True)
class PreparedReport:
    references: dict
    value: dict = field(repr=False)

    def __post_init__(self):
        object.__setattr__(self, 'references', MappingProxyType(reference(dict(self.references))))
        # The validated report projection contains only immutable scalar values.
        object.__setattr__(self, 'value', MappingProxyType(dict(self.value)))


def _json(value): return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def decision(content):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError()
            value[key] = item
        return value
    try:
        value = json.loads(content, object_pairs_hook=unique)
        if type(value) is not dict:
            raise ValueError()
        action = value.get('action')
        if action == 'tool' and set(value) == {'action', 'call'}:
            return action, ToolCall.parse(_json(value['call']).encode())
        if action == 'report' and set(value) == {'action', 'evidence'} and type(value['evidence']) is str:
            return action, value['evidence']
        if action == 'clarify' and set(value) == {'action'}:
            return action, None
        raise ValueError()
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise PermanentFailure('PLANNER_DECISION_INVALID') from None


def planner_policy(catalog, version=STABLE_PROMPT):
    return ticket_prompt(version).render(catalog)


def ticket_projection(result):
    return {key: result[key] for key in ('ticket_no', 'status', 'updated_at')}


def run_read_plan(text, policy, plan, read, *, max_turns, canonical_history):
    """Shared decision flow; the host supplies and authorizes the two callbacks."""
    messages = [dict(role='system', content=policy),
                dict(role='user', content=_json(dict(kind='untrusted_user_input', text=text)))]
    observed = {}
    for turn in range(max_turns):
        content = plan(turn, messages)
        action, value = decision(content)
        if action == 'clarify':
            return None
        if action == 'report':
            call = observed.get(value)
            if call is None or call.name != TICKET_READ[0]:
                raise PermanentFailure('REPORT_EVIDENCE_INVALID')
            # Re-read the observed ticket so the final report uses current proof.
            return read(value, call)
        slot = 'tool-' + str(turn)
        result = read(slot, value)
        observed[slot] = value
        assistant = (_json(dict(action='tool', call=dict(name=value.name, version=value.version, arguments=value.arguments)))
                     if canonical_history else content)
        projected = _json(dict(kind='untrusted_tool_result', slot=slot,
                          name=value.name, value=ticket_projection(result)))
        messages.extend([dict(role='assistant', content=assistant), dict(role='user', content=projected)])
    raise PermanentFailure('PLANNER_TURN_LIMIT')


class TicketReadLoop:
    def __init__(self, source_access: ToolExecutor, tools: ToolExecutor, model: RuntimeChatClient, *, max_turns=4,
                 canonical_history=False, transport_hash=None, approvals: TicketApprovals | None = None):
        if not isinstance(source_access, ToolExecutor) or not isinstance(tools, ToolExecutor) or not isinstance(model, RuntimeChatClient):
            raise ValueError('Explicit source, tool and model adapters required')
        if type(max_turns) is not int or not 1 <= max_turns <= 4:
            raise ValueError('Invalid planner turn limit')
        if type(canonical_history) is not bool:
            raise ValueError('Invalid planner history policy')
        if any((tool['name'], tool['version']) != TICKET_READ for tool in tools.registry.catalog()):
            raise ValueError('Ticket loop requires an explicit context projection for each business tool')
        if model.profile.provider not in ticket_prompt(model.profile.prompt_version).providers:
            raise ValueError('Ticket prompt is incompatible with the configured provider')
        self.source_access, self.tools, self.model, self.max_turns = source_access, tools, model, max_turns
        self.canonical_history = canonical_history
        self.transport_hash = transport_hash
        self.approvals = approvals

    def __call__(self, context):
        return dict(self.prepare(context).references)

    def prepare(self, context):
        if self.model.profile.tenant != context.lease.tenant:
            raise PermanentFailure('MODEL_PROFILE_DENIED')
        source = self.source_access.execute(
            context, 'source-input', ToolCall('get_current_message', 'source-v1', {}))
        if self.approvals is not None and source['text'].partition('\n')[0].removesuffix('\r') == '/ticket-request':
            return self._pause_explicit_proposal(context, source)
        catalog = self.tools.registry.catalog()
        prompt, model = self._bound_prompt(context)
        policy = prompt.render(catalog)
        terms = dict(loop=LOOP_VERSION, policy=policy, profile=model.profile.definition_hash(),
                     source_snapshot=source['snapshot_id'], source_hash=source['payload_hash'], max_turns=self.max_turns)
        if self.canonical_history:
            terms['history'] = 'canonical-tool-decisions-v1'
        if self.transport_hash is not None:
            terms['ticket_transport'] = self.transport_hash
        binding = fingerprint(terms)
        version = LOOP_VERSION if prompt.version == STABLE_PROMPT else prompt.version
        context.step('ticket-loop', 'INTERNAL', 'NONE', dict(operation='ticket.agent', version=version,
                                                             resource_ref=context.run['principal_ref'], input_ref=source['snapshot_id'], arguments_sha256=binding), lambda: {'outcome': 'bound'})

        def plan(turn, messages):
            # Evidence may have a newer authorized version after resume. A new
            # context gets a new MODEL reservation under the original budget;
            # the stable loop binding protects source/policy/catalog identity.
            key = 'plan:' + str(turn) + ':' + fingerprint(messages)[:40]
            return model.complete(context, key, messages).content

        ticket = run_read_plan(source['text'], policy, plan,
                               lambda slot, call: self.tools.execute(context, slot, call),
                               max_turns=self.max_turns, canonical_history=self.canonical_history)
        return self._report(context, source, ticket)

    def _bound_prompt(self, context):
        previous = context.prior_step_intent('ticket-loop')
        version = self.model.profile.prompt_version
        if previous is not None:
            if previous.get('operation') != 'ticket.agent':
                raise PermanentFailure('TICKET_PROMPT_BINDING_INVALID')
            version = previous.get('version')
            if version == LOOP_VERSION:
                version = STABLE_PROMPT
        try:
            prompt = ticket_prompt(version)
        except ValueError:
            raise PermanentFailure('TICKET_PROMPT_BINDING_INVALID') from None
        if self.model.profile.provider not in prompt.providers:
            raise PermanentFailure('TICKET_PROMPT_PROVIDER_UNSUPPORTED')
        return prompt, self.model.with_prompt_version(prompt.version)

    def _pause_explicit_proposal(self, context, source):
        try:
            _, title, content = source['text'].split('\n', 2)
            title = title.removesuffix('\r')
            if not title.strip() or not content.strip():
                raise ValueError()
            digest = fingerprint(dict(route=PROPOSAL_VERSION, source=source['payload_hash']))
            context.step('ticket-loop', 'INTERNAL', 'NONE',
                         dict(operation='ticket.propose', version=PROPOSAL_VERSION, resource_ref=context.run['principal_ref'],
                              input_ref=source['snapshot_id'], arguments_sha256=digest), lambda: {'outcome': 'bound'})
            self.approvals.pause(context, UUID(source['snapshot_id']), title, content, wait_seconds=300)
        except ValueError:
            raise PermanentFailure('TICKET_PROPOSAL_INVALID') from None

    @staticmethod
    def _report(context, source, ticket):
        if ticket is None:
            expected = dict(schema_version='ticket-report-v1', kind='clarification', source_snapshot_id=source['snapshot_id'],
                            source_hash=source['payload_hash'], text='请提供需要查询的工单编号。')
            refs = dict(outcome='clarification-ready', version='ticket-report-v1', input_ref=source['snapshot_id'])
        else:
            if (ticket.get('schema_version') != 'ticket-status-v1' or type(ticket.get('status')) is not int or ticket['status'] not in STATUS_NAMES
                    or ticket.get('snapshot_id') != source['snapshot_id'] or ticket.get('source_hash') != source['payload_hash']
                    or ticket.get('source_event_id') != str(context.run['source_event_id']) or ticket.get('run_id') != str(context.lease.run_id)):
                raise PermanentFailure('REPORT_EVIDENCE_INVALID')
            expected = dict(schema_version='ticket-report-v1', kind='ticket-status', source_snapshot_id=source['snapshot_id'],
                            source_hash=source['payload_hash'], ticket_no=ticket['ticket_no'], status=ticket['status'],
                            updated_at=ticket['updated_at'], resource_version=ticket['resource_version'],
                            text=f"工单 {ticket['ticket_no']} 当前状态：{STATUS_NAMES[ticket['status']]}。")
            refs = dict(outcome='ticket-status-ready', version='ticket-report-v1', input_ref=source['snapshot_id'],
                        resource_ref=ticket['ticket_no'], arguments_sha256=ticket['resource_version'])
        digest = fingerprint(expected)
        value = context.payload_step('report:' + digest[:48], 'INTERNAL', 'NONE',
                                     dict(operation='ticket.report', version='ticket-report-v1',
                                          input_ref=source['snapshot_id'], arguments_sha256=digest),
                                     'working-state-v1', lambda: expected)
        if fingerprint(value) != digest:
            raise PermanentFailure('REPORT_CHECKPOINT_INVALID')
        return PreparedReport(refs, expected)
