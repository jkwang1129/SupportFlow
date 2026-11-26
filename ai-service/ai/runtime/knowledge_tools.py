"""Per-run live knowledge adapter. Reuse query vectors, never knowledge permission."""
import asyncio
import hashlib
import hmac
import json
import re
from typing import Annotated, Literal
from uuid import UUID, uuid4

from pydantic import Field, field_validator

from .customer_tools import UUID_PATTERN
from .embeddings import RuntimeEmbeddingClient
from .knowledge import KnowledgeClient, MODEL_PATTERN, POLICY
from .source import SourceClient
from .tools import ToolModel, ToolIdentity, ReadAuthorization, ReadTool
from .types import fingerprint, RuntimeConflict
from .worker import ExecutionContext, PermanentFailure

VERSION = 'knowledge-live-v1'
SHA = r'^[0-9a-f]{64}$'


class KnowledgeArguments(ToolModel):
    query: str = Field(min_length=1, max_length=4096, repr=False)
    top_k: int = Field(default=5, ge=1, le=5)
    doc_ids: list[Annotated[int, Field(ge=1, le=9223372036854775807)]] | None = Field(default=None, max_length=200)

    @field_validator('query')
    @classmethod
    def bounded_query(cls, value):
        if not value.strip() or '\0' in value or len(value.encode('utf-8')) > 4096:
            raise ValueError('Invalid knowledge query')
        return value

    @field_validator('doc_ids')
    @classmethod
    def scope(cls, value): return None if value is None else list(dict.fromkeys(value))


class KnowledgeHitResult(ToolModel):
    doc_id: str = Field(pattern=r'^[1-9][0-9]{0,18}$')
    chunk_id: str = Field(pattern=r'^[1-9][0-9]{0,18}$')
    title: str = Field(max_length=200, repr=False)
    snippet: str = Field(min_length=1, max_length=1024, repr=False)
    score_ppm: int = Field(ge=-1000000, le=1000000)
    embedding_model: str = Field(pattern='^' + MODEL_PATTERN + '$')


class KnowledgeToolResult(ToolModel):
    schema_version: Literal['knowledge-tool-v1']
    mode: Literal['vector', 'empty-scope']
    policy_version: Literal['visitor-published-v1']
    source_event_id: str = Field(pattern=UUID_PATTERN)
    run_id: str = Field(pattern=UUID_PATTERN)
    snapshot_id: str = Field(pattern=UUID_PATTERN)
    source_hash: str = Field(pattern=SHA)
    query_sha256: str = Field(pattern=SHA)
    embedding_model: str = Field(pattern='^' + MODEL_PATTERN + '$')
    request_sha256: str | None = Field(pattern=SHA)
    resource_version: str | None = Field(pattern=SHA)
    hits: list[KnowledgeHitResult] = Field(max_length=5, repr=False)


