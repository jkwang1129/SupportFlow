"""模型训练中心：把意图语料真正向量化，评一把，落成一个**可以切换的版本**。

这个模块回答「训的是什么」：不是去微调大模型（那需要厂商侧的训练接口与算力），
而是**重建机器人的意图识别索引**——

    语料（页面维护的 + 真实会话里弱标注的）
      → 向量化（与知识库同一套 embedding 接口，1024 维）
      → 留一法评估（拿每条语料去掉自己去匹配，看能不能回到同一个意图）
      → 落成一个版本（bot_version + bot_intent_sample）
      → 识别时按「线上版本」的向量匹配意图（灰度版本按比例生效）

所以「训练」是真的在算：换了语料、换了版本，机器人的意图识别结果就会跟着变。

两个刻意的设计：

  1. **训练在后台线程里跑，进度写库**：页面 2 秒轮询一次就能看到
     「采集语料 → 向量化 x/y → 评估识别率 → 落库版本」的真实进度，
     而不是一个假的动画；
  2. **增量训练复用旧向量**：上一次版本里没变的语料直接把向量抄过来，
     只给新语料算向量——这才是"增量"，也是页面上 v9.2 那种小版本号的含义。
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
import threading
from datetime import datetime
from typing import Any

from ..core.db import connect
from ..rag.embedding import embed_texts
from ..rag.store import next_id

logger = logging.getLogger(__name__)

# 版本状态（与 bot_version.status 对齐）
V_TRAINING = 1
V_GRAY = 2
V_ONLINE = 3
V_RETIRED = 4

VERSION_STATUS_TEXT = {V_TRAINING: "训练中", V_GRAY: "灰度中", V_ONLINE: "已上线", V_RETIRED: "已下线"}

# 任务状态（与 training_job.status 对齐）
J_WAITING = 1
J_RUNNING = 2
J_SUCCEEDED = 3
J_FAILED = 4

JOB_STATUS_TEXT = {J_WAITING: "等待中", J_RUNNING: "执行中", J_SUCCEEDED: "成功", J_FAILED: "失败"}

# 训练模式
MODE_FULL = 1
MODE_INCREASE = 2

MODE_TEXT = {MODE_FULL: "全量", MODE_INCREASE: "增量"}

# 一条语料最长多少字：太长的"问题"多半是粘贴的一整段，向量化没有意义
SAMPLE_MAX_CHARS = 200
# 评估阶段最多评估多少条（留一法要两两比，样本多了会很慢）
EVAL_LIMIT = 400
# 历史会话最多取多少条做弱标注语料
HISTORY_LIMIT = 300
# 弱标注的门槛：机器人当时判出来的意图置信度要够高，否则宁可不进语料
HISTORY_MIN_CONFIDENCE = 60.0

_SPLIT = re.compile(r"[\n,，;；、|]+")


# --------------------------------------------------------------------------- 语料


def split_samples(text: str | None) -> list[str]:
    """把页面里一行/一行维护的语料切成一条条样本（换行、逗号、顿号、分号都算分隔）"""
    if not text:
        return []
    result: list[str] = []
    for piece in _SPLIT.split(str(text)):
        sample = piece.strip()
        if not sample:
            continue
        sample = sample[:SAMPLE_MAX_CHARS]
        if sample not in result:
            result.append(sample)
    return result


def collect_corpus(tenant_code: str) -> list[dict[str, Any]]:
    """采集语料：① 页面维护的意图语料 ② 历史真实会话里判过意图的问题（弱标注）。"""
    corpus: list[dict[str, Any]] = []
    seen: set[str] = set()
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT intent_code, name, samples
              FROM bot_intent
             WHERE tenant_code = %s AND is_deleted = FALSE AND status = 1
             ORDER BY id
            """,
            (tenant_code,),
        ).fetchall()
        for row in rows:
            for sample in split_samples(row.get("samples")):
                key = sample.lower()
                if key in seen:
                    continue
                seen.add(key)
                corpus.append({
                    "intentCode": row.get("intent_code"),
                    "intentName": row.get("name"),
                    "sample": sample,
                    "source": 1,
                })

        history = conn.execute(
            """
            SELECT last_intent_code, last_intent, last_message
              FROM bot_dialogue
             WHERE tenant_code = %s AND is_deleted = FALSE
               AND last_intent_code IS NOT NULL AND last_intent_code <> ''
               AND COALESCE(last_confidence, 0) >= %s
               AND last_message IS NOT NULL AND last_message <> ''
             ORDER BY update_time DESC
             LIMIT %s
            """,
            (tenant_code, HISTORY_MIN_CONFIDENCE, HISTORY_LIMIT),
        ).fetchall()
        for row in history:
            sample = str(row.get("last_message") or "").strip()[:SAMPLE_MAX_CHARS]
            key = sample.lower()
            if not sample or key in seen:
                continue
            seen.add(key)
            corpus.append({
                "intentCode": row.get("last_intent_code"),
                "intentName": row.get("last_intent"),
                "sample": sample,
                "source": 2,
            })
    return corpus


