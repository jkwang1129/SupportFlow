"""模型训练中心接口：训练任务、版本记录、灰度发布 / 回滚。

页面上那一屏「训练进度 / 版本记录 v9.x / 重新训练 / 灰度切换」就是这四个接口：

    GET  /ai/v1/bot/training              → 总览（当前版本 + 灰度版本 + 最近任务 + 版本记录 + 语料口径）
    POST /ai/v1/bot/training/jobs         → 发起训练（1-全量 / 2-增量，可带灰度比例）
    GET  /ai/v1/bot/training/jobs/{no}    → 某个任务的进度（页面 2 秒轮询它）
    POST /ai/v1/bot/training/versions/{v}/publish   → 发布 / 灰度
    POST /ai/v1/bot/training/versions/{v}/rollback  → 回滚到该版本
    GET  /ai/v1/bot/training/match        → 调试：这句话会被判成哪个意图（用的是哪个版本）

训练是后台线程跑的、进度写库，所以接口本身不阻塞——发起后立刻返回任务号。
"""

from __future__ import annotations

from typing import Any, Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..config import get_settings
from ..services import training

router = APIRouter(prefix="/ai/v1/bot/training", tags=["training"])


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TrainBody(ApiModel):
    """发起训练：模式 + 训练完的灰度比例（0 = 直接全量上线）"""

    mode: int = Field(default=training.MODE_INCREASE, description="1-全量、2-增量")
    gray_percent: int = Field(default=0, ge=0, le=100, description="训练完成后按这个比例灰度")


class PublishBody(ApiModel):
    """发布 / 回滚：灰度比例 0 = 全量"""

    gray_percent: int = Field(default=0, ge=0, le=100)


def ok(data: Any = None):
    return {"code": 0, "message": "成功", "data": data}


def require_tenant(tenant_code: Optional[str]) -> str:
    if not tenant_code or tenant_code == "PLATFORM":
        raise HTTPException(status_code=400, detail="缺少企业租户上下文")
    return tenant_code


@router.get("")
def overview(x_tenant_code: Optional[str] = Header(default=None)):
    """训练中心总览（页面首屏就是它）"""
    tenant = require_tenant(x_tenant_code)
    return ok(training.overview(tenant))


@router.post("/jobs")
def create_job(body: TrainBody, x_tenant_code: Optional[str] = Header(default=None)):
    """发起一次训练：立刻返回任务号，页面拿它轮询进度"""
    tenant = require_tenant(x_tenant_code)
    try:
        job = training.start_job(tenant, body.mode, body.gray_percent)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok({"job": job, "overview": training.overview(tenant)})


@router.get("/jobs")
def list_jobs(limit: int = 10, x_tenant_code: Optional[str] = Header(default=None)):
    """训练任务列表（最近几条，页面上看历史）"""
    tenant = require_tenant(x_tenant_code)
    return ok(training.list_jobs(tenant, max(1, min(limit, 50))))


@router.get("/jobs/{job_no}")
def job_detail(job_no: str, x_tenant_code: Optional[str] = Header(default=None)):
    """某个任务的进度：status / progress / phase / 产出（页面轮询这个）"""
    tenant = require_tenant(x_tenant_code)
    job = training.get_job(tenant, job_no)
    if job is None:
        raise HTTPException(status_code=404, detail=f"训练任务不存在：{job_no}")
    return ok(job)


@router.post("/versions/{version_no}/publish")
def publish(version_no: str, body: PublishBody,
            x_tenant_code: Optional[str] = Header(default=None)):
    """发布版本：grayPercent=0 全量上线，>0 灰度（旧版本继续兜底）"""
    tenant = require_tenant(x_tenant_code)
    try:
        return ok(training.publish(tenant, version_no, body.gray_percent))
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.post("/versions/{version_no}/rollback")
def rollback(version_no: str, x_tenant_code: Optional[str] = Header(default=None)):
    """回滚：把指定版本重新全量上线（其余版本下线）"""
    tenant = require_tenant(x_tenant_code)
    try:
        return ok(training.rollback(tenant, version_no))
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.get("/match")
def match(question: str, session_no: Optional[str] = None,
          x_tenant_code: Optional[str] = Header(default=None)):
    """调试用：这句话在当前生效版本里会匹配到哪个意图、相似度多少"""
    tenant = require_tenant(x_tenant_code)
    result = training.vector_match(tenant, question, session_no=session_no)
    version = training.resolve_version(tenant, session_no)
    return ok({
        "versionNo": version,
        "match": result,
        "minScore": get_settings().train_match_min_score,
    })
