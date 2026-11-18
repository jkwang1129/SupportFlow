#!/usr/bin/env bash
# SupportFlow 智能客服 · 官网本地预览（IDEA 里可以直接运行这一个文件）
#
#   ./serve.sh                      # http://localhost:5180
#   ./serve.sh --port 5181          # 换端口
#   ./serve.sh --no-open            # 不自动开浏览器
#   SUPPORTFLOW_API_BASE=http://127.0.0.1:9090/api ./serve.sh
#
# 优先用仓库里现成的 Python（ai-service/.venv），没有就用系统 python3，
# 再没有就退回原来的 Node 版 serve.mjs——三条路都不需要装任何依赖。
set -euo pipefail

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SITE_DIR/.." && pwd)"

PYTHON=""
for candidate in "$REPO_DIR/ai-service/.venv/bin/python" "$SITE_DIR/.venv/bin/python" "$REPO_DIR/.venv/bin/python"; do
  if [ -x "$candidate" ]; then PYTHON="$candidate"; break; fi
done
if [ -z "$PYTHON" ] && command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
fi

if [ -n "$PYTHON" ]; then
  exec "$PYTHON" "$SITE_DIR/scripts/serve.py" "$@"
fi

if command -v node >/dev/null 2>&1; then
  echo "未找到 Python，改用 Node 版预览服务器（scripts/serve.mjs）"
  exec node "$SITE_DIR/scripts/serve.mjs" "$@"
fi

echo "❌ 既没有 Python3 也没有 Node.js，装一个再用：https://www.python.org/downloads/" >&2
exit 1
