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
| PR-6 计费去重 | 客服计费链路已实现且真 PG/实际服务验证通过 | UsageBatch、UsageMeterService 批次账本、BillingUsageOutbox；14 项 PG、签名/HTTP/计数测试、新旧库实际上报 | 原始未入批次计数仍为 best-effort；网关 tenant_usage_daily 为独立链路，留后续扩展。A4 客服批次完成 |
| PR-7 版本化迁移 | 本地范围已实现并验证：Ready、六库 init/migrate/verify | 只读 gate；manifest/SQL Runner/history/checksum/事务/锁，接入原升级入口和新旧库实际服务验收 | 当前93表/索引/RLS；customer007、AI003及既有数据升级保护。生产备份/运维属M12。A5本地完成并随迁移回归 |
| PR-8 持久化 Run | 部分实现：Runtime基础/双向桥接/Worker正文恢复 | Run/Step/Worker、双向API/SDK；183项Runtime、103项客服PG；原子payload+Step完成、真实JAR→AI授权→回复/丢回执核验；默认关闭 | 只读Loop已接协议桩/真PG；实际组合联调、业务发布handler、可信调度/自动登记仍缺。B1进行中 |
| PR-9 Tool Calling | 部分实现：内部受控只读层 | `ai/runtime/tools.py`严格Registry/Executor、可信Run主体、当前ACL/结果绑定/持久化预算；旧brain固定图保留 | 实际业务只读适配、幂等写工具与bounded loop；MCP只做同一契约适配。B2 |
| PR-10 Checkpoint/HITL | 未实现 | `brain.py`、`rag/graph.py` 均 `compile()` 无 checkpointer；`docs/architecture.md` 明示缺失 | 业务对话状态不能代替执行 checkpoint；审批绑定参数、恢复重新鉴权、未知副作用核验。B3 |
| PR-11 Eval/Replay/Trace | 部分实现 | 未跟踪 `scripts/evaluation`、`docs/evaluation/2026-11-19` 为既有本地小样本记录；`core/trace.py`、`llm_usage.py`、图 steps | 已有Run/Step存储和进程恢复测试，缺统一回放平台、工具/业务恢复数据集与质量门禁；历史样本不冒充普适准确率。A 起步/D 完善 |
| PR-12 RAG | 部分完成：本轮拒答首批已通过 | `rag/graph.py` 增加 abstain；版本化 12 案例 × 两引擎 24 项通过；`services/rag.py#verify_citations` 仍只核编号 | 未完成事实支持、冲突/版本/ACL 评测，越界引用正文问题仍 xfail；不预设必须混检/重排。A6 首批/C |

Java 表中省略前缀的路径位于 `backend/supportflow-customer-service/src/main/java/io/supportflow/`；Mapper XML 位于对应服务 `src/main/resources/mapper`。

## 新增模块能力矩阵