# --------------------------------------------------------------------------- 版本


def _parse_version(version_no: str | None) -> tuple[int, int]:
    match = re.match(r"v(\d+)\.(\d+)$", (version_no or "").strip())
    if not match:
        return 0, 0
    return int(match.group(1)), int(match.group(2))


def _version_rows(tenant_code: str, limit: int = 20) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT version_no, train_type, base_model, accuracy, status, gray_percent,
                   sample_count, intent_count, job_no, remark, publish_time, create_time
              FROM bot_version
             WHERE tenant_code = %s AND is_deleted = FALSE
             ORDER BY create_time DESC, id DESC
             LIMIT %s
            """,
            (tenant_code, limit),
        ).fetchall()
    return [dict(row) for row in rows]


def latest_version(tenant_code: str) -> dict[str, Any] | None:
    """最近一次训练产出的版本（不管有没有上线）——增量训练以它为基线。"""
    rows = _version_rows(tenant_code, limit=50)
    if not rows:
        return None
    return max(rows, key=lambda row: _parse_version(row.get("version_no")))


def online_version(tenant_code: str) -> dict[str, Any] | None:
    """当前全量上线的版本（status=3）"""
    rows = [row for row in _version_rows(tenant_code, limit=50) if row.get("status") == V_ONLINE]
    if not rows:
        return None
    return max(rows, key=lambda row: _parse_version(row.get("version_no")))


def gray_version(tenant_code: str) -> dict[str, Any] | None:
    """当前灰度中的版本（status=2）"""
    rows = [row for row in _version_rows(tenant_code, limit=50) if row.get("status") == V_GRAY]
    if not rows:
        return None
    return max(rows, key=lambda row: _parse_version(row.get("version_no")))


def _next_version_no(tenant_code: str, mode: int) -> str:
    """全量训练进大版本（v1.0 → v2.0），增量训练进小版本（v1.0 → v1.1）"""
    base = latest_version(tenant_code)
    major, minor = _parse_version(base.get("version_no") if base else None)
    if mode == MODE_FULL:
        return f"v{major + 1}.0"
    if major == 0:
        return "v1.0"
    return f"v{major}.{minor + 1}"


def version_view(row: dict[str, Any]) -> dict[str, Any]:
    """版本行 → 页面用的形状（驼峰 + 文案 + 百分比）"""
    status = int(row.get("status") or V_TRAINING)
    train_type = int(row.get("train_type") or MODE_INCREASE)
    publish_time = row.get("publish_time")
    create_time = row.get("create_time")
    return {
        "versionNo": row.get("version_no"),
        "mode": train_type,
        "modeText": MODE_TEXT.get(train_type, "增量"),
        "status": status,
        "statusText": VERSION_STATUS_TEXT.get(status, "未知"),
        "grayPercent": int(row.get("gray_percent") or 0),
        "sampleCount": int(row.get("sample_count") or 0),
        "intentCount": int(row.get("intent_count") or 0),
        "accuracy": float(row["accuracy"]) if row.get("accuracy") is not None else None,
        "jobNo": row.get("job_no"),
        "remark": row.get("remark"),
        "baseModel": row.get("base_model"),
        "publishTime": publish_time.strftime("%Y-%m-%d %H:%M:%S") if isinstance(publish_time, datetime) else None,
        "createTime": create_time.strftime("%Y-%m-%d %H:%M:%S") if isinstance(create_time, datetime) else None,
    }


# --------------------------------------------------------------------------- 训练任务


def _job_view(row: dict[str, Any]) -> dict[str, Any]:
    status = int(row.get("status") or J_WAITING)
    mode = int(row.get("mode") or MODE_INCREASE)
    start = row.get("start_time")
    finish = row.get("finish_time")
    return {
        "jobNo": row.get("job_no"),
        "mode": mode,
        "modeText": MODE_TEXT.get(mode, "增量"),
        "status": status,
        "statusText": JOB_STATUS_TEXT.get(status, "未知"),
        "progress": int(row.get("progress") or 0),
        "phase": row.get("phase"),
        "sampleCount": int(row.get("sample_count") or 0),
        "intentCount": int(row.get("intent_count") or 0),
        "grayPercent": int(row.get("gray_percent") or 0),
        "resultVersion": row.get("result_version"),
        "errorMessage": row.get("error_msg"),
        "startTime": start.strftime("%Y-%m-%d %H:%M:%S") if isinstance(start, datetime) else None,
        "finishTime": finish.strftime("%Y-%m-%d %H:%M:%S") if isinstance(finish, datetime) else None,
        "createTime": row.get("create_time").strftime("%Y-%m-%d %H:%M:%S")
        if isinstance(row.get("create_time"), datetime) else None,
    }


def list_jobs(tenant_code: str, limit: int = 10) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT job_no, mode, status, progress, phase, sample_count, intent_count,
                   gray_percent, result_version, error_msg, start_time, finish_time, create_time
              FROM training_job
             WHERE tenant_code = %s AND is_deleted = FALSE
             ORDER BY create_time DESC, id DESC
             LIMIT %s
            """,
            (tenant_code, limit),
        ).fetchall()
    return [_job_view(dict(row)) for row in rows]


