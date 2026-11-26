"""Extract and verify provider usage evidence for a durable model step."""
from .payloads import decode
from .types import RuntimeConflict, fingerprint
from .usage_pricing import format_amount

MODEL_OPERATIONS = ('model.chat', 'model.embedding')


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


def payload_usage(intent, value):
    operation = intent['operation']
    chat = operation == 'model.chat'
    version_field = 'prompt_version' if chat else 'query_version'
    if (operation not in MODEL_OPERATIONS or value['schema_version'] != ('chat-result-v1' if chat else 'embedding-result-v1')
            or value[version_field] != intent['version'] or value['profile_ref'] != intent['model']
            or value['profile_hash'] != intent['input_ref'] or value['request_hash'] != intent['arguments_sha256']):
        raise ValueError('Invalid model checkpoint binding')
    return checkpoint_usage(value['usage'], chat=chat)


def _step_usage(step):
    payload_id, receipt_id = step['payload_id'], step['usage_payload_id']
    if payload_id is None and receipt_id is None:
        return None, None
    intent = step['intent_ref']
    if (intent['operation'] not in MODEL_OPERATIONS or step['state'] != 'SUCCEEDED' or step['effect'] != 'NONE'
            or step['result_ref'] != {'resource_ref': 'payload:' + str(payload_id or receipt_id)}
            or step['intent_hash'] != fingerprint(dict(kind='MODEL', effect='NONE', intent=intent))):
        raise ValueError('Invalid model step binding')

    observed = None
    if payload_id is not None:
        schema = 'model-output-v1'
        if step['payload_schema'] != schema or step['fence'] != step['payload_fence']:
            raise ValueError('Invalid model checkpoint binding')
        observed = payload_usage(intent, decode(schema, step['payload_json'], step['payload_hash']))
    if receipt_id is None:
        return observed

    chat = intent['operation'] == 'model.chat'
    output_tokens = step['usage_chat_output_tokens']
    if (step['usage_fence'] != step['fence'] or step['usage_intent_hash'] != step['intent_hash']
            or (output_tokens is not None) != chat):
        raise ValueError('Invalid model usage receipt binding')
    usage = {'available': True, 'input_tokens': step['usage_input_tokens']}
    if chat:
        usage['output_tokens'] = output_tokens
    durable = checkpoint_usage(usage, chat=chat)
    if payload_id is not None and (receipt_id != payload_id or step['usage_payload_hash'] != step['payload_hash']
                                   or durable != observed):
        raise ValueError('Model usage receipt conflicts with the live checkpoint')
    return durable


def summarize(run, steps, *, price_book=None):
    attempts = run['model_calls']
    if attempts != len(steps) or len(steps) > 100:
        raise RuntimeConflict('Runtime model reservations are inconsistent')
    inputs, outputs = [], []
    priced_attempts = priced_picos = 0
    try:
        for step in steps:
            tokens_in, tokens_out = _step_usage(step)
            if tokens_in is not None:
                inputs.append(tokens_in)
                if price_book is not None:
                    amount = price_book.estimate_picos(step['intent_ref']['input_ref'], tokens_in, tokens_out)
                    if amount is not None:
                        priced_attempts += 1
                        priced_picos += amount
            if tokens_out is not None:
                outputs.append(tokens_out)
    except (ValueError, TypeError, KeyError, RecursionError):
        raise RuntimeConflict('Runtime model usage checkpoint is invalid') from None
    report = {
        'schema_version': 'runtime-model-usage-v1',
        'run_id': str(run['run_id']),
        'model_attempts_reserved': attempts,
        'attempts_with_observed_usage': len(inputs),
        'attempts_without_observed_usage': attempts - len(inputs),
        'observed_input_tokens': sum(inputs) if inputs else None,
        'observed_chat_output_tokens': sum(outputs) if outputs else None,
    }
    if price_book is not None:
        report['cost_estimate'] = {
            'schema_version': 'runtime-cost-estimate-v1',
            'price_book_version': price_book.version,
            'price_book_sha256': price_book.digest,
            'currency': price_book.currency,
            'observed_priced_amount': format_amount(priced_picos) if priced_attempts else None,
            'priced_attempts': priced_attempts,
            'observed_attempts_without_rate': len(inputs) - priced_attempts,
            'attempts_without_observed_usage': attempts - len(inputs),
        }
    return report
