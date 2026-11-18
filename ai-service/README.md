# SupportFlow AI 服务

FastAPI 应用入口为 [ai/main.py](ai/main.py)，默认在 `127.0.0.1:9100` 监听。支持智能客服轮次、知识入库与检索、会话质检、图片判读、语音和训练接口。

```bash
python -m venv .venv
.venv/bin/python -m pip install -r requirements.txt -r requirements-ai.txt
cp .env.example .env
.venv/bin/python -m ai
```

Windows 使用 `.venv\Scripts\python` 和 `Copy-Item`。根据 [.env.example](.env.example) 指定 `ai_db`、`customer_db` 和模型配置。接口存活可以只安装基础依赖后用 `GET /api/ai/health` 检查。LangGraph、LlamaIndex 和文件解析依赖位于 [requirements-ai.txt](requirements-ai.txt)。未配置真实模型时，可用已有的规则与检索兜底路径联调。

| 接口 | 用途 |
| --- | --- |
| `POST /api/ai/v1/agent/brain` | 识别意图、检索、回复与转人工建议 |
| `POST /api/ai/v1/rag/ask` | 知识问答和引用 |
| `/api/ai/v1/kb/*` | 入库、检索与索引管理 |
| `POST /api/ai/v1/qa/evaluate` | 结构化质检 |
| `POST /api/ai/v1/agent/vision` | 图片理解 |

安装 pytest 后可用 `python -m pytest -q` 验证健康接口。[完整联调](../docs/local-development.md)
