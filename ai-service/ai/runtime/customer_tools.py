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
