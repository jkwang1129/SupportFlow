#!/usr/bin/env python3
"""SupportFlow 智能客服 · 官网本地预览服务器（纯标准库，IDEA 里可直接运行、可反复执行）。

用法（命令行）：
    python3 scripts/serve.py                  # http://localhost:5180，端口被上一次占用会自动重启
    python3 scripts/serve.py --port 5181
    python3 scripts/serve.py --no-open        # 不自动打开浏览器
    python3 scripts/serve.py --no-restart     # 端口被占用时直接报错，不碰任何进程
    python3 scripts/serve.py --force          # 端口被"别的程序"占用时也强行清掉再起
    python3 scripts/serve.py --api-base http://127.0.0.1:9090/api

用法（IntelliJ IDEA）：右键本文件 → Run 'serve'；或运行下拉里选「官网预览」。

它干三件事：
  1. **可重复执行**：启动前先看端口。占用者是"上一次的官网预览服务"就自动停掉再重启，
     不用手动 kill，也不会因为 Address already in use 启动失败；
  2. 把 website/ 当静态站托管（和生产 nginx 一致：找不到的路径回落到 index.html）；
  3. 把 /api/** 反代到后端网关（默认 http://127.0.0.1:9090/api），
     这样本地就能拿到后台"真实套餐价格"与"官网配置"——
     网关没起也不影响官网打开，价格会自动落到静态兜底。

为什么不用 vite/webpack：官网是纯静态站点（HTML + CSS + 原生 JS，SEO 友好、可整站丢 CDN），
引构建工具只会带来依赖与版本负担；这里只用 Python 标准库，不装任何包，IDEA 里点一下就能跑。
"""

from __future__ import annotations

import argparse
import errno
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
HTML_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
    ".txt": "text/plain; charset=utf-8",
    ".xml": "application/xml; charset=utf-8",
}
# 预览进程的"身份证"：写在系统临时目录，按端口区分（不往项目目录里丢垃圾文件）
PID_FILE = Path(tempfile.gettempdir()) / "supportflow-website-preview.pid"


# =====================================================================
# 端口占用处理：认出"上一次的预览服务"，停掉它，再重新启动
# =====================================================================

def _run(command: list[str]) -> str:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=5)
        return (completed.stdout or "").strip()
    except Exception:  # noqa: BLE001 - 环境里没有这个命令就算了
        return ""


def process_table() -> list[tuple[int, str]]:
    """当前进程表：[(pid, 完整命令行)]，用于识别"是不是我们自己起的预览服务"。"""
    rows: list[tuple[int, str]] = []
    for line in _run(["ps", "-ax", "-o", "pid=,command="]).splitlines():
        line = line.strip()
        if not line:
            continue
        pid_text, _, command = line.partition(" ")
        if pid_text.isdigit():
            rows.append((int(pid_text), command.strip()))
    return rows


def cmdline_of(pid: int) -> str:
    for row_pid, command in process_table():
        if row_pid == pid:
            return command
    return ""


def looks_like_preview(command: str) -> bool:
    """是不是"本项目的官网预览服务"：serve.sh / scripts/serve.py / scripts/serve.mjs。"""
    if not command:
        return False
    if "supportflow-official" not in command and str(ROOT) not in command:
        return False
    return any(token in command for token in ("serve.sh", "scripts/serve.py", "scripts/serve.mjs"))


def listening_pids(port: int) -> list[int]:
    """谁在监听这个端口：macOS 用 lsof，Linux 用 ss / fuser。"""
    pids: set[int] = set()
    if shutil.which("lsof"):
        for token in _run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"]).split():
            if token.isdigit():
                pids.add(int(token))
    if not pids and shutil.which("ss"):
        for match in re.findall(r"pid=(\d+)", _run(["ss", "-ltnp", f"sport = :{port}"])):
            pids.add(int(match))
    if not pids and shutil.which("fuser"):
        for token in _run(["fuser", "-n", "tcp", str(port)]).split():
            if token.isdigit():
                pids.add(int(token))
    pid_file = read_pid_file()
    if pid_file and pid_file not in pids and cmdline_of(pid_file) and looks_like_preview(cmdline_of(pid_file)):
        pids.add(pid_file)
    return sorted(pids)


def read_pid_file() -> int | None:
    try:
        return int(PID_FILE.read_text(encoding="utf-8").strip())
    except Exception:  # noqa: BLE001 - 文件不在 / 内容坏了都当没有
        return None


def write_pid_file(pid: int) -> None:
    try:
        PID_FILE.write_text(str(pid), encoding="utf-8")
    except OSError:
        pass


def clear_pid_file(pid: int) -> None:
    if read_pid_file() == pid:
        try:
            PID_FILE.unlink()
        except OSError:
            pass


def port_is_free(port: int, host: str = "127.0.0.1") -> bool:
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind((host, port))
        return True
    except OSError:
        return False
    finally:
        probe.close()


