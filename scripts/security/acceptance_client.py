"""Exercise the local customer support flow using synthetic test data."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import base64
import json
import os
import secrets
import time
from pathlib import Path

import httpx
from websockets.sync.client import connect

root = Path(__file__).resolve().parents[2]
artifacts = root / ".local" / "security-services"
client = httpx.Client(base_url="http://127.0.0.1:19090/api", timeout=50, trust_env=False)
ws_base = "ws://127.0.0.1:5173/ws/realtime"
audit = []
licenses = {}
tag = secrets.token_hex(3)


def note(name, detail):
    audit.append({"check": name, "result": detail})
    (artifacts / "results.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    print(name, detail, flush=True)


def api(method, path, token=None, data=None, want_failure=False, **kw):
    response = client.request(method, path, headers={
                              "Authorization": "Bearer " + token} if token else {}, json=data, **kw)
    try:
        body = response.json()
    except ValueError:
        raise AssertionError(f"{method} {path}: HTTP {response.status_code}, expected JSON") from None
    if want_failure:
        assert response.is_error or body.get("code") != 0, f"{method} {path}: expected business denial"
        return body.get("code") or response.status_code
    if response.is_error or body.get("code") != 0:
        raise AssertionError(
            f"{method} {path}: HTTP {response.status_code}, code {body.get('code')}, {str(body.get('message', ''))[:200]}")
    return body.get("data")


def captcha():
    c = api("GET", "/user/auth/captcha")
    return {"captchaId": c["captchaId"], "captchaCode": c["debugCode"]}


def login(email, password):
    r = api("POST", "/user/auth/login", data={"account": email, "password": password, **captcha()})
    return r["token"]


png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/l8AAAAASUVORK5CYII=")


def enroll(admin, name):
    company = f"Acceptance {tag} {name}"
    email = f"{tag}-{name}@example.test"
    phone = "139" + "".join(str(secrets.randbelow(10)) for _ in range(8))
    password = secrets.token_urlsafe(20) + "X9"
    data = {"companyName": company, "industry": "零售", "scale": "20人以下", "contactName": "测试坐席",
            "phone": phone, "email": email, "password": password}
    policy = api("GET", "/user/auth/register-policy")
    if policy.get("requireDoubleVerify"):
        verification = api("POST", "/user/auth/register/code", data={"email": email, **captcha()})
        data["verifyCode"] = verification["debugCode"]
    api("POST", "/user/auth/register", data={**data, **captcha()})
    token = login(email, password)
    uploaded = api("POST", "/customer/files/enterprise/license", token,
                   files={"file": ("test-license.png", png, "image/png")})
    api("POST", "/tenant/enterprise/my/submit", token, data={"companyName": company, "industry": "零售", "scale": "20人以下",
        "licenseNo": "91" + "".join(str(secrets.randbelow(10)) for _ in range(16)), "registerAddress": "测试地址", "legalPerson": "测试法人",
        "licenseFileId": uploaded["fileId"], "contactName": "测试坐席", "contactPhone": phone, "contactEmail": email})
    reviews = api("GET", "/tenant/admin/reviews", admin, params={"keyword": company})["records"]
    record, = [r for r in reviews if r["companyName"] == company]
    approved = api("POST", f"/tenant/admin/reviews/{record['id']}/approve", admin)
    assert approved["status"] == 2 and approved["tenantCode"] != "PLATFORM"
    token = login(email, password)
    assert api("GET", "/user/auth/me", token)["tenantCode"] == approved["tenantCode"]
    licenses[name] = uploaded['fileId']
    for reader in (token, admin):
        response = client.get('/customer/files/enterprise/' +
                              uploaded['fileId'], headers={'Authorization': 'Bearer ' + reader})
        assert response.status_code == 200 and response.content == png
        assert response.headers['cache-control'] == 'no-store'
    return token


def recv(ws, kind, predicate=lambda msg: True, seconds=42):
    deadline = time.monotonic() + seconds
    seen = []
    try:
        while time.monotonic() < deadline:
            m = json.loads(ws.recv(timeout=max(0, deadline - time.monotonic())))
            seen.append(m.get("type"))
            if m.get("type") == kind and predicate(m):
                return m
            if m.get("type") == "ERROR":
                raise AssertionError(f"WebSocket error: {m.get('code')}, {m.get('message')}")
    except TimeoutError:
        pass
    raise AssertionError(f"Expected WebSocket {kind}; got {seen}")


def send(ws, kind, sn, **data):
    ws.send(json.dumps({"type": kind, "sessionNo": sn, **data}, ensure_ascii=False))


def open_visitor(app_key, name):
    return api("POST", "/customer/sessions/open", data={"appKey": app_key, "visitorName": name})


def socket(token):
    return connect(ws_base + "?token=" + token, proxy=None, open_timeout=8)


def message(ws, session, content, msg_id):
    send(ws, "SEND", session, clientMsgNo=msg_id, msgType=1, content=content)
    return recv(ws, "ACK", lambda m: m.get("clientMsgNo") == msg_id)["data"]


def check_state(token, session, states):
    for _ in range(50):
        data = api("GET", f"/customer/sessions/{session}", token)
        if data.get("status") in states:
            return data
        time.sleep(.3)
    raise AssertionError(f"Session did not enter expected state {states}")
