"""LlamaIndex 负责文件读取与切块，向量化和存储走知识库的共用实现。"""

from __future__ import annotations

import logging
import os
import tempfile
from uuid import UUID

from ..config import get_settings
from ..core.trace import get_trace_id
from . import embedding as embedding_module
from . import splitter as splitter_module
from . import store

logger = logging.getLogger(__name__)

def available() -> bool:
    """Whether the optional parsing and splitting engine can be loaded."""
    import importlib.util

    try:
        return importlib.util.find_spec("llama_index.core") is not None
    except (ImportError, ValueError):
        return False


def describe() -> str:
    """给健康检查/日志用：说清楚当前用的是哪套实现。"""
    return "llama-index" if available() else "builtin"


def _splitter():
    """LlamaIndex 的切块器。

    注意 ``chunk_size`` 的单位是 **token**（不是字符）：同样是 600，
    内置实现是"600 个字符"，LlamaIndex 是"600 个 token"。中文大致 1 token ≈ 1~1.5 个字，
    所以走 LlamaIndex 时块会更大一些、块数更少。想让两边块大小接近，
    把 ``SUPPORTFLOW_AI_KB_CHUNK_SIZE`` 调小（比如 400）即可。
    """
    from llama_index.core.node_parser import SentenceSplitter

    settings = get_settings()
    return SentenceSplitter(
        chunk_size=settings.kb_chunk_size,
        chunk_overlap=settings.kb_chunk_overlap,
        # 段落优先按空行切；句子边界交给它内置的分句规则
        paragraph_separator="\n\n",
    )


def _read_file(file_name: str, payload: bytes):
    """用 SimpleDirectoryReader 解析文件：它按后缀挑 Reader（docx/pdf/md/txt 都支持）。

    LlamaIndex 的 Reader 自己还要装小依赖（读 docx 要 ``docx2txt``、读 pdf 要 ``pypdf``）。
    真遇到缺依赖时不能整篇文档索引失败——退回内置解析器把文字读出来，
    并打一行日志说清原因，否则用户只看到一句"索引失败"，不知道为什么要装包。
    """
    from llama_index.core import Document, SimpleDirectoryReader

    suffix = os.path.splitext(file_name)[1] or ".txt"
    try:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "upload" + suffix)
            with open(path, "wb") as handle:
                handle.write(payload)
            documents = SimpleDirectoryReader(input_files=[path]).load_data()
        if not documents:
            raise ValueError(f"解析出来是空的：{file_name}")
        for document in documents:
            document.metadata["file_name"] = file_name
        return documents
    except Exception as exc:  # noqa: BLE001
        from . import parser as parser_module

        logger.warning("LlamaIndex 读取失败，改用内置解析器 code=RAG_READER_FALLBACK errorType=%s",type(exc).__name__)
        text = parser_module.normalize(parser_module.parse(file_name, payload))
        if not text.strip():
            raise
        return [Document(text=text, metadata={"file_name": file_name})]


def _embed_texts(texts: list[str], tenant_code: str):
    return embedding_module.embed_texts(texts, tenant_code=tenant_code, trace_id=get_trace_id() or "-")


def _nodes_to_chunks(nodes) -> list:
    """把 LlamaIndex 的节点转成 store 认的切片对象。"""
    chunks = []
    for index, node in enumerate(nodes, start=1):
        text = (node.get_content() or "").strip()
        if not text:
            continue
        chunks.append(splitter_module.Chunk(
            index=index,
            content=text,
            char_count=len(text),
            token_count=splitter_module.estimate_tokens(text),
        ))
    return chunks


