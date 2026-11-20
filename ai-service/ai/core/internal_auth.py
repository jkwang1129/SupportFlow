"""Authorize service requests before they reach handlers or charge model usage."""

from __future__ import annotations

import hmac
import re
import time

from fastapi import FastAPI, Request
from starlette.responses import JSONResponse

from ..config import Settings
from .runtime_auth import RuntimeAuthMiddleware, AUTHORIZED


_HEADER = "X-SupportFlow-AI-Auth"
_TENANT = "X-Tenant-Code"
_CREDENTIAL = re.compile(r"(gateway|customer|ops|tenant):([0-9]{1,12}):([a-f0-9]{64})")
_TENANT_CODE = re.compile(r"[A-Za-z0-9_-]{1,32}")
_TENANT_KEYS = ("tenant_code", "tenantCode")
_RAW_UPLOADS = {"/ai/v1/kb/index", "/ai/v1/speech/asr"}

_CUSTOMER_OPERATIONS = {
    ("POST", "/ai/v1/kb/index"), ("POST", "/ai/v1/kb/index-text"),
    ("POST", "/ai/v1/kb/search"), ("POST", "/ai/v1/agent/brain"),
    ("POST", "/ai/v1/rag/ask"), ("POST", "/ai/v1/qa/evaluate"),
    ("POST", "/ai/v1/agent/vision"), ("POST", "/ai/v1/speech/asr"),
    ("POST", "/ai/v1/speech/tts"), ("GET", "/ai/v1/bot/settings"),
    ("GET", "/ai/v1/kb/repair-status"),
}
_CUSTOMER_CHECKS = {("GET", "/ai/v1/kb/health"), ("GET", "/ai/v1/agent/brain/health")}
_KB_DOCUMENT = re.compile(r"/ai/v1/kb/documents/[0-9]{1,19}")
_OPS_READ = {
    "/internal/usage/metrics", "/internal/usage/status",
    "/internal/usage/tenant-total", "/internal/usage/tenant-metrics",
}
_OPS_TRACE = re.compile(r"/internal/usage/trace/[A-Za-z0-9_-]{1,128}")


def _access(caller: str, method: str, path: str) -> tuple[bool, bool]:
    """Return (operation permitted, tenant required)."""
    if caller == "gateway":
        return (path.startswith("/ai/v1/bot/") or (method, path) == ("GET", "/ai/v1/kb/status"), True)
    if caller == "customer":
        if (method, path) in _CUSTOMER_CHECKS:
            return True, False
        document = method == "DELETE" and _KB_DOCUMENT.fullmatch(path)
        chunks = method == "GET" and path.endswith("/chunks") and _KB_DOCUMENT.fullmatch(path[:-7])
        return bool((method, path) in _CUSTOMER_OPERATIONS or document or chunks), True
    if caller == "ops":
        permitted = method == "GET" and (path in _OPS_READ or _OPS_TRACE.fullmatch(path))
        return bool(permitted), path == "/internal/usage/tenant-total"
    return (method, path) == ("GET", "/internal/usage/tenants"), False


def install_internal_auth(app: FastAPI, settings: Settings) -> None:
    secrets = {
        caller: getattr(settings, f"{caller}_secret").get_secret_value().encode("utf-8")
        for caller in ("gateway", "customer", "ops", "tenant")
    }
    configured = [key for key in secrets.values() if len(key) >= 32]
    if len(configured) != len(set(configured)):
        raise ValueError("AI callers must use distinct service credentials")
    prefix = settings.api_prefix

    @app.middleware("http")
    async def verify(request: Request, call_next):
        path = request.url.path
        if path.startswith(prefix + '/ai/v1/runtime/'):
            if request.scope.get(AUTHORIZED) is not True:
                return JSONResponse({'detail': 'Runtime service authentication required'}, status_code=401)
            return await call_next(request)
        if request.method == "GET" and path in {prefix + probe for probe in (
                "/ai/health", "/ai/health/live", "/ai/health/ready")}:
            return await call_next(request)
        if not path.startswith(prefix + "/"):
            return JSONResponse({"detail": "Not found"}, status_code=404)
        credentials = request.headers.getlist(_HEADER)
        tenants = request.headers.getlist(_TENANT)
        match = _CREDENTIAL.fullmatch(credentials[0]) if len(credentials) == 1 else None
        if not match or len(tenants) > 1:
            return JSONResponse({"detail": "AI service authentication required"}, status_code=401)
        caller, expires, signature = match.groups()
        tenant = tenants[0] if tenants else ""
        key = secrets[caller]
        raw_path = request.scope.get("raw_path", path.encode()).decode("ascii", errors="replace")
        canonical = "\n".join(("supportflow-ai:v1", caller, expires, request.method, raw_path, tenant))
        now = int(time.time())
        if (
            len(key) < 32 or not now < int(expires) <= now + 35
            or not hmac.compare_digest(signature, hmac.digest(key, canonical.encode(), "sha256").hex())
        ):
            return JSONResponse({"detail": "Invalid AI service credential"}, status_code=401)
        operation = path[len(prefix):]
        allowed, requires_tenant = _access(caller, request.method, operation)
        if (
            not allowed or (requires_tenant and not tenant)
            or (tenant and (not _TENANT_CODE.fullmatch(tenant) or tenant.upper() == "PLATFORM"))
        ):
            return JSONResponse({"detail": "AI operation is not authorized"}, status_code=403)
        query_tenants = [
            value for field in _TENANT_KEYS for value in request.query_params.getlist(field)
        ]
        if any(value != tenant for value in query_tenants):
            return JSONResponse({"detail": "AI tenant does not match service authorization"}, status_code=403)
        if request.method in {"POST", "PUT", "PATCH"} and operation not in _RAW_UPLOADS and await request.body():
            try:
                body = await request.json()
            except (ValueError, UnicodeError):
                return JSONResponse({"detail": "Invalid JSON request"}, status_code=400)
            if isinstance(body, dict) and any(body[field] != tenant for field in _TENANT_KEYS if field in body):
                return JSONResponse({"detail": "AI tenant does not match service authorization"}, status_code=403)
        return await call_next(request)

    # Outer ASGI layer bounds/caches the raw body without relying on private
    # Request attributes. Old v1 signatures cannot authorize runtime operations.
    app.add_middleware(RuntimeAuthMiddleware, prefix=prefix, secrets=secrets)
