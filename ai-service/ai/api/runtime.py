"""Internal durable admission/status/cancellation; acceptance is not execution success."""
from typing import Literal
from uuid import UUID

import psycopg
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, field_validator

from ..config import get_settings
from ..runtime.store import RuntimeStore
from ..runtime.types import RunInput, Limits, RuntimeConflict, QueueFull, identifier, tenant_code

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
