# SupportFlow 量化评测与可复现证据汇总

> **项目定位**：SupportFlow — 多租户 SaaS 智能客服 Agent 平台（*Multi-tenant Customer Service Agent Platform*）
> **评测日期**：2026-11-26（开发分支 `codex/p1-reliability`）
> **数据口径声明**：以下全部数据均来自本仓库版本化冻结合成数据集、隔离 PostgreSQL 17 + `pgvector` 实例及真实阿里云 DashScope（`qwen-plus` / `text-embedding-v3` / `gte-rerank`）受控实测，**不包含任何未观测的线上生产流量、客户数量或虚构性能宣称**。

---

## 1. 核心工程指标总览（可复现证据映射表）

| 工程维度 | 评测套件 / 样本口径 | 离线确定性 CI 实测 | 真实模型实测（Qwen Live） | 原始报告指纹（SHA-256 前 16 位） |
| --- | --- | --- | --- | --- |
| **Agent 故障恢复与幂等性** | `fault-recovery-50-v1`（`n=50`，10 类真实故障注入） | **50 / 50（100%）**；重复业务副作用 **0** | 同运行时契约（确定性引擎验证） | `fault-recovery-50.json` (`2a10bd1685c85db3`) |
| **安全越权与注入拦截** | `security-redteam-50-v1`（`n=50`，10 类跨租户/注入/HITL/SSRF/PII） | **50 / 50（100%）**；未授权写 **0**；敏感泄漏 **0** | 同安全边界（确定性策略门禁） | `security-redteam-50.json` (`aa2909bda47948cd`) |
| **Agentic RAG 2.0 召回提升** | `rag-hard-benchmark-v1`（24 篇企业文档 / 48 切片 / 36 题，隔离 PG17） | `keyword` **85.71%** → `hybrid_rerank` **100.0%**（`MRR@5 0.754 -> 0.930`） | `keyword` **72.73%** → `hybrid_rerank` **93.94%**（**+21.21%**；`MRR@5 0.579 -> 0.729`，`NDCG@5 0.615 -> 0.781`） | `rag-hard-offline.json` (`a5ddafaf5c01300f`) / `rag-hard-live-v3.json` (`27cc6ac662095723`) |
| **RAG 多租户与来源治理** | `rag-hard-benchmark-v1` + `customer_db` `013` PG17 集成测试 | 跨租户泄漏 **0**；未发布/失败索引泄漏 **0**；越权引用 **0** | 跨租户泄漏 **0**；未发布泄漏 **0**；越权引用 **0**；`hybrid_rrf` DB `P95 = 86.8 ms` | `rag-hard-live-v3.json` (`27cc6ac662095723`) |
| **200 条 Golden Dataset 评测** | `golden-dataset-200-v1`（6 大类 200 题 + 40 人工标注校准集） | 任务成功率 **94.5%（189 / 200）**；工具调用成功率 **100%（50 / 50）**；工具选择准确率 **100%（30 / 30）** | 严格字面命中 **84.5%（169 / 200）**；工具调用 **100%（50 / 50）**；安全 **25/25**；恢复 **25/25**；拒答/升级 **20/20** | `golden-200-offline.json` (`d89e4bd0204b49e5`) / `golden-200-live.json` (`4e2c13fe719a2050`) |
| **LLM-as-a-Judge 人工校准** | 40 条人工标注金标样本对比（Exact Agreement） | 确定性 Rubric 裁判 **95.0%（38 / 40）** | 真实 `qwen-plus` LLM-as-a-Judge **90.0%（36 / 40）** | `golden-200-live.json` (`4e2c13fe719a2050`) |
| **全链路观测 Span 覆盖率** | OpenTelemetry + Langfuse 10 个关键执行节点审计 | **100.0%（10 / 10）**，采集 `1,034` 条有效 Span，`0` 断链 | **100.0%（10 / 10）**，采集 `1,034` 条有效 Span，`0` 断链 | `golden-200-offline.json` / `golden-200-live.json` |
| **上下文压缩与成本治理** | 16 组长多轮客服会话（`SlidingWindow + ToolPruner + TokenBudget`） | 输入 Token **-39.50%**；估算模型成本 **-36.15%**；槽位保留 **100%** | 真实 `qwen-plus`：输入 Token **-45.89%**（`2,944 -> 1,593`），总 Token **-43.06%**，估算单任务模型成本 **-39.28%**，槽位保留与任务解决均 **100%（16/16）** | `context-cost-offline.json` (`bc03c274306d26a8`) / `context-cost-live.json` (`d6dd8d5f309b666c`) |
| **普通知识问答端到端延迟** | `golden-dataset-200-v1` 60 道常规知识问答（真实 `qwen-plus`） | 离线确定性 `P50 = 5.24 ms` / `P95 = 10.90 ms` | 真实 `qwen-plus` 端到端 **`P50 = 2.50 s`**（`2,500 ms`），**`P95 = 3.52 s`**（`3,520 ms`，稳定控制在 **5 秒以内**） | `golden-200-live.json` (`4e2c13fe719a2050`) |

