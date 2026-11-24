"""向量化：把文本变成定长向量。

两条路：
    1. **真实向量**：配了千问密钥就走 DashScope 的 OpenAI 兼容 ``/embeddings``
       接口（``text-embedding-v3``，默认 1024 维）。这是生产用法——语义相近的句子
       向量也相近，检索才准。
    2. **本地兜底**：没配密钥时用"分词 + 哈希"生成一个确定性的 1024 维向量。
       它只有字面匹配能力（同样的词才会靠近），**语义检索效果很差**，
       仅用于本地把"上传 → 解析 → 切块 → 索引 → 检索"整条链路跑通。
       返回结果里会带 ``source`` 字段，前端会明确标出"当前是本地兜底向量"。

维度固定 1024，和 ``kb_chunk.embedding vector(1024)`` 对齐。换模型（维度变了）
要重建索引，所以每块都记了 ``embedding_model``。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import math
import re
import time

from ..config import get_settings
from ..core.chat_transport import ModelCallFailure, _configuration, _model_client, _send_embedding_json
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

EMBEDDING_DIM = 1024
# 一次最多送多少条：千问批量接口有条数上限，分批送更稳
BATCH_SIZE = 10
# 中文按字切、英文数字按词切；再用相邻字组成 bigram，弥补没有分词的短板
_TOKEN = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")


def embed_texts(texts: list[str], *, tenant_code: str = "", trace_id: str = "") -> tuple[list[list[float]], str]:
    """把一批文本向量化。

    @return (向量列表, 向量来源) —— 来源取值 ``qwen:text-embedding-v3`` 或 ``local-hash``
    """
    if not texts:
        return [], "none"
    settings = get_settings()
    trace_id = trace_id or get_trace_id()
    # 没单独配向量密钥时复用千问的 key（同一个账号），避免"明明配了密钥却还在用兜底向量"
    if settings.embedding_key() and settings.embedding_provider != "local":
        try:
            vectors = _embed_remote(texts, settings, tenant_code=tenant_code, trace_id=trace_id)
            return vectors, f"{settings.embedding_provider}:{settings.embedding_model}"
        except Exception as exc:  # noqa: BLE001
            # 远程失败不能把整个上传卡死：退回本地向量，并把原因记下来
            cause = exc.code if isinstance(exc, ModelCallFailure) else type(exc).__name__
            logger.warning("远程向量化降级 trace=%s code=RAG_EMBEDDING_FALLBACK reason=%s",
                           trace_id or "-", cause)
    return [_local_vector(text) for text in texts], "local-hash"


def _embed_remote(texts: list[str], settings, *, tenant_code: str, trace_id: str) -> list[list[float]]:
    chosen = {"base_url": settings.embedding_base_url, "api_key": settings.embedding_key(),
              "model": settings.embedding_model}
    timeout = settings.embedding_timeout
    _configuration(chosen, timeout)
    return asyncio.run(_embed_batches(texts, chosen, timeout, tenant_code=tenant_code, trace_id=trace_id))


async def _embed_batches(texts, chosen, timeout, *, tenant_code: str, trace_id: str) -> list[list[float]]:
    vectors: list[list[float]] = []
    async with _model_client(timeout) as client:
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start:start + BATCH_SIZE]
            body = {"model": chosen["model"], "input": batch, "dimensions": EMBEDDING_DIM}
            started = time.perf_counter()
            logger.info("向量化请求 trace=%s tenant=%s model=%s texts=%d",
                        trace_id or "-", tenant_code or "-", chosen["model"], len(batch))
            payload = await _send_embedding_json(chosen, body, timeout, client=client)
            vectors.extend(_batch_vectors(payload, len(batch)))
            logger.info("向量化完成 trace=%s tenant=%s batchCount=%d totalCount=%d latencyMs=%d",
                        trace_id or "-", tenant_code or "-", len(batch), len(vectors),
                        int((time.perf_counter() - started) * 1000))
    return vectors


def _batch_vectors(payload: dict, batch_size: int) -> list[list[float]]:
    items = payload.get("data")
    if not isinstance(items, list) or len(items) != batch_size:
        raise ModelCallFailure("EMBEDDING_OUTPUT_INVALID")
    indexed: dict[int, list[float]] = {}
    try:
        for item in items:
            index = item["index"]
            vector = item["embedding"]
            if type(index) is not int or not 0 <= index < batch_size or index in indexed:
                raise ValueError()
            if not isinstance(vector, list) or len(vector) != EMBEDDING_DIM:
                raise ValueError()
            if any(type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 1e6
                   for value in vector):
                raise ValueError()
            indexed[index] = [float(value) for value in vector]
    except (KeyError, OverflowError, TypeError, ValueError):
        raise ModelCallFailure("EMBEDDING_OUTPUT_INVALID") from None
    return [indexed[index] for index in range(batch_size)]


def _tokens(text: str) -> list[str]:
    """切词：中文单字 + 相邻二字组合（bigram），英文数字整词。"""
    units = _TOKEN.findall((text or "").lower())
    tokens = list(units)
    for i in range(len(units) - 1):
        left, right = units[i], units[i + 1]
        if len(left) == 1 and len(right) == 1:
            tokens.append(left + right)
    return tokens


def _local_vector(text: str) -> list[float]:
    """本地确定性向量：分词 → 哈希到固定维度 → 累加 → 归一化。

    同一段文字永远得到同一个向量，所以"同样的词"能互相匹配；
    但它不理解语义（"退款"和"退钱"不会靠近），仅作兜底。
    """
    vector = [0.0] * EMBEDDING_DIM
    tokens = _tokens(text)
    if not tokens:
        return vector
    for token in tokens:
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        value = int.from_bytes(digest, "big")
        index = value % EMBEDDING_DIM
        sign = 1.0 if (value >> 63) & 1 == 0 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm > 0:
        vector = [value / norm for value in vector]
    return vector


def to_pgvector(vector: list[float]) -> str:
    """转成 pgvector 认的字面量：'[0.1,0.2,...]'。"""
    return "[" + ",".join(f"{value:.7f}" for value in vector) + "]"