def get_job(tenant_code: str, job_no: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT job_no, mode, status, progress, phase, sample_count, intent_count,
                   gray_percent, result_version, error_msg, start_time, finish_time, create_time
              FROM training_job
             WHERE tenant_code = %s AND job_no = %s AND is_deleted = FALSE
             LIMIT 1
            """,
            (tenant_code, job_no),
        ).fetchone()
    return _job_view(dict(row)) if row else None


def _update_job(job_id: int, **fields: Any) -> None:
    if not fields:
        return
    columns = ", ".join(f"{key} = %s" for key in fields)
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            f"UPDATE training_job SET {columns}, update_time = CURRENT_TIMESTAMP WHERE id = %s",
            (*fields.values(), job_id),
        )
        conn.commit()


def start_job(tenant_code: str, mode: int, gray_percent: int) -> dict[str, Any]:
    """建一个训练任务并在后台线程里执行；同一租户同时只允许一个训练任务。"""
    if mode not in (MODE_FULL, MODE_INCREASE):
        raise ValueError("训练模式只能是 1-全量 / 2-增量")
    gray = max(0, min(int(gray_percent or 0), 100))
    running = [job for job in list_jobs(tenant_code, limit=20)
               if job["status"] in (J_WAITING, J_RUNNING)]
    if running:
        raise ValueError(f"已有训练任务在进行中（{running[0]['jobNo']}），等它跑完再发起新的")

    job_no = "TJ" + datetime.now().strftime("%Y%m%d%H%M%S") + str(next_id())[-4:]
    job_id = next_id()
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO training_job
                  (id, tenant_code, job_no, job_type, mode, status, progress, phase,
                   sample_count, intent_count, gray_percent, creator)
            VALUES (%s, %s, %s, 1, %s, %s, 0, %s, 0, 0, %s, 'SELF_SERVICE')
            """,
            (job_id, tenant_code, job_no, mode, J_WAITING, "排队中", gray),
        )
        conn.commit()
    logger.info("训练任务已创建 tenant=%s job=%s mode=%s gray=%s%%",
                tenant_code, job_no, MODE_TEXT[mode], gray)
    threading.Thread(
        target=_run_job, args=(job_id, tenant_code, job_no, mode, gray),
        name=f"training-{job_no}", daemon=True,
    ).start()
    return get_job(tenant_code, job_no) or {}