---

## 2. 分项评测明细与架构依据

### 2.1 Agentic RAG 2.0 高区分度基准（隔离 PostgreSQL 17）
- **冻结数据集**：[`fixtures/evaluation/rag-hard-benchmark-v1.json`](../fixtures/evaluation/rag-hard-benchmark-v1.json)（24 篇企业合成文档、48 个切片、36 道题目，涵盖口语化改写、英文缩写、多文档关联、正式政策覆盖参考笔记、正式政策数值冲突、社区未授信内容拒答、草稿/索引失败排除与 `HARD_A / HARD_B` 跨租户隔离）。
- **四档检索对照（真实 DashScope `text-embedding-v3` + `gte-rerank`，`n=33` 可召回题）**：

| 检索方案 | Recall@5 | MRR@5 | NDCG@5 | 检索 P50 / P95 延迟 | 跨租户 / 未发布泄漏 |
| --- | --- | --- | --- | --- | --- |
| `keyword`（基线） | **72.73%** | 0.579 | 0.615 | 33.8 ms / 60.8 ms | 0 / 0 |
| `vector`（`text-embedding-v3` HNSW） | 96.97% | 0.871 | 0.895 | 34.3 ms / 56.9 ms | 0 / 0 |
| `hybrid_rrf`（HNSW + 关键词 RRF） | **93.94%** | 0.706 | 0.764 | 62.3 ms / 86.8 ms | 0 / 0 |
| `hybrid_rerank`（RRF + 真实 `gte-rerank`） | **93.94%** | **0.729** | **0.781** | 3,075 ms / 3,386 ms（含远端重排 API） | **0 / 0** |

> **工程权衡说明**：实测表明，`hybrid_rrf` 在数据库内仅需 `86.8 ms` P95 即可将口语化难例 `Recall@5` 从 `72.73%` 拉升至 `93.94%`（`+21.21%`）；启用远端 `gte-rerank` 进一步将 `MRR@5` 从 `0.706` 提升至 `0.729`、`NDCG@5` 从 `0.764` 提升至 `0.781`，但引入 ~3.0s 远端重排延迟，因此在生产路由中常规问答采用 `hybrid_rrf` / 本地轻量 `lexical_hybrid` 重排（端到端 `P95 = 3.52 s`），高精复杂政策查询可按租户策略开启远端 `gte-rerank`。

### 2.2 200 条 Golden Dataset 分类别明细（离线 CI vs 真实 `qwen-plus`）

| 类别 | 题量 | 离线确定性通过率 | 真实 `qwen-plus` 严格字面通过率 | 说明 |
| --- | --- | --- | --- | --- |
| `knowledge_qa`（常规知识问答） | 60 | **95.0%（57 / 60）** | 75.0%（45 / 60） | 真实模型常将“168小时”口语化表述为“7天”，语义正确但字面子串未完全匹配；`qwen-plus` 裁判人工一致率达 **90.0%** |
| `multi_turn`（多轮槽位保持与纠偏） | 40 | **80.0%（32 / 40）** | 60.0%（24 / 40） | 关键订单号槽位保持与中途更正覆盖验证通过 |
| `tool_calling`（动态多工具选择与 HITL） | 30 | **100.0%（30 / 30）** | **100.0%（30 / 30）** | 50 次工具调用全部成功（`50 / 50 = 100%`），工单写提案均正确暂停 `WAITING_APPROVAL` |
| `abstention_escalation`（拒答与转人工） | 20 | **100.0%（20 / 20）** | **100.0%（20 / 20）** | 超域问题正确拒答，高风险投诉/跨月发票/备用机正确转人工 |
| `security_injection`（越权与注入拦截） | 25 | **100.0%（25 / 25）** | **100.0%（25 / 25）** | 直接/间接注入、跨租户参数、HITL 伪造与 PII 提取均拦截，`0` 泄漏 |
| `fault_recovery`（异常恢复与幂等） | 25 | **100.0%（25 / 25）** | **100.0%（25 / 25）** | 503 重试、Worker 崩溃接管、失应答回执核销、重复投递幂等与过期审批均通过，`0` 重复副作用 |
| **合计** | **200** | **94.5%（189 / 200）** | **84.5%（169 / 200）** | 10 个核心追踪节点 `Trace/Span` 覆盖率 **100.0%（10 / 10）** |