def wait_port_free(port: int, timeout: float = 6.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if port_is_free(port):
            return True
        time.sleep(0.25)
    return port_is_free(port)


def stop_process(pid: int, timeout: float = 5.0) -> bool:
    """先 SIGTERM，给它时间自己收尾；还不退就 SIGKILL。"""
    if pid == os.getpid():
        return False
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not _alive(pid):
            return True
        time.sleep(0.2)
    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        return not _alive(pid)
    for _ in range(10):
        if not _alive(pid):
            return True
        time.sleep(0.2)
    return not _alive(pid)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def stop_previous(port: int, force: bool = False) -> tuple[bool, list[str]]:
    """停掉上一次占着这个端口的预览服务。返回 (是否已腾出端口, 过程说明)。"""
    notes: list[str] = []
    pids = listening_pids(port)
    if not pids:
        # 端口占用但查不到监听进程（IPv6、权限受限等）：退化成"找我们自己的预览进程"
        pids = [pid for pid, command in process_table()
                if pid != os.getpid() and looks_like_preview(command)]
    if not pids:
        notes.append("没查到占用端口的进程（可能是别的用户/容器占的），直接尝试重新绑定。")
        return port_is_free(port), notes

    ours, others = [], []
    for pid in pids:
        if pid == os.getpid():
            continue
        command = cmdline_of(pid)
        (ours if looks_like_preview(command) else others).append((pid, command))

    if others and not force:
        detail = "、".join(f"PID {pid}（{(cmd or '未知进程')[:60]}）" for pid, cmd in others)
        notes.append(f"端口 {port} 被别的程序占用：{detail}")
        notes.append("这不是官网预览服务，我没有动它。可以：①换个端口 --port 5181；"
                     "②确认可以停掉它时加 --force。")
        return False, notes

    targets = ours + (others if force else [])
    for pid, command in targets:
        label = "上一次的官网预览服务" if looks_like_preview(command) else "占用端口的其他程序"
        notes.append(f"停止{label}：PID {pid}")
        if not stop_process(pid):
            notes.append(f"⚠️ PID {pid} 没停下来（可能是别的用户起的，权限不够）")

    freed = wait_port_free(port)
    notes.append(f"端口 {port} {'已释放' if freed else '仍被占用'}")
    return freed, notes


# =====================================================================
# HTTP：静态文件 + /api 反代（与 scripts/serve.mjs 行为一致）
# =====================================================================

class Handler(BaseHTTPRequestHandler):

    server_version = "SupportFlowOfficialPreview/1.0"
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, api_base: str, quiet: bool = False, **kwargs):
        self.api_base = api_base.rstrip("/")
        self.quiet = quiet
        super().__init__(*args, **kwargs)

    def do_GET(self):
        self.dispatch()

    def do_HEAD(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def do_PUT(self):
        self.dispatch()

    def do_DELETE(self):
        self.dispatch()

    def do_PATCH(self):
        self.dispatch()

    def dispatch(self):
        path = self.path.split("?", 1)[0]
        try:
            if path == "/api" or path.startswith("/api/"):
                self.proxy()
            else:
                self.static()
        except (BrokenPipeError, ConnectionResetError):
            # 浏览器提前断开（刷新、切页）不是错误，没必要打堆栈
            pass
        except Exception as error:  # noqa: BLE001 - 预览服务器，兜住就行
            self.send_json(500, {"code": 50000, "message": f"预览服务出错：{error}", "data": None})

    # ------------------------------------------------------------ 静态文件
    def static(self):
        raw = unquote(self.path.split("?", 1)[0])
        target = (ROOT / raw.lstrip("/")).resolve()
        # 目录穿越保护：只允许访问官网项目目录内的文件
        if ROOT not in target.parents and target != ROOT:
            self.send_json(403, {"code": 40300, "message": "禁止访问项目目录以外的文件", "data": None})
            return
        if target.is_dir():
            target = target / "index.html"
        if not target.is_file():
            # 与 nginx 的 try_files 一致：找不到就回落到首页
            target = ROOT / "index.html"
        body = target.read_bytes()
        self.log_line(f"静态  {self.path}  →  200 {target.name}（{len(body) / 1024:.1f} KB）")
        self.send_response(200)
        self.send_header("Content-Type", HTML_TYPES.get(target.suffix.lower(), "application/octet-stream"))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # ------------------------------------------------------------ /api 反代
    def proxy(self):
        upstream = self.api_base + self.path[len("/api"):]
        length = int(self.headers.get("Content-Length") or 0)
        payload = self.rfile.read(length) if length else None
        headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower() not in {"host", "connection", "content-length", "accept-encoding"}
        }
        request = urllib.request.Request(upstream, data=payload, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read()
                status = response.status
                content_type = response.headers.get("Content-Type", "application/json; charset=utf-8")
        except urllib.error.HTTPError as error:
            body = error.read()
            status = error.code
            content_type = error.headers.get("Content-Type", "application/json; charset=utf-8")
        except Exception as error:  # noqa: BLE001 - 网关没起时给官网一个明确的 502
            self.log_line(f"代理  {self.path}  →  502（网关不可用：{error}）")
            self.send_json(502, {
                "code": 50200,
                "message": f"后端网关不可用（{self.api_base}），官网会用静态兜底价：{error}",
                "data": None,
            })
            return
        self.log_line(f"代理  {self.path}  →  {status} ← {upstream}")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # ------------------------------------------------------------ 小工具
    def send_json(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_line(self, text: str):
        if not self.quiet:
            print("  " + text, flush=True)

    def log_message(self, fmt: str, *args):  # 关掉默认的逐行日志，用上面的中文日志代替
        return


# =====================================================================
# 启动
# =====================================================================

def bind_server(host: str, port: int, api_base: str, quiet: bool) -> ThreadingHTTPServer:
    handler = partial(Handler, api_base=api_base, quiet=quiet)
    return ThreadingHTTPServer((host, port), handler)


def main() -> int:
    parser = argparse.ArgumentParser(description="SupportFlow 智能客服 · 官网本地预览")
    parser.add_argument("-p", "--port", type=int,
                        default=int(os.environ.get("PORT") or os.environ.get("SUPPORTFLOW_OFFICIAL_PORT") or 5180),
                        help="监听端口（默认 5180，也可用环境变量 PORT）")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址（默认 127.0.0.1）")
    parser.add_argument("--api-base",
                        default=os.environ.get("SUPPORTFLOW_API_BASE") or "http://127.0.0.1:9090/api",
                        help="后端网关地址（默认 http://127.0.0.1:9090/api，也可用环境变量 SUPPORTFLOW_API_BASE）")
    parser.add_argument("--no-open", action="store_true", help="不自动打开浏览器")
    parser.add_argument("--quiet", action="store_true", help="不打印每个请求")
    parser.add_argument("--no-restart", action="store_true",
                        help="端口被占用时直接报错退出，不自动停掉上一次的预览服务")
    parser.add_argument("--force", action="store_true",
                        help="端口被别的程序占用时也强行停掉（默认只重启官网自己的预览服务）")
    args = parser.parse_args()

    if not (ROOT / "index.html").is_file():
        print(f"❌ 没找到官网首页：{ROOT / 'index.html'}", file=sys.stderr)
        return 1

    def start():
        return bind_server(args.host, args.port, args.api_base, args.quiet)

    # ── 1) 可重复执行：端口被上一次的预览服务占着就先停掉它
    restarted = False
    try:
        httpd = start()
    except OSError as error:
        busy = error.errno in (errno.EADDRINUSE, 48, 98)
        if busy and not args.no_restart:
            print(f"⟳ 端口 {args.port} 已被占用，先停掉上一次的预览服务再重启…")
            freed, notes = stop_previous(args.port, force=args.force)
            for note in notes:
                print("   " + note)
            if not freed:
                print(f"❌ 端口 {args.port} 还是被占用，换个端口试试："
                      f"python3 scripts/serve.py --port {args.port + 1}", file=sys.stderr)
                return 1
            try:
                httpd = start()
                restarted = True
            except OSError as retry_error:
                print(f"❌ 重启后仍起不来：{retry_error}", file=sys.stderr)
                return 1
        else:
            if busy:
                print(f"❌ 端口 {args.port} 被占用（已按 --no-restart 要求不做处理）", file=sys.stderr)
            elif error.errno in (errno.EPERM, errno.EACCES):
                print(f"❌ 没有权限监听 {args.host}:{args.port}：{error}", file=sys.stderr)
            else:
                print(f"❌ 端口 {args.port} 起不来：{error}", file=sys.stderr)
            print(f"   换个端口试试：python3 scripts/serve.py --port {args.port + 1}", file=sys.stderr)
            return 1

    write_pid_file(os.getpid())

    url = f"http://{'localhost' if args.host in ('127.0.0.1', '0.0.0.0') else args.host}:{args.port}/"
    print()
    print("SupportFlow 智能客服 · 官网本地预览" + ("（已自动重启）" if restarted else ""))
    print(f"  访问地址：{url}")
    print(f"  站点目录：{ROOT}")
    print(f"  API 反代：/api  →  {args.api_base}")
    print(f"  进程号：{os.getpid()}（下次再跑本脚本会自动顶替它，不需要手动 kill）")
    print("  提示：「登录 / 免费试用」默认跳管理端 http://localhost:5173，")
    print(f"       想换地址就打开 {url}?app=http://localhost:5173")
    print("  停止：在控制台按 Ctrl + C（IDEA 里点红色 ■）")
    print()

    if not args.no_open:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    # 被下一次启动 SIGTERM 掉的时候也走同一套收尾逻辑（不留僵尸、不留 pid 文件）
    def on_signal(signum, frame):  # noqa: ARG001
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止官网预览服务。")
    finally:
        httpd.server_close()
        clear_pid_file(os.getpid())
    return 0


if __name__ == "__main__":
    sys.exit(main())
