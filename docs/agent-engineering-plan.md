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
| PR-7 版本化迁移 | 本地范围已实现并验证：Ready、六库 init/migrate/verify | 只读 gate；manifest/SQL Runner/history/checksum/事务/锁，接入原升级入口和新旧库实际服务验收 | 当前97表/索引/RLS；customer009、AI007及既有数据升级保护。生产备份/运维属M12。A5本地完成并随迁移回归 |
| PR-8 持久化 Run | 部分实现：单租户可信登记、投递与只读 Loop 实际连通 | Run/Step/Worker、事务 Outbox、签名 API、固定运营 Profile、真实进程接管及受控回复回执；默认关闭的影子入口 | 待扩展租户策略管理、自动批准执行宿主及业务回复切换策略。B1 试点已验 |
| PR-9 Tool Calling | 部分实现：只读工具/受控回复与幂等建单WRITE | `ai/runtime/tools.py`严格Registry/Executor、可信主体/Scope、当前ACL/结果绑定/预算；知识Java窄API/SDK/每次重读工具、Embedding共享预算与实际JAR联调；有界工单Loop | 幂等建单事务/签名API已验，建单SDK/WRITE执行已验，模型WRITE与知识决策Loop仍缺；回复由宿主核验报告发布，非通用模型WRITE工具。MCP尚未接入。B2 |
| PR-10 Checkpoint/HITL | 部分实现：工单审批、恢复、会话发现、预览与决定 API | 原子正文和原预算恢复、真实进程接管、限时审批绑定主体/快照/参数、一次性消费、到期/撤销、坐席角色与来源重验、回复及建单未知写核销 | 待接坐席操作界面和面向真实业务的批准执行宿主；其他写操作需各自回执。B3 |
| PR-11 Eval/Replay/Trace | 部分实现 | 未跟踪 `scripts/evaluation`、`docs/evaluation/2026-11-19` 为既有本地小样本记录；`core/trace.py`、`llm_usage.py`、图 steps | 已有Run/Step存储和进程恢复测试，缺统一回放平台、工具/业务恢复数据集与质量门禁；历史样本不冒充普适准确率。A 起步/D 完善 |
| PR-12 RAG | 部分完成：本轮拒答首批已通过 | `rag/graph.py` 增加 abstain；版本化 12 案例 × 两引擎 24 项通过；`services/rag.py#verify_citations` 仍只核编号 | 文档范围/空选集/关键词降级、访客发布就绪策略已回归；未完成事实支持、冲突/版本/ACL 评测，越界引用正文问题仍 xfail；不预设必须混检/重排。A6 首批/C |

Java 表中省略前缀的路径位于 `backend/supportflow-customer-service/src/main/java/io/supportflow/`；Mapper XML 位于对应服务 `src/main/resources/mapper`。

## 新增模块能力矩阵

| 模块 | 当前状态 | 可复用实现/证据 | 必须补齐及依赖 |
| --- | --- | --- | --- |
| M01 Reliable Runtime | 部分实现，尚未切换业务触发 | Run/Step/Worker、双向桥接、租约/Step许可、原子业务回执；正文与Step成功原子存储、跨进程恢复；回复/建单核销及工单审批 | 只读Loop/报告发布已通过实际服务组合；可信触发、租户策略和审批交互待补，其他写操作需各自的核销适配。B |
| M02 Context Engineering | 部分实现 | 旧最近12条对话；customer006冻结当前输入/每次重验；AI003步骤正文存储有字节/节点/深度及完整性边界 | Worker已能恢复正文；尚无统一长历史构建器/Token预算/压缩对照；正文存储不代替上下文策略或Checkpoint。B基础/C完善 |
| M03 Tool Calling/MCP | 部分实现：实际受控业务工具层 | Registry/Executor、来源/工单/知识工具与签名SDK，实际Worker/受限PG核权/刷新/共享Embedding预算；有界工单Loop；宿主报告原子发布与回执核销 | 原子建单事务/签名API已验，建单SDK/WRITE执行已验，模型WRITE/知识决策Loop、通用未知写与可信MCP仍缺；不重复写业务服务。B |
| M04 Permission/Guardrails | 部分实现 | PR-1/2、附件权限/受限DB/RLS；工具Scope/闭合参数/当前资源核权/固定事实报告；操作专属签名许可/参数摘要与回执绑定、有效MAC跨操作拒绝、越权/假引用/假写声明负例 | 已补RAG/KB所测应用日志与已处理错误的内容排除；完整直接/间接注入集、通用PII/秘密保护、限时参数审批/防重放仍缺；无任意代码需求，暂不建Sandbox。A/B |
| M05 RAG Engineering | 部分实现，本轮补齐低分拒答 | `rag/{store,retriever,graph}.py`、pgvector、关键词 fallback、单轮改写、引用编号、租户过滤；两引擎拒答/文档范围/空选集和访客发布就绪过滤回归 | 事实支持、冲突/过期、知识发布版本尚缺；混检重排先基线后选择。A/C |
| M06 Evaluation | 部分实现，契约与工程故障门禁 | 已有评测保留；RAG24项契约/5项报告负面；Runtime373项（含工单核销与审批测试）、客服PG139项、38项回执存储、实际只读Loop/进程恢复/五例发布/两例核销/七例知识工具和操作许可有机器证据 | 工程测试不等于200条语义质量平台，仍缺holdout、业务事实评分、恢复/工具质量评测与安全replay；Judge若采用需人工校准。A/D |
| M07 Trace/Observability | 部分实现 | Java `TraceContext`；Python `core/trace.py`、图 steps、`llm_usage.py`；运营 `ObservabilityService#trace/evaluate`、告警规则与调用记录查询；本轮 RAG steps 增加 traceId/sequence/schemaVersion 与拒答原因 | 已有模型调用 Trace 查询与告警应复用；已有持久化Run/Step与trace_id；缺工具详情、异步业务链路/审批事件、统一查询、脱敏治理和安全replay。A/B/D |
| M08 Model Gateway/Prompt | 部分实现：共用有界Chat/查询Embedding与Runtime适配 | core.llm_chat单次HTTP/稳定错误/正文边界；显式租户ChatProfile、Prompt/请求Hash、MODEL原子正文/usage及预算恢复；实际协议TCP故障/进程验收；llm_gateway.py仍旧Mock占位 | 视觉/旧RAG同步Embedding、租户选择/Prompt发布/供应商权限和usage/Token金额结算治理仍缺；不造第二入口。E |
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

