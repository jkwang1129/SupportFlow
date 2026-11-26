# SupportFlow Agent 工程能力矩阵与收官路线

> **项目定位**：SupportFlow — 多租户 SaaS 智能客服 Agent 平台（*Multi-tenant Customer Service Agent Platform*）
> **当前核查基线**：分支 `codex/p1-reliability`，已完成基础可靠性改造（A0–E27，86 份 ADR、六库 106 张表）及 Phase 1–6 全栈 Agent 平台能力收官（RAG 2.0 混合重排与来源治理、Workflow+Agent Loop 混合路由与多工具闭环、上下文压缩与熔断降级、OTel+Langfuse 全链路观测、200 条 Golden Dataset + 50 安全 + 50 故障恢复实测与运维 Runbook）。
> **关联文档**：量化评测汇总见 [量化评测与证据汇总](evaluation-summary.md)；运维手册见 [生产运维 Runbook](runbook.md)；历史逐批推进日志见 [批次归档（A0–E27）](archive/batch-changelog-a0-e27.md)；实测命令与报告哈希见 [可靠性改造验收记录](reliability-verification.md)；设计决策见 [ADR 索引](decisions/README.md)。

---

## 1. 六大核心工程支柱现状总览

| 核心支柱 | 已落地能力（有源码、自动化回归与实测报告支撑） | 生产上线前剩余事项 |
| --- | --- | --- |
| **1. Agent Runtime**<br/>自主工具调用与可靠执行 | **已完成**：`IntentRouter`（`WORKFLOW / AGENT_LOOP / HUMAN_HANDOFF` 混合条件路由）、`CustomerAgentLoop`（多工具动态规划 + 自然语言建单提案 HITL）、持久化 `Run/Step/Payload`、Java 事务 `Outbox`、数据库租约/心跳/跨进程接管与不可变回执核销（50/50 故障恢复通过，重复副作用 `0`）。 | 按业务灰度策略在生产环境逐租户放量与压测。 |
| **2. Context Engineering**<br/>上下文管理与压缩 | **已完成**：`ContextEngine`（系统规则与不可信 JSON 帧隔离、注入信号检测、`SlidingWindowCompressor` 摘要压缩与关键槽位保留、`ToolResultPruner` 白名单裁剪、`TokenBudgetManager` 确定性预算裁剪）；真实 `qwen-plus` 16 组多轮实测输入 Token 降 **45.89%**、单任务估算成本降 **39.28%**、槽位保留 **100%**。 | 随真实线上多轮分布持续微调窗口大小与白名单字段。 |
| **3. AI Security**<br/>工具权限与多租户安全 | **已完成**：8 个非超级用户受限 DB 角色 + 严格 `FORCE RLS`、服务间 HMAC 签名、脱敏客户资料与访客可见历史会话只读工具、工具闭合 Schema + Scope + 资源归属复核、参数绑定单次审批；50 项越权与 Prompt Injection 红队测试拦截率 **100%（50/50）**，未授权写与敏感泄漏均为 `0`。 | 定期补充新型对抗样本进入 CI 红队集。 |
| **4. Agentic RAG & 知识治理**<br/>高可靠企业知识检索 | **已完成**：`pgvector` HNSW + 线上可选 `Hybrid Search + RRF + Reranking`（Qwen `gte-rerank` / 本地混合重排）、查询改写、`customer_db` `013` 服务端可信来源权威分级（`official / reference / community`）、低分拒答、逐句原文支撑校验、数值冲突拦截与索引一键回滚；高区分度 PG17 基准 `Recall@5` 从 `72.73%` 提升至 `93.94%`（`+21.21%`），跨租户/未发布泄漏均为 `0`。 | 生产大语料下定期评估 HNSW `ef_search` 与重排耗时预算。 |
| **5. Evaluation**<br/>自动化评测与质量回归 | **已完成**：冻结 **200 条 Golden Dataset**（六大类，离线任务成功率 **94.5%**，工具调用 **100%**，真实 `qwen-plus` QA 端到端 `P95 = 3.52 s`）、40 样本 `LLM-as-a-Judge` 人工校准（真实 `qwen-plus` 一致率 **90.0%**）、50 项安全红队与 50 项故障恢复套件，全部离线门禁已接入 `.github/workflows/verification.yml`。 | 接入线上抽样回流坏例并定期冻结新版本评测集。 |
| **6. Observability & LLMOps**<br/>追踪定位、路由与成本治理 | **已完成**：`OpenTelemetry + W3C Trace Context + Langfuse` 批量导出与 PII 脱敏（10 个关键执行节点覆盖率 **100%**）、无副作用规划回放；`Model Gateway` 多供应商路由、按供应商滑动窗口 `CircuitBreaker` 熔断与降级、租户并发及日/月 Token/金额预留与 [`docs/runbook.md`](runbook.md)。 | 生产部署时配置真实 Langfuse / OTLP Collector 终端地址与告警通道。 |