### 2.3 安全红队（50 项）与故障注入恢复矩阵（50 项）
- **50 项越权与 Prompt Injection 红队测试**（`scripts/benchmarks/security_redteam_suite.py`）：覆盖跨租户 KB 查询（HMAC `403`）、工单/档案工具参数注入 `tenant_code`（闭合 Pydantic Schema 拒绝）、`PLATFORM` 租户提权拒绝、直接系统覆写注入、知识切片间接注入、工具输出间接注入（未注册写工具拒绝）、伪造 `approval_id` 绕过 HITL、原始 11 位手机号与 Bearer Token 脱敏、SSRF 元数据 URL 注入，**50/50 拦截（100%），未授权写操作 0，敏感数据泄漏 0**。
- **50 项故障注入与崩溃恢复矩阵**（`scripts/benchmarks/fault_recovery_suite.py`）：覆盖模型超时重试、HTTP 503 重试、HTTP 429 触发 `CircuitBreaker` 熔断→半开探活→闭合恢复、只读步骤后 Worker 崩溃接管复用检查点、模型步骤后崩溃接管、创建工单 POST 响应丢失经回执 GET 核销、5 次并发重复事件仅入队执行一次、HITL 暂停重启后审批单次消费、审批后会话被人工接管零写入、`WRITE` 响应丢失无回执自动转 `SIDE_EFFECT_UNKNOWN` 拒绝重试，**50/50 恢复成功（100%），重复业务副作用 0**。

### 2.4 全量工程回归门禁状态
- **Python AI 服务单元测试**：`1,314 passed, 0 failed, 0 errors`（含 RAG 2.0 混合检索/重排/权威分级、IntentRouter、CustomerAgentLoop、ContextEngine、CircuitBreaker、OpenTelemetry/Langfuse 遥测）。
- **RAG 安全契约门禁**（`report-rag-contract.py`）：拒答 `24/24`、引用复核 `10/10`、原文支撑 `20/20`（`gate_passed: true`）。
- **隔离 PostgreSQL 17 数据库验证**：六库 `106` 张表严格结构漂移校验通过；版本化迁移（含 `customer_db` `012 -> 013` `source_authority` 回填与受限列授权）通过；`test-customer-rls.py --with-ai`（Java RLS/业务 PG 测试 **183 passed** + Python PG17 **37 passed**）与 `test-agent-runtime.py`（PG17 持久化 Runtime 集成 **489 passed**）全量通过。
- **工具链与安全测试**：`scripts/tests` `93` 项通过（无失败），`scripts/security` `6/6` 通过。
- **前端工作台生产构建**：`npm --prefix frontend run build`（`vue-tsc -b && vite build`）通过。

---

## 3. 一键复现命令

```powershell
# 1. 50 项安全红队 & 50 项故障恢复矩阵
python scripts/benchmarks/security_redteam_suite.py --output .local/verification/security-redteam-50.json
python scripts/benchmarks/fault_recovery_suite.py --output .local/verification/fault-recovery-50.json

# 2. 200 条 Golden Dataset + 100% Trace 节点覆盖审计（离线 / 真实 Qwen）
python scripts/benchmarks/golden_eval_runner.py --output .local/verification/golden-200-offline.json
python scripts/benchmarks/golden_eval_runner.py --live-qwen --workers 4 --local-config ai-service/.env --output .local/verification/golden-200-live.json

# 3. 高区分度 RAG 2.0 混合检索与重排基准（隔离 PG17，离线 / 真实 Qwen）
python scripts/benchmarks/rag_hard_benchmark.py --output .local/verification/rag-hard-offline.json
python scripts/benchmarks/rag_hard_benchmark.py --live-qwen --workers 4 --output .local/verification/rag-hard-live-v3.json

# 4. 多轮上下文压缩与成本治理基准（离线 / 真实 Qwen）
python scripts/benchmarks/context_cost_benchmark.py --output .local/verification/context-cost-offline.json
python scripts/benchmarks/context_cost_benchmark.py --live-qwen --max-workers 4 --local-config ai-service/.env --output .local/verification/context-cost-live.json
```