| 模块 | 当前状态 | 可复用实现/证据 | 必须补齐及依赖 |
| --- | --- | --- | --- |
| M01 Reliable Runtime | 部分实现，尚未切换业务触发 | Run/Step/Worker、真实双向桥接、租约/Step许可、原子业务回执；中间产物与Step成功原子存储、Worker跨进程恢复 | 只读Loop已有核心验收；实际组合联调、未知写核销/审批、可信触发；PR-8/9/10。B |
| M02 Context Engineering | 部分实现 | 旧最近12条对话；customer006冻结当前输入/每次重验；AI003步骤正文存储有字节/节点/深度及完整性边界 | Worker已能恢复正文；尚无统一长历史构建器/Token预算/压缩对照；正文存储不代替上下文策略或Checkpoint。B基础/C完善 |
| M03 Tool Calling/MCP | 部分实现：内部ReadTool层 | Registry/Executor、62项契约+15项来源适配+13项真PG；实际Worker→客户JAR当前授权与缓存撤权2例 | 接实际Java只读/写工具、后可信MCP；不重复写业务服务。B |
| M04 Permission/Guardrails | 部分实现 | PR-1/2、`ChatAttachmentAccess`、`LicensePrivilegeTest`、受限 DB 角色/平台审计 | 工具 Scope、资源和输出约束、注入负面集、限时参数审批、防重放；无任意代码需求，暂不建 Sandbox。A/B |
| M05 RAG Engineering | 部分实现，本轮补齐低分拒答 | `rag/{store,retriever,graph}.py`、pgvector、关键词 fallback、单轮改写、引用编号、租户过滤；新增两引擎拒答契约测试 | 事实支持、冲突/过期、知识发布版本尚缺；混检重排先基线后选择。A/C |
| M06 Evaluation | 部分实现，契约与工程故障门禁 | 已有评测保留；RAG24项契约/5项报告负面；Runtime183项、客服PG103项、来源SDK32/结果SDK38、payload17项及实际双向写入均有脚本 | 工程测试不等于200条语义质量平台，仍缺holdout、业务事实评分、恢复/工具质量评测与安全replay；Judge若采用需人工校准。A/D |
| M07 Trace/Observability | 部分实现 | Java `TraceContext`；Python `core/trace.py`、图 steps、`llm_usage.py`；运营 `ObservabilityService#trace/evaluate`、告警规则与调用记录查询；本轮 RAG steps 增加 traceId/sequence/schemaVersion 与拒答原因 | 已有模型调用 Trace 查询与告警应复用；已有持久化Run/Step与trace_id；缺工具详情、异步业务链路/审批事件、统一查询、脱敏治理和安全replay。A/B/D |
| M08 Model Gateway/Prompt | 部分实现：真实共用Chat入口已受限 | core.llm_chat复用受限单次HTTP/稳定错误/正文边界，55项测试含真实TCP；视觉/Embedding、租户选择/usage仍原实现，llm_gateway.py仍旧Mock占位 | 统一现有调用封装，错误/超时/总预算、结构化输出、供应商权限、Prompt 版本；不额外造第二入口。E |
| M09 Streaming/交互 | 部分实现 | `supportflow-realtime-service` WebSocket、消息 seq/ACK/补拉、机器人 typing | 缺持久化 Run 事件、审批/取消/恢复展示；优先复用 WebSocket。依赖 B、M07。E |
| M10 Multi-Agent/Sandbox | 当前不适用，按需评估 | 当前核心业务不需不可信代码执行；尚无单 Agent 收益基线 | 不是必做缺陷；有明确任务与对照收益再建，MCP 动态发现亦非当前必需。F |
| M11 资源/成本治理 | 部分实现 | 有界线程池、网关限流、`llm_usage.py` 实际 usage 与价格估算、计费日聚合；运营 `TenantCostBudget`、`ObservabilityService#costGuard/evaluate` 和客服 `CostGuardClient` 已有月预算告警/降级/暂停 | 复用既有预算配置；当前闸门缓存 60 秒且读取失败放行，不能当硬额度。已有跨Worker租约/fencing、Run调用/步骤/重试计数预算和租户积压上限；仍缺Token/费用并发预留结算和跨进程租户配额/公平性；计费去重 A4，运行预算随 M01，完整隔离压测 E。费用估算不是账单。B/E |
| M12 Production Operations | 部分实现 | Compose 依赖、启动脚本、GitHub CI、新旧库/服务隔离验收、凭据分发与升级文档 | 本地六库init/migrate/verify和schema readiness已验；生产部署、备份恢复实测、Runbook/SLO告警与旧任务兼容仍缺；本地演示不能称生产运营。A5/E |

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

## 当前执行位置（2026-11-20，B1 有界工单只读Loop核心子批次）

阶段A已完成本地与远端CI退出条件，原业务并发/消息/认领/客服计费批次、六库迁移/Readiness与三套实际部署路径持续回归；RAG已知正文引用缺口仍保留，原始未入批次计数/网关计费分支仍按M11补齐。main没有合并本开发分支。

B1已有持久化Run/Step/租约/fence/预算/Worker、Java Outbox与双向签名API/SDK、当前写许可/原子业务回复回执、来源快照/原子中间正文、Worker进程恢复、显式租户模型Profile与单次MODEL恢复。B2来源与工单只读工具已与实际客户JAR验收，支持当前ACL和可变版本刷新/预算阻断。已验证SQL仍六库93表/1视图/5函数；没有新重复服务或缓存数据库。

最新基线0d4db15的[CI 44738498124](https://github.com/jkwang1129/SupportFlow/actions/runs/44738498124)两个job全部通过。本批新增TicketReadLoop：严格模型JSON驱动工具选择/停止/澄清、稳定政策绑定、上下文Hash模型恢复、最终当前核权与固定事实报告；不执行业务回复或创建工单。493项普通AI、183项Runtime专项通过；新17项纯契约/15项真PG，模型和业务HTTP均协议Stub。不能把它说成完整实际业务Agent链路，组合真实模型协议TCP→Worker→客户JAR联调为下一批。

后续依次：实际只读Loop组合联调；知识只读（含发布/租户权限与显式Embedding模型预算）、幂等写工具/报告发布、未知写核销、可信自动触发和HITL。上下文长历史/压缩、完整Eval/Trace、Provider选择/Token金额治理及生产运维仍按C～E；MCP原生接入/Multi-Agent等按后续必要性实施。默认旧Workflow保留，B1/B2尚未整体关闭，没有真实生产流量/客户/收益或付费模型质量数据。
