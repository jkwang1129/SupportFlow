"""Explicit host-registered internal ticket creation; no model Loop or default routing."""
import asyncio
from typing import Literal
from uuid import UUID

from pydantic import Field,field_validator

from .source import SourceClient
from .ticket_creation import TicketCreationClient,arguments_hash,receipt_reference,_text
from .tools import ReadAuthorization,ToolModel,WriteTool
from .types import fingerprint


class TicketCreateArguments(ToolModel):
    title: str=Field(max_length=128,repr=False)
    content: str=Field(max_length=8192,repr=False)

    @field_validator('title')
    @classmethod
    def title_bound(cls,value):_text(value,128,512);return value

    @field_validator('content')
    @classmethod
    def body_bound(cls,value):_text(value,8192,8192);return value


class TicketCreateResult(ToolModel):
    operation: Literal['ticket.create']
    version: Literal['ticket-create-v1']
    outcome: Literal['ticket-committed']
    business_ref: str=Field(pattern=r'^ticket:[1-9][0-9]{0,18}$')
    resource_ref: str=Field(pattern=r'^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')
    input_ref: str=Field(pattern=r'^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$')
    arguments_sha256: str=Field(pattern=r'^[0-9a-f]{64}$')

    @field_validator('business_ref')
    @classmethod
    def id_bound(cls,value):
        if int(value.removeprefix('ticket:'))>9223372036854775807:raise ValueError('Invalid ticket reference')
        return value


def ticket_create_tool(source_client:SourceClient,client:TicketCreationClient,*,timeout_seconds=8):
    if (not isinstance(source_client,SourceClient) or not isinstance(client,TicketCreationClient)
            or source_client.base_url!=client.base_url or source_client._secret!=client._secret):
        raise ValueError('Matching configured source and ticket clients required')

    def bounded(operation,deadline):
        async def call():
            async with asyncio.timeout(deadline.remaining()):return await operation()
        return asyncio.run(call())

    def authorize(identity,arguments,deadline):
        snapshot=bounded(lambda:source_client.capture(identity.source,identity.run_id),deadline)
        return ReadAuthorization(dict(resource_ref=identity.source.principal_ref,input_ref=str(snapshot.snapshot_id),
            version='source-v1',arguments_sha256=snapshot.payload_hash))

    def intent(identity,arguments,input_ref):
        snapshot=UUID(input_ref)
        if str(snapshot)!=input_ref:raise ValueError('Invalid ticket source snapshot')
        digest=arguments_hash(identity.source,identity.run_id,snapshot,arguments['title'],arguments['content'])
        return dict(operation='ticket.create',version='ticket-create-v1',resource_ref=identity.source.principal_ref,input_ref=input_ref,arguments_sha256=digest)

    def invoke(identity,arguments,intent,lease,deadline):
        snapshot=UUID(intent['input_ref'])
        result=bounded(lambda:client.create(identity.source,lease,snapshot,arguments['title'],arguments['content']),deadline)
        return receipt_reference(result,identity.source,identity.run_id,snapshot,intent['arguments_sha256'])

    def confirm(identity,arguments,intent,deadline):
        snapshot=UUID(intent['input_ref'])
        result=bounded(lambda:client.receipt(identity.source,identity.run_id,expected_snapshot_id=snapshot,expected_hash=intent['arguments_sha256']),deadline)
        return None if result is None else receipt_reference(result,identity.source,identity.run_id,snapshot,intent['arguments_sha256'])

    def verify(identity,arguments,result,intent):
        return (result['operation']==intent['operation'] and result['version']==intent['version']
            and result['input_ref']==intent['input_ref'] and result['arguments_sha256']==intent['arguments_sha256'])

    policy=fingerprint(dict(policy='internal-unassigned-ticket-v1',customer_origin=client.base_url,timeout_seconds=timeout_seconds,
        arguments=TicketCreateArguments.model_json_schema(),result=TicketCreateResult.model_json_schema()))
    return WriteTool('create_ticket','ticket-create-v1','ticket:create','ticket.create',policy,TicketCreateArguments,TicketCreateResult,
        authorize,intent,invoke,confirm,verify,timeout_seconds)
