# SupportFlow Agent 工程能力矩阵与收官路线

> **项目定位**：SupportFlow — 多租户 SaaS 智能客服 Agent 平台（*Multi-tenant Customer Service Agent Platform*）
> **当前核查基线**：分支 `codex/p1-reliability`，提交 `b9f46b1`（已完成 A0–E27 共 145 次提交、86 份 ADR、六库 106 张表）。
> **关联文档**：历史逐批推进日志见 [批次归档（A0–E27）](archive/batch-changelog-a0-e27.md)；实测命令与报告哈希见 [可靠性改造验收记录](reliability-verification.md)；设计决策见 [ADR 索引](decisions/README.md)。

---

## 1. 六大核心工程支柱现状总览

| 核心支柱 | 已落地能力（有源码与自动化回归支撑） | 待收官补齐项（下一阶段目标） |
| --- | --- | --- |
| **1. Agent Runtime**<br/>自主工具调用与可靠执行 | 持久化 `Run/Step/Payload`、Java 事务 `Outbox`、数据库租约/心跳/跨进程接管、限时 HITL 审批、任务进度事件游标补拉与取消、基于不可变回执的未知写核销。 | 解除 `TicketReadLoop` 单工具与 `/ticket-request` 限制，落地 `Workflow + Agent Loop` 条件路由（`Intent Router`）与多工具动态选择、自然语言建单提案。 |
| **2. Context Engineering**<br/>上下文管理与压缩 | 系统规则与不可信 JSON 数据隔离、工单规划历史压缩（`4,907B -> 120B`）、客服意图字节预算（`4 KiB` 省 `14.63%` Token）、回合锚定截断、当前轮订单号更正。 | 长多轮会话滚动摘要压缩、大工具输出分页/白名单裁剪，以及在多轮任务集上实测单任务平均模型成本降幅。 |
| **3. AI Security**<br/>工具权限与多租户安全 | 8 个非超级用户受限 DB 角色 + 严格 `FORCE RLS`、服务间 HMAC 签名、操作专属短期写许可、工具闭合 Schema + Scope + 资源归属复核、参数绑定单次审批。 | 新增客户资料（脱敏）与历史会话只读工具的权限边界，将越权与直接/间接 Prompt Injection 自动化回归扩充至 ≥50 项。 |
| **4.Agentic RAG & 知识治理**<br/>高可靠企业知识检索 | `pgvector` HNSW、查询改写、低分拒答、逐句原文支撑校验、数值冲突拦截与人工核验路由、索引暂存原子切换、过期清理、上一版索引回滚及 37 题离线/在线混检对照。 | 服务端可信来源权威分级（正式政策 vs 参考资料）、将 `Hybrid Search + RRF + Reranking` 接入线上可选检索链路并在高区分度语料上实测 `Recall@5` 提升。 |
| **5. Evaluation**<br/>自动化评测与质量回归 | 25 题主集 + 12 题政策留出集 + 9 题意图集 + 10 题边界集 + 故障轨迹与只读规划回放，全部接入 GitHub Actions CI。 | 构建覆盖六大类的 **200 条 Golden Dataset**，接入 `LLM-as-a-Judge` 与人工一致性校准，补齐 ≥50 项故障注入恢复测试。 |
| **6. Observability & LLMOps**<br/>追踪定位、路由与成本治理 | 异步请求号贯穿、跨库执行时间线、无副作用回放、积压延迟告警；有界模型传输、租户供应商路由、工单 Prompt 版本发布/回退、租户并发配额及日/月 Token 与金额预留。 | 接入 `OpenTelemetry + Langfuse` 实现标准 `Run/Step/Span` 追踪导出；在 `Model Gateway` 增加按供应商滑动窗口熔断器（`Circuit Breaker`）；补运维 `Runbook`。 |

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
| **PR-8** | 持久化 Agent Run | **核心完成（~80%）** | `ai/runtime/{store,worker,payloads}.py`、`AgentEventOutbox`、`test-agent-runtime.py` | 接入 `Intent Router` 打通复杂客服请求分流 |
| **PR-9** | Tool Registry 与受控执行 | **部分完成（~75%）** | `ai/runtime/tools.py`、知识/工单读写工具及 `mcp_ticket.py` | 补齐客户资料、历史会话工具及多工具联合规划循环 |
| **PR-10** | Checkpoint / Resume / HITL | **工单闭环完成（~80%）** | `ai/runtime/approvals.py`、工作台审批/进度补拉/取消组件、`ticket_create_receipt` | 支持自然语言建单提案触发及 ≥50 项故障恢复统计 |
| **PR-11** | Evaluation / Replay / Trace | **部分完成（~75%）** | `scripts/benchmarks/`、`trace_timeline.py`、`ticket_replay.py` | 扩充至 200 条 Golden Dataset + Judge 校准 + OTel/Langfuse |
| **PR-12** | RAG Grounding 与拒答 | **主体完成（~90%）** | `ai/rag/{graph,grounded_reply,stage_retention}.py`、`KbService.java` | 补可信来源权威分级、线上 Hybrid RRF + Reranking 及难例基准 |

---

## 3. 工程交付基本准则

1. **边界清晰**：Java 管理确定性业务事实、权限校验、事务与副作用回执；Python 管理编排、检索、上下文压缩与受控工具决策。
2. **安全底线**：禁止使用超级用户或 `BYPASSRLS` 运行业务；新增任何工具或接口必须具备闭合 Schema、可信身份租户绑定与负面越权测试。
3. **证据导向**：所有评测、延迟、Token 与恢复指标必须来自可复现脚本、明确样本分母与原始 JSON 报告，未测项标记 `NOT_MEASURED`，严禁编造数据。
4. **整洁可维护**：遵守标准 Python（PEP 8）与 Java 代码排版规范，禁止单行分号堆砌与无空格压缩写法；脚本、测试与文档按职责分层归档。