def index_document(tenant_code: str, doc_id: int, file_name: str, payload: bytes,
                   *, chunk_size: int | None = None, chunk_overlap: int | None = None,
                   attempt_id: UUID | None = None):
    """LlamaIndex 版索引：SimpleDirectoryReader 读 → SentenceSplitter 切 → 共用客户端向量化。"""
    from .pipeline import IndexResult  # 延迟导入，避免循环

    documents = _read_file(file_name, payload)
    parser = _splitter()
    if chunk_size:
        parser.chunk_size = chunk_size
    if chunk_overlap is not None:
        parser.chunk_overlap = chunk_overlap
    nodes = parser.get_nodes_from_documents(documents)
    chunks = _nodes_to_chunks(nodes)
    if not chunks:
        raise ValueError("切块结果为空，请检查文档内容")

    vectors, source = _embed_texts([chunk.content for chunk in chunks], tenant_code)

    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source, attempt_id=attempt_id)
    logger.info("LlamaIndex 索引完成 tenant=%s docId=%s 切片=%d 向量来源=%s",
                tenant_code, doc_id, saved, source)
    return IndexResult(
        doc_id=doc_id,
        file_name=file_name,
        char_count=sum(chunk.char_count for chunk in chunks),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[{"chunk_no": c.index, "char_count": c.char_count,
                  "token_count": c.token_count, "content": c.content} for c in chunks[:5]],
    )


def index_text(tenant_code: str, doc_id: int, content: str,
               *, chunk_size: int | None = None, chunk_overlap: int | None = None,
               attempt_id: UUID | None = None):
    """手工正文：包成 Document 后走同一套切块 + 向量化。"""
    from llama_index.core import Document
    from .pipeline import IndexResult

    document = Document(text=content, metadata={"file_name": ""})
    parser = _splitter()
    if chunk_size:
        parser.chunk_size = chunk_size
    if chunk_overlap is not None:
        parser.chunk_overlap = chunk_overlap
    chunks = _nodes_to_chunks(parser.get_nodes_from_documents([document]))
    if not chunks:
        raise ValueError("正文为空，没有可索引的内容")

    vectors, source = _embed_texts([chunk.content for chunk in chunks], tenant_code)

    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source, attempt_id=attempt_id)
    return IndexResult(
        doc_id=doc_id,
        file_name="",
        char_count=sum(chunk.char_count for chunk in chunks),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[{"chunk_no": c.index, "char_count": c.char_count,
                  "token_count": c.token_count, "content": c.content} for c in chunks[:5]],
    )


def search(tenant_code: str, query: str, top_k: int = 5, doc_ids: list[int] | None = None,*,published_only: bool=False) -> dict:
    """检索：使用共用向量化，再交给 pgvector 做余弦检索。"""
    from .document_scope import document_scope,published_policy
    doc_ids=document_scope(doc_ids)
    published_only=published_policy(published_only)
    if doc_ids==[]:return {"query":query,"results":[],"mode":"empty-scope","vector_source":"none"}
    vectors, source = _embed_texts([query], tenant_code)
    vector = vectors[0] if vectors else []

    local = source == "local-hash"
    results: list[dict] = []
    mode = "vector"
    if local:
        # 向量化没配密钥（退回本地兜底向量）时不做余弦检索：
        # 那种向量没有语义，检出来的结果反而误导人
        results = store.keyword_search(tenant_code, query, top_k=top_k,doc_ids=doc_ids,published_only=published_only)
        mode = "keyword-local-vector"
    elif vector and any(value != 0 for value in vector):
        results = store.search(tenant_code, vector, top_k=top_k, doc_ids=doc_ids,published_only=published_only)
    if not results:
        results = store.keyword_search(tenant_code, query, top_k=top_k,doc_ids=doc_ids,published_only=published_only)
        mode = "keyword"
    # 局部导入：pipeline 顶层也 import 了本模块，放顶层会形成循环导入
    from . import pipeline as pipeline_module
    pipeline_module.warn_if_degraded(mode, source, query)
    logger.info("知识库检索（llama-index） tenant=%s queryChars=%d 命中=%d 模式=%s 向量来源=%s",
                tenant_code, len(query), len(results), mode, source)
    return {"query": query, "results": results, "vector_source": source, "mode": mode}
