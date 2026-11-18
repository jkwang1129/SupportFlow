"""LLM 网关：统一鉴权/路由/降级/计量。当前为 mock 实现，接入点已预留。"""

from __future__ import annotations

import logging
import time
from typing import Protocol

from ..config import get_settings
from .trace import get_trace_id

logger = logging.getLogger(__name__)


class ChatModel(Protocol):
    def generate(self, prompt: str) -> str: ...


class MockModel:
    def generate(self, prompt: str) -> str:
        return f"[mock] 已收到你的消息：{prompt[:60]}（接入大模型后由 LLM 网关真实生成）"


class LLMGateway:
    """统一入口：provider 路由、超时降级、token 计量（后续实现）。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.provider = settings.llm_default_provider
        self.model = settings.llm_default_model
        self._model: ChatModel = MockModel()
        logger.info(
            "LLM 网关初始化 provider=%s model=%s mode=%s",
            self.provider, self.model or "-", "mock" if self.provider == "mock" else "remote",
        )
        if self.provider != "mock":
            # TODO: 按 provider 初始化 OpenAI/千问/DeepSeek 客户端
            pass

    def generate(self, prompt: str) -> str:
        # TODO: 流式（SSE）、成本计量、失败降级
        settings = get_settings()
        started = time.perf_counter()
        logger.info(
            "LLM 生成开始 trace=%s provider=%s model=%s promptChars=%d",
            get_trace_id() or "-", self.provider, self.model or "-", len(prompt or ""),
        )
        if settings.llm_log_payload:
            logger.info("LLM 生成入参 trace=%s prompt=%s", get_trace_id() or "-", prompt)
        try:
            reply = self._model.generate(prompt)
        except Exception as exc:  # noqa: BLE001
            logger.error(
                "LLM 生成失败 trace=%s provider=%s model=%s latencyMs=%d error=%s: %s",
                get_trace_id() or "-", self.provider, self.model or "-",
                int((time.perf_counter() - started) * 1000), type(exc).__name__, exc,
            )
            raise
        if settings.llm_log_payload:
            logger.info("LLM 生成出参 trace=%s reply=%s", get_trace_id() or "-", reply)
        logger.info(
            "LLM 生成完成 trace=%s provider=%s model=%s latencyMs=%d replyChars=%d",
            get_trace_id() or "-", self.provider, self.model or "-",
            int((time.perf_counter() - started) * 1000), len(reply or ""),
        )
        return reply


gateway = LLMGateway()
