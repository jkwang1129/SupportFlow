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

import logging
import time
from typing import Any

import httpx

from ..config import get_settings
from ..core.db import connect
from ..core.llm_chat import provider_conf
from ..core.llm_usage import record_chat_usage
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)


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
        logger.debug("读取租户视觉模型失败 tenant=%s error=%s", tenant_code, exc)
        return None
    return str(row["model_key"]) if row and row.get("model_key") else None


async def call_vision(chosen: dict[str, str], prompt: str, images: list[str], timeout: int,
                      *, scene: str = "图片识别") -> dict[str, Any]:
    """发一次多模态请求。

    @param images 形如 ``data:image/jpeg;base64,...`` 的 data URL 列表
    """
    settings = get_settings()
    if not images:
        raise ValueError("多模态调用至少要有一张图片")
    if len(images) > settings.vision_max_images:
        raise ValueError(f"一次最多识别 {settings.vision_max_images} 张图片")
    # 单张大小也要卡：Java 侧压到 100~300KB 才送过来，这里防的是"别的调用方直接把原图 base64 塞进来"，
    # 那会让 body 涨到十几 MB，网关截断后报出来的错误跟图片本身一点关系都没有，很难查
    limit_mb = settings.vision_max_image_mb
    oversized = [index for index, image in enumerate(images, start=1)
                 if len(image) > limit_mb * 1024 * 1024]
    if oversized:
        raise ValueError(
            f"第 {'、'.join(str(index) for index in oversized)} 张图超过 {limit_mb}MB"
            "（data URL 长度），请压缩后再送识别")

    trace = get_trace_id() or "-"
    content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
    for index, image in enumerate(images, start=1):
        content.append({"type": "image_url", "image_url": {"url": image}})
        logger.info("%s入参 trace=%s provider=%s model=%s 第%d张图 bytes≈%d",
                    scene, trace, chosen["provider"], chosen["model"], index, len(image))

    payload = {
        "model": chosen["model"],
        "temperature": 0.1,
        "messages": [{"role": "user", "content": content}],
    }
    started = time.perf_counter()
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(f"{chosen['base_url']}/chat/completions",
                                     headers={"Authorization": f"Bearer {chosen['api_key']}",
                                              "Content-Type": "application/json"},
                                     json=payload)
        if response.status_code >= 400:
            # 和文本调用一个原则：厂商的报错正文必须带出来（"Model not exist" 全在正文里）
            logger.error("%s调用失败 trace=%s provider=%s model=%s status=%d body=%s",
                         scene, trace, chosen["provider"], chosen["model"],
                         response.status_code, response.text[:600])
            # 视觉调用同样计量（失败也记），平台监控里能看出"图片识别是不是在报错"
            record_chat_usage(chosen=chosen, scene=scene,
                              latency_ms=int((time.perf_counter() - started) * 1000), success=False)
            raise RuntimeError(f"{chosen['provider']} 返回 {response.status_code}："
                               f"{response.text[:300]}")
        data = response.json()
    latency_ms = int((time.perf_counter() - started) * 1000)
    logger.info("%s出参 trace=%s provider=%s model=%s cost=%dms body=%s",
                scene, trace, chosen["provider"], chosen["model"],
                latency_ms,
                str(data)[:1200])
    record_chat_usage(chosen=chosen, scene=scene, latency_ms=latency_ms, data=data)
    return data


def message_content(data: dict[str, Any]) -> str:
    """从响应里取正文（多模态响应结构和文本一样，都是 choices[0].message.content）。"""
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    return str(message.get("content") or "").strip()
