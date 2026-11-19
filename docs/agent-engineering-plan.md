# Agent 工程能力盘点与实施计划

核查日期：2026-11-19。基线：`dca4b8e614adbce7ade8ba72323b2a4e6da4470e`，分支 `codex/p1-reliability`。本文记录本地源码核查；历史 CI 不等于本轮测试。输入为原代码审查清单和生产级 Agent 补充方案，已完整阅读。没有把方案中的目标当成现有实现。

工作区原有未跟踪的评测脚本、报告、简历及两份输入文档保持原样；它们不是本轮新增成果，也不自动纳入提交。无仓库或上级 `AGENTS.md`。本阶段不引入新服务、消息中间件或云平台，不调用收费模型，不修改现有生产数据。

## 原 PR 能力矩阵

“已实现”只针对该行范围。历史验证与当前运行结果分开；后者见 [阶段验收](reliability-verification.md)。路径均相对仓库根。

| 项目 | 当前状态 | 源码、测试或历史证据 | 缺口与优先级 |
| --- | --- | --- | --- |
| PR-0 基线/CI | 已实现，需持续扩展 | `.github/workflows/verification.yml`；Java `src/test`、`ai-service/tests`；`scripts/test-customer-rls.py`、`test-runtime-security.py`、`test-security-flow.py` | 当前变更重跑专项及业务回归；新增可靠性门禁。A |
| PR-1 RLS/运行账号 | 已实现，本轮安全回归通过 | `customer/config/TenantRlsConfig.java`、`RuntimeDatabaseGuard.java`、`security/PlatformAccess.java`；`customer_db_runtime_security.sql`；`backend/deploy/db-init/40-runtime-roles.sql`；真实 PG 与新/旧库服务回归 | 复用受限账号与 fail-closed，不另建隔离机制；不能防御凭据失窃后任意 SQL。A |
| PR-2 内部认证 | 已实现，本轮安全回归通过 | `ai-service/ai/core/internal_auth.py`；网关 `AiGatewayAccessTest`；`ai-service/tests/test_internal_auth.py`；新/旧库真实 HTTP 与服务权限回归 | 当前签名机制不等于未来写工具的主体/参数授权、防重放；写工具阶段补充。A/B |
| PR-3 会话串行 | 本轮已实现且专项/全量验证通过 | `BotBrainService`、`SessionSerialExecutor`；`BotBrainConcurrencyTest`、`SessionSerialExecutorTest` 共 6 项 | 旧代码固定调度最大并发 2；修复后为 1。仅单进程互斥，跨 Worker 顺序仍归 PR-8。A1 完成 |
| PR-4 消息幂等 | 本轮已实现且真实 PG 验证通过 | `SessionService#appendMessage`、两个 Mapper；`SessionMessagePostgresTest` 7 项 | 原 25P02、回滚计费问题已复现后修复；含关闭竞争、跨租户和软删除键。持久化计量/任务仍在 PR-6/8。A2 完成 |
| PR-5 原子认领 | 本轮已实现且真实 PG 验证通过 | `SessionService#claimSession`、`SessionMapper#claimUnassigned`；`SessionClaimPostgresTest` 5 项 | 原先双胜者/双事件、同人并发、关闭竞争、无效 ID 已复现修复。A3 完成 |
| PR-6 计费去重 | 部分实现，缺批次幂等 | `BillingMapper.xml#upsertUsage`；`BillingUsageCollector`、`BillingUsageReporter`、网关用量上报 | 日统计唯一键不能去重；内存 drain/重启丢增量亦需明确。稳定批次、事务去重、发送方重试。A4 |
| PR-7 版本化迁移 | 部分实现 | `backend/deploy/db-init`、`scripts/upgrade-runtime-security.py` 已可新建/升级严格角色；`ai/core/schema_guard.py` 仍引用缺失目录/脚本，异常仅警告 | 统一 init/migrate/verify、版本校验、受限账号无 DDL、readiness、旧库数据保留。A5 |
| PR-8 持久化 Run | 未实现 | `SessionService#scheduleBotReply` 提交后投递内存线程池；`ai/workers/consumer.py` 占位 | Run/Step/Outbox、租约与 fencing、事件幂等、预算、取消、跨 Worker 会话串行。B1 |
| PR-9 Tool Calling | 未实现通用层 | `ai/agents/brain.py` 固定图；Java 工单/知识 API 为可复用业务能力 | 内部 Registry/Executor、可信主体、Schema、只读和写工具、结果验证；MCP 只做适配。B2 |
| PR-10 Checkpoint/HITL | 未实现 | `brain.py`、`rag/graph.py` 均 `compile()` 无 checkpointer；`docs/architecture.md` 明示缺失 | 业务对话状态不能代替执行 checkpoint；审批绑定参数、恢复重新鉴权、未知副作用核验。B3 |
| PR-11 Eval/Replay/Trace | 部分实现 | 未跟踪 `scripts/evaluation`、`docs/evaluation/2026-11-19` 为既有本地小样本记录；`core/trace.py`、`llm_usage.py`、图 steps | 缺统一 Run/Step、只读回放、工具/恢复数据集与 CI 质量门禁；历史样本不冒充普适准确率。A 起步/D 完善 |
| PR-12 RAG | 部分完成：本轮拒答首批已通过 | `rag/graph.py` 增加 abstain；版本化 12 案例 × 两引擎 24 项通过；`services/rag.py#verify_citations` 仍只核编号 | 未完成事实支持、冲突/版本/ACL 评测，越界引用正文问题仍 xfail；不预设必须混检/重排。A6 首批/C |

