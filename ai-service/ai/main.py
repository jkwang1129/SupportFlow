"""FastAPI 入口。"""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.exception_handlers import http_exception_handler
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from .config import get_settings
from .core.internal_auth import install_internal_auth
from .core.logging_config import setup_logging
from .core.schema_guard import ensure_schema
from .core import schema_guard
from starlette.concurrency import run_in_threadpool
from .core.trace import TRACE_ID_HEADER, new_trace_id, set_trace_id

# 先初始化日志再导入 API：保证模块级组件（如 LLM 网关）的启动日志走统一格式
setup_logging()

from .api import (  # noqa: E402
    bot, brain, chat, health, kb, observability, qa, rag, runtime, speech, training, usage, vision,
)

logger = logging.getLogger(__name__)

# 库结构没升级时的报错长这样：column "xxx" does not exist / relation "xxx" does not exist。
# 直接抛给用户是一串 psycopg 堆栈，看不出要干什么；这里翻译成"跑哪个脚本"。
#
# 关键是**只给对的那个脚本**：ai_db 和 customer_db 是两套增量脚本，
# 一股脑把两个都列出来，等于让人挨个试（之前就是这么写的，体验很差）。
_AI_DB_OBJECTS = (
    "bot_setting", "bot_intent", "bot_model", "bot_dialogue", "bot_version",
    "training_job", "ai_usage_record", "ai_bot_config", "bot_intent_sample", "gray_percent",
    # 这几个列名带着 AI 库的业务语义，出现就基本能断定是 AI 库
    "transfer_message", "transfer_prompt", "transfer_keywords", "transfer_on_anger",
    "transfer_after_unresolved", "reception_enabled", "welcome_message", "fallback_message",
    "intent_code", "escalate", "model_key", "hit_count", "samples", "last_intent",
)
_CUSTOMER_DB_OBJECTS = (
    "session_message", "session_event", "session", "qa_rule", "qa_task", "qa_review",
    "qa_alert", "kb_document", "kb_chunk", "kb_category", "file_meta", "channel_key",
    "channel", "customer", "agent_status", "skill_group",
    # 历史踩过的客户库列名，一并登记上，报错时能直接指对脚本
    "bot_transfer_reason", "timeout_seconds", "is_realtime", "hit_keywords", "severity",
    "client_msg_no", "visible_to", "last_msg_seq", "allowed_origins", "index_status",
    "chunk_count", "overflow_after_seconds", "mime_type",
)


def _schema_hint(message: str) -> str:
    """按报错里出现的对象名判断是哪个库，只给对应的那一个脚本。"""
    text = message or ""
    if any(name in text for name in _AI_DB_OBJECTS):
        return "backend/deploy/db-init/README.md（AI 库，运行账号不得执行 DDL）"
    if any(name in text for name in _CUSTOMER_DB_OBJECTS):
        return "backend/deploy/db-init/README.md（客户库，运行账号不得执行 DDL）"
    return "backend/deploy/db-init/README.md（先核对数据库与缺失结构）"


def _install_schema_error_handler(app: FastAPI) -> None:
    """把"缺表 / 缺列"这类数据库结构错误翻译成一句能照做的话。"""
    try:
        import psycopg
    except Exception:  # noqa: BLE001
        return

    @app.exception_handler(psycopg.errors.UndefinedColumn)
    @app.exception_handler(psycopg.errors.UndefinedTable)
    async def _schema_error(_request, exc):  # noqa: ANN001, ANN202
        message = str(exc)
        hint = _schema_hint(message)
        logger.warning("数据库结构不完整：%s → 需要执行 %s", message, hint)
        return JSONResponse(status_code=500, content={
            "detail": "AI 数据库结构不完整，请联系管理员",
        })


def _install_trace_middleware(app: FastAPI) -> None:
    """调用链号贯穿整个 AI 服务。

    Java 侧调 AI 时带 ``X-Request-Id``；这里在每个请求开始时把它放进 trace 上下文，
    于是这次请求里发生的任何一次模型调用（``record_chat_usage``）都自动落同一个号，
    响应也把它回给调用方。**用中间件而不是依赖注入**：依赖只对声明的路由生效，
    漏一个路由就会出现"这次调用没号"，而漏掉的往往是出问题时最想查的那个。
    """

    @app.middleware("http")
    async def _trace(request, call_next):  # noqa: ANN001, ANN202
        incoming = request.headers.get(TRACE_ID_HEADER)
        trace_id = (incoming or "").strip() or new_trace_id()
        set_trace_id(trace_id)
        response = await call_next(request)
        response.headers[TRACE_ID_HEADER] = trace_id
        return response


def create_app() -> FastAPI:
    settings = get_settings()
    from .core.runtime_roles import require_runtime_roles
    require_runtime_roles()
    # Only diagnose here; runtime identities cannot migrate schema.
    ensure_schema()
    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        debug=False,
    )
    _install_schema_error_handler(app)

    @app.exception_handler(HTTPException)
    async def _http_error(request, error):
        if error.status_code >= 500:
            return JSONResponse({"detail": "AI 服务暂不可用"}, status_code=error.status_code)
        return await http_exception_handler(request, error)

    @app.exception_handler(RequestValidationError)
    async def _invalid_input(_request, _error):
        return JSONResponse({"detail": "AI 请求参数格式不正确"}, status_code=422)

    app.include_router(health.router, prefix=settings.api_prefix)
    app.include_router(chat.router, prefix=settings.api_prefix)
    app.include_router(bot.router, prefix=settings.api_prefix)
    app.include_router(qa.router, prefix=settings.api_prefix)
    app.include_router(kb.router, prefix=settings.api_prefix)
    app.include_router(rag.router, prefix=settings.api_prefix)
    app.include_router(brain.router, prefix=settings.api_prefix)
    app.include_router(runtime.router, prefix=settings.api_prefix)
    app.include_router(vision.router, prefix=settings.api_prefix)
    # 语音识别 / 语音合成（阿里云 NLS）：400 热线录音转写 + IVR 播报音频
    app.include_router(speech.router, prefix=settings.api_prefix)
    # AI 调用计量（平台「AI 监控」用）：调用量 / token / 成本 / 配额依据
    app.include_router(usage.router, prefix=settings.api_prefix)
    # 可观测（平台「可观测」页用）：按调用链号查明细、单租户成本（成本预算用）
    app.include_router(observability.router, prefix=settings.api_prefix)
    # 模型训练中心：训练任务 / 版本记录 / 灰度切换
    app.include_router(training.router, prefix=settings.api_prefix)
    @app.middleware("http")
    async def require_ready(request, call_next):
        probes = {settings.api_prefix + path for path in ("/ai/health", "/ai/health/live", "/ai/health/ready")}
        if request.url.path not in probes and not await run_in_threadpool(schema_guard.is_ready):
            return JSONResponse({"detail": "AI 服务依赖尚未就绪"}, status_code=503)
        return await call_next(request)

    # Added last: identity/operation checks run before the schema gate, even during outages.
    install_internal_auth(app, settings)
    _install_trace_middleware(app)
    return app


app = create_app()
