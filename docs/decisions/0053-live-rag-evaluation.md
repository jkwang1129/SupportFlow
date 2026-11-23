# ADR-0053 真实模型 RAG 配对评测与语义复核

在 [D9 对照运行器](0052-paired-rag-benchmark.md) 中新增显式 `--live-qwen` 模式。两组共享隔离 PostgreSQL、固定语料、访客来源过滤、关键词检索和应用提示词。单次检索的改写上限为 0，Agentic RAG 的上限为 1。真实模型试跑不会更改应用默认配置和业务 API。

## 调用与用量

- 仅使用固定语料与当前问题必要的历史，标注只参与离线评分。通过已有模型 HTTP 客户端调用 `qwen-plus`。固定官方 DashScope 端点和模型名，从当前进程环境或本机忽略的 `ai-service/.env` 获取密钥。凭证不进入参数、报告和日志，CI 沿用固定生成替身。[千问兼容接口文档](https://www.alibabacloud.com/help/en/model-studio/model-calling-in-sub-workspace)
- 固定温度 0.2，关闭思考模式，每对每组各跑一次，逐题交换先后。每题最多一次生成。单次试跑最多 50 次请求，单请求 JSON 上限 12,000 字节、生成上限 512 Token、超时 25 秒。使用现有无重试传输，出现供应商传输异常就停止后续请求，保留失败和未调用样本。结果文件必须是新路径。评测不写业务用量账本。
- 每条记录保留请求摘要、供应商回执标识、返回模型名、终止原因、调用耗时与可核对的 Token。只有输入、输出与总数能对账的回执才累计，缺失或不一致的用量单列为未知。本机执行耗时包含实际远程 Chat、数据库检索和编排，不覆盖生产网关。`qwen-plus` 是供应商返回的模型名，本次未获得更细的快照版本。供应商账单金额标记为 `NOT_MEASURED`。

## 评分与校准

结构门禁继续校验空范围、检索授权、复核来源、引用编号和预算。复核员逐条记录正文语义决策、每项标注事实是否齐全、是否附加了实质性无依据结论、每个引用是否支持它绑定的结论，以及判分理由。事实允许合理同义改写及由问句已明确的条件，遗漏未被问句限定的政策适用条件计为缺失。引用可用于支持部分正确事实，必须能指回授权、有权威且支撑相关结论的原文。引用分母取正文和返回列表中出现的引用索引并集。

建议人工只能在正文明确需要由人工裁定或核验时计作 `handoff`。普通拒答话术或面向额外问题的可选人工咨询继续分别归类。报告提供人工建议的混淆矩阵，实际转人工操作留空。原始报告的 SHA-256 绑定逐题判分，评审缺项、重复记录或引用超出结构校验范围均拒绝。对比语义与接口结构，共同识别决策不一致和引用结构有效但事实不受支持的用例。首次复核由 Codex 逐条核对，尚无独立人工复核或模型评审器一致性数据。

## 复现与回退

常规 CI 和本地回归无需外部模型。需要真实输出时，在 Docker 可用且本机已配置密钥后，使用新路径运行。复现原始分数时直接使用原始 JSON 与对应评分文件，离线评分不会调用模型。

```powershell
python -m pip install -r ai-service/requirements-test.txt
python scripts/rag_quality_benchmark.py --live-qwen --output .local/verification/rag-live-new.json
python scripts/rag_quality_review.py --raw .local/verification/rag-live-new.json --output .local/verification/rag-review-template.json
python scripts/rag_quality_review.py --raw .local/implementation/d10-rag-qwen-live.json --review .local/implementation/d10-rag-qwen-review.json --output .local/verification/rag-reviewed-reproduced.json
python -m unittest discover -s scripts/tests -p 'test_rag_quality_*.py' -v
```

停用真实运行选项可继续使用确定性基准。回退本批可恢复 D9 的报告与评分方式，业务库、模型配置和应用接口无需恢复。