Java 表中省略前缀的路径位于 `backend/supportflow-customer-service/src/main/java/io/supportflow/`；Mapper XML 位于对应服务 `src/main/resources/mapper`。

## 新增模块能力矩阵

| 模块 | 当前状态 | 可复用实现/证据 | 必须补齐及依赖 |
| --- | --- | --- | --- |
| M01 Reliable Runtime | 未实现目标 Runtime | 既有固定 Workflow、会话状态与 afterCommit；不能算 durable loop | PR-8/9/10；与 M11 共用租约、预算和有界调度。B |
| M02 Context Engineering | 部分实现 | `BotBrainService#buildHistory` 最近 12 条；`ai/agents/brain.py` slots/turns、`ai/services/brain.py` 对话持久化 | 统一构建器、可信/不可信分区、Token 上限、确定性事实、压缩/恢复对照。依赖 B checkpoint。C |
| M03 Tool Calling/MCP | 未实现通用层 | 既有工单创建/查询、知识检索 Java API | 先内部工具与权限、后可信 MCP 适配；不重复写业务服务。B |
| M04 Permission/Guardrails | 部分实现 | PR-1/2、`ChatAttachmentAccess`、`LicensePrivilegeTest`、受限 DB 角色/平台审计 | 工具 Scope、资源和输出约束、注入负面集、限时参数审批、防重放；无任意代码需求，暂不建 Sandbox。A/B |
| M05 RAG Engineering | 部分实现，本轮补齐低分拒答 | `rag/{store,retriever,graph}.py`、pgvector、关键词 fallback、单轮改写、引用编号、租户过滤；新增两引擎拒答契约测试 | 事实支持、冲突/过期、知识发布版本尚缺；混检重排先基线后选择。A/C |
| M06 Evaluation | 部分实现，本轮新增契约门禁 | 既有本地评测不重复开发；新增 `fixtures/evaluation/rag-abstention-v1.json`、`report-rag-contract.py`，24 项契约前后对照及 5 项报告负面测试 | 不是 200 条集或语义质量平台；仍缺 holdout、业务事实评分、恢复/工具评测与安全 replay；Judge 若采用需人工校准。A/D |
| M07 Trace/Observability | 部分实现 | Java `TraceContext`；Python `core/trace.py`、图 steps、`llm_usage.py`；运营 `ObservabilityService#trace/evaluate`、告警规则与调用记录查询；本轮 RAG steps 增加 traceId/sequence/schemaVersion 与拒答原因 | 已有模型调用 Trace 查询与告警应复用；缺持久化 Run/Step/工具关联、脱敏治理、异步链路、重试/审批事件和安全 replay。A/B/D |
| M08 Model Gateway/Prompt | 部分实现 | 实际调用在 `core/llm_chat.py`、`llm_vision.py`、`rag/embedding.py`；租户模型选择/usage；`llm_gateway.py` 仍 Mock 占位 | 统一现有调用封装，错误/超时/总预算、结构化输出、供应商权限、Prompt 版本；不额外造第二入口。E |
| M09 Streaming/交互 | 部分实现 | `supportflow-realtime-service` WebSocket、消息 seq/ACK/补拉、机器人 typing | 缺持久化 Run 事件、审批/取消/恢复展示；优先复用 WebSocket。依赖 B、M07。E |
| M10 Multi-Agent/Sandbox | 当前不适用，按需评估 | 当前核心业务不需不可信代码执行；尚无单 Agent 收益基线 | 不是必做缺陷；有明确任务与对照收益再建，MCP 动态发现亦非当前必需。F |
| M11 资源/成本治理 | 部分实现 | 有界线程池、网关限流、`llm_usage.py` 实际 usage 与价格估算、计费日聚合；运营 `TenantCostBudget`、`ObservabilityService#costGuard/evaluate` 和客服 `CostGuardClient` 已有月预算告警/降级/暂停 | 复用既有预算配置；当前闸门缓存 60 秒且读取失败放行，不能当硬额度。无并发预留/结算、公平调度/跨 Worker 租约；计费去重 A4，运行预算随 M01，完整隔离压测 E。费用估算不是账单。B/E |
| M12 Production Operations | 部分实现 | Compose 依赖、启动脚本、GitHub CI、新旧库/服务隔离验收、凭据分发与升级文档 | 全栈部署、schema readiness、备份恢复实测、Runbook/SLO 告警与旧任务兼容；本地演示不能称生产运营。A5/E |

