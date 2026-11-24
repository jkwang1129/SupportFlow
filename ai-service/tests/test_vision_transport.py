"""Vision provider faults and the signed customer API use synthetic content only."""

import asyncio
import base64
import json
import logging
from types import SimpleNamespace

import httpx
import pytest

from ai.core import chat_transport, llm_chat, llm_vision
from ai.core.chat_transport import MAX_CHAT_BYTES, ModelCallFailure
from ai.services import vision
from test_chat_transport import CHOSEN, SlowBody, local_provider
from test_internal_auth import client, signed


PATH = "/api/ai/v1/agent/vision"
TENANT = "TENANT_A"
QUESTION = "private-fixture-question"
IMAGE = "data:image/jpeg;base64," + base64.b64encode(b"private-fixture-image").decode()
CONTENT = {"ocrText": "private-fixture-ocr", "summary": "private-fixture-summary",
           "orderNo": "private-fixture-order", "needHuman": True}
ANSWER = {"choices": [{"message": {"role": "assistant", "content": json.dumps(CONTENT)}}],
          "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}}


@pytest.fixture(autouse=True)
def vision_settings(monkeypatch):
    settings = SimpleNamespace(vision_max_images=3, vision_max_image_mb=6, llm_timeout=2)
    monkeypatch.setattr(llm_vision, "get_settings", lambda: settings)
    monkeypatch.setattr(vision, "get_settings", lambda: settings)
    return settings


@pytest.fixture
def usage(monkeypatch):
    events = []
    monkeypatch.setattr(llm_chat, "record_chat_usage", lambda **values: events.append(values))
    return events


def invoke(handler, *, images=None, timeout=1, prompt=QUESTION):
    return asyncio.run(llm_vision.call_vision(CHOSEN, prompt, [IMAGE] if images is None else images, timeout,
                                            tenant_code=TENANT, _transport=httpx.MockTransport(handler)))


def json_response(value):
    return httpx.Response(200, headers={"Content-Type": "application/json"},
                          stream=httpx.ByteStream(json.dumps(value).encode()))


def post_image(client, image=IMAGE):
    return client.post(PATH, json={"tenant_code": TENANT, "images": [image], "question": QUESTION},
                       headers={**signed("POST", PATH, tenant=TENANT), "X-Request-Id": "fixture-trace"})


def test_signed_vision_sends_large_image_over_tcp_and_keeps_business_fields(client, monkeypatch, usage, caplog):
    caplog.set_level(logging.INFO)
    # The same request would exceed the text Chat cap.
    large_image = "data:image/jpeg;base64," + base64.b64encode(b"a" * MAX_CHAT_BYTES).decode()
    with local_provider("normal", response_body=ANSWER) as (base, attempts):
        monkeypatch.setattr(vision, "vision_provider", lambda tenant: (CHOSEN | {"base_url": base}, "fixture"))
        result = post_image(client, large_image)
    assert result.status_code == 200
    assert result.headers["X-Request-Id"] == "fixture-trace"
    body = result.json()
    assert body["available"] and body["need_human"]
    assert (body["ocr_text"], body["summary"], body["order_no"]) == (
        CONTENT["ocrText"], CONTENT["summary"], CONTENT["orderNo"])
    assert len(attempts) == 1
    path, auth, payload = attempts[0]
    assert (path, auth) == ("/v1/chat/completions", "Bearer " + CHOSEN["api_key"])
    parts = payload["messages"][0]["content"]
    assert QUESTION in parts[0]["text"]
    assert parts[1] == {"type": "image_url", "image_url": {"url": large_image}}
    assert len(usage) == 1 and usage[0]["tenant_code"] == TENANT and usage[0]["scene"] == "图片识别"
    assert usage[0]["data"]["usage"]["total_tokens"] == 18
    assert "ocrChars=" in caplog.text
    for raw in (QUESTION, CHOSEN["api_key"], large_image[:64], *CONTENT.values()):
        if isinstance(raw, str):
            assert raw not in caplog.text


@pytest.mark.parametrize("status, code, api_status", [(429, "MODEL_RATE_LIMITED", 503),
                                                     (503, "MODEL_UNAVAILABLE", 503),
                                                     (401, "MODEL_AUTHORIZATION_DENIED", 502)])
def test_provider_error_is_observed_once_through_signed_api(status, code, api_status, client,
                                                          monkeypatch, usage, caplog):
    echo = "private-fixture-provider-response"
    with local_provider("normal", response_body={"detail": echo}, status=status) as (base, attempts):
        monkeypatch.setattr(vision, "vision_provider", lambda tenant: (CHOSEN | {"base_url": base}, "fixture"))
        result = post_image(client)
    assert result.status_code == api_status
    assert result.json() == {"detail": "AI 服务暂不可用"}
    assert code in caplog.text and echo not in caplog.text + result.text
    assert len(attempts) == len(usage) == 1
    assert usage[0]["tenant_code"] == TENANT and usage[0]["success"] is False
    assert "data" not in usage[0]


@pytest.mark.parametrize("images", [[], [IMAGE] * 4, ["https://images.test/remote.png"],
                                    ["data:image/svg+xml;base64,YQ=="], ["data:image/png;base64,"],
                                    ["data:image/png;base64,*!invalid"], ["data:image/png;base64,字"]])
def test_bad_image_encodings_and_counts_stop_before_model_and_metering(images, usage):
    with pytest.raises(llm_vision.VisionInputError):
        invoke(lambda _: pytest.fail("invalid image reached model"), images=images)
    assert usage == []


def test_invalid_image_is_a_sanitized_client_error(client, monkeypatch, usage):
    monkeypatch.setattr(vision, "vision_provider", lambda tenant: (CHOSEN, "fixture"))
    result = post_image(client, "data:image/jpeg;base64,private-fixture-invalid-encoding")
    assert result.status_code == 400 and result.json() == {"detail": "图片 Base64 编码无效"}
    assert usage == []


def test_missing_provider_keeps_existing_unavailable_result(client, monkeypatch, usage):
    monkeypatch.setattr(vision, "vision_provider", lambda tenant: (None, "视觉模型未配置"))
    result = post_image(client)
    assert result.status_code == 200
    assert result.json()["available"] is False and "未配置" in result.json()["hint"]
    assert usage == []


def test_plain_text_model_reply_still_falls_back_to_summary(client, monkeypatch, usage):
    plain_answer = {"choices": [{"message": {"content": "这是一张支付结果截图"}}]}
    with local_provider("normal", response_body=plain_answer) as (base, attempts):
        monkeypatch.setattr(vision, "vision_provider", lambda tenant: (CHOSEN | {"base_url": base}, "fixture"))
        result = post_image(client)
    assert result.status_code == 200
    assert result.json()["available"] and result.json()["summary"] == "这是一张支付结果截图"
    assert result.json()["ocr_text"] == ""
    assert len(attempts) == len(usage) == 1


def test_per_image_and_aggregate_size_are_enforced_before_provider(vision_settings, monkeypatch, usage):
    vision_settings.vision_max_image_mb = 0
    with pytest.raises(llm_vision.VisionInputError, match="单张"):
        invoke(lambda _: pytest.fail("oversized image reached model"))
    monkeypatch.setattr(llm_vision, "MAX_VISION_REQUEST_BYTES", len(IMAGE) * 2 - 1)
    with pytest.raises(llm_vision.VisionInputError, match="总大小"):
        invoke(lambda _: pytest.fail("oversized images reached model"), images=[IMAGE] * 2)
    assert usage == []


def test_serialized_vision_payload_has_a_separate_cap(monkeypatch, usage):
    monkeypatch.setattr(chat_transport, "MAX_VISION_REQUEST_BYTES", 512)
    with pytest.raises(ModelCallFailure, match="MODEL_REQUEST_INVALID") as caught:
        invoke(lambda _: pytest.fail("oversized JSON reached model"), prompt="字" * 250)
    assert not caught.value.attempted and usage == []


@pytest.mark.parametrize("invalid", [{}, {"choices": [{"message": {"content": {}}}]},
                                     {"choices": [{"message": {"content": " "}}]},
                                     {"choices": [{"message": 1}]}])
def test_missing_assistant_text_is_a_metered_failure(invalid, usage):
    with pytest.raises(ModelCallFailure, match="MODEL_RESPONSE_INVALID"):
        invoke(lambda _: json_response(invalid))
    assert len(usage) == 1 and usage[0]["success"] is False


def test_slow_response_body_has_one_deadline_and_one_attempt(usage):
    calls = []

    def slow(request):
        calls.append(request)
        return httpx.Response(200, headers={"Content-Type": "application/json"}, stream=SlowBody())

    with pytest.raises(ModelCallFailure, match="MODEL_TIMEOUT"):
        invoke(slow, timeout=.05)
    assert len(calls) == len(usage) == 1 and usage[0]["success"] is False


def test_vision_response_shares_smaller_text_size_limit(usage):
    large = b"x" * (MAX_CHAT_BYTES + 1)
    with pytest.raises(ModelCallFailure, match="MODEL_RESPONSE_TOO_LARGE"):
        invoke(lambda _: httpx.Response(200, headers={"Content-Type": "application/json"},
                                       stream=httpx.ByteStream(large)))
    assert len(usage) == 1 and usage[0]["success"] is False
