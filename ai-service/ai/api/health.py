"""健康检查。"""

from __future__ import annotations

from fastapi import APIRouter
from starlette.responses import JSONResponse
from ..core import schema_guard

from .. import __version__
from ..config import get_settings

router = APIRouter()


@router.get("/ai/health")
def health() -> dict[str, object]:
    settings = get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
        "version": __version__,
        "app": settings.app_name,
    }


@router.get("/ai/health/live")
def live() -> dict[str, object]:
    return health()


@router.get("/ai/health/ready")
def ready():
    available = schema_guard.is_ready()
    # Public probes reveal neither schema names nor credentials/driver diagnostics.
    return JSONResponse({"status": "UP" if available else "DOWN"}, status_code=200 if available else 503)
