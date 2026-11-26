"""向量存取：切片写进 pgvector、按相似度检索出来。

为什么用 pgvector 而不是另起一个向量库：知识库本身就在 PostgreSQL 里（kb_document），
把切片和向量放在同一个库、同一个 tenant_code 维度下，租户隔离和备份策略都不用再想一套。
数据量真上来了（千万级切片）再换 Milvus 之类的专用库也不迟，接口就下面这几个函数。
"""

from __future__ import annotations

import logging
import re
import threading
import time
from contextlib import contextmanager
from uuid import UUID

import psycopg
from psycopg.rows import dict_row

from ..config import get_settings
from .embedding import to_pgvector
from .document_scope import document_scope, published_policy

logger = logging.getLogger(__name__)

VALID_SOURCE_AUTHORITIES = ("official", "reference", "community")


def normalize_source_authority(value: object) -> str:
    """Normalize source authority metadata with safe fallback for legacy/mocked rows."""
    if isinstance(value, str) and value in VALID_SOURCE_AUTHORITIES:
        return value
    return "official"

# 单进程内的自增序号：配合毫秒时间戳拼成 19 位雪花号（和 Java 侧的 id 风格保持一致）
_lock = threading.Lock()
_last_ms = 0
_sequence = 0


def next_id() -> int:
    """生成一个分布式 ID：毫秒时间戳左移 12 位 + 自增序号（单机够用，多实例也不会撞）。"""
    global _last_ms, _sequence
    with _lock:
        now = int(time.time() * 1000)
        if now == _last_ms:
            _sequence += 1
            if _sequence > 4095:
                time.sleep(0.001)
                now = int(time.time() * 1000)
                _sequence = 0
        else:
            _sequence = 0
        _last_ms = now
        # 时间戳左移 12 位 + 12 位自增：16 位数字，离 BIGINT 上限很远，不会撞号
        return (now << 12) | (_sequence & 0xFFF)


def connect():
    """连知识库所在的那个库（默认就是 customer_db）。"""
    settings = get_settings()
    dsn = settings.kb_database_url or _derive_kb_dsn(settings.database_url)
    return psycopg.connect(dsn, row_factory=dict_row)


@contextmanager
def tenant_connection(tenant_code: str):
    """Bind the authenticated tenant for this transaction only."""
    if not isinstance(tenant_code, str) or tenant_code.upper() == "PLATFORM" or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9_-]{0,15}", tenant_code
    ):
        raise ValueError("A valid business tenant is required")
    with connect() as conn:
        conn.execute("SELECT pg_catalog.set_config('app.tenant_code', %s, true)", (tenant_code,))
        yield conn


def _derive_kb_dsn(ai_dsn: str) -> str:
    """没单独配 kb_database_url 时，从 ai_db 的连接串推出 customer_db 的连接串。"""
    return ai_dsn.replace("/ai_db", "/customer_db")


def _lock_chunk_mutation(conn, tenant_code: str, doc_id: int) -> None:
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                 (f"supportflow:kb-chunks:{tenant_code}:{doc_id}",))


def replace_chunks(
    tenant_code: str,
    doc_id: int,
    chunks: list,
    vectors: list[list[float]],
    embedding_model: str,
    *,
    attempt_id: UUID | None = None,
) -> int:
    """Replace legacy chunks or stage a generation for an authorized document write."""
    if len(chunks) != len(vectors):
        raise ValueError(f"切片数与向量数不一致：{len(chunks)} vs {len(vectors)}")
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        _lock_chunk_mutation(conn, tenant_code, doc_id)
        rows = [
            (
                next_id(),
                tenant_code,
                doc_id,
                chunk.index,
                chunk.content,
                chunk.char_count,
                chunk.token_count,
                to_pgvector(vector) if vector else None,
                embedding_model,
            )
            for chunk, vector in zip(chunks, vectors)
        ]
        if attempt_id is None:
            cur.execute("DELETE FROM kb_chunk WHERE tenant_code = %s AND doc_id = %s", (tenant_code, doc_id))
            cur.executemany("""
                INSERT INTO kb_chunk
                    (id, tenant_code, doc_id, chunk_no, content, char_count, token_count, embedding, embedding_model)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s)
                """, rows)
        else:
            cur.execute("""
                DELETE FROM kb_chunk_stage WHERE tenant_code=%s AND doc_id=%s AND attempt_id=%s
                """, (tenant_code, doc_id, attempt_id))
            cur.execute("""
                DELETE FROM kb_chunk_stage WHERE tenant_code=%s AND ctid IN (
                    SELECT ctid FROM kb_chunk_stage WHERE tenant_code=%s
                      AND create_time < statement_timestamp() - interval '24 hours'
                    ORDER BY create_time LIMIT 256
                )
                """, (tenant_code, tenant_code))
            cur.executemany("""
                INSERT INTO kb_chunk_stage
                    (id, tenant_code, doc_id, chunk_no, content, char_count, token_count, embedding, embedding_model, attempt_id)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s, %s)
                """, [(*row, attempt_id) for row in rows])
        return len(rows)