def _run_job(job_id: int, tenant_code: str, job_no: str, mode: int, gray_percent: int) -> None:
    """真正干活的地方：采集 → 向量化 → 评估 → 落库版本。任何异常都写进任务里。"""
    version_no = ""
    try:
        _update_job(job_id, status=J_RUNNING, phase="采集语料", progress=5,
                    start_time=datetime.now())
        corpus = collect_corpus(tenant_code)
        if not corpus:
            raise RuntimeError("没有可用语料：先在「意图与语料」里填几条，或者让机器人接待几轮真实会话")
        intents = {item["intentCode"] for item in corpus}
        _update_job(job_id, phase="向量化", progress=15,
                    sample_count=len(corpus), intent_count=len(intents))

        base = latest_version(tenant_code) if mode == MODE_INCREASE else None
        version_no = _next_version_no(tenant_code, mode)
        rows = _build_samples(tenant_code, version_no, corpus, base)

        _update_job(job_id, phase="评估识别率", progress=85)
        accuracy = _evaluate(rows)

        _update_job(job_id, phase="落库版本", progress=95)
        _save_version(tenant_code, version_no, mode, gray_percent, rows, accuracy, job_no)
        _update_job(job_id, status=J_SUCCEEDED, phase="完成", progress=100,
                    result_version=version_no, finish_time=datetime.now())
        logger.info("训练完成 tenant=%s job=%s version=%s 语料=%d 识别率=%.2f%%",
                    tenant_code, job_no, version_no, len(rows), accuracy)
    except Exception as exc:  # noqa: BLE001
        logger.warning("训练失败 tenant=%s job=%s error=%s", tenant_code, job_no, exc)
        _update_job(job_id, status=J_FAILED, phase="失败", error_msg=str(exc)[:500],
                    finish_time=datetime.now())
        if version_no:
            with connect() as conn, conn.cursor() as cur:
                cur.execute("DELETE FROM bot_intent_sample WHERE tenant_code = %s AND version_no = %s",
                            (tenant_code, version_no))
                cur.execute("DELETE FROM bot_version WHERE tenant_code = %s AND version_no = %s",
                            (tenant_code, version_no))
                conn.commit()