## 当前执行位置（2026-11-21，B3 会话审批发现）

阶段 A 已完成退出条件，B1/B2/B3 仍部分实现。现有默认客服流程继续运行。Runtime 已有预算、租约、Outbox、签名 API、正文检查点与进程恢复、有界 Loop 和报告发布。回复与建单支持按回执核销，缺回执继续阻断。工单限时审批已接等待、恢复与决定，可信触发、提案发现和坐席交互待交付。

3d644cb的[CI 44854145977](https://github.com/jkwang1129/SupportFlow/actions/runs/44854145977)两个job全部成功。文档范围/空选集、诊断内容排除和访客发布/索引就绪策略已完成本地/远端验收；该批为Customer008/AI004、94表/1视图/5函数，新增KB列读取不授正文/文档修改。

ad66e75的[CI 44856556934](https://github.com/jkwang1129/SupportFlow/actions/runs/44856556934)两个job全部成功。Runtime查询Embedding已具MODEL共享预算、原子向量Checkpoint与恢复，旧RAG尚未迁入。新50项单元/14项PG、645项AI普通/228项Runtime以及完整默认服务都已在该精确提交CI核实。

本批在既有customer增加窄POST知识API，当前来源/发布就绪/同租户/模型标签与有限结果同事务核验，查询后再次核DB时钟TTL；复用服务签名/read-tools默认关闭。新6项Java单元/9项真实PG，客服PG共112项与Python KB19项通过；228项Runtime及实际JAR签名知识查询/原全部Loop/发布/核销回归通过。响应绑定请求SHA与投影Hash（不是全库版本），无业务副作用/模型调用。三套默认服务验收结果见reliability-verification。本批未注册Python知识工具。证据/边界/回滚见ADR-0019。

914767a的[CI 44859565743](https://github.com/jkwang1129/SupportFlow/actions/runs/44859565743)两个job全部成功。当前新增KnowledgeClient复用固定CustomerTransport，严格类型/字段/来源/请求/查询/投影Hash/片段/范围核验；91项新SDK单元含3例真实TCP及实际Java外层long字符串兼容。AI普通736项通过/233条件skip/1原xfail，24项拒答门禁保持。228项Runtime及原全部实际组合/新产品SDK→真实JAR/受限PG联调通过；证据/回滚见reliability-verification/ADR-0020，SDK本身尚非预算工具。

85c0b89的[CI 44861323394](https://github.com/jkwang1129/SupportFlow/actions/runs/44861323394)两个job全部成功。当前知识Registry工具已接来源/签名SDK/共享MODEL预算，模型仅传query/top_k/doc_ids；每个Run固定Profile/策略，每次来源重验/知识重读，向量可原Checkpoint复用，空范围0模型/知识请求。工具26+新截止9+原Embedding50共85项通过；AI普通771通过/246条件skip/1原xfail，24项拒答门禁保持；新13项PG随241项专项/原全部实际组合保持。7例真实Worker→Embedding TCP→知识SDK/Java/受限PG通过，恢复下线命中1→0且总4Tools/1Model/1Retry。证据/设计/回滚见reliability-verification与ADR-0021。不是完整知识模型决策Loop或自动业务触发。

1dc48d6的[CI 44862361047](https://github.com/jkwang1129/SupportFlow/actions/runs/44862361047)两个job全部成功。当前先交付Customer009不可变建单回执存储，按tenant/run唯一并绑定同租户来源/工单，SI权限/强制RLS；六库95表，旧工单等字段不变。38项真实角色存储、迁移17项/漂移/初始化、三套完整实际服务和112项客服PG+19项KB通过。ADR-0022及reliability-verification记录设计/夹具失败/真实结果/回滚；只有存储不等于已完成Agent建单。

45151c6的[CI 44876389932](https://github.com/jkwang1129/SupportFlow/actions/runs/44876389932)两个job全部成功。当前新增操作专属许可：只允许reply/ticket两个固定操作版本配对，建单授权独立默认关闭，Java有效MAC跨操作也不能写回复；原回复签名字面向量保持。AI普通784通过/262条件skip/1原xfail、24项拒答门禁，新16项Runtime PG随257项/原全部实际组合、新1项客服PG随113项+19 KB、全Java构建和三套完整服务通过。ADR-0023与reliability-verification记录设计/证据/回滚，只有许可不等于业务建单。

610ad79的[CI 44878260746](https://github.com/jkwang1129/SupportFlow/actions/runs/44878260746)两个job全部成功，操作专属许可本地/远端退出条件完成。当前复用TicketService完成Java原子建单及只读回执：固定策略与来源客户/会话，实际结果核验，工单/事件/客户统计/回执同事务，旧渠道行为保留。新4项参数单元/16项PG通过，客服专项共129项与KB19项通过，全模块构建通过；8路同Run只生效一次，未作性能外推。ADR-0024与验收记录保留夹具失败及边界。原子业务子批次2e5cd53的[CI 44880321165](https://github.com/jkwang1129/SupportFlow/actions/runs/44880321165)两个job全部成功。当前新增独立ticket-create签名POST/GET：写开关默认关闭、GET复用read-tools，授权RPC在事务外，匹配回执先查、实际创建同事务再核。新7项权限单元（连旧共34）/10项PG（共139客服+19 KB）与全模块构建通过；3例实际JAR→AI许可/受限PG确认1张工单、终态确认、丢弃确认后GET、来源接管0副作用。保留UNKNOWN不冒充核销，ADR-0025/验收记录保留边界/失败/回滚。6799b6c的[CI 44881610275](https://github.com/jkwang1129/SupportFlow/actions/runs/44881610275)两个job全部成功，受控API本地/远端退出条件完成。

当前建单SDK具备精确原文摘要/固定签名路径、冻结回执/绑定与int64核验、只读回执查询、单次HTTP总期限且无隐式POST重试。新97项SDK单元（含3例真实TCP，连旧共316）/Java4项共享向量通过；AI普通881通过/262条件skip/1原xfail、RAG24项通过；257项Runtime及原全部实际组合保持，产品SDK首个建单POST/3例实际JAR→AI→受限PG通过，UNKNOWN保留。ADR-0026/验收记录保留夹具失败/边界/回滚。8f65bf2的[CI 44882505302](https://github.com/jkwang1129/SupportFlow/actions/runs/44882505302)两个job全部成功，建单SDK本地/远端退出条件完成。

当前ADR-0027将固定create_ticket接入既有Registry/Executor，Scope/闭合参数/来源重验、原共享预算、操作意图与引用结果、单次不确定写GET确认均实现；ReadTool旧路径保持。每Run每操作冻结参数/服务策略，换call key不能改正文，成功写恢复只GET。新42项工具单元（连原63共105）、AI普通923通过/281条件skip/1原xfail、RAG24项通过。Runtime首轮275过/1夹具协议错误，修正后新增19项全部通过、旧257首轮通过，精确CI仍全跑276；10例实际Worker→WRITE工具/SDK→Java→AI许可/受限PG/TCP故障通过。正常3Steps/2Tools、丢确认4Steps/3Tools且仅1POST、重启只GET/1Retry，缺回执/预算不足/取消保持UNKNOWN与后续任务阻断。ADR-0027/验收记录保留分轮证据、边界、风险与回滚。模型WRITE决策Loop、可信触发/Profile Resolver、HITL/工单UNKNOWN终态核销尚未完成；不能把本批宿主选工具说成模型自主规划建单。知识用户级ACL/版本与在途输出最终核验仍未全部完成；可信登记路由/Profile Resolver/HITL、上下文/Eval/Trace/Token金额/运维继续按B～E依赖推进。无生产规模、付费模型质量/Token成本或业务收益成果。


b703d4f 的 [CI 44883937143](https://github.com/jkwang1129/SupportFlow/actions/runs/44883937143) 前后端任务全部成功，包括 276 项 Runtime。B3 已增加 AI005 工单核销审计账本，强制租户隔离、同租户步骤外键及不可变权限。25 项真实 PG 新用例、17 项迁移测试、96 表结构和三套完整服务均通过。004 → 005 保留原任务、步骤、正文和回复核销记录。工单终态核销入口按 ADR-0029 交付，HITL 审批仍待后续接入。


工单终态核销已通过本地验收。可信宿主读取匹配的历史回执后，持久化审计、解除同会话任务阻断，保留失败状态、取消标记、历史完成时间及预算。65 项定向数据库用例和真实服务 10 例通过，其中两例各用一次 GET 确认既成工单，缺回执仍阻断。下一阶段实施 ADR-0030 的限时审批，先接 AI006 等待状态和审批数据，再接恢复、审批消费及权限。

AI006 已增加 WAITING_APPROVAL 与持久化审批记录。18 项审批存储、17 项迁移、97 表契约和三套完整服务通过。下一批接暂停、决定和按原检查点恢复，随后将审批消费、当前来源核权和业务写许可绑定。验收详见 ADR-0030。

审批暂停、决定与原检查点恢复已通过本地验收。定向 32 项数据库用例通过，其中 16 项新增审批生命周期。真实 Java/AI/PG 拒绝已通过但未消费的写权限，业务工单和回执均为零。下一批把审批一次性消费与写步骤预留放入同一事务，再验真正执行及过期后的行为。

审批一次性消费与正式工单执行通过本地验收。真实服务三案显示获批产生一张工单、一条事件和一条回执，批准后来源撤权或过期均零写入。数据库验证步骤、预算和消费三者原子提交。下一批接现有 Worker 到期发现，随后接可信坐席审查入口。

AI007 将未消费的过期审批加入既有 Worker 发现及领取。77 项审批与 Worker 定向 PostgreSQL 测试、17 项迁移和 97 表结构通过。已暂停任务和审计在升级前后逐字段一致。坐席审查按 ADR-0032 分别接 AI 内部协议和原客服权限。

独立默认关闭的内部审批审查协议通过 15 项新单元、5 项实际 PostgreSQL 用例和三条真实服务审批路径。坐席审查从签名服务收到来源、审批及参数摘要并在锁后重验。下一批接 Java 坐席权限和状态兼容。

1e278f4 的 [CI 44892566490](https://github.com/jkwang1129/SupportFlow/actions/runs/44892566490) 全部通过。客服侧已接登录态、当前管理员或主管角色、短事务核验来源和签名审批决定。受限数据库测试 145 项通过，另有 19 项知识库测试。待审 Run 的重复投递可以确认。坐席需要通过原提案预览取得审批 ID 和摘要，再提交决定。

AI 内部签名预览已通过测试。它绑定租户、来源、Run、原检查点和有效期。59 项审批数据库用例以及 945 项普通 AI 测试通过。客服侧将复核当前来源及提案摘要后向已授权坐席返回正文。

客服预览与决定已在真实 Java/AI/受限 PostgreSQL 三条流程中连通，受限客服数据库测试共 148 项通过。已有可信事件和 Run 的坐席可调用接口查看原提案并决定。待审任务发现、坐席界面和业务入口仍待交付。

运营侧可按指定租户和现有供应商配置启动只读工单 Worker。真实独立进程验证配置关闭时保留任务，开启后只消费指定租户并生成一份内部报告。已通过 82 项定向测试、44 项 Runtime 数据库用例和 10 条实际工单读取路径。下一批接客服端受控事件调度，随后核对访客消息登记的事务及故障边界。

客服已验证显式单租户调度，默认关闭。只读 Worker 与客服调度分别限定同一运营侧租户。下一批从已可信的访客文本入口登记 Outbox，确保重发、回滚、已接管会话和队列不可用时行为符合原消息契约。

显式试点已用真实签名消息 API 验证从访客发送、Outbox、自动投递到独立 Worker 报告。重复消息复用同一事件与任务，共 153 项客服数据库测试通过。原客服闭环继续运行，影子链路目前只生成内部报告。接回 B3 的坐席会话审批发现，并继续上下文、工具与评测工程。

AI 已增加签名会话审批发现。返回有效审批的事件和任务引用，不读取提案正文。13 项定向数据库测试和 959 项普通 AI 测试通过。下一批客服侧核对当前坐席角色、同会话来源与快照后提供查询。

客服会话审批发现、原提案预览和审查决定已组合通过真实 Java、AI 和受限 PostgreSQL 的三条路径。受限客服数据库测试共 156 项通过。接下来将已鉴权接口接入当前会话的坐席操作。