## 分阶段计划与退出条件

| 顺序 | 工作范围 | 退出条件 |
| --- | --- | --- |
| A0 盘点与基线 | 本矩阵；当前 Java/Python/PG 安全回归；登记现有评测与 Trace | 区分历史记录/当前实测、复现命令和隔离环境；确认不重复 P0 改造 |
| A1 PR-3 | 只修会话锁生命周期 | 旧代码固定 A/B/C 调度失败；新代码同键最大并发 1、异键并发、异常/中断无泄漏 |
| A2 PR-4 | PG 消息幂等 | 真 PG 同 ID/seq、单次消息及副作用、回滚、无 25P02 |
| A3 PR-5 | 原子认领 | 真 PG 一名胜者、一套事件和提示；关闭拒绝、同人幂等 |
| A4 PR-6 | 稳定计费批次 | 增量与去重同事务；并发、丢响应重试、批次内容冲突；发送端保留批次 |
| A5 PR-7 | 权威版本迁移/readiness | 新建、旧库升级、重复执行、失败回滚、数据/RLS/角色不退化 |
| A6 PR-12 首批 | 低证据终止生成；初始质量回归与节点轨迹 | LangGraph/linear 一致；正负例、改写预算、不调用模型；事实支持另立验收 |
| B1 PR-8/M01/M11 | 先 ADR/状态机、持久化事件与 Worker，后 bounded loop | 领取/心跳/fencing/取消、预算、重启恢复；同会话跨 Worker 串行，重复事件无副作用 |
| B2 PR-9/M03/M04 | 工单查询、知识查询、幂等创建工单；最小 Step Trace | Schema/Scope/归属/超时/错误分类/结果核验；Java 再鉴权；实际本地 API 与 Stub 都通过 |
| B3 PR-10 | Checkpoint、限时审批与恢复 | 参数摘要/主体/资源绑定、撤销/过期、重鉴权、未知写结果查询；故障注入不重复写 |
| C M02/M05 | 上下文预算/压缩/恢复、RAG 事实支持及知识生命周期 | 固定版本/预算对照；质量与 Token 同报，保留失败样本，选择有证据的检索改进 |
| D PR-11/M06/M07 | 完善版本化评测、安全回放、Trace 查询与 CI 门禁 | 有可复现改进对照，注入错误能使门禁失败，回放不执行写工具，跨租户不可见 |
| E M08/M09/M11/M12 | 统一模型和 Prompt、Run 进度、预算/公平性、部署与运维 | 故障供应商/429/超时、并发预算、租户公平、重连/取消、升级/备份恢复/回滚实测 |
| F M10 | 可选编排/代码隔离 | 有业务需求且相比单 Agent 可解释收益才启用；不阻塞核心版本 |

A6 可在 A2～A5 之间独立实施，不与 DB/Runtime 改造混在一个批次。M06/M07 从 A 开始收集基线，B 把 run/step 关联作为结构的一部分，D 才完善展示和治理。PR-8 的表和迁移必须沿用 A5 权威路径。

## 设计决策与交付规则

1. Java 保留业务事实、事务、资源权限和最终写操作；Python 在现有 AI 服务内部新增 Runtime 模块。先以本地工单业务完成工具闭环，不虚构订单/退款集成。
2. 优先 PostgreSQL 原子状态迁移/持久化任务，是否需要 MQ 以故障和吞吐证据决定；不能用 `@Async` 替代可靠投递。
3. PR-3 选择带引用计数的每键互斥，引用覆盖执行者和等待者；不用固定全局锁阻塞无关租户，也不用只改 `remove(key, value)` 的无效修复。分布式领取在 PR-8 解决。
4. 现有鉴权、账号、RLS、WebSocket、模型调用、知识库、评测脚本优先复用；新增关键选型在编码前补 ADR。
5. 每批必须先复现或明确可测不变量，再实现和负面测试。报告修改文件、原始日志、环境/版本/模式、命令、实际结果、迁移/回滚、未验证边界。`NOT_MEASURED` 优于填入目标值。
6. 本地原始日志保存在 `.local/implementation/`；必要的公开工程说明放在 `docs/`。仅使用合成数据和隔离数据库，默认离线模型。性能与质量数据必须注明样本和环境。
7. 不以完成计划代替完成项目；不因单批测试通过宣称全阶段、生产或所有模块验收通过。每批完成后更新本矩阵及验收记录，再进入下一批。