def _build_samples(tenant_code: str, version_no: str, corpus: list[dict[str, Any]],
                   base: dict[str, Any] | None) -> list[dict[str, Any]]:
    """把语料变成带向量的样本行；增量训练复用上一版已有的向量。"""
    reused: dict[str, dict[str, Any]] = {}
    if base is not None:
        with connect() as conn:
            old = conn.execute(
                """
                SELECT intent_code, intent_name, sample, source, embedding, embedding_model
                  FROM bot_intent_sample
                 WHERE tenant_code = %s AND version_no = %s
                """,
                (tenant_code, base.get("version_no")),
            ).fetchall()
        for row in old:
            reused[str(row.get("sample"))] = dict(row)

    rows: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    for item in corpus:
        cached = reused.get(item["sample"])
        if cached is not None and cached.get("embedding") is not None:
            rows.append({**item, "embedding": _to_vector(cached["embedding"]),
                         "embeddingModel": cached.get("embedding_model")})
        else:
            pending.append(item)

    if pending:
        vectors, source = embed_texts([item["sample"] for item in pending], tenant_code=tenant_code)
        for item, vector in zip(pending, vectors):
            rows.append({**item, "embedding": vector, "embeddingModel": source})
    logger.info("语料向量化 tenant=%s version=%s 复用=%d 新算=%d",
                tenant_code, version_no, len(rows) - len(pending), len(pending))

    with connect() as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM bot_intent_sample WHERE tenant_code = %s AND version_no = %s",
                    (tenant_code, version_no))
        for row in rows:
            cur.execute(
                """
                INSERT INTO bot_intent_sample
                      (id, tenant_code, version_no, intent_code, intent_name, sample, source,
                       embedding, embedding_model)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (next_id(), tenant_code, version_no, row["intentCode"], row["intentName"],
                 row["sample"], row["source"], _as_vector(row["embedding"]), row["embeddingModel"]),
            )
        conn.commit()
    return rows


def _evaluate(rows: list[dict[str, Any]]) -> float:
    """留一法识别率：去掉自己，看最近的那条语料是不是同一个意图。

    这是能自证的口径——不编"识别率 96.3%"，跑出来是多少就是多少。
    """
    pool = [row for row in rows if row.get("embedding")][:EVAL_LIMIT]
    if len(pool) < 2:
        return 0.0
    correct = 0
    for index, row in enumerate(pool):
        best_code, best_score = None, -1.0
        for other_index, other in enumerate(pool):
            if other_index == index or other["intentCode"] == row["intentCode"] and other["sample"] == row["sample"]:
                continue
            score = _cosine(row["embedding"], other["embedding"])
            if score > best_score:
                best_code, best_score = other["intentCode"], score
        if best_code == row["intentCode"]:
            correct += 1
    return round(correct * 100.0 / len(pool), 2)


def _save_version(tenant_code: str, version_no: str, mode: int, gray_percent: int,
                  rows: list[dict[str, Any]], accuracy: float, job_no: str) -> None:
    """落版本行，并按灰度决定谁是"当前生效"的版本。"""
    previous = online_version(tenant_code)
    status = V_GRAY if gray_percent > 0 else V_ONLINE
    model_source = next((row.get("embeddingModel") for row in rows if row.get("embeddingModel")), None)
    delta = ""
    if previous is not None and previous.get("accuracy") is not None:
        diff = round(accuracy - float(previous["accuracy"]), 2)
        delta = f"，较 {previous.get('version_no')} " + (f"提升 {diff}%" if diff >= 0 else f"下降 {abs(diff)}%")
    remark = (f"{MODE_TEXT[mode]}训练 · 语料 {len(rows)} 条 / 意图 {len(set(r['intentCode'] for r in rows))} 个 · "
              f"识别率 {accuracy}%{delta}")

    with connect() as conn, conn.cursor() as cur:
        cur.execute("UPDATE bot_version SET status = %s, update_time = CURRENT_TIMESTAMP "
                    "WHERE tenant_code = %s AND status IN (%s, %s) AND is_deleted = FALSE",
                    (V_RETIRED, tenant_code, V_ONLINE, V_GRAY))
        cur.execute(
            """
            INSERT INTO bot_version
                  (id, tenant_code, version_no, train_type, base_model, accuracy, status,
                   gray_percent, sample_count, intent_count, job_no, remark, publish_time, creator)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'SELF_SERVICE')
            """,
            (next_id(), tenant_code, version_no, mode, model_source, accuracy, status,
             gray_percent, len(rows), len(set(r["intentCode"] for r in rows)), job_no, remark,
             datetime.now() if status == V_ONLINE else None),
        )
        conn.commit()


def publish(tenant_code: str, version_no: str, gray_percent: int) -> dict[str, Any]:
    """把某个版本发布到线上：gray=0 是全量，>0 是灰度（旧版本继续兜底）。"""
    gray = max(0, min(int(gray_percent or 0), 100))
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, status FROM bot_version WHERE tenant_code = %s AND version_no = %s "
            "AND is_deleted = FALSE LIMIT 1",
            (tenant_code, version_no),
        )
        row = cur.fetchone()
        if row is None:
            raise ValueError(f"版本不存在：{version_no}")
        if gray == 0:
            # 全量上线：其它版本下线，这个版本置为「已上线」
            cur.execute("UPDATE bot_version SET status = %s, gray_percent = 0, "
                        "update_time = CURRENT_TIMESTAMP "
                        "WHERE tenant_code = %s AND version_no <> %s AND is_deleted = FALSE",
                        (V_RETIRED, tenant_code, version_no))
            cur.execute("UPDATE bot_version SET status = %s, gray_percent = 0, "
                        "publish_time = COALESCE(publish_time, CURRENT_TIMESTAMP), "
                        "update_time = CURRENT_TIMESTAMP WHERE id = %s",
                        (V_ONLINE, row["id"]))
        else:
            # 灰度：这个版本置为灰度中，**必须留一个「已上线」的兜底版本**——
            # 否则 resolve_version 只能全打灰度版本，"灰度 50%" 会变成 100%（踩过）。
            cur.execute("UPDATE bot_version SET status = %s, gray_percent = 0, "
                        "update_time = CURRENT_TIMESTAMP "
                        "WHERE tenant_code = %s AND version_no <> %s AND is_deleted = FALSE "
                        "AND status = %s",
                        (V_ONLINE, tenant_code, version_no, V_GRAY))
            cur.execute("UPDATE bot_version SET status = %s, gray_percent = %s, "
                        "update_time = CURRENT_TIMESTAMP WHERE id = %s",
                        (V_GRAY, gray, row["id"]))
            cur.execute("SELECT COUNT(1) AS online FROM bot_version "
                        "WHERE tenant_code = %s AND status = %s AND is_deleted = FALSE",
                        (tenant_code, V_ONLINE))
            if int((cur.fetchone() or {}).get("online") or 0) == 0:
                # 没有兜底版本（比如刚训练完就灰度）：把版本号最大的历史版本提上来当兜底
                cur.execute(
                    """
                    UPDATE bot_version SET status = %s, update_time = CURRENT_TIMESTAMP
                     WHERE id = (SELECT id FROM bot_version
                                  WHERE tenant_code = %s AND is_deleted = FALSE
                                    AND version_no <> %s AND status = %s
                                  ORDER BY (substring(version_no from 2))::numeric DESC
                                  LIMIT 1)
                    """,
                    (V_ONLINE, tenant_code, version_no, V_RETIRED))
        conn.commit()
    logger.info("版本发布 tenant=%s version=%s gray=%s%%", tenant_code, version_no, gray)
    return overview(tenant_code)


def rollback(tenant_code: str, version_no: str) -> dict[str, Any]:
    """回滚：把指定版本重新置为「已上线」（全量），其余版本下线。"""
    return publish(tenant_code, version_no, 0)


# --------------------------------------------------------------------------- 识别时的版本


def resolve_version(tenant_code: str, session_no: str | None = None) -> str | None:
    """这次请求用哪个版本：有灰度版本时按「租户 + 会话」稳定哈希决定。"""
    gray = gray_version(tenant_code)
    if gray is None:
        online = online_version(tenant_code)
        return online.get("version_no") if online else None
    percent = int(gray.get("gray_percent") or 0)
    if percent <= 0:
        return gray.get("version_no")
    bucket = _bucket(tenant_code, session_no)
    if bucket < percent:
        return gray.get("version_no")
    online = online_version(tenant_code)
    return (online or gray).get("version_no")


def _bucket(tenant_code: str, session_no: str | None) -> int:
    """0~99 的稳定桶号：同一个会话每次都会落进同一个桶，灰度才不会"这次新、下次旧"。"""
    key = f"{tenant_code}|{session_no or ''}"
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return int(digest[:8], 16) % 100


def vector_match(tenant_code: str, question: str, version_no: str | None = None,
                 session_no: str | None = None) -> dict[str, Any] | None:
    """用「版本里的样本向量」匹配意图。

    @return {"intentCode","intentName","score","versionNo","sample"}；没版本/没语料/相似度太低返回 None
    """
    if not tenant_code or not question:
        return None
    try:
        version = version_no or resolve_version(tenant_code, session_no)
        if not version:
            return None
        vectors, _source = embed_texts([question], tenant_code=tenant_code)
        if not vectors:
            return None
        vector = _as_vector(vectors[0])
        with connect() as conn:
            row = conn.execute(
                """
                SELECT intent_code, intent_name, sample,
                       1 - (embedding <=> %s::vector) AS similarity
                  FROM bot_intent_sample
                 WHERE tenant_code = %s AND version_no = %s AND embedding IS NOT NULL
                 ORDER BY embedding <=> %s::vector
                 LIMIT 1
                """,
                (vector, tenant_code, version, vector),
            ).fetchone()
        if not row:
            return None
        score = float(row.get("similarity") or 0)
        return {
            "intentCode": row.get("intent_code"),
            "intentName": row.get("intent_name"),
            "sample": row.get("sample"),
            "score": round(score, 4),
            "versionNo": version,
        }
    except Exception as exc:  # noqa: BLE001
        # 训练索引读不到不能影响回复：退回原来的规则匹配
        logger.warning("按训练版本匹配意图失败 tenant=%s error=%s", tenant_code, exc)
        return None


# --------------------------------------------------------------------------- 页面用的总览


def overview(tenant_code: str) -> dict[str, Any]:
    """训练中心一整屏：当前版本 / 灰度版本 / 最近任务 / 版本记录 / 语料口径"""
    corpus = []
    try:
        corpus = collect_corpus(tenant_code)
    except Exception as exc:  # noqa: BLE001
        logger.warning("统计语料失败 tenant=%s error=%s", tenant_code, exc)
    manual = len([item for item in corpus if item["source"] == 1])
    history = len([item for item in corpus if item["source"] == 2])
    online = online_version(tenant_code)
    gray = gray_version(tenant_code)
    jobs = list_jobs(tenant_code, limit=5)
    return {
        "onlineVersion": version_view(online) if online else None,
        "grayVersion": version_view(gray) if gray else None,
        "lastJob": jobs[0] if jobs else None,
        "jobs": jobs,
        "versions": [version_view(row) for row in _version_rows(tenant_code)],
        "corpus": {
            "manual": manual,
            "history": history,
            "total": manual + history,
            "intents": len({item["intentCode"] for item in corpus}),
            "historyMinConfidence": HISTORY_MIN_CONFIDENCE,
        },
    }


def _as_vector(vector: Any) -> str:
    """把 float 列表转成 pgvector 认的字面量"""
    if isinstance(vector, str):
        return vector
    return "[" + ",".join(f"{float(value):.6f}" for value in vector) + "]"


def _to_vector(vector: Any) -> list[float]:
    """pgvector 取出来是 '[0.1,0.2,…]' 这样的字符串：增量训练复用旧向量时要先解析回列表

    （不解析就会在算余弦时炸 `could not convert string to float: '['`——踩过。）
    """
    if isinstance(vector, str):
        text = vector.strip().lstrip("[").rstrip("]")
        return [float(piece) for piece in text.split(",") if piece.strip()]
    return [float(value) for value in vector]


def _cosine(left: Any, right: Any) -> float:
    left_values = _to_vector(left)
    right_values = _to_vector(right)
    dot = sum(a * b for a, b in zip(left_values, right_values))
    norm_left = math.sqrt(sum(a * a for a in left_values))
    norm_right = math.sqrt(sum(b * b for b in right_values))
    if norm_left == 0 or norm_right == 0:
        return 0.0
    return dot / (norm_left * norm_right)
