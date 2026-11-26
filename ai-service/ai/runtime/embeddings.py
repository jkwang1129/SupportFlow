"""One budgeted query embedding; private vectors are atomically checkpointed."""
import asyncio
from dataclasses import dataclass, field
import math
from time import monotonic

from ..core.chat_transport import ModelCallFailure, _configuration, _send_embedding_json
from .model_usage import checkpoint_usage
from .types import tenant_code, identifier, fingerprint
from .worker import PermanentFailure, TransientFailure

DIMENSIONS = 1024


@dataclass(frozen=True)
class EmbeddingProfile:
    tenant: str
    profile_ref: str
    provider: str
    model: str
    base_url: str
    api_key: str = field(repr=False)
    query_version: str = 'embedding-query-v1'
    timeout_seconds: float = 10

    def __post_init__(self):
        tenant_code(self.tenant)
        for value, limit in ((self.profile_ref, 64), (self.provider, 32), (self.model, 128), (self.query_version, 32)):
            identifier(value, limit)
        if type(self.timeout_seconds) not in (int, float) or not .05 <= self.timeout_seconds <= 30:
            raise ValueError('Invalid embedding deadline')
        if not isinstance(self.api_key, str) or len(self.api_key) < 16:
            raise ValueError('Embedding credential is not configured')
        try:
            _configuration(self.chosen(), self.timeout_seconds)
        except ModelCallFailure:
            raise ValueError('Invalid embedding profile') from None

    def chosen(self): return dict(provider=self.provider, model=self.model,
                                  base_url=self.base_url, api_key=self.api_key)

    def definition_hash(self):
        return fingerprint(dict(tenant=self.tenant, profile=self.profile_ref, provider=self.provider, model=self.model,
                                endpoint_sha256=fingerprint(self.base_url), dimensions=DIMENSIONS, query_version=self.query_version, timeout_seconds=self.timeout_seconds))


@dataclass(frozen=True)
class EmbeddingResult:
    vector: tuple[float, ...] = field(repr=False)
    profile_ref: str
    query_version: str
    request_hash: str
    input_tokens: int | None


class RuntimeEmbeddingClient:
    def __init__(self, profile: EmbeddingProfile, *, transport=None):
        if not isinstance(profile, EmbeddingProfile):
            raise ValueError('Explicit host embedding profile required')
        self.profile, self._transport = profile, transport

    @staticmethod
    def _vector(value):
        if type(value) is not list or len(value) != DIMENSIONS:
            raise ValueError()
        if any(type(item) not in (int, float) or not math.isfinite(item) or abs(item) > 1e6 for item in value):
            raise ValueError()
        vector = tuple(float(item) for item in value)
        # Existing to_pgvector emits seven decimal places; a vector which turns
        # entirely zero there cannot support the existing cosine search.
        if not any(float(format(item, '.7f')) != 0 for item in vector):
            raise ValueError()
        return vector

    @staticmethod
    def _usage(usage):
        if usage is None:
            return dict(available=False)
        if type(usage) is not dict:
            raise ValueError()
        if 'prompt_tokens' not in usage or 'total_tokens' not in usage:
            return dict(available=False)
        a, b = usage['prompt_tokens'], usage['total_tokens']
        if type(a) is not int or type(b) is not int or not 0 <= a <= 9223372036854775807 or a != b:
            raise ValueError()
        return dict(available=True, input_tokens=a)

    def _result(self, value, digest):
        try:
            if (type(value) is not dict or set(value) != {'schema_version', 'vector', 'usage', 'profile_ref', 'profile_hash', 'query_version', 'request_hash'}
                    or value['schema_version'] != 'embedding-result-v1' or value['profile_ref'] != self.profile.profile_ref
                    or value['profile_hash'] != self.profile.definition_hash() or value['query_version'] != self.profile.query_version
                    or value['request_hash'] != digest):
                raise ValueError()
            vector = self._vector(value['vector'])
            tokens, _ = checkpoint_usage(value['usage'], chat=False)
            return EmbeddingResult(vector, self.profile.profile_ref, self.profile.query_version, digest, tokens)
        except (ValueError, TypeError, KeyError, OverflowError):
            raise PermanentFailure('EMBEDDING_CHECKPOINT_INVALID') from None

    def embed(self, context, key, text, *, expires_at=None):
        if expires_at is not None and (type(expires_at) not in (int, float) or not math.isfinite(expires_at)):
            raise ValueError('Invalid host embedding deadline')

        def network_timeout():
            remaining = self.profile.timeout_seconds if expires_at is None else min(
                self.profile.timeout_seconds, expires_at - monotonic())
            if remaining < .05:
                raise TransientFailure('MODEL_TIMEOUT')
            return remaining
        network_timeout()  # Do not reserve a model for an already expired call.
        if (self.profile.tenant != context.lease.tenant or context.run['tenant_code'] != context.lease.tenant
                or context.run['run_id'] != context.lease.run_id):
            raise PermanentFailure('MODEL_PROFILE_DENIED')
        try:
            if (type(text) is not str or not text.strip() or '\0' in text or len(text.encode('utf-8')) > 4096
                    or self.profile.api_key in text):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise PermanentFailure('EMBEDDING_INPUT_INVALID') from None
        payload = dict(model=self.profile.model, input=[text], encoding_format='float', dimensions=DIMENSIONS)
        digest = fingerprint(payload)
        intent = dict(operation='model.embedding', model=self.profile.profile_ref, version=self.profile.query_version,
                      input_ref=self.profile.definition_hash(), arguments_sha256=digest)

        def execute():
            try:
                data = asyncio.run(_send_embedding_json(self.profile.chosen(), payload,
                                   network_timeout(), transport=self._transport))
            except ModelCallFailure as error:
                failure = TransientFailure if error.retryable else PermanentFailure
                raise failure(error.code) from None
            try:
                if data.get('object') != 'list' or data.get('model') != self.profile.model:
                    raise ValueError()
                items = data['data']
                if type(items) is not list or len(items) != 1 or type(items[0]) is not dict:
                    raise ValueError()
                item = items[0]
                if item.get('object') != 'embedding' or type(item.get('index')) is not int or item['index'] != 0:
                    raise ValueError()
                vector = self._vector(item['embedding'])
                usage = self._usage(data.get('usage'))
            except (ValueError, TypeError, KeyError, OverflowError):
                raise PermanentFailure('EMBEDDING_OUTPUT_INVALID') from None
            value = dict(schema_version='embedding-result-v1', vector=list(vector), usage=usage, profile_ref=self.profile.profile_ref,
                         profile_hash=self.profile.definition_hash(), query_version=self.profile.query_version, request_hash=digest)
            self._result(value, digest)
            return value
        value = context.payload_step(key, 'MODEL', 'NONE', intent, 'model-output-v1', execute)
        return self._result(value, digest)
