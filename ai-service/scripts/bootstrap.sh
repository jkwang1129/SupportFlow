#!/usr/bin/env bash
# 一键初始化：创建 .venv 并安装运行依赖
set -e
cd "$(dirname "$0")/.."

PY="${PYTHON:-python3}"
if [ ! -d .venv ]; then
  echo "[1/2] 创建虚拟环境 .venv ..."
  "$PY" -m venv .venv
else
  echo "[1/2] 虚拟环境已存在，跳过创建"
fi

echo "[2/2] 安装依赖 ..."
./.venv/bin/pip install -r requirements.txt

# 知识库 / Agent 的可选依赖（llama-index、langchain 等）体积较大，
# 默认不装；需要时显式加 --with-ai，或用 AI=1 bash scripts/bootstrap.sh
if [ "${AI:-0}" = "1" ] || [ "${1:-}" = "--with-ai" ]; then
  echo "[附加] 安装 AI 依赖（LlamaIndex / LangChain ...，体积较大，请耐心等待）..."
  ./.venv/bin/pip install -r requirements-ai.txt
else
  echo ""
  echo "提示：知识库要真正走 LlamaIndex，需要再装一次可选依赖："
  echo "    ./.venv/bin/pip install -r requirements-ai.txt"
  echo "或重跑本脚本并加参数：bash scripts/bootstrap.sh --with-ai"
fi

echo ""
echo "完成。请在 IDEA 中把项目解释器指向：$(pwd)/.venv/bin/python"
echo "然后选择运行配置 supportflow-ai (Shell) 或 supportflow-ai (Python) 即可。"
