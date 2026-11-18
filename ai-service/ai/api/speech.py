"""语音接口：一句话识别（ASR）与语音合成（TTS）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/speech/asr | 请求体是 16k 单声道 WAV 原始字节，返回转写文本 |
| POST | /api/ai/v1/speech/tts | 请求体是 JSON（文本），**返回音频字节**（默认 wav） |
| GET | /api/ai/v1/speech/health | 探活 + 体检（语音开没开、缺哪个配置） |

调用方：

* customer-service 在 400 热线「录音回传」后调 ASR，把转写写进通话记录；
* 话务平台/呼叫中心用 TTS 把 IVR 播报文案合成音频（不用自己做语音合成）。

设计口径与其他 AI 接口一致：**没配置就如实说不可用**，不编结果；
密钥只留在服务端，调用方拿到的是文本与音频。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from ..config import get_settings
from ..core import speech_nls
from ..core.trace import require_trace_id

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/speech", tags=["speech"])


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    tenant_code: str = Field(default="", max_length=32)
    # 音色与格式都可不传：不传用平台默认（知祥 / wav / 16k）
    voice: str = Field(default="", max_length=32)
    format: str = Field(default="", max_length=8)


@router.get("/health")
def speech_health() -> dict[str, Any]:
    """体检：语音开没开、缺哪个配置、当前音色是什么。"""
    settings = get_settings()
    available, desc = speech_nls.speech_available()
    return {
        "status": "UP" if available else "DEGRADED",
        "scene": "语音识别（ASR）/ 语音合成（TTS）",
        "speechEnabled": settings.speech_enabled,
        "speechConfigured": available,
        "speechSource": desc,
        "asr": {"format": settings.speech_asr_format,
                "sampleRate": settings.speech_asr_sample_rate},
        "tts": {"voice": settings.speech_tts_voice,
                "format": settings.speech_tts_format,
                "sampleRate": settings.speech_tts_sample_rate},
        "maxAudioMb": settings.speech_max_audio_mb,
        "maxTextChars": settings.speech_max_text_chars,
        "hint": "" if available else
                f"{desc}。配好 SUPPORTFLOW_AI_SPEECH_ENABLED=true 与 AppKey / AccessKey 后即可使用；"
                "没配时通话录音不做转写、IVR 也回退成播报文本，不会编造识别结果。",
    }


@router.post("/asr")
async def asr(request: Request, trace_id: str = Depends(require_trace_id)) -> dict[str, Any]:
    """一句话识别：请求体是音频原始字节（默认 16k 单声道 WAV）。

    查询参数 ``format`` / ``sample_rate`` 可覆盖默认值；租户号从 ``X-Tenant-Code`` 读。
    """
    audio = await request.body()
    tenant_code = request.headers.get("X-Tenant-Code", "")
    audio_format = request.query_params.get("format", "")
    try:
        sample_rate = int(request.query_params.get("sample_rate") or 0)
    except ValueError:
        raise HTTPException(status_code=400, detail="sample_rate 必须是整数") from None
    logger.info("收到语音识别请求 trace=%s tenant=%s bytes=%d format=%s",
                trace_id, tenant_code or "-", len(audio), audio_format or "默认")
    if not audio:
        raise HTTPException(status_code=400, detail="请求体里没有音频字节")
    started = time.perf_counter()
    try:
        result = await speech_nls.recognize(audio, audio_format=audio_format,
                                            sample_rate=sample_rate, tenant_code=tenant_code)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("语音识别失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"语音识别失败：{exc}") from exc
    result["costMs"] = int((time.perf_counter() - started) * 1000)
    logger.info("语音识别接口完成 trace=%s 可用=%s 文本=%d字",
                trace_id, result.get("available"), len(result.get("text") or ""))
    return result


@router.post("/tts")
async def tts(body: TtsRequest, trace_id: str = Depends(require_trace_id)) -> Response:
    """语音合成：文本 → 音频字节。

    成功时响应体是音频（Content-Type 如 ``audio/wav``）；
    未配置语音能力时返回 200 + JSON（``available=false``），调用方据此降级，
    不要把"没配置"当成服务故障。
    """
    logger.info("收到语音合成请求 trace=%s tenant=%s %d字 voice=%s",
                trace_id, body.tenant_code or "-", len(body.text), body.voice or "默认")
    try:
        result = await speech_nls.synthesize(body.text, voice=body.voice,
                                             audio_format=body.format,
                                             tenant_code=body.tenant_code)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("语音合成失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"语音合成失败：{exc}") from exc
    if not result.get("available"):
        return Response(
            content=json.dumps(
                {"available": False, "hint": result.get("hint", "")}, ensure_ascii=False),
            media_type="application/json;charset=UTF-8")
    logger.info("语音合成接口完成 trace=%s %d字节 %s",
                trace_id, len(result["audio"]), result["content_type"])
    return Response(
        content=result["audio"],
        media_type=result["content_type"],
        headers={"X-Speech-Voice": result.get("voice", ""),
                 "X-Speech-Cost-Ms": str(result.get("duration_ms", 0))})
