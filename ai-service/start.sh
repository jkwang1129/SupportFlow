#!/usr/bin/env bash
# ============================================================================
# supportflow-ai · AI 编排中心启动脚本（参考企业知识库后端 start.sh 方案）
#
# 用法:
#   ./start.sh                     # 默认 127.0.0.1:9100
#   ./start.sh --host 0.0.0.0      # 监听所有网卡
#   ./start.sh --port 9200         # 指定端口
#   ./start.sh --no-reload         # 关闭热重载
#   ./start.sh restart             # 重启服务（先停旧进程再启动）
#   ./start.sh -h, --help          # 显示帮助
#
# 该脚本可在 IDEA（Shell 运行配置）、终端中直接使用，
# 不依赖 Python 插件与 IDEA 解释器设置，由脚本自行管理 .venv。
# ============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"
# zsh 在函数内部会把 $0 变成函数名，这里先存一份，usage 才能显示正确的脚本名
SCRIPT_NAME="$0"

APP_HOST="${SUPPORTFLOW_AI_HOST:-127.0.0.1}"
APP_PORT="${SUPPORTFLOW_AI_PORT:-9100}"
APP_RELOAD="true"
MODE="start"

usage() {
  cat <<EOF
用法: $SCRIPT_NAME [选项]

  --host HOST    监听地址（默认 \$SUPPORTFLOW_AI_HOST 或 127.0.0.1）
  --port PORT    监听端口（默认 \$SUPPORTFLOW_AI_PORT 或 9100）
  --no-reload    关闭热重载
  restart        重启服务（自动停止旧进程后启动）
  stop           只停止本项目占用的端口，不启动服务
  -h, --help     显示帮助
EOF
}

# 列出占用指定端口的进程号（每行一个）
port_pids() {
  lsof -ti tcp:"$1" 2>/dev/null || true
}

# 逐个结束进程。
# 注意：IDEA 的 Shell 运行配置是用 /bin/zsh 执行本脚本的，而 zsh 默认不做
# 单词拆分，写成 kill $pids 时多行进程号会被当成“一个”参数，导致 kill 直接失败
# （报 illegal process id 并被 2>/dev/null 吞掉），旧进程永远杀不掉。
kill_each() {
  local sig="$1"
  local pids="$2"
  local pid
  printf '%s\n' "$pids" | while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    kill "-$sig" "$pid" 2>/dev/null || true
  done
}

# 兜底清理：本项目 .venv 启动、但已经不再占用目标端口的残留进程
# （uvicorn 热重载的 reloader 父进程被漏杀时就是这种情况）
kill_orphans() {
  local pid
  for pattern in "$PROJECT_DIR/.venv/bin/python" ".venv/bin/python -m ai"; do
    pgrep -f "$pattern" 2>/dev/null | while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      [ "$pid" = "$$" ] && continue
      [ "$pid" = "$PPID" ] && continue
      kill -9 "$pid" 2>/dev/null || true
    done
  done
}

stop_port() {
  local port="$1"
  local pids
  local wait_round

  pids=$(port_pids "$port")
  if [ -n "$pids" ]; then
    echo -e "  ${YELLOW}发现端口 $port 被占用：$(printf '%s' "$pids" | tr '\n' ' ')${NC}"
    echo -e "  ${YELLOW}正在停止旧进程（SIGTERM）...${NC}"
    kill_each TERM "$pids"
    for wait_round in 1 2 3 4 5; do
      sleep 1
      pids=$(port_pids "$port")
      [ -z "$pids" ] && break
    done
    if [ -n "$pids" ]; then
      echo -e "  ${YELLOW}旧进程未退出，强制停止（SIGKILL）...${NC}"
      kill_each KILL "$pids"
      sleep 1
    fi
  fi

  # 兜底：清理本项目遗留的 AI 服务进程（含热重载的父/子进程）
  if command -v pgrep >/dev/null 2>&1; then
    kill_orphans
    sleep 1
  fi

  pids=$(port_pids "$port")
  if [ -n "$pids" ]; then
    echo -e "  ${RED}端口 $port 仍被占用，无法启动：${NC}"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sed 's/^/    /'
    echo -e "  ${YELLOW}请手动执行 kill -9 <PID> 后重试。${NC}"
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    restart)
      MODE="restart"
      shift
      ;;
    stop)
      MODE="stop"
      shift
      ;;
    --host)
      APP_HOST="$2"
      shift 2
      ;;
    --port)
      APP_PORT="$2"
      shift 2
      ;;
    --no-reload)
      APP_RELOAD="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo -e "${RED}未知参数: $1${NC}（使用 --help 查看用法）"
      exit 1
      ;;
  esac
