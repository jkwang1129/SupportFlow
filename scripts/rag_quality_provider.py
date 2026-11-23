"""Explicit, bounded live generation for the synthetic RAG benchmark."""
import json
import os
from time import perf_counter

from dotenv import dotenv_values

from ai.core.chat_transport import ModelCallFailure
from ai.core.llm_chat import call_chat, message_content, provider_conf
from rag_quality_dataset import canonical_digest

BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
MODEL = 'qwen-plus'
MAX_CALLS = 50
MAX_OUTPUT_TOKENS = 512
MAX_REQUEST_BYTES = 12_000
TIMEOUT_SECONDS = 25


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
        key_name = 'SUPPORTFLOW_AI_QWEN_API_KEY'
        api_key = os.environ.get(key_name) or (dotenv_values(path, interpolate=False).get(key_name) if path.is_file() else None)
        if not api_key or api_key.startswith('${') or not api_key.isascii() or any(char.isspace() for char in api_key):
            raise ValueError(f'Configure {key_name} in the environment or the ignored AI configuration file')
        return cls(api_key)

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