def knowledge_search_tool(context, source_client, knowledge_client, embedding_client, *, timeout_seconds=10):
    if (not isinstance(context, ExecutionContext) or not isinstance(source_client, SourceClient)
            or not isinstance(knowledge_client, KnowledgeClient) or not isinstance(embedding_client, RuntimeEmbeddingClient)
            or source_client.base_url != knowledge_client.base_url
            or not hmac.compare_digest(source_client._secret.encode(), knowledge_client._secret.encode())):
        raise ValueError('Per-run context and configured same-service clients required')
    identity = ToolIdentity.from_context(context)
    profile = embedding_client.profile
    if profile.tenant != identity.source.tenant:
        raise PermanentFailure('MODEL_PROFILE_DENIED')
    label = profile.provider + ':' + profile.model
    # Enforce the existing Java indexing-label contract before creating a tool.
    if not re.fullmatch(MODEL_PATTERN, label):
        raise ValueError('Incompatible knowledge embedding label')
    policy_hash = fingerprint(dict(profile=profile.definition_hash(), service=fingerprint(source_client.base_url),
                                   policy=POLICY, tool_version=VERSION, timeout_seconds=timeout_seconds))
    authorized = None

    def check(proposed):
        if proposed != identity:
            raise PermanentFailure('TOOL_IDENTITY_INVALID')
        context.check_active()

    def load(operation, deadline):
        async def bounded():
            async with asyncio.timeout(deadline.remaining()):
                return await operation()
        return asyncio.run(bounded())

    def authorize(proposed, arguments, deadline):
        nonlocal authorized
        authorized = None
        check(proposed)
        if profile.api_key in arguments['query'] or source_client._secret in arguments['query']:
            raise PermanentFailure('EMBEDDING_INPUT_INVALID')
        try:
            context.step('knowledge-policy-v1', 'INTERNAL', 'NONE', dict(operation='knowledge.policy', version=VERSION,
                                                                         model=profile.profile_ref, arguments_sha256=policy_hash), lambda: {'outcome': 'bound'})
        except RuntimeConflict:
            raise PermanentFailure('KNOWLEDGE_POLICY_CHANGED') from None
        snapshot = load(lambda: source_client.capture(proposed.source, proposed.run_id), deadline)
        authorized = (snapshot.snapshot_id, snapshot.payload_hash)
        return ReadAuthorization(dict(resource_ref=str(snapshot.snapshot_id), input_ref=snapshot.payload_hash, version=VERSION,
                                      business_ref=policy_hash, arguments_sha256=fingerprint(uuid4().hex)))

    def invoke(proposed, arguments, deadline):
        nonlocal authorized
        check(proposed)
        proof = authorized
        authorized = None
        if proof is None:
            raise PermanentFailure('TOOL_RESOURCE_DENIED')
        snapshot, source_hash = proof
        query_hash = hashlib.sha256(arguments['query'].encode()).hexdigest()
        if arguments['doc_ids'] == []:
            # No fabricated vector/Java request/version or supplier usage.
            return dict(schema_version='knowledge-tool-v1', mode='empty-scope', policy_version=POLICY,
                        source_event_id=str(proposed.source.source_event_id), run_id=str(proposed.run_id), snapshot_id=str(snapshot),
                        source_hash=source_hash, query_sha256=query_hash, embedding_model=label, request_sha256=None, resource_version=None, hits=[])
        deadline.remaining()
        value = embedding_client.embed(context, 'knowledge-embedding:' +
                                       query_hash[:40], arguments['query'], expires_at=deadline.expires)
        check(proposed)
        deadline.remaining()
        result = load(lambda: knowledge_client.search(proposed.source, proposed.run_id, snapshot_id=snapshot, source_hash=source_hash,
                                                      query_sha256=query_hash, embedding_model=label, vector=value.vector, top_k=arguments['top_k'], doc_ids=arguments['doc_ids']), deadline)
        return result.result() | dict(schema_version='knowledge-tool-v1', mode='vector')

    def verify(proposed, arguments, result, grant):
        proof = grant.references
        if (proposed != identity or proof.get('version') != VERSION or proof.get('business_ref') != policy_hash
                or result['source_event_id'] != str(proposed.source.source_event_id) or result['run_id'] != str(proposed.run_id)
                or result['snapshot_id'] != proof.get('resource_ref') or result['source_hash'] != proof.get('input_ref')
                or result['query_sha256'] != hashlib.sha256(arguments['query'].encode()).hexdigest() or result['embedding_model'] != label):
            return False
        if result['mode'] == 'empty-scope':
            return arguments['doc_ids'] == [] and not result['hits'] and result['request_sha256'] is None and result['resource_version'] is None
        if arguments['doc_ids'] == [] or result['request_sha256'] is None or result['resource_version'] is None:
            return False
        wire = {key: value for key, value in result.items() if key != 'mode'} | dict(
            schema_version='knowledge-search-v1', tenant_code=proposed.source.tenant)
        # Same strict projection verifier for live and atomically stored bodies.
        KnowledgeClient._decode(200, json.dumps(dict(code=0, message='', data=wire), ensure_ascii=False).encode(), proposed.source,
                                proposed.run_id, UUID(
                                    proof['resource_ref']), proof['input_ref'], result['request_sha256'], result['query_sha256'], label,
                                arguments['top_k'], arguments['doc_ids'])
        return True

    return ReadTool('search_knowledge', VERSION, 'knowledge:read', KnowledgeArguments, KnowledgeToolResult,
                    authorize, invoke, verify, timeout_seconds, cache_policy='current-version')
