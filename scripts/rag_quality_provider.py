"""Explicit, bounded live generation for the synthetic RAG benchmark."""
import json
import os
from time import perf_counter

from dotenv import dotenv_values

from ai.core.chat_transport import ModelCallFailure, _model_client, _send_embedding_json
from ai.core.llm_chat import call_chat, message_content, provider_conf
from ai.rag.embedding import BATCH_SIZE, EMBEDDING_DIM, _batch_vectors
from rag_quality_dataset import canonical_digest

BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
MODEL = 'qwen-plus'
MAX_CALLS = 50
MAX_OUTPUT_TOKENS = 512
MAX_REQUEST_BYTES = 12_000
TIMEOUT_SECONDS = 25
EMBEDDING_MODEL = 'text-embedding-v3'
MAX_EMBEDDING_CALLS = 10
MAX_EMBEDDING_REQUEST_BYTES = 8_000


def qwen_api_key(path):
    key_name = 'SUPPORTFLOW_AI_QWEN_API_KEY'
    api_key = os.environ.get(key_name) or (dotenv_values(path, interpolate=False).get(key_name) if path.is_file() else None)
    if not api_key or api_key.startswith('${') or not api_key.isascii() or any(char.isspace() for char in api_key):
        raise ValueError(f'Configure {key_name} in the environment or the ignored AI configuration file')
    return api_key


def provider_usage(data):
    usage = data.get('usage')
    if not isinstance(usage, dict):
        return None
    names = ('prompt_tokens', 'completion_tokens', 'total_tokens')
    values = [usage.get(key) for key in names]
    if any(type(value) is not int or value < 0 for value in values) or values[0] + values[1] != values[2]:
        return None
    return dict(zip(names, values))


class QwenGeneration:
    def __init__(self, api_key):
        self.chosen = provider_conf('qwen', api_key, BASE_URL, MODEL)
        self.attempts = 0
        self.stopped = False

    @classmethod
    def from_local_config(cls, path):
        return cls(qwen_api_key(path))

    def configuration(self):
        return {'provider': 'qwen', 'model': MODEL, 'base_url': BASE_URL, 'max_calls': MAX_CALLS,
                'max_output_tokens': MAX_OUTPUT_TOKENS, 'max_request_bytes': MAX_REQUEST_BYTES,
                'timeout_seconds': TIMEOUT_SECONDS, 'attempts': self.attempts,
                'temperature': 0.2, 'enable_thinking': False, 'repeats_per_profile': 1}

    async def generate(self, payload, receipt, *, scene='RAG评测'):
        payload = {**payload, 'max_tokens': MAX_OUTPUT_TOKENS, 'enable_thinking': False}
        if self.stopped or self.attempts >= MAX_CALLS:
            raise ModelCallFailure('EVAL_PROVIDER_STOPPED', attempted=False)
        if len(json.dumps(payload, ensure_ascii=False).encode('utf-8')) > MAX_REQUEST_BYTES:
            raise ModelCallFailure('EVAL_INPUT_BUDGET_EXCEEDED', attempted=False)
        self.attempts += 1
        receipt.update(attempted=True, request_sha256=canonical_digest(payload))
        started = perf_counter()
        try:
            data = await call_chat(self.chosen, payload, TIMEOUT_SECONDS, scene=scene, meter_usage=False)
        except ModelCallFailure as error:
            if not error.attempted:
                self.attempts -= 1
            self.stopped = True
            receipt.update(attempted=error.attempted, http_status=error.status_code)
            raise
        finally:
            receipt['latency_ms'] = round((perf_counter() - started) * 1000, 3)
        choices = data.get('choices')
        first = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
        receipt.update(usage=provider_usage(data), request_id=data.get('id'), returned_model=data.get('model'),
                       finish_reason=first.get('finish_reason'))
        if first.get('finish_reason') == 'length':
            raise ModelCallFailure('EVAL_GENERATION_TRUNCATED')
        answer = message_content(data)
        if not answer:
            raise ModelCallFailure('EVAL_EMPTY_GENERATION')
        receipt['response_text'] = answer
        return data


async def encode_qwen_groups(groups, api_key):
    """Make one bounded embedding pass for predeclared synthetic document/query groups."""
    batches = [(name, texts[start:start + BATCH_SIZE]) for name, texts in groups.items()
               for start in range(0, len(texts), BATCH_SIZE)]
    if len(batches) > MAX_EMBEDDING_CALLS:
        raise ValueError('Embedding request count exceeds the frozen evaluation budget')
    requests = [(name, {'model': EMBEDDING_MODEL, 'input': texts, 'dimensions': EMBEDDING_DIM})
                for name, texts in batches]
    if any(len(json.dumps(body, ensure_ascii=False).encode('utf-8')) > MAX_EMBEDDING_REQUEST_BYTES for _, body in requests):
        raise ValueError('Embedding payload exceeds the frozen evaluation budget')
    chosen = provider_conf('qwen', api_key, BASE_URL, EMBEDDING_MODEL)
    vectors = {name: [] for name in groups}
    receipts = []
    async with _model_client(TIMEOUT_SECONDS) as client:
        for name, body in requests:
            started = perf_counter()
            data = await _send_embedding_json(chosen, body, TIMEOUT_SECONDS, client=client)
            batch = _batch_vectors(data, len(body['input']))
            if any(not any(vector) for vector in batch):
                raise ModelCallFailure('EMBEDDING_OUTPUT_INVALID')
            vectors[name].extend(batch)
            usage = data.get('usage')
            tokens = usage.get('total_tokens') if isinstance(usage, dict) else None
            receipts.append({'group': name, 'texts': len(batch), 'duration_ms': round((perf_counter() - started) * 1000, 3),
                             'total_tokens': tokens if type(tokens) is int and tokens >= 0 else None,
                             'request_sha256': canonical_digest(body), 'vector_sha256': canonical_digest(batch)})
    return vectors, receipts
