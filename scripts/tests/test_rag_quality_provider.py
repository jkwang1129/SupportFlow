"""The live benchmark's spending and receipt contracts, without provider access."""
import asyncio
from pathlib import Path
import sys
import unittest
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rag_quality_benchmark import aggregate
import rag_quality_provider as provider


class QwenGenerationTest(unittest.TestCase):
    def setUp(self):
        self.live = provider.QwenGeneration('synthetic-test-credential')
        self.payload = {'model': 'qwen-plus', 'messages': [{'role': 'user', 'content': '合成问题'}]}
        self.response = {'id': 'synthetic-call', 'model': 'qwen-plus',
                         'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5},
                         'choices': [{'finish_reason': 'stop', 'message': {'content': '合成回答 [1]'}}]}

    def test_success_returns_usage_without_writing_application_ledger(self):
        receipt = {}
        with patch.object(provider, 'call_chat', new=AsyncMock(return_value=self.response)) as call:
            self.assertIs(self.response, asyncio.run(self.live.generate(self.payload, receipt)))
        chosen, request, deadline = call.await_args.args
        self.assertEqual(provider.BASE_URL, chosen['base_url'])
        self.assertEqual(512, request['max_tokens'])
        self.assertFalse(request['enable_thinking'])
        self.assertNotIn('max_tokens', self.payload)
        self.assertEqual(25, deadline)
        self.assertFalse(call.await_args.kwargs['meter_usage'])
        self.assertEqual(self.response['usage'], receipt['usage'])
        self.assertTrue(receipt['attempted'])
        self.assertNotIn('synthetic-test-credential', repr(receipt) + repr(self.live.configuration()))

    def test_failed_supplier_stops_later_calls_and_keeps_status(self):
        failure = provider.ModelCallFailure('MODEL_AUTHORIZATION_DENIED', status_code=401)
        first, later = {}, {'attempted': False}
        with patch.object(provider, 'call_chat', new=AsyncMock(side_effect=failure)) as call:
            with self.assertRaisesRegex(provider.ModelCallFailure, 'MODEL_AUTHORIZATION_DENIED'):
                asyncio.run(self.live.generate(self.payload, first))
            with self.assertRaisesRegex(provider.ModelCallFailure, 'EVAL_PROVIDER_STOPPED'):
                asyncio.run(self.live.generate(self.payload, later))
        self.assertEqual(1, call.await_count)
        self.assertTrue(first['attempted'])
        self.assertEqual(401, first['http_status'])
        self.assertFalse(later['attempted'])
        self.assertEqual(1, self.live.attempts)

    def test_budget_exhaustion_and_oversize_do_not_send_requests(self):
        with patch.object(provider, 'call_chat', new=AsyncMock()) as call:
            with patch.object(provider, 'MAX_REQUEST_BYTES', 20):
                with self.assertRaisesRegex(provider.ModelCallFailure, 'EVAL_INPUT_BUDGET_EXCEEDED'):
                    asyncio.run(self.live.generate(self.payload, {}))
            self.live.attempts = 50
            with self.assertRaisesRegex(provider.ModelCallFailure, 'EVAL_PROVIDER_STOPPED'):
                asyncio.run(self.live.generate(self.payload, {}))
        call.assert_not_awaited()

    def test_truncation_is_rejected_but_billed_tokens_stay_observable(self):
        self.response['choices'][0]['finish_reason'] = 'length'
        receipt = {}
        with patch.object(provider, 'call_chat', new=AsyncMock(return_value=self.response)):
            with self.assertRaisesRegex(provider.ModelCallFailure, 'EVAL_GENERATION_TRUNCATED'):
                asyncio.run(self.live.generate(self.payload, receipt))
        self.assertEqual(5, receipt['usage']['total_tokens'])

    def test_missing_and_inconsistent_usage_remain_unknown(self):
        for malformed in ({}, {'usage': []}, {'usage': {'prompt_tokens': 2, 'completion_tokens': True, 'total_tokens': 3}},
                          {'usage': {'prompt_tokens': 2, 'completion_tokens': 2, 'total_tokens': 3}}):
            with self.subTest(malformed=malformed):
                self.assertIsNone(provider.provider_usage(malformed))
        row = {'generation_receipt': {'attempted': True}, 'provider_tokens': None, 'model_calls': 1,
               'expected_outcome': 'answer', 'decision': None, 'latency_ms': 10, 'error': 'MODEL_TIMEOUT',
               'retrievals': [], 'scores': {'recall_at_5': 0, 'citation_pairs': [], 'outcome_match': False,
                                            'unauthorized_document_ids': [], 'forbidden_output_markers': []}}
        result = aggregate([row, row | {'provider_tokens': self.response['usage']}], live=True)['provider_tokens']
        self.assertEqual({'attempted': 2, 'with_usage': 1, 'unknown': 1, 'observed': self.response['usage']}, result)
