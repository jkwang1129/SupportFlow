# ADR-0055 访客问答的结构化答复与原文核查

D10 记录了正文已拒答而接口仍返回 `answer`，以及引用能定位但事实支撑不足的情况。访客问答可通过 `SUPPORTFLOW_AI_RAG_REQUIRE_GROUNDED_REPLY=true` 采用结构化模型答复。配置默认关闭，按部署启用。这个批次保持相同检索，便于独立评估答复协议的影响。[D10 验收](../reliability-verification.md#d10-真实模型配对与语义复核2026-11-23)

## 执行约束

- 模型返回 `decision=abstain` 或最多三条带证据的答复。每条答复包含中文结论及一到三条原文证据。提示词要求完整保留新结论的适用条件、否定和计量单位，避免额外猜测办理路径、材料和承诺。引用所用的资料及原文由运行时核对。[提示词和协议](../../ai-service/ai/rag/grounded_reply.py)
- 服务器验证对象结构、字段数量、文本长度、有效字符、引用序号与切片中的逐字原文。每个引用允许最多三条连续完整原句，避免误拒模型复制的短段。显示的引用编号由服务器绑定真实切片后生成。错位、部分原句、不连续拼接、重复键、预置编号或无效结构都会停止返回生成正文。显式拒答同步更新接口决策并清空引用。模型缺失也返回拒答。原有发布状态和切片内容的返回前复核继续生效。
- 这条协议验证原文存在与来源绑定。用词和结论的语义支持仍通过评测核实。当前知识表的来源码表示手工、导入或 AI 生成，权威政策类型和版本冲突仍需可信元数据。协议提供客户可见的人工核验建议，转人工执行继续由原业务流程决定。

## 对照与回退

两组均使用原 Agentic RAG 的改写上限、阈值、关键词检索和访客文档范围。逐题核对首轮和改写后有序结果完全一致。原版使用自由文本答复，候选使用结构化答复和原文验证。两组共享同一模型、生成预算及执行顺序轮换。原始报告记录拒答原因、请求和源码摘要、供应商回执与答复。

CI 使用固定替身覆盖结构及检索等价，真实试跑由命令显式启用。候选语义质量按原有固定题及评分口径逐题复核，线上启用仍需独立样本验收。回退设为 `false` 并重启 AI 服务即可恢复原答复方式，数据库无需迁移或回退。

```powershell
python -m pip install -r ai-service/requirements-test.txt
python scripts/rag_quality_benchmark.py --comparison grounding --output .local/verification/rag-grounding-benchmark.json
python scripts/rag_quality_benchmark.py --comparison grounding --live-qwen --output .local/verification/rag-grounding-live-new.json
python scripts/rag_quality_review.py --raw .local/verification/rag-grounding-live-new.json --output .local/verification/rag-grounding-review-template.json
python scripts/rag_quality_review.py --raw .local/implementation/d12-rag-grounding-qwen-final.json --review .local/implementation/d12-rag-grounding-review.json --output .local/verification/rag-grounding-rescored.json
```
