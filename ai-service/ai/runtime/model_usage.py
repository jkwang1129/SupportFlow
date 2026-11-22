"""Extract provider usage from an atomic model checkpoint."""
from .payloads import decode
from .types import RuntimeConflict, fingerprint


def checkpoint_usage(usage, *, chat):
    if type(usage) is not dict or type(usage.get('available')) is not bool:
        raise ValueError('Invalid checkpoint usage')
    if not usage['available']:
        if set(usage) != {'available'}:
            raise ValueError('Invalid absent usage')
        return None, None
    fields = ('input_tokens', 'output_tokens') if chat else ('input_tokens',)
    if set(usage) != {'available', *fields} or any(
            type(usage[field]) is not int or not 0 <= usage[field] <= 9223372036854775807 for field in fields):
        raise ValueError('Invalid checkpoint token counts')
    return usage['input_tokens'], usage.get('output_tokens')


def _step_usage(step):
    if step['payload_id'] is None:
        return None, None
    schema = 'model-output-v1'
    intent = step['intent_ref']
    operation = intent['operation']
    chat = operation == 'model.chat'
    if (operation not in ('model.chat', 'model.embedding') or step['state'] != 'SUCCEEDED' or step['effect'] != 'NONE'
            or step['payload_schema'] != schema or step['fence'] != step['payload_fence']
            or step['result_ref'] != {'resource_ref': 'payload:' + str(step['payload_id'])}
            or step['intent_hash'] != fingerprint(dict(kind='MODEL', effect='NONE', intent=intent))):
        raise ValueError('Invalid model step binding')
    value = decode(schema, step['payload_json'], step['payload_hash'])
    version_field = 'prompt_version' if chat else 'query_version'
    if (value['schema_version'] != ('chat-result-v1' if chat else 'embedding-result-v1')
            or value[version_field] != intent['version'] or value['profile_ref'] != intent['model']
            or value['profile_hash'] != intent['input_ref'] or value['request_hash'] != intent['arguments_sha256']):
        raise ValueError('Invalid model checkpoint binding')
    return checkpoint_usage(value['usage'], chat=chat)


def summarize(run, steps):
    attempts = run['model_calls']
    if attempts != len(steps) or len(steps) > 100:
        raise RuntimeConflict('Runtime model reservations are inconsistent')
    inputs, outputs = [], []
    try:
        for step in steps:
            tokens_in, tokens_out = _step_usage(step)
            if tokens_in is not None:
                inputs.append(tokens_in)
            if tokens_out is not None:
                outputs.append(tokens_out)
    except (ValueError, TypeError, KeyError, RecursionError):
        raise RuntimeConflict('Runtime model usage checkpoint is invalid') from None
    return {
        'schema_version': 'runtime-model-usage-v1',
        'run_id': str(run['run_id']),
        'model_attempts_reserved': attempts,
        'attempts_with_observed_usage': len(inputs),
        'attempts_without_observed_usage': attempts - len(inputs),
        'observed_input_tokens': sum(inputs) if inputs else None,
        'observed_chat_output_tokens': sum(outputs) if outputs else None,
    }
