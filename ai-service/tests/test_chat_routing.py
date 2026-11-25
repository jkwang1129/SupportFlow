"""Legacy Chat routes use tenant policy and synthetic loopback providers."""

import asyncio
import logging

import pytest

from ai.config import Settings
from ai.core import llm_chat
from ai.services import brain, qa, rag
from test_chat_transport import local_provider
from test_internal_auth import client, signed


TENANT = "TENANT_A"
QA_PATH = "/api/ai/v1/qa/evaluate"
ANSWER = {"choices": [{"message": {"content": '{"aiScore":91,"riskLevel":1,"rules":[]}'}}],
          "usage": {"prompt_tokens": 10, "completion_tokens": 7}}


@pytest.fixture
def settings(monkeypatch):
    configured = Settings(_env_file=None, qwen_api_key="fixture-qwen-key",
                          deepseek_api_key="fixture-deepseek-key", qwen_model="qwen-plus",
                          deepseek_model="deepseek-chat", llm_default_provider="", llm_default_model="")
    for module in (llm_chat, qa, brain, rag):
        monkeypatch.setattr(module, "get_settings", lambda: configured)
    return configured


def selection(provider, model, *, enabled=True):
    return {"provider": provider, "model_key": model, "is_enabled": enabled}


@pytest.mark.parametrize("saved, provider", [
    (selection("qwen", "deepseek-hosted-alias"), "qwen"),
    (selection("supportflow", "deepseek-chat"), "deepseek"),
    (selection("", "qwen-plus"), "qwen"),
    (selection("DASHSCOPE", "tenant-custom-v4"), "qwen"),
    (None, "qwen"),
])
def test_successful_selection_uses_declared_authority_and_keeps_legacy_model_keys(settings, monkeypatch,
                                                                               saved, provider):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: saved)
    chosen, _ = llm_chat.resolve_provider(TENANT)
    assert chosen["provider"] == provider
    assert chosen["api_key"] == getattr(settings, provider + "_api_key")
    assert chosen["model"] == ((saved or {}).get("model_key") or settings.qwen_model)


@pytest.mark.parametrize("saved", [
    selection("deepseek", "deepseek-chat", enabled=False),
    selection(None, "missing-model", enabled=None),
    selection("unsupported", "qwen-plus"),
    selection("supportflow", "unknown-alias"),
])
def test_invalid_tenant_selection_cannot_use_platform_default(settings, monkeypatch, saved):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: saved)
    assert llm_chat.resolve_provider(TENANT) == (None, "MODEL_SELECTION_INVALID")


def test_configuration_outage_degrades_without_reusing_previous_route(settings, monkeypatch, caplog):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: selection("deepseek", "deepseek-chat"))
    assert llm_chat.resolve_provider(TENANT)[0]["provider"] == "deepseek"

    def broken(_):
        raise RuntimeError("private-fixture-config-error")

    monkeypatch.setattr(llm_chat, "_tenant_selection", broken)
    assert llm_chat.resolve_provider(TENANT) == (None, "MODEL_TENANT_CONFIG_UNAVAILABLE")
    assert "private-fixture-config-error" not in caplog.text
    assert llm_chat.describe_effective(TENANT)["effective"] == "本地降级"


def test_unconfigured_tenant_cannot_change_explicit_platform_route(settings, monkeypatch):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: None)
    settings.llm_default_provider = "deepseek"
    settings.llm_default_model = "deepseek-reasoner"
    deepseek, _ = llm_chat.resolve_provider(TENANT)
    assert deepseek["provider"] == "deepseek" and deepseek["model"] == "deepseek-reasoner"
    assert llm_chat.resolve_provider(TENANT, "qwen") == (None, "MODEL_ROUTE_OVERRIDE_DENIED")
    settings.deepseek_api_key = ""
    assert llm_chat.resolve_provider(TENANT) == (None, "MODEL_PROVIDER_UNAVAILABLE")


