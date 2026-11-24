"""多模态（视觉）调用：把图片交给视觉大模型做 OCR 与截图判读。

参考企业智能招聘系统的做法，分工是**Java 压图、Python 发请求**：

| 谁 | 干什么 | 为什么 |
| --- | --- | --- |
| Java（customer-service） | 从对象存储取原图 → 缩放成 JPEG → base64 data URL | 图片在 RustFS 里，Java 手里有对象存储客户端；压缩后 payload 可控 |
| Python（这里） | 按 OpenAI 兼容的多模态格式发出去，解析结构化结果 | 各家的多模态格式大同小异，收口在一层里，换模型不改业务 |

调用形态（DashScope 兼容模式，与千问文本接口同一套协议）：

```json
{
  "model": "qwen-vl-plus",
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "请识别这张图…"},
      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
    ]
  }]
}
```

**为什么不用传统 OCR API**：客服场景要的不只是"把字抠出来"，还要"看懂这张图在说什么"
（这是报错截图？订单截图？商品破损照？），多模态模型一次就能给出"文字 + 判读"；
招 Java 侧的招聘系统也是这么干的（PDF → 图片 → qwen-vl）。
"""

from __future__ import annotations

import base64
import logging
import time
from typing import Any

from ..config import get_settings
from .chat_transport import MAX_VISION_REQUEST_BYTES, ModelCallFailure, _send_vision_json
from .db import connect
from .llm_chat import _meter, provider_conf
from .trace import get_trace_id

logger = logging.getLogger(__name__)
IMAGE_DATA_TYPES = {"data:image/jpeg;base64", "data:image/png;base64", "data:image/webp;base64",
                    "data:image/gif;base64"}


class VisionInputError(ValueError):
    """A local image input was rejected before provider dispatch."""


def vision_provider(tenant_code: str = "") -> tuple[dict[str, str] | None, str]:
    """挑一个可用的视觉模型。

    优先级：租户在「智能机器人 → 模型选择」里配的**视觉模型**（`bot_model.model_type=2`）
    → 全局默认（`SUPPORTFLOW_AI_QWEN_VL_MODEL`，默认 `qwen-vl-plus`）。

    @return (供应商配置, 说明)；没有任何千问密钥时返回 (None, 原因)
    """
    settings = get_settings()
    if not settings.qwen_api_key:
        return None, "未配置千问密钥（SUPPORTFLOW_AI_QWEN_API_KEY），视觉识别不可用"

    tenant_model = _tenant_vision_model(tenant_code)
    model = tenant_model or settings.qwen_vl_model
    source = "租户配置" if tenant_model else "平台默认"
    return (provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url, model),
            f"qwen:{model}（{source}）")


def _tenant_vision_model(tenant_code: str) -> str | None:
    """租户在模型选择里挑的视觉模型（没配返回 None）。

    优先取「智能机器人 → 模型选择」里选中的那个（`bot_setting.model_key` 且类型是视觉），
    选中的不是视觉模型、或者租户加过多个视觉模型时，退回"第一个启用的视觉模型"——
    这样页面上换了模型，识别这里立刻跟着换，不用改配置重启。

    读失败一律降级到平台默认：识别功能不能因为一张配置表读不到就整条不可用。
    """
    if not tenant_code:
        return None
    try:
        with connect() as conn:
            row = conn.execute(
                """
                SELECT m.model_key
                  FROM bot_setting s
                  JOIN bot_model m
                    ON m.tenant_code = s.tenant_code
                   AND m.is_deleted = FALSE
                   AND m.is_enabled = TRUE
                 WHERE s.tenant_code = %s AND s.is_deleted = FALSE AND m.model_type = 2
                 ORDER BY (m.model_key = s.model_key) DESC, m.id
                 LIMIT 1
                """,
                (tenant_code,),
            ).fetchone()
    except Exception as exc:  # noqa: BLE001
        logger.debug("读取租户视觉模型失败 tenant=%s errorType=%s", tenant_code, type(exc).__name__)
        return None
    return str(row["model_key"]) if row and row.get("model_key") else None


async def call_vision(chosen: dict[str, str], prompt: str, images: list[str], timeout: int,
                      *, tenant_code: str, _transport=None) -> dict[str, Any]:
    """Send one bounded multimodal request and attribute the attempt to its tenant."""
    settings = get_settings()
    if not images:
        raise VisionInputError("没有可识别的图片")
    if len(images) > settings.vision_max_images:
        raise VisionInputError(f"一次最多识别 {settings.vision_max_images} 张图片")
    if sum(map(len, images)) > MAX_VISION_REQUEST_BYTES:
        raise VisionInputError("图片总大小超限，请压缩后再送识别")
    max_image_bytes = settings.vision_max_image_mb * 1024 * 1024
    content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
    for image in images:
        if len(image) > max_image_bytes:
            raise VisionInputError(f"单张图片超过 {settings.vision_max_image_mb} MiB，请压缩后再送识别")
        media_type, _, encoded = image.partition(",")
        if media_type not in IMAGE_DATA_TYPES or not encoded:
            raise VisionInputError("请提供 Base64 图片")
        try:
            base64.b64decode(encoded, validate=True)
        except ValueError:
            raise VisionInputError("图片 Base64 编码无效") from None
        content.append({"type": "image_url", "image_url": {"url": image}})

    payload = {
        "model": chosen.get("model"),
        "temperature": 0.1,
        "messages": [{"role": "user", "content": content}],
    }
    started = time.perf_counter()
    try:
        data = await _send_vision_json(chosen, payload, timeout, transport=_transport)
        if not message_content(data):
            raise ModelCallFailure("MODEL_RESPONSE_INVALID")
    except ModelCallFailure as failure:
        logger.warning("视觉模型调用失败 trace=%s code=%s status=%s",
                       get_trace_id() or "-", failure.code, failure.status_code or "-")
        if failure.attempted:
            _meter(tenant_code=tenant_code, chosen=chosen, scene="图片识别",
                   latency_ms=int((time.perf_counter() - started) * 1000), success=False)
        raise
    _meter(tenant_code=tenant_code, chosen=chosen, scene="图片识别",
           latency_ms=int((time.perf_counter() - started) * 1000), data=data)
    return data


def message_content(data: dict[str, Any]) -> str:
    """Extract the text returned in the first assistant message."""
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return ""
    message = choices[0].get("message")
    content = message.get("content") if isinstance(message, dict) else None
    return content.strip() if isinstance(content, str) else ""
