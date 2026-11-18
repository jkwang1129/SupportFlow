"""阿里云智能语音交互（NLS）：一句话识别（ASR）与语音合成（TTS）。

做法照搬「企业智能招聘系统」那套（见它的《增加AI面试官功能》2.7 节），
接口形态完全一致，只是收口到了本项目唯一的 AI 服务里：

```text
CreateToken（HMAC-SHA1 签名，域名固定 nls-meta.cn-shanghai.aliyuncs.com，版本 2019-02-28）
      │  拿到 Token.Id，缓存到过期前 1 小时
      ▼
ASR  POST {gateway}/stream/v1/asr   ← 16k 单声道 WAV 字节，头 X-NLS-Token
TTS  POST {gateway}/stream/v1/tts   ← JSON 文本，头 X-NLS-Token，返回音频字节
```

三条约定：

1. **没有配置就如实不可用**（``available=False``），不抛异常吓调用方——
   Java 侧据此给客户一句"暂时听不了/说不了"，而不是编一段识别结果；
2. **token 按过期时间缓存**，多实例各缓存各的（NLS 的 token 是同一个账号共享的，重复换是浪费）；
3. **密钥只在服务端**：浏览器/话务平台拿到的是音频与文本，拿不到 AccessKey。
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import time
import uuid
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

import httpx

from ..config import get_settings

logger = logging.getLogger(__name__)

# NLS 网关的固定成功码
NLS_OK = 20000000


def percent_encode(value: str) -> str:
    """阿里云 RPC 签名的 percentEncode：RFC3986 之后再处理 ``+ * ~`` 三个特例。"""
    return (quote(str(value), safe="")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~"))


def utc_timestamp() -> str:
    """RPC 要求的 ISO8601 UTC 时间：2026-10-02T03:04:05Z。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sign_create_token(access_key_id: str, access_key_secret: str, region: str,
                      nonce: str, timestamp: str) -> str:
    """算 CreateToken 的签名（拆出来是为了自检能单独验，不用起服务）。

    ``stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonicalized)``，
    签名密钥是 ``AccessKeySecret + "&"``（阿里云 RPC 的老规矩），最后 base64。
    """
    params = {
        "AccessKeyId": access_key_id,
        "Action": "CreateToken",
        "Format": "JSON",
        "RegionId": region,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureNonce": nonce,
        "SignatureVersion": "1.0",
        "Timestamp": timestamp,
        "Version": "2019-02-28",
    }
    canonicalized = "&".join(f"{percent_encode(k)}={percent_encode(v)}"
                             for k, v in sorted(params.items()))
    string_to_sign = "POST&" + percent_encode("/") + "&" + percent_encode(canonicalized)
    digest = hmac.new((access_key_secret + "&").encode("utf-8"),
                      string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    return base64.b64encode(digest).decode("utf-8"), canonicalized


class NlsTokenCache:
    """CreateToken + 进程内缓存（过期前 1 小时刷新，与招聘系统一致）。"""

    REFRESH_AHEAD_SECONDS = 3600

    def __init__(self) -> None:
        self._token: str = ""
        self._expire_at: float = 0.0
        self._lock = asyncio.Lock()

    async def token(self) -> str:
        now = time.time()
        if self._token and now < self._expire_at - self.REFRESH_AHEAD_SECONDS:
            return self._token
        async with self._lock:
            # 等锁期间别人可能已经换好了，再判一次
            now = time.time()
            if self._token and now < self._expire_at - self.REFRESH_AHEAD_SECONDS:
                return self._token
            await self._refresh()
            return self._token

    async def _refresh(self) -> None:
        settings = get_settings()
        nonce = str(uuid.uuid4())
        timestamp = utc_timestamp()
        signature, canonicalized = sign_create_token(
            settings.speech_access_key_id, settings.speech_access_key_secret,
            settings.speech_region, nonce, timestamp)
        url = (f"{settings.speech_meta_base.rstrip('/')}/?"
               f"{canonicalized}&Signature={percent_encode(signature)}")
        started = time.perf_counter()
        async with httpx.AsyncClient(timeout=settings.speech_timeout) as client:
            response = await client.post(url)
        if response.status_code >= 400:
            logger.error("NLS CreateToken 失败 status=%d body=%s",
                         response.status_code, response.text[:400])
            raise RuntimeError(f"阿里云语音换 token 失败：HTTP {response.status_code}"
                               f" {response.text[:200]}")
        body = response.json()
        token = ((body or {}).get("Token") or {}).get("Id")
        expire_at = ((body or {}).get("Token") or {}).get("ExpireTime")
        if not token:
            logger.error("NLS CreateToken 返回里没有 Token.Id：%s", str(body)[:400])
            raise RuntimeError(f"阿里云语音换 token 失败：{str(body)[:200]}")
        self._token = str(token)
        # ExpireTime 是秒级时间戳；拿不到就按 1 小时兜底（保守，宁可早点换）
        self._expire_at = float(expire_at) if expire_at else time.time() + 3600
        logger.info("NLS CreateToken 成功 cost=%dms 有效期至 %s",
                    int((time.perf_counter() - started) * 1000),
                    datetime.fromtimestamp(self._expire_at, timezone.utc).isoformat())


_tokens = NlsTokenCache()


def speech_available() -> tuple[bool, str]:
    """语音能力是否可用（缺哪个配置就直说，不回显密钥本身）。"""
    settings = get_settings()
    if not settings.speech_enabled:
        return False, "语音能力未开启（SUPPORTFLOW_AI_SPEECH_ENABLED=false）"
    if not settings.speech_app_key:
        return False, "未配置语音项目 AppKey（SUPPORTFLOW_AI_SPEECH_APP_KEY）"
    if not settings.speech_access_key_id or not settings.speech_access_key_secret:
        return False, "未配置阿里云 AccessKey（SUPPORTFLOW_AI_SPEECH_ACCESS_KEY_ID / SECRET）"
    return True, f"{settings.speech_region} · voice={settings.speech_tts_voice}"


async def recognize(audio: bytes, *, audio_format: str = "wav", sample_rate: int = 16000,
                    tenant_code: str = "") -> dict[str, Any]:
    """一句话识别：16k 单声道 WAV 字节 → 文本。

    返回 ``{"available": True, "text": "...", "duration_ms": 123}``；
    未配置时返回 ``{"available": False, "text": "", "hint": "..."}``（不抛异常）。
    """
    settings = get_settings()
    available, hint = speech_available()
    if not available:
        return {"available": False, "text": "", "hint": hint}
    if not audio:
        raise ValueError("没有可识别的音频")
    limit = settings.speech_max_audio_mb * 1024 * 1024
    if len(audio) > limit:
        raise ValueError(f"音频超过 {settings.speech_max_audio_mb}MB，请先压缩或只传一句话")
    fmt = (audio_format or settings.speech_asr_format or "wav").lower()
    rate = int(sample_rate or settings.speech_asr_sample_rate or 16000)
    url = (f"{settings.speech_gateway_base.rstrip('/')}/stream/v1/asr"
           f"?appkey={percent_encode(settings.speech_app_key)}"
           f"&format={percent_encode(fmt)}&sample_rate={rate}"
           "&enable_punctuation_prediction=true&enable_inverse_text_normalization=true")
    started = time.perf_counter()
    async with httpx.AsyncClient(timeout=settings.speech_timeout) as client:
        response = await client.post(
            url,
            headers={"X-NLS-Token": await _tokens.token(),
                     "Content-Type": "application/octet-stream"},
            content=audio)
    cost_ms = int((time.perf_counter() - started) * 1000)
    if response.status_code >= 400:
        logger.error("NLS ASR 失败 tenant=%s status=%d bytes=%d body=%s",
                     tenant_code or "-", response.status_code, len(audio), response.text[:400])
        raise RuntimeError(f"语音识别失败：HTTP {response.status_code} {response.text[:200]}")
    body = response.json()
    status = int((body or {}).get("status") or 0)
    if status != NLS_OK:
        logger.error("NLS ASR 返回错误 tenant=%s status=%s message=%s",
                     tenant_code or "-", status, (body or {}).get("message"))
        raise RuntimeError(f"语音识别失败：{status} {(body or {}).get('message') or ''}")
    text = str((body or {}).get("result") or "").strip()
    logger.info("NLS ASR 完成 tenant=%s bytes=%d 文本=%d字 cost=%dms：%s",
                tenant_code or "-", len(audio), len(text), cost_ms, text[:60])
    return {"available": True, "text": text, "duration_ms": cost_ms}


async def synthesize(text: str, *, voice: str = "", audio_format: str = "",
                     tenant_code: str = "") -> dict[str, Any]:
    """语音合成：文本 → 音频字节（默认 16k WAV，知祥音色）。

    返回 ``{"available": True, "audio": b"...", "content_type": "audio/wav", "format": "wav"}``；
    未配置时返回 ``{"available": False, "audio": b"", "hint": "..."}``。
    """
    settings = get_settings()
    available, hint = speech_available()
    if not available:
        return {"available": False, "audio": b"", "hint": hint}
    plain = (text or "").strip()
    if not plain:
        raise ValueError("要合成的文本为空")
    if len(plain) > settings.speech_max_text_chars:
        raise ValueError(f"文本超过 {settings.speech_max_text_chars} 字，请分段合成")
    fmt = (audio_format or settings.speech_tts_format or "wav").lower()
    payload: dict[str, Any] = {
        "appkey": settings.speech_app_key,
        "text": plain,
        "format": fmt,
        "voice": (voice or settings.speech_tts_voice or "zhixiang"),
        "sample_rate": int(settings.speech_tts_sample_rate or 16000),
        "volume": int(settings.speech_tts_volume or 50),
        "speech_rate": int(settings.speech_tts_speech_rate or 0),
        "pitch": int(settings.speech_tts_pitch or 0),
    }
    started = time.perf_counter()
    async with httpx.AsyncClient(timeout=settings.speech_timeout) as client:
        response = await client.post(
            f"{settings.speech_gateway_base.rstrip('/')}/stream/v1/tts",
            headers={"X-NLS-Token": await _tokens.token(),
                     "Content-Type": "application/json"},
            content=json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    cost_ms = int((time.perf_counter() - started) * 1000)
    content_type = (response.headers.get("Content-Type") or "").lower()
    # 成功返回音频字节；失败返回 JSON（status != 20000000）——靠 Content-Type + 首字节判断，
    # 不能只看 HTTP 状态码，NLS 的错误也是 200
    if response.status_code >= 400 or "application/json" in content_type:
        detail = response.text[:300]
        logger.error("NLS TTS 失败 tenant=%s status=%d body=%s",
                     tenant_code or "-", response.status_code, detail)
        raise RuntimeError(f"语音合成失败：{detail}")
    audio = response.content
    if not audio:
        raise RuntimeError("语音合成返回了空音频")
    media_type = content_type.split(";")[0].strip() or f"audio/{fmt}"
    logger.info("NLS TTS 完成 tenant=%s %d字 → %d字节 cost=%dms voice=%s",
                tenant_code or "-", len(plain), len(audio), cost_ms, payload["voice"])
    return {"available": True, "audio": audio, "content_type": media_type,
            "format": fmt, "duration_ms": cost_ms, "voice": payload["voice"]}