def test_model_diagnostics_use_fresh_single_read_and_hide_key(settings, monkeypatch):
    calls = []
    rows = [selection("deepseek", "deepseek-chat"), selection("qwen", "qwen-plus")]
    settings.deepseek_model = "deepseek-reasoner"

    def read(tenant):
        calls.append(tenant)
        return rows.pop(0)

    monkeypatch.setattr(llm_chat, "_tenant_selection", read)
    before = llm_chat.describe_effective(TENANT)
    after = llm_chat.describe_effective(TENANT)
    assert before["effective"] == "deepseek:deepseek-reasoner"
    assert after["effective"] == "qwen:qwen-plus" and calls == [TENANT, TENANT]
    assert before["routeCode"] == after["routeCode"] == "MODEL_ROUTE_READY"
    assert "fixture-" not in str((before, after))


@pytest.mark.parametrize("tenant", ["", "PLATFORM", " invalid ", "TOO_LONG_FOR_TENANT_KEY"])
def test_invalid_tenant_cannot_resolve_a_route(settings, monkeypatch, tenant):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: pytest.fail("Unexpected DB access"))
    assert llm_chat.resolve_provider(tenant) == (None, "MODEL_TENANT_REQUIRED")


def post_qa(client, **extra):
    payload = {"tenantCode": TENANT, "sessionName": "fixture-session", "agentName": "agent",
               "transcript": "synthetic transcript"} | extra
    return client.post(QA_PATH, json=payload, headers=signed("POST", QA_PATH, tenant=TENANT)).json()["data"]


@pytest.mark.parametrize("extra", [{}, {"provider": "qwen"}, {"model": "qwen-plus"},
                                    {"provider": "deepseek", "model": "qwen-plus"}, {"provider": "mock"}])
def test_qa_disallowed_or_unavailable_model_never_sends_to_another_provider(client, settings, monkeypatch, extra):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: selection("deepseek", "deepseek-chat"))
    if not extra:
        settings.deepseek_api_key = ""
    with (local_provider("normal", response_body=ANSWER) as (deepseek, attempts),
          local_provider("normal", response_body=ANSWER) as (qwen, other)):
        settings.deepseek_base_url, settings.qwen_base_url = deepseek, qwen
        assert post_qa(client, **extra)["source"] == "fallback-rule"
    assert attempts == other == []


@pytest.mark.parametrize("status, source", [(200, "llm-deepseek"), (429, "fallback-rule"), (503, "fallback-rule")])
def test_signed_qa_sends_once_to_selected_supplier_and_never_retries_elsewhere(client, settings, monkeypatch,
                                                                                 status, source):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: selection("deepseek", "deepseek-chat"))
    metered = []
    monkeypatch.setattr(llm_chat, "record_chat_usage", lambda **usage: metered.append(usage))
    with (local_provider("normal", response_body=ANSWER, status=status) as (deepseek, chosen),
          local_provider("normal", response_body=ANSWER) as (qwen, other)):
        settings.deepseek_base_url, settings.qwen_base_url = deepseek, qwen
        assert post_qa(client, provider="deepseek", model="deepseek-chat")["source"] == source
    assert len(chosen) == len(metered) == 1 and other == []
    assert metered[0]["tenant_code"] == TENANT and metered[0].get("success", True) is (status == 200)
    assert chosen[0][2]["model"] == "deepseek-chat"


def test_intent_and_rag_keep_local_behaviors_when_route_is_unavailable(settings, monkeypatch):
    monkeypatch.setattr(llm_chat, "_tenant_selection", lambda _: selection("qwen", "qwen-plus"))
    settings.qwen_api_key = ""
    hits = [{"doc_id": 1, "doc_title": "Test", "chunk_no": 1, "content": "safe policy", "score": 1}]
    with local_provider("normal", response_body=ANSWER) as (other, attempts):
        settings.deepseek_base_url = other
        assert asyncio.run(brain.analyze_with_llm("question", [], [], TENANT)) is None
        local = asyncio.run(rag.compose_answer_details("question", hits, TENANT))
        strict = asyncio.run(rag.compose_answer_details("question", hits, TENANT, require_grounded_reply=True))
    assert "safe policy" in local.text and local.citations and not local.generated
    assert strict.abstention_reason == "grounded_model_unavailable" and not strict.citations
    assert attempts == []
