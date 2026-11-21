"""Internal durable admission/status/cancellation; acceptance is not execution success."""
from typing import Literal
from uuid import UUID

import psycopg
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, field_validator, model_validator

from ..config import get_settings
from ..runtime.store import RuntimeStore
from ..runtime.types import RunInput, Limits, RuntimeConflict, QueueFull, identifier, tenant_code, ActionDenied
from ..runtime.authority import sign as sign_authority

router = APIRouter(prefix='/ai/v1/runtime/runs', tags=['internal-runtime'])
V1_LIMITS = Limits(max_steps=12, max_model_calls=4, max_tool_calls=8, max_retries=2, wall_seconds=120)


class RunRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    tenant_code: StrictStr = Field(min_length=1, max_length=16)
    source_event_id: StrictStr = Field(min_length=36, max_length=36)
    session_no: StrictStr = Field(min_length=1, max_length=64)
    event_seq: StrictInt = Field(gt=0, le=9223372036854775807)
    message_id: StrictInt = Field(gt=0, le=9223372036854775807)
    principal_ref: StrictStr = Field(pattern=r'^visitor-session:[1-9][0-9]{0,18}$')
    trace_id: StrictStr = Field(min_length=1, max_length=64)
    runtime_version: Literal['runtime-v1'] = 'runtime-v1'

    @field_validator('tenant_code')
    @classmethod
    def trusted_tenant(cls, value):
        return tenant_code(value)

    @field_validator('source_event_id')
    @classmethod
    def canonical_uuid(cls, value):
        if str(UUID(value)) != value:
            raise ValueError('Canonical event UUID required')
        return value

    @field_validator('session_no', 'trace_id')
    @classmethod
    def opaque_id(cls, value):
        return identifier(value)


class EmptyRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')


class ActionRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    tenant_code: StrictStr = Field(min_length=1,max_length=16)
    source_event_id: StrictStr = Field(min_length=36,max_length=36)
    owner: StrictStr = Field(min_length=1,max_length=64)
    fence: StrictInt = Field(gt=0,le=9223372036854775807)
    snapshot_id: StrictStr = Field(min_length=36,max_length=36)
    arguments_sha256: StrictStr = Field(pattern=r'^[0-9a-f]{64}$')
    operation: Literal['session.reply','ticket.create'] = 'session.reply'
    version: Literal['reply-v1','ticket-create-v1'] = 'reply-v1'

    @model_validator(mode='after')
    def operation_version_pair(self):
        if (self.operation,self.version) not in (('session.reply','reply-v1'),('ticket.create','ticket-create-v1')):
            raise ValueError('Unsupported action operation/version pair')
        return self

    @field_validator('source_event_id','snapshot_id')
    @classmethod
    def canonical_uuid(cls,value):
        if str(UUID(value))!=value: raise ValueError('Canonical UUID required')
        return value

    @field_validator('owner')
    @classmethod
    def opaque_owner(cls,value): return identifier(value)

    @field_validator('tenant_code')
    @classmethod
    def trusted_tenant(cls,value): return tenant_code(value)


def runtime_store():
    if not get_settings().runtime_api_enabled:
        raise HTTPException(503, 'Runtime receiver is disabled')
    return RuntimeStore()


def status(row):
    if row is None:
        raise HTTPException(404, 'Run not found')
    return {key: row[key] for key in ('tenant_code', 'run_id', 'source_event_id', 'state', 'attempt',
                                     'step_count', 'model_calls', 'tool_calls', 'retry_count', 'error_code')}


def database_operation(action):
    try:
        return status(action())
    except QueueFull:
        raise HTTPException(429, 'Runtime backlog is full', headers={'Retry-After': '1'}) from None
    except RuntimeConflict:
        raise HTTPException(409, 'Runtime event conflicts with persisted input') from None
    except psycopg.Error:
        raise HTTPException(503, 'Runtime persistence unavailable') from None


@router.post('', status_code=202)
def enqueue(body: RunRequest, request: Request, store=Depends(runtime_store)):
    # Header tenant is established only by the ASGI authentication layer.
    tenant = request.state.runtime_tenant
    if body.tenant_code != tenant:
        raise HTTPException(403, 'Runtime tenant mismatch')
    value = RunInput(tenant, UUID(body.source_event_id), body.session_no, body.event_seq, body.message_id,
                     body.principal_ref, body.trace_id, body.runtime_version)
    return database_operation(lambda: store.enqueue(value, V1_LIMITS))


@router.get('/{run_id}')
def read(run_id: UUID, request: Request, store=Depends(runtime_store)):
    return database_operation(lambda: store.get(request.state.runtime_tenant, run_id))


@router.post('/{run_id}/cancel')
def cancel(run_id: UUID, body: EmptyRequest, request: Request, store=Depends(runtime_store)):
    return database_operation(lambda: store.cancel(request.state.runtime_tenant, run_id))


@router.post('/{run_id}/authorize')
def authorize(run_id: UUID, body: ActionRequest, request: Request, store=Depends(runtime_store)):
    tenant=request.state.runtime_tenant
    if body.tenant_code!=tenant: raise HTTPException(403,'Runtime tenant mismatch')
    if body.operation=='ticket.create' and not get_settings().runtime_ticket_actions_enabled:
        raise HTTPException(503,'Ticket action authority is disabled')
    try:
        authorize=store.authorize_reply if body.operation=='session.reply' else store.authorize_ticket_create
        grant=authorize(tenant,run_id,body.source_event_id,body.owner,body.fence,body.snapshot_id,body.arguments_sha256)
        if grant is None: raise HTTPException(404,'Run not found')
        return sign_authority(grant,get_settings().customer_secret.get_secret_value())
    except ActionDenied:
        raise HTTPException(409,'Runtime action is not authorized') from None
    except psycopg.Error:
        raise HTTPException(503,'Runtime authority unavailable') from None
