"""图片识别接口：客户发来的图，看懂它。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/agent/vision | 识别一组图片：OCR 原文 + 一句话判读 + 结构化字段 |
| GET | /api/ai/v1/agent/vision/health | 探活 + 体检（用的是哪个视觉模型、密钥读到没有） |

调用方是 customer-service：它把图片压成 JPEG 的 base64 data URL 送过来（图片存在 RustFS，Java 手里有存储客户端）。
"""

from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..config import get_settings
from ..core.chat_transport import ModelCallFailure
from ..core.llm_vision import VisionInputError, vision_provider
from ..core.trace import require_trace_id
from ..services import vision as vision_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/agent", tags=["vision"])


class VisionRequest(BaseModel):
    tenant_code: str = Field(min_length=1, max_length=32)
    session_id: str = Field(default="", max_length=64)
    # data URL 列表，形如 data:image/jpeg;base64,...；由 Java 侧压缩后送来
    images: list[str] = Field(default_factory=list)
    question: str = Field(default="", max_length=500)


@router.post("/vision")
async def vision(body: VisionRequest, trace_id: str = Depends(require_trace_id)) -> dict[str, Any]:
    started = time.perf_counter()
    logger.info("收到图片识别请求 trace=%s tenant=%s imageCount=%d",
                trace_id, body.tenant_code, len(body.images))
    if not body.images:
        raise HTTPException(status_code=400, detail="没有可识别的图片")
    try:
        result = await vision_service.recognize(
            images=body.images, question=body.question, tenant_code=body.tenant_code)
    except VisionInputError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from None
    except ModelCallFailure as failure:
        status = 503 if failure.retryable else 502
        if failure.code == "MODEL_REQUEST_INVALID":
            status = 400
        raise HTTPException(status_code=status, detail=failure.code) from None
    except Exception as exc:
        logger.error("图片识别失败 trace=%s errorType=%s", trace_id, type(exc).__name__)
        raise HTTPException(status_code=500, detail="VISION_INTERNAL_ERROR") from None
    logger.info("图片识别接口完成 trace=%s available=%s latencyMs=%d",
                trace_id, result["available"],
                int((time.perf_counter() - started) * 1000))
    return result


@router.get("/vision/health")
def vision_health(tenant_code: str | None = None) -> dict[str, Any]:
    """体检：当前会用哪个视觉模型、密钥读到没有。

    和文本模型一个口径：只报"有没有"，不回显密钥本身。
    """
    chosen, desc = vision_provider(tenant_code or "")
    return {
        "status": "UP" if chosen else "DEGRADED",
        "scene": "图片识别 / OCR / 截图判读",
        "visionKeyConfigured": chosen is not None,
        "visionModel": chosen["model"] if chosen else "",
        "visionSource": desc,
        "maxImages": get_settings().vision_max_images,
        "hint": "" if chosen else
                f"{desc}。配好 SUPPORTFLOW_AI_QWEN_API_KEY 后即可识别图片；"
                "没配时机器人会如实告诉客户『暂时看不了图片』，不会编造识别结果。",
    }