def delete_chunks(tenant_code: str, doc_id: int) -> int:
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        _lock_chunk_mutation(conn, tenant_code, doc_id)
        cur.execute("DELETE FROM kb_chunk WHERE tenant_code = %s AND doc_id = %s", (tenant_code, doc_id))
        return cur.rowcount


def list_chunks(tenant_code: str, doc_id: int, limit: int = 200) -> list[dict]:
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT id, doc_id, chunk_no, content, char_count, token_count, embedding_model, create_time
              FROM kb_chunk
             WHERE tenant_code = %s AND doc_id = %s
             ORDER BY chunk_no
             LIMIT %s
            """,
            (tenant_code, doc_id, limit),
        )
        return cur.fetchall()


def search(tenant_code: str, vector: list[float], top_k: int = 5, doc_ids: list[int] | None = None, *, published_only: bool = False) -> list[dict]:
    """按余弦相似度检索：走 HNSW 索引，返回相似度降序的切片。"""
    doc_ids = document_scope(doc_ids)
    published_only = published_policy(published_only)
    if not vector or doc_ids == []:
        return []
    literal = to_pgvector(vector)
    sql = """
        SELECT c.id, c.doc_id, c.chunk_no, c.content, c.embedding_model,
               d.title AS doc_title, d.status AS doc_status,
               COALESCE(d.source_authority, 'official') AS source_authority,
               1 - (c.embedding <=> %s::vector) AS score
          FROM kb_chunk c
          JOIN kb_document d ON d.id = c.doc_id AND d.tenant_code = c.tenant_code
         WHERE c.tenant_code = %s
           AND c.embedding IS NOT NULL
           AND d.is_deleted = FALSE
    """
    params: list = [literal, tenant_code]
    if published_only:
        sql += ' AND d.status = 3 AND d.index_status = 3'
    if doc_ids is not None:
        sql += " AND c.doc_id = ANY(%s)"
        params.append(doc_ids)
    sql += " ORDER BY c.embedding <=> %s::vector LIMIT %s"
    params.extend([literal, max(1, min(top_k, 50))])
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        # HNSW 是"先按向量取 K 个、再按 WHERE 过滤"（后过滤）。
        # 多租户同库时，别的租户的切片会占掉候选名额，导致本租户召回不足。
        # 把 ef_search 调大能明显缓解；彻底解决要按租户分区或独立表（第 27 篇再展开）。
        cur.execute("SET LOCAL hnsw.ef_search = 200")
        cur.execute(sql, params)
        rows = cur.fetchall()
    for row in rows:
        row["score"] = round(float(row["score"]), 4)
        row["create_time"] = str(row.get("create_time") or "")
        row["source_authority"] = normalize_source_authority(row.get("source_authority"))
    return rows


vector_search = search


# 中文没有分词器时的通用做法：把查询切成"相邻两字"的片段（bigram）逐个匹配。
# 例："退货多久能到账" → 退货 / 货多 / 多久 / 久能 / 能到 / 到账
# 只要文档里出现"到账"，这条查询就能把它捞出来——
# 比"拿整句话当子串去找"（ILIKE '%退货多久能到账%'，几乎必然 0 条）实用得多。
_CJK_RUN = re.compile(r"[\u4e00-\u9fff]+")
_ASCII_RUN = re.compile(r"[A-Za-z0-9]+")
MAX_QUERY_TERMS = 20

# 虚词字表：用于把"可以 / 之后 / 多久 / 久可 / 以到"这类纯虚词片段从查询里剔掉。
#
# 为什么必须剔：中文没有分词时靠"相邻两字"凑片段，一句"退款之后多久可以到账？"
# 会切出 9 个片段，其中 7 个是虚词组合。它们在语料里到处都是，
# 于是"退款开票怎么处理""价保规则"这种沾边的块能靠一个"可以"拿到分数，
# 把真正写着"退款到账时效"的那一块挤出 top_k——模型拿到的全是无关资料，
# 只能如实回一句"资料里没有提到退款到账的时效"，看起来就像"知识库没用"。
#
# 规则定得很保守：**整条片段都由虚词字组成才丢**。
# 所以"到账"（到是虚词、账不是）会保留，"退货""退款"更不会受影响。
FUNCTION_CHARS = set(
    "的了着过吗呢吧啊呀么什怎为可以之后多久能会要想请问你我他她它们这那哪些个"
    "是有没不就都也还再又很太好给让把被在和与或及等至从对上下里外另还用做来说去"
    "把被叫让给跟向对为于"
)


def query_terms(query: str, max_terms: int = MAX_QUERY_TERMS) -> list[str]:
    """把查询拆成可匹配的片段：中文取相邻两字，英文数字取整词，去重后最多 20 个。

    纯虚词组成的片段（可以 / 之后 / 多久…）会被丢掉：它们对"这段话在问什么"
    没有任何区分度，留着只会把噪音文档顶上来，见 ``FUNCTION_CHARS`` 的说明。
    """
    text = (query or "").strip()
    if not text:
        return []
    terms: list[str] = []
    for run in _ASCII_RUN.findall(text.lower()):
        if len(run) >= 2:
            terms.append(run)
    for run in _CJK_RUN.findall(text):
        for index in range(len(run) - 1):
            terms.append(run[index:index + 2])
    seen: set[str] = set()
    result: list[str] = []
    for term in terms:
        if term not in seen:
            seen.add(term)
            if _is_function_fragment(term):
                continue
            result.append(term)
    return result[:max_terms]


def _is_function_fragment(term: str) -> bool:
    """整条片段是否都由虚词字组成（是的话就没有检索价值）。"""
    if len(term) != 2:
        return False
    return all(char in FUNCTION_CHARS for char in term)


def keyword_search(tenant_code: str, query: str, top_k: int = 5, doc_ids: list[int] | None = None, *, published_only: bool = False) -> list[dict]:
    """关键词兜底检索：把查询拆成片段，命中越多排得越前。

    什么时候用：没配向量密钥（本地兜底向量没有语义），或者向量检索一条都没命中。
    返回的 score 是"命中片段数 / 查询片段数"，所以是 0~1 之间的匹配度，不是余弦相似度。
    """
    doc_ids = document_scope(doc_ids)
    published_only = published_policy(published_only)
    if doc_ids == []:
        return []
    terms = query_terms(query)
    if not terms:
        return []
    hit_case = " + ".join(["CASE WHEN c.content ILIKE %s THEN 1 ELSE 0 END"] * len(terms))
    hit_where = " OR ".join(["c.content ILIKE %s"] * len(terms))
    patterns = [f"%{term}%" for term in terms]
    scope_sql = ' AND c.doc_id = ANY(%s)' if doc_ids is not None else ''
    visibility_sql = ' AND d.status = 3 AND d.index_status = 3' if published_only else ''
    sql = f"""
        SELECT c.id, c.doc_id, c.chunk_no, c.content, c.embedding_model,
               d.title AS doc_title, d.status AS doc_status,
               COALESCE(d.source_authority, 'official') AS source_authority,
               ({hit_case})::float / %s AS score
          FROM kb_chunk c
          JOIN kb_document d ON d.id = c.doc_id AND d.tenant_code = c.tenant_code
         WHERE c.tenant_code = %s
           AND d.is_deleted = FALSE
           AND ({hit_where})
           {scope_sql}
           {visibility_sql}
         ORDER BY score DESC, c.doc_id DESC, c.chunk_no
         LIMIT %s
    """
    params = patterns + [len(terms), tenant_code] + patterns
    if doc_ids is not None:
        params.append(doc_ids)
    params.append(max(1, min(top_k, 50)))
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()
    for row in rows:
        row["score"] = round(float(row["score"]), 4)
        row["create_time"] = str(row.get("create_time") or "")
        row["source_authority"] = normalize_source_authority(row.get("source_authority"))
    return rows


def _candidate_identity(hit: dict) -> tuple[object, object]:
    """Identify a chunk across legs by (doc_id, chunk_no) with id fallback."""
    doc_id = hit.get("doc_id")
    chunk_no = hit.get("chunk_no")
    chunk_id = hit.get("id")
    by_doc = ("doc_chunk", doc_id, chunk_no) if doc_id is not None and chunk_no is not None else None
    by_id = ("id", chunk_id) if chunk_id is not None else None
    return by_doc, by_id


def hybrid_search(
    tenant_code: str,
    query: str,
    query_vector: list[float] | None,
    top_k: int = 5,
    candidate_depth: int = 15,
    rrf_k: int = 60,
    doc_ids: list[int] | None = None,
    published_only: bool = False,
) -> list[dict]:
    """Combine vector_search and keyword_search via Reciprocal Rank Fusion (RRF)."""
    doc_ids = document_scope(doc_ids)
    published_only = published_policy(published_only)
    if doc_ids == []:
        return []
    limit = max(1, min(int(top_k), 50))
    depth = max(limit, min(int(candidate_depth), 50))
    rrf_constant = max(1, int(rrf_k))

    has_vector = bool(query_vector) and any(value != 0 for value in query_vector)
    vector_hits = (
        search(tenant_code, query_vector, top_k=depth, doc_ids=doc_ids, published_only=published_only)
        if has_vector
        else []
    )
    keyword_hits = (
        keyword_search(tenant_code, query, top_k=depth, doc_ids=doc_ids, published_only=published_only)
        if (query or "").strip()
        else []
    )

    merged: list[dict] = []
    key_to_slot: dict[object, int] = {}

    for leg_name, hits in (("vector", vector_hits), ("keyword", keyword_hits)):
        seen_slots: set[int] = set()
        for rank, raw_hit in enumerate(hits, start=1):
            by_doc, by_id = _candidate_identity(raw_hit)
            slot = None
            if by_doc is not None and by_doc in key_to_slot:
                slot = key_to_slot[by_doc]
            elif by_id is not None and by_id in key_to_slot:
                slot = key_to_slot[by_id]

            if slot is None:
                entry = {
                    "hit": dict(raw_hit),
                    "rrf_score": 0.0,
                    "best_rank": rank,
                    "vector_score": None,
                    "keyword_score": None,
                }
                entry["hit"]["source_authority"] = normalize_source_authority(
                    entry["hit"].get("source_authority")
                )
                slot = len(merged)
                merged.append(entry)
            if by_doc is not None:
                key_to_slot[by_doc] = slot
            if by_id is not None:
                key_to_slot[by_id] = slot

            if slot in seen_slots:
                continue
            seen_slots.add(slot)
            target = merged[slot]
            target["rrf_score"] += 1.0 / (rrf_constant + rank)
            target["best_rank"] = min(target["best_rank"], rank)
            leg_score = float(raw_hit.get("score") or 0.0)
            if leg_name == "vector":
                target["vector_score"] = round(leg_score, 4)
            else:
                target["keyword_score"] = round(leg_score, 4)

    ordered = sorted(
        merged,
        key=lambda item: (
            -round(item["rrf_score"], 10),
            item["best_rank"],
            -max(item["vector_score"] or 0.0, item["keyword_score"] or 0.0),
            int(item["hit"].get("id") or 0),
        ),
    )
    results: list[dict] = []
    for item in ordered[:limit]:
        row = dict(item["hit"])
        rrf_val = round(item["rrf_score"], 6)
        row["rrf_score"] = rrf_val
        row["score"] = rrf_val
        row["vector_score"] = item["vector_score"]
        row["keyword_score"] = item["keyword_score"]
        results.append(row)
    return results


def published_evidence_is_current(tenant_code: str, hits: list[dict], doc_ids: list[int] | None) -> bool:
    """Verify the exact evidence supplied to generation in a new scoped snapshot."""
    if not 1 <= len(hits) <= 50:
        return False
    scope = document_scope(doc_ids)
    expected: dict[int, tuple] = {}
    for hit in hits:
        chunk_id = hit["id"]
        raw_authority = hit.get("source_authority")
        authority = "official" if raw_authority is None else raw_authority
        expected[chunk_id] = (
            hit["doc_id"],
            hit["chunk_no"],
            hit["content"],
            hit["doc_title"],
            authority,
        )
    if len(expected) != len(hits) or any(type(chunk_id) is not int or not 1 <= chunk_id <= 9223372036854775807
                                         for chunk_id in expected):
        return False
    if scope is not None and any(value[0] not in scope for value in expected.values()):
        return False
    with tenant_connection(tenant_code) as conn, conn.cursor() as cur:
        cur.execute("SET LOCAL statement_timeout = '2000ms'")
        cur.execute("""
            SELECT c.id, c.doc_id, c.chunk_no, c.content, d.title AS doc_title,
                   COALESCE(d.source_authority, 'official') AS source_authority
              FROM kb_chunk c JOIN kb_document d ON d.id=c.doc_id AND d.tenant_code=c.tenant_code
             WHERE c.tenant_code=%s AND c.id=ANY(%s)
               AND d.is_deleted=FALSE AND d.status=3 AND d.index_status=3
        """, (tenant_code, list(expected)))
        actual = {
            row["id"]: (
                row["doc_id"],
                row["chunk_no"],
                row["content"],
                row["doc_title"],
                normalize_source_authority(row.get("source_authority")),
            )
            for row in cur.fetchall()
        }
    return actual == expected


recheck_published_chunks = published_evidence_is_current


def ping() -> bool:
    """探活：给健康检查用。"""
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT embedding FROM kb_chunk LIMIT 0")
            return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("知识库探活失败 code=KB_STORE_UNAVAILABLE errorType=%s", type(exc).__name__)
        return False
