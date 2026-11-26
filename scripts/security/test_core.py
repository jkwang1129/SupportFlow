"""Local acceptance against actual Java and Python services, using only synthetic data."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import time
import httpx
import acceptance_client as test

test.artifacts = test.root / ".local" / "security-services"
test.ws_base = "ws://127.0.0.1:9096/ws/realtime"
direct = httpx.Client(base_url="http://127.0.0.1:9100", timeout=2, trust_env=False)
for i in range(75):
    try:
        admin_path = test.artifacts / ".test-admin"
        if admin_path.exists() and direct.get("/api/ai/health").status_code == 200 and all(
            httpx.get(f"http://127.0.0.1:{port}/actuator/health", timeout=2, trust_env=False).status_code == 200
            for port in (19090, 9091, 9092, 9093, 9094, 9095, 9096)
        ):
            break
    except httpx.HTTPError:
        pass
    time.sleep(1)
else:
    raise RuntimeError("Isolated services did not become healthy")

assert test.client.get("/ai/health").status_code == 200
assert test.client.post("/ai/v1/kb/index").status_code == 404
assert test.client.get("/ai/v1/bot/settings").status_code == 401
assert direct.post("/api/ai/v1/kb/index",
                   headers={"X-Tenant-Code": "TENANT_A", "X-Request-Id": "local-test"}).status_code == 401
assert direct.get("/api/ai/health/live").status_code == 200
assert direct.get("/api/ai/health/ready").status_code == 200
test.note("HTTP boundary", "health=200, gateway internal KB=404, gateway console anonymous=401, direct anonymous=401")

admin = test.login("acceptance-admin@example.test", admin_path.read_text(encoding="utf-8"))
a, b = (test.enroll(admin, name) for name in ("alpha", "beta"))
assert test.api("GET", "/ai/v1/bot/settings", a).get("botName")
license_path = '/customer/files/enterprise/' + test.licenses['alpha']
for headers in ({}, {'Authorization': 'Bearer ' + b}, {'X-Tenant-Code': 'PLATFORM'}):
    denied = test.client.get(license_path, headers=headers)
    assert denied.is_error or denied.json().get('code') != 0
for endpoint in ('profile', 'submit'):
    code = test.api('POST', '/tenant/enterprise/my/' + endpoint, b,
                    data={'companyName': 'Fixture', 'industry': '零售', 'scale': '20人以下', 'licenseNo': '913100000000000001',
                          'registerAddress': 'Fixture', 'legalPerson': 'Fixture', 'licenseFileId': test.licenses['alpha'],
                          'contactName': 'Fixture', 'contactPhone': '13900000001', 'contactEmail': 'fixture@example.test'}, want_failure=True)
    assert code == 40301, code
test.api('GET', '/customer/platform/ai-quality', admin)
test.api('GET', '/customer/platform/ai-quality', a, want_failure=True)
ticket_body = {'title': 'Runtime role regression', 'content': 'Local synthetic support request',
               'category': 5, 'priority': 2, 'customerName': 'Fixture'}
platform_ticket = test.api('POST', '/customer/tickets/platform', a, data=ticket_body)
tenant = test.api('GET', '/user/auth/me', a)['tenantCode']
number = platform_ticket['ticketNo']
assert int(test.api('GET', '/platform/tickets', admin)['total']) >= 1
test.api('GET', '/platform/tickets', a, want_failure=True)
for action in ('claim', 'reply'):
    body = {'content': 'Local review reply', 'visibleToCustomer': True} if action == 'reply' else None
    test.api('POST', f'/platform/tickets/{number}/{action}', admin, data=body, params={'tenant': tenant})
test.note('License and platform privileges',
          'owner and reviewer read passed; anonymous/cross-owner read and foreign references denied; scoped platform ticket and QA operations passed')

test.note("Java gateway to Python", "real enterprise login can read its own bot configuration")

source = next((test.root / "fixtures" / "knowledge-base").glob("01-*.md"))
doc = test.api("POST", "/customer/kb/documents/upload", a,
               files={"file": ("refund-policy.md", source.read_bytes(), "text/markdown")},
               data={"title": "Local security regression document"})
for _ in range(36):
    if doc["indexStatus"] in (3, 4):
        break
    time.sleep(.5)
    doc = test.api("GET", f"/customer/kb/documents/{doc['id']}", a)["document"]
assert doc["chunkCount"] > 0, doc.get("indexMessage")
test.api("POST", f"/customer/kb/documents/{doc['id']}/status", a, data={"status": 3})
question = "退款审核通过后，信用卡退款的到账时间？"
hit_a = test.api("POST", "/customer/kb/search", a, data={"query": question, "topK": 5})
hit_b = test.api("POST", "/customer/kb/search", b, data={"query": question, "topK": 5})
assert hit_a["results"] and not hit_b["results"]
test.api("GET", f"/customer/kb/documents/{doc['id']}", b, want_failure=True)
status_a = test.api("GET", "/ai/v1/kb/status", a)
status_b = test.api("GET", "/ai/v1/kb/status", b)
assert sum(item["chunks"] for item in status_a["chunkVectorModels"]) == doc["chunkCount"], status_a
assert status_b["chunkVectorModels"] == [] and "fallbackDocIds" not in status_a
test.note("Java customer to Python KB",
          f"{doc['chunkCount']} indexed chunks; search and console diagnostics separated A/B")

document_path = f"/customer/kb/documents/{doc['id']}"
original_chunks = test.api("GET", document_path + "/chunks", a)
test.api("PUT", document_path, a, data={"content": "信用卡退款到账期限为三个工作日。"})
candidate = test.api("GET", document_path + "/index/previous", a)
assert set(candidate) == {"versionId", "chunkCount"} and candidate["chunkCount"] == len(original_chunks)
rollback_path = document_path + "/index/rollback"
rollback_body = {"versionId": candidate["versionId"]}
assert test.api("POST", rollback_path, b, data=rollback_body, want_failure=True) == 40401
restored = test.api("POST", rollback_path, a, data=rollback_body)
assert restored["status"] == 3 and restored["chunkCount"] == len(original_chunks)
assert test.api("GET", document_path + "/chunks", a) == original_chunks
assert test.api("GET", document_path, a)["contentSource"] == "chunks"
assert test.api("POST", rollback_path, a, data=rollback_body, want_failure=True) == 40901
assert test.api("POST", "/customer/kb/search", a, data={"query": question, "topK": 5})["results"]
test.note("KB one-version rollback",
          f"{len(original_chunks)} original chunks restored; foreign and repeated requests denied")

channel = test.api("POST", "/customer/channels", a, data={"name": "Local test channel", "channelType": 1})
if channel["status"] != 1:
    channel = test.api("POST", f"/customer/channels/{channel['id']}/status", a, data={"enabled": True})
visitor = test.open_visitor(channel["appKey"], "Local test visitor")
import base64
png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
uploaded = test.client.post("/customer/sessions/attachments",
                            params={"appKey": channel["appKey"], "sessionNo": visitor["sessionNo"]},
                            headers={"X-Visitor-Token": visitor["visitorToken"]},
                            files={"file": ("fixture.png", png, "image/png")}).json()
assert uploaded["code"] == 0, uploaded.get("message")
image_path = "/customer/sessions/attachments/" + uploaded["data"]["fileId"]


def media_denied(headers, session_no):
    response = test.client.get(image_path, params={"sessionNo": session_no}, headers=headers)
    assert response.is_error or response.json().get("code") != 0


media_denied({}, visitor["sessionNo"])
media_denied({"Authorization": "Bearer " + b}, visitor["sessionNo"])
other_visitor = test.open_visitor(channel["appKey"], "Different visitor")
media_denied({"X-Visitor-Token": other_visitor["visitorToken"]}, visitor["sessionNo"])
media_denied({"X-Visitor-Token": other_visitor["visitorToken"]}, other_visitor["sessionNo"])
for headers in ({"Authorization": "Bearer " + a}, {"X-Visitor-Token": visitor["visitorToken"]}):
    response = test.client.get(image_path, params={"sessionNo": visitor["sessionNo"]}, headers=headers)
    assert response.status_code == 200 and response.content == png
    assert response.headers.get("cache-control") == "no-store"
avatar = test.api("POST", "/customer/bot/avatar", a, files={"file": ("fixture.png", png, "image/png")})
assert test.client.get(avatar["url"].removeprefix("/api")).content == png
test.note("Private chat media",
          "own visitor and enterprise reads passed; anonymous, other tenant and other session rejected; public avatar passed")
with test.socket(visitor["visitorToken"]) as ws:
    test.recv(ws, "CONNECTED")
    initial = test.message(ws, visitor["sessionNo"], question, "local-security-" + test.tag)
    bot_reply = test.recv(ws, "MESSAGE", lambda msg: msg.get("data", {}).get("senderType") == 3)["data"]
ai_log = (test.artifacts / "ai.log").read_text(encoding="utf-8", errors="replace")
assert 'POST /api/ai/v1/agent/brain HTTP/1.1" 200' in ai_log, "AI brain did not accept the Java request"
test.note("Java customer to Python brain",
          "visitor WebSocket sent a message, the protected brain returned HTTP 200, and a bot reply arrived")

with test.socket(a) as staff:
    test.recv(staff, "CONNECTED")
    test.send(staff, "NOTE", visitor["sessionNo"], clientMsgNo="private-sync-" + test.tag,
              content="Synthetic staff-only sync note", msgType=1)
    private_note = test.recv(staff, "ACK", lambda frame: frame.get(
        "clientMsgNo") == "private-sync-" + test.tag)["data"]
with test.socket(visitor["visitorToken"]) as ws:
    test.recv(ws, "CONNECTED")
    test.recv(ws, "JOINED")
    test.send(ws, "SYNC", visitor["sessionNo"], data={"afterSeq": initial["seq"]})
    synced = test.recv(ws, "SYNCED")
    data = synced["data"]
    assert synced["sessionNo"] == visitor["sessionNo"] and data["afterSeq"] == initial["seq"]
    assert bot_reply["msgId"] in {row["msgId"] for row in data["messages"]}
    assert private_note["msgId"] not in {row["msgId"] for row in data["messages"]}
    assert all(row["seq"] > initial["seq"] for row in data["messages"]) and data["hasMore"] is False
    test.send(ws, "SYNC", visitor["sessionNo"], data={"afterSeq": -1})
    assert test.recv(ws, "ERROR")["code"] == 40001
    test.send(ws, "SYNC", other_visitor["sessionNo"], data={"afterSeq": 0})
    assert test.recv(ws, "ERROR")["code"] == 40301
test.note("Visitor reconnect",
          "actual WebSocket returned the missed reply; staff-only note was absent; invalid cursor and other session were rejected")

# The actual scheduler/client must obtain a matching business receipt, not merely HTTP 200.
for _ in range(40):
    usage = test.api("GET", "/billing/usage", a)
    if int(usage["messages"]) >= 2 and int(usage["sessions"]) >= 1 and int(usage["aiCalls"]) >= 1:
        break
    time.sleep(1)
else:
    raise AssertionError("Customer usage did not reach the authenticated billing batch endpoint")
import uuid
from datetime import date
unsigned_batch = {"batchId": str(uuid.uuid4()), "tenantCode": tenant, "statDate": date.today().isoformat(),
                  "sessions": 0, "messages": 0, "aiCalls": 0, "seats": 0, "storageGb": 0}
assert test.client.post("/billing/internal/usage/report", json=unsigned_batch).status_code == 403
assert httpx.post("http://127.0.0.1:9094/api/billing/internal/usage/report", json=unsigned_batch,
                  timeout=5, trust_env=False).status_code == 403
test.note("Authenticated metering",
          "actual customer scheduler persisted and delivered usage; unsigned gateway/direct writes rejected")