done

if [ "$MODE" = "stop" ]; then
  echo ""
  echo -e "${CYAN}${BOLD}停止 supportflow-ai（端口 $APP_PORT）${NC}"
  stop_port "$APP_PORT"
  echo -e "  ${GREEN}✓${NC} 已停止"
  echo ""
  exit 0
fi

echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║              supportflow-ai · AI 编排中心启动              ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# ============================================================================
# Step 1: 释放端口（避免重复启动残留进程占用）
# ============================================================================
echo -e "${BOLD}[1/4]${NC} 检查端口 $APP_PORT ..."
stop_port "$APP_PORT"
echo -e "  ${GREEN}✓${NC} 端口检查完成"
echo ""

# ============================================================================
# Step 2: 校验 Python 虚拟环境
# ============================================================================
echo -e "${BOLD}[2/4]${NC} 检查 Python 虚拟环境..."
if [ ! -x .venv/bin/python ]; then
  echo -e "  ${YELLOW}未发现 .venv，正在创建...${NC}"
  python3 -m venv .venv
fi
PY_VERSION="$(.venv/bin/python --version 2>&1)"
echo -e "  ${GREEN}✓${NC} $PY_VERSION"
echo ""

# ============================================================================
# Step 3: 安装/校验依赖
# ============================================================================
echo -e "${BOLD}[3/4]${NC} 检查运行依赖..."
if ./.venv/bin/python -c "import fastapi, uvicorn" >/dev/null 2>&1; then
  echo -e "  ${GREEN}✓${NC} 依赖已就绪"
else
  echo -e "  ${YELLOW}缺少依赖，正在安装 requirements.txt ...${NC}"
  ./.venv/bin/pip install -r requirements.txt
  echo -e "  ${GREEN}✓${NC} 依赖安装完成"
fi
echo ""

# ============================================================================
# Step 4: 启动 FastAPI 应用
# ============================================================================
echo -e "${BOLD}[4/4]${NC} 启动 FastAPI 应用..."
echo ""
echo -e "  ${CYAN}══════════════════════════════════════════════════${NC}"
echo -e "  ${BOLD}AI 服务地址:${NC}  ${BLUE}http://$APP_HOST:$APP_PORT${NC}"
echo -e "  ${BOLD}健康检查:${NC}    ${BLUE}http://$APP_HOST:$APP_PORT/api/ai/health${NC}"
echo -e "  ${BOLD}API 文档:${NC}    ${BLUE}http://$APP_HOST:$APP_PORT/docs${NC}"
echo -e "  ${BOLD}热重载:${NC}      ${YELLOW}$APP_RELOAD${NC}"
echo -e "  ${BOLD}退出:${NC}        Ctrl + C"
echo -e "  ${CYAN}══════════════════════════════════════════════════${NC}"
echo ""

export SUPPORTFLOW_AI_HOST="$APP_HOST"
export SUPPORTFLOW_AI_PORT="$APP_PORT"
if [ "$APP_RELOAD" = "true" ]; then
  export SUPPORTFLOW_AI_DEBUG="true"
else
  export SUPPORTFLOW_AI_DEBUG="false"
fi

# 前台运行：日志实时输出到 IDEA Console / 终端，Ctrl+C 即可停止
exec ./.venv/bin/python -m ai