---

## 2. 原代码审查清单（PR-0 ～ PR-12）状态矩阵

| 编号 | 模块范围 | 当前状态 | 核心源码与测试证据 | 剩余收口点 |
| --- | --- | --- | --- | --- |
| **PR-0** | 测试基线与 CI | **已完成（持续演进）** | `.github/workflows/verification.yml`；63 个 Java 测试类、80 个 Python 测试文件、Playwright 浏览器回归 | 随后续新能力同步扩充 CI 门禁 |
| **PR-1** | RLS fail-closed 与受限账号 | **已完成** | `TenantRlsConfig.java`、`RuntimeDatabaseGuard.java`、`40-runtime-roles.sql`、`test-runtime-security.py` | 保持非超级用户与严格 RLS 基线不退化 |
| **PR-2** | AI 内部接口鉴权 | **已完成** | `ai/core/internal_auth.py`、`AiGatewayAccess.java`、`CustomerInternalAccess.java` | 新增内部工具端点继续沿用签名与租户绑定 |
| **PR-3** | 会话串行锁生命周期 | **已完成** | `SessionSerialExecutor.java`、`BotBrainConcurrencyTest`（6 项并发/中断回归） | 无（跨进程顺序由 PR-8 保障） |
| **PR-4** | PostgreSQL 消息幂等 | **已完成** | `SessionService#appendMessage`、`SessionMessagePostgresTest`（7 项真实 PG 回归，消除 `25P02`） | 无 |
| **PR-5** | 坐席原子认领 | **已完成** | `SessionMapper#claimUnassigned`、`SessionClaimPostgresTest`（5 项竞态回归） | 无 |
| **PR-6** | 计费用量批次去重 | **已完成** | `BillingUsageOutbox`、`30-usage-batches.sql`、`test-usage-batches.py`（14 项真实 PG 回归） | 网关独立日统计保持现有边界 |
| **PR-7** | 版本化迁移与 Readiness | **已完成** | `db-migrate.py`、`manifest.json`（六库 106 表）、`SchemaReadiness`、`test-schema-drift.py` | 新表变更严格走版本化迁移与漂移校验 |
| **PR-8** | 持久化 Agent Run | **已完成** | `ai/runtime/{store,worker,payloads,intent_router}.py`、`AgentEventOutbox`、`test-agent-runtime.py` | 生产按租户放量观测 |
| **PR-9** | Tool Registry 与受控执行 | **已完成** | `ai/runtime/{tools,knowledge_tools,profile_tools,tickets,mcp_ticket,agent_loop}.py`、Java `RuntimeCustomer*Controller` | 持续按需扩展只读业务工具 |
| **PR-10** | Checkpoint / Resume / HITL | **已完成** | `ai/runtime/approvals.py`、自然语言 `propose_ticket` HITL、`fault_recovery_suite.py`（50/50 通过，`0` 重复副作用） | 保持 0 重复副作用门禁 |
| **PR-11** | Evaluation / Replay / Trace | **已完成** | `golden_eval_runner.py`（200 题）、`llm_judge_calibration.py`、`ai/core/telemetry.py`（OTel+Langfuse 100% 覆盖） | 持续沉淀线上抽样坏例 |
| **PR-12** | RAG Grounding 与拒答 | **已完成** | `customer_db_013_kb_source_authority.sql`、`ai/rag/{store,reranker,pipeline,graph,grounded_reply}.py`、`rag_hard_benchmark.py` | 按需调优租户级重排策略 |

---

## 3. 工程交付基本准则

1. **边界清晰**：Java 管理确定性业务事实、权限校验、事务与副作用回执；Python 管理编排、检索、上下文压缩与受控工具决策。
2. **安全底线**：禁止使用超级用户或 `BYPASSRLS` 运行业务；新增任何工具或接口必须具备闭合 Schema、可信身份租户绑定与负面越权测试。
3. **证据导向**：所有评测、延迟、Token 与恢复指标必须来自可复现脚本、明确样本分母与原始 JSON 报告，未测项标记 `NOT_MEASURED`，严禁编造数据。
4. **整洁可维护**：遵守标准 Python（PEP 8）与 Java 代码排版规范，禁止单行分号堆砌与无空格压缩写法；脚本、测试与文档按职责分层归档。
