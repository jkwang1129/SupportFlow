"""Read adapters for existing authenticated customer APIs; no arbitrary URL/JWT.

These are synchronous Worker adapters. Each network operation creates the
existing SourceClient's short-lived HTTPX client and enforces an outer deadline.
"""
import asyncio
import hashlib
from typing import Literal

from pydantic import Field, field_validator

from .source import SourceClient
from .tools import ReadAuthorization, ReadTool, ToolModel
from .tickets import TicketClient, ticket_digest


UUID_PATTERN = r'^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$'


class CurrentMessageArguments(ToolModel):
    pass  # The model cannot select another session/event/customer.


class CurrentMessageResult(ToolModel):
    schema_version: Literal['current-message-v1']
    source_event_id: str = Field(pattern=UUID_PATTERN)
    run_id: str = Field(pattern=UUID_PATTERN)
    snapshot_id: str = Field(pattern=UUID_PATTERN)
    payload_hash: str = Field(pattern=r'^[0-9a-f]{64}$')
    role: Literal['user']
    text: str = Field(min_length=1, max_length=8192, repr=False)

    @field_validator('text')
    @classmethod
    def bounded_text(cls, value):
        if not value.strip() or len(value.encode('utf-8')) > 8192:
            raise ValueError('Invalid source text')
        return value


def current_message_tool(client: SourceClient, *, timeout_seconds=3):
    if not isinstance(client, SourceClient):
        raise ValueError('Configured authenticated SourceClient required')

    def load(operation, identity, deadline):
        async def bounded():
            async with asyncio.timeout(deadline.remaining()):
                return await operation(identity.source, identity.run_id)
        return asyncio.run(bounded())

    def authorize(identity, arguments, deadline):
        # Capture is an idempotent immutable snapshot of this exact source.
        # It rechecks current session/owner/source before returning any body.
        snapshot = load(client.capture, identity, deadline)
        return ReadAuthorization({'resource_ref': str(snapshot.snapshot_id), 'input_ref': snapshot.payload_hash,
                                  'version': 'source-v1', 'arguments_sha256': hashlib.sha256(snapshot.text.encode()).hexdigest()})

    def invoke(identity, arguments, deadline):
        snapshot = load(client.read, identity, deadline)
        return dict(schema_version='current-message-v1', source_event_id=str(identity.source.source_event_id),
                    run_id=str(identity.run_id), snapshot_id=str(snapshot.snapshot_id), payload_hash=snapshot.payload_hash,
                    role='user', text=snapshot.text)

    def verify(identity, arguments, result, grant):
        proof = grant.references
        return (result['source_event_id'] == str(identity.source.source_event_id)
                and result['run_id'] == str(identity.run_id)
                and result['snapshot_id'] == proof.get('resource_ref')
                and result['payload_hash'] == proof.get('input_ref') and proof.get('version') == 'source-v1'
                and hashlib.sha256(result['text'].encode()).hexdigest() == proof.get('arguments_sha256'))

    return ReadTool('get_current_message', 'source-v1', 'session:read', CurrentMessageArguments,
                    CurrentMessageResult, authorize, invoke, verify, timeout_seconds)


class TicketArguments(ToolModel):
    ticket_no: str = Field(pattern=r'^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')


class TicketResult(ToolModel):
    schema_version: Literal['ticket-status-v1']
    source_event_id: str = Field(pattern=UUID_PATTERN)
    run_id: str = Field(pattern=UUID_PATTERN)
    snapshot_id: str = Field(pattern=UUID_PATTERN)
    source_hash: str = Field(pattern=r'^[0-9a-f]{64}$')
    ticket_no: str = Field(pattern=r'^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')
    status: int = Field(ge=1,le=5)
    updated_at: str = Field(max_length=26)
    resource_version: str = Field(pattern=r'^[0-9a-f]{64}$')


def ticket_status_tool(client: TicketClient, *, timeout_seconds=3):
    if not isinstance(client,TicketClient):
        raise ValueError('Configured authenticated TicketClient required')

    def load(identity,arguments,deadline):
        async def bounded():
            async with asyncio.timeout(deadline.remaining()):
                return await client.read(identity.source,identity.run_id,arguments['ticket_no'])
        return asyncio.run(bounded())

    def authorize(identity,arguments,deadline):
        value=load(identity,arguments,deadline)
        return ReadAuthorization({'resource_ref':value.ticket_no,'input_ref':str(value.snapshot_id),
                                  'version':'ticket-status-v1','arguments_sha256':value.resource_version,'business_ref':value.source_hash})

    def invoke(identity,arguments,deadline):
        return load(identity,arguments,deadline).result()

    def verify(identity,arguments,result,grant):
        proof=grant.references
        return (result['source_event_id']==str(identity.source.source_event_id) and result['run_id']==str(identity.run_id)
                and result['ticket_no']==arguments['ticket_no']==proof.get('resource_ref')
                and result['snapshot_id']==proof.get('input_ref') and result['source_hash']==proof.get('business_ref')
                and proof.get('version')=='ticket-status-v1' and result['resource_version']==proof.get('arguments_sha256')
                and ticket_digest(identity.source,identity.run_id,result['snapshot_id'],result['source_hash'],result['ticket_no'],
                                  result['status'],result['updated_at'])==result['resource_version'])

    return ReadTool('get_ticket_status','ticket-status-v2','ticket:read',TicketArguments,TicketResult,
                    authorize,invoke,verify,timeout_seconds,cache_policy='current-version')
