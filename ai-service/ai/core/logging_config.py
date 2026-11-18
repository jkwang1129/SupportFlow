"""统一日志配置：AI 调用链路（接口层 / 服务层 / LLM 网关）共用一套输出格式。

格式说明：``时间(毫秒) | 级别 | 模块 | 内容``，内容里统一带 ``trace=``，
便于和 Java 侧调用日志串联排查。
"""

from __future__ import annotations

import logging
import sys

from ..config import get_settings

LOG_FORMAT = "%(asctime)s.%(msecs)03d | %(levelname)-5s | %(name)-22s | %(message)s"
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

_configured = False


def setup_logging() -> None:
    """初始化全局日志，重复调用只生效一次。"""
    global _configured
    if _configured:
        return

    settings = get_settings()
    level = getattr(logging, (settings.log_level or "INFO").upper(), logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(LOG_FORMAT, DATE_FORMAT))
    handler.setLevel(level)

    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(level)
    root.addHandler(handler)

    # 第三方 HTTP 客户端日志降噪：调用细节由业务日志记录，避免刷屏
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)

    _configured = True
