# 架构决策记录索引（ADR 0001–0086）

本目录记录 SupportFlow 演进为**多租户 SaaS 智能客服 Agent 平台**过程中的全部关键架构决策、替代方案取舍、上线顺序与回滚边界。为便于按工程模块查阅，以下将 86 份 ADR 归类为六大主题（编号保持原始提交时间顺序）。

---

## 一、多租户安全、权限边界与数据库治理（AI Security & DB Ops）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0002](0002-schema-readiness-and-migrations.md) | 只读 Readiness 与版本化迁移边界 | 分离运行期只读结构探测与管理员版本化 DDL 迁移、漂移检查 |
| [ADR-0004](0004-source-snapshot-boundary.md) | 业务来源快照与恢复时的资源校验 | 不可变来源会话快照与执行恢复时的实时归属复核 |
| [ADR-0005](0005-business-result-authority.md) | 结果写入的执行权、短期许可与幂等 | Python 执行租约校验与短期业务写许可签发 |
| [ADR-0023](0023-operation-bound-action-authority.md) | 操作专属的短期写许可 | 严格隔离 `reply` 与 `ticket` 写许可，防止跨操作越权复用 |
| [ADR-0039](0039-disposable-postgres-restore.md) | 在独立 PostgreSQL 集群演练六库恢复 | 六库备份还原、结构契约与受限角色权限核验 |
| [ADR-0056](0056-agent-input-permission-boundaries.md) | Agent 输入与权限边界回归 | 可信系统规则与不可信数据分离及 10 项数据库越权回归门禁 |
| [ADR-0075](0075-attachment-and-database-restore.md) | 六库与附件联合恢复演练 | PostgreSQL 六库与 RustFS 对象存储跨介质一致性恢复 |
| [ADR-0076](0076-restored-service-acceptance.md) | 恢复后的应用验收 | 在恢复目标上挂载真实客服与 AI 服务验证业务读取与权限拒绝 |
| [ADR-0077](0077-attachment-readiness.md) | 附件就绪与对象存储故障 | 附件独立就绪探针，存储故障时纯文本会话不中断 |
| [ADR-0079](0079-expired-checkpoint-retention.md) | 过期 Agent 检查点清理 | 独立维护身份按租户限批清理终态过期正文，保留审计与用量回执 |

---

## 二、持久化 Agent Runtime 内核与任务交互（Agent Runtime & Streaming）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0003](0003-durable-runtime-boundary.md) | 持久化 Runtime 的状态、租约与业务边界 | `agent_run`/`agent_step` 状态机、fenced 租约、心跳接管与 Java Outbox |
| [ADR-0006](0006-durable-step-payloads.md) | 步骤正文与完成状态原子持久化 | 步骤结果正文原子保存与跨进程重启恢复 |
| [ADR-0013](0013-verified-report-publication.md) | 核验报告接已有原子业务回复 | 宿主核验事实报告并原子提交回复消息与业务回执 |
| [ADR-0014](0014-terminal-reply-reconciliation.md) | 终态未知回复的受控提交核销 | 基于不可变回执核销响应丢失导致的 `SIDE_EFFECT_UNKNOWN` |
| [ADR-0028](0028-ticket-reconciliation-ledger.md) | 工单未知写核销的不可变审计存储 | 建单未知状态核销的强制 RLS 只追加审计账本 |
| [ADR-0029](0029-terminal-ticket-reconciliation.md) | 工单未知写的终态核销 | 只读回执确认后解除同会话后续任务阻断 |
| [ADR-0033](0033-opt-in-ticket-worker.md) | 单租户只读工单 Worker | 按租户显式开启的受控后台 Worker 宿主 |
| [ADR-0034](0034-shadow-visitor-admission.md) | 访客文本的只读任务登记 | 签名访客消息同事务登记 Outbox 与自动投递 |
| [ADR-0041](0041-authenticated-runtime-progress.md) | 通过已有审批权限读取任务进度 | 向已授权审核坐席暴露脱敏运行轮次与步骤进度 |
| [ADR-0060](0060-workspace-approval-progress.md) | 坐席工作台的工单执行进度 | 工作台审批提交、重开与切换会话时的状态一致性 |
| [ADR-0061](0061-visitor-message-catchup.md) | 通过访客长连接补拉会话消息 | 访客 WebSocket 在线漏帧与断线重连按序号分页补拉 |
| [ADR-0064](0064-session-terminal-approval-progress.md) | 整页刷新后按会话恢复工单任务进度 | 浏览器整页重载后重新核权定位最近已审批终态 |
| [ADR-0065](0065-consumed-approval-progress.md) | 整页刷新后恢复已消费审批的执行进度 | 扩展重载恢复覆盖 `RUNNING` 与 `RETRY_WAIT` 阶段 |
| [ADR-0066](0066-approval-run-event-cursor.md) | 工单任务事件与序号补拉 | `agent_run_event` 同事务落库与按游标分页续传 |
| [ADR-0067](0067-workspace-approval-event-recovery.md) | 坐席工作台自动补拉任务事件 | 前端自动轮询续传、离线暂停与切换取消 |
| [ADR-0068](0068-staff-ticket-task-cancellation.md) | 坐席取消已批准的工单任务 | 行锁处理取消与完成竞争，如实区分未执行与写入待核查 |
| [ADR-0069](0069-multi-node-ticket-notices.md) | 多节点工单通知 | 向多实例实时节点分别推送签名通知，单节点故障不阻塞并支持补拉 |

---

## 三、受控工具调用、MCP 与人工审批闭环（Tool Calling & HITL）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0007](0007-controlled-read-tools.md) | 受控只读工具与当前权限核验 | 闭合 Schema `ToolRegistry` / `ToolExecutor` 与实时授权重验 |
| [ADR-0008](0008-runtime-ticket-read.md) | Runtime 工单最小状态读取 | 窄字段工单状态查询契约与归属校验 |
| [ADR-0009](0009-versioned-read-cache.md) | 可变只读结果按当前授权版本复用 | 基于当前版本指纹刷新或复用只读工具结果 |
| [ADR-0012](0012-bounded-ticket-read-loop.md) | 最小有界模型—工单只读工具循环 | 有界轮次工单规划循环与报告前二次重读证明 |
| [ADR-0019](0019-controlled-knowledge-query.md) | 业务端受控知识查询协议 | Java 窄接口同事务核验来源、发布状态与模型标签 |
| [ADR-0020](0020-knowledge-client-contract.md) | 知识查询签名 SDK 与结果核验 | Python 知识客户端严格响应摘要与投影校验 |
| [ADR-0021](0021-live-knowledge-tool.md) | 每次重新授权的知识工具与共享预算 | `search_knowledge` 接入工具执行器与共享 Embedding 预算 |
| [ADR-0022](0022-ticket-creation-receipt-storage.md) | 建单业务回执的持久化边界 | `ticket_create_receipt` 按 `(tenant, run)` 唯一绑定不可变回执 |
| [ADR-0024](0024-atomic-agent-ticket-creation.md) | 复用工单业务的原子 Agent 建单 | 工单、事件、客户统计与建单回执同事务提交 |
| [ADR-0025](0025-controlled-ticket-creation-api.md) | 受控建单 API 与提交事实查询 | 签名建单 `POST` 与回执确认 `GET` 分离，事务外核验写许可 |
| [ADR-0026](0026-ticket-creation-client.md) | 建单签名 SDK 与严格回执验证 | 单次 HTTP 期限、禁止隐式 `POST` 重试与严格回执绑定校验 |
| [ADR-0027](0027-bounded-ticket-write-tool.md) | 在既有执行器中加入有界建单工具 | `create_ticket` 写前冻结意图，丢失响应后仅通过 `GET` 恢复 |
| [ADR-0030](0030-durable-approval-boundary.md) | 限时审批与运行恢复边界 | `WAITING_APPROVAL` 持久化审批记录与原检查点恢复 |
| [ADR-0031](0031-approval-consumption-and-expiry.md) | 审批消费与过期发现 | 审批一次性消费与写步骤预留同事务提交，到期自动扫描 |
| [ADR-0032](0032-ticket-approval-review-boundary.md) | 坐席审查授权边界 | 主管/管理员角色校验、原提案预览与签名审批提交 |
| [ADR-0036](0036-bounded-ticket-mcp.md) | 受控工单 MCP 调用 | 可选只读工单 MCP 客户端适配，复用全部权限与预算约束 |
| [ADR-0038](0038-explicit-ticket-proposal.md) | 由访客明确请求生成待审工单 | 打通访客请求、提案暂停、坐席批准与独立 Worker 建单闭环 |
| [ADR-0059](0059-signed-ticket-journey.md) | 从签名访客入口验收工单闭环 | 同会话先查历史工单再审批建单及 4 类异常零副作用联调 |
| [ADR-0062](0062-approved-ticket-visitor-notice.md) | 获批工单的访客确认消息 | 获批建单确认消息与工单、回执同事务入库 |
| [ADR-0063](0063-online-approved-ticket-notice.md) | 在线推送已提交的工单确认 | 事务提交后向在线访客推送工单通知，失败可走消息补拉 |

---

## 四、上下文工程与 Agentic RAG 2.0（Context & RAG Engineering）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0015](0015-rag-document-scope.md) | 检索降级保留文档范围 | 向量降级关键词检索时保持 `doc_ids` 约束，空列表零检索 |
| [ADR-0016](0016-rag-diagnostic-boundary.md) | RAG 应用诊断与错误响应边界 | 应用日志与错误响应脱敏，移除文档正文与上游原始异常 |
| [ADR-0017](0017-visitor-knowledge-visibility.md) | 访客知识查询仅使用已发布且索引完成的文档 | 严格隔离草稿、下线与索引失败文档 |
| [ADR-0035](0035-canonical-planner-history.md) | 工单规划上下文的结构化历史 | 压缩历史轮次工具调用文本（样本从 `4,907B` 降至 `120B`） |
| [ADR-0040](0040-rag-extractive-claims.md) | RAG 逐句引用原文模式 | 可选严格原文复制校验，量化拒答收益与转述损失 |
| [ADR-0042](0042-rag-publication-recheck.md) | 在返回访客问答前复核知识来源 | 防御生成期间文档下线、删除或切片替换导致的陈旧回复 |
| [ADR-0043](0043-bounded-brain-analysis-context.md) | 为客服意图分析设置上下文预算 | 按字节预算裁剪历史消息，保留当前提问与已有意图槽位 |
| [ADR-0054](0054-brain-context-qualification.md) | 客服意图上下文预算对照 | 在 `qwen-plus` 上实测原版、`2 KiB`、`4 KiB` 策略质量与 Token 降幅 |
| [ADR-0055](0055-grounded-visitor-rag-replies.md) | 访客问答的结构化答复与原文核查 | 逐条主张绑定原文连续句子切片，消除幻觉与猜测 |
| [ADR-0080](0080-rag-policy-holdout.md) | RAG 政策冲突与证据不足留出集 | 独立冻结 16 篇资料、12 道冲突/人工核验/缺证据评测题 |
| [ADR-0081](0081-rag-policy-review-routing.md) | 访客 RAG 的政策冲突与人工核验 | 同口径数值冲突零生成拦截，原文明确人工核验要求路由转人工 |
| [ADR-0082](0082-kb-document-write-serialization.md) | 知识文档写入按文档串行 | 文档级写锁串行化编辑、重索引、发布与同事务软删清切片 |
| [ADR-0083](0083-kb-index-staging.md) | 知识切片暂存与原子切换 | `kb_chunk_stage` 隔离生成切片，Java 文档事务内原子激活 |
| [ADR-0084](0084-kb-stage-retention.md) | 闲置租户知识索引暂存清理 | `timestamptz` 到期判定与独立受限角色限批清理孤儿暂存行 |
| [ADR-0085](0085-kb-previous-index-rollback.md) | 保留上一版知识索引并支持一次回滚 | 保存替换前正文与向量快照，按版本 UUID 免重算回滚 |
| [ADR-0086](0086-rag-retrieval-comparison.md) | 知识检索排序对照 | 在 37 题冻结集上实测关键词、稠密向量与 RRF 混合检索指标与延迟 |

---

## 五、评测闭环、执行追踪与回放（Evaluation & Observability）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0051](0051-fixed-rag-quality-dataset.md) | RAG 对照评测的固定样本和标注 | 冻结 25 题主评测集、SHA-256 指纹与评分口径 |
| [ADR-0052](0052-paired-rag-benchmark.md) | 单次检索与 Agentic RAG 的配对评测 | 隔离 PG 确定性配对运行与 CI 回归门禁 |
| [ADR-0053](0053-live-rag-evaluation.md) | 真实模型 RAG 配对评测与语义复核 | `qwen-plus` 实测 Token 回执记录与逐题事实支撑复核 |
| [ADR-0057](0057-cross-service-trace-timeline.md) | Java 投递与 Python 执行时间线 | 按事件、Run 或原请求号跨库只读关联完整执行轨迹 |
| [ADR-0058](0058-controlled-ticket-planner-replay.md) | 工单只读规划的受控回放 | 复用线上规划逻辑重演录制会话，篡改输入摘要自动阻断 |
| [ADR-0078](0078-runtime-queue-metrics.md) | Runtime 积压与延迟快照 | 按租户统计积压、过期租约、首次领取与终态分位延迟告警 |

---

## 六、模型网关、Prompt 版本与成本治理（LLMOps & Cost Governance）

| 编号 | 决策主题 | 核心关注点 |
| --- | --- | --- |
| [ADR-0001](0001-usage-batch-delivery.md) | 客服计费批次与可靠重报 | 持久化 `BillingUsageOutbox` 与消费端批次账本同事务去重 |
| [ADR-0010](0010-bounded-chat-transport.md) | 复用现有对话入口的单次受限传输 | 显式超时、响应字节上限、禁止重定向与稳定故障码分类 |
| [ADR-0011](0011-runtime-model-profile.md) | 显式模型 Profile 与持久化 MODEL 步骤 | 租户绑定模型配置、MODEL 检查点与瞬态错误受控重试 |
| [ADR-0018](0018-runtime-query-embedding.md) | Runtime 单次查询 Embedding 的预算与恢复 | 查询向量化纳入共享 MODEL 预算与原子向量检查点 |
| [ADR-0037](0037-runtime-observed-model-usage.md) | Runtime 已观测模型用量 | 按租户汇总单次 Run 的 Chat/Embedding 实测 Token 与未知次数 |
| [ADR-0044](0044-durable-model-usage-receipts.md) | Runtime 模型用量回执 | 独立持久化 `agent_model_usage` 回执，不受检查点清理影响 |
| [ADR-0045](0045-versioned-runtime-cost-estimates.md) | 按显式价目表估算 Runtime 用量 | 绑定价目快照版本与摘要计算任务费用，区分缺费率与缺用量 |
| [ADR-0046](0046-tenant-model-concurrency.md) | 租户模型并发配额 | 数据库原子模型并发槽位预留，防单租户耗尽并发 |
| [ADR-0047](0047-tenant-daily-token-budget.md) | 租户每日模型 Token 预留与结算 | UTC 日维度调用前预留 Token 上限、完成后按实测回执结算 |
| [ADR-0048](0048-model-token-day-accounting.md) | 租户模型 Token 日汇总 | 维护 `agent_model_token_day` 日汇总账目并与明细同事务核对 |
| [ADR-0049](0049-tenant-monthly-model-token-quota.md) | 租户日度和月度模型 Token 额度 | 基于最多 31 天日汇总实现高效月度 Token 配额准入拦截 |
| [ADR-0050](0050-tenant-model-cost-reservations.md) | 租户模型金额预留与结算 | 调用前按绑定费率预留日/月估算金额并原子结算 |
| [ADR-0070](0070-legacy-ai-cost-guard.md) | 旧客服 AI 月预算闸门 | Ops 月度费用读取异常或超限时安全跳过模型或保持暂停 |
| [ADR-0071](0071-bounded-vision-model-transport.md) | 视觉模型有界传输 | 视觉模型接入共用有界传输、图片体积上限与租户用量归属 |
| [ADR-0072](0072-bounded-legacy-embedding.md) | 旧知识库向量化使用共用模型传输 | 批量向量顺序校验、统一本地降级与同文档原子切片替换 |
| [ADR-0073](0073-ticket-planner-prompt-release.md) | 工单 Runtime Prompt 版本发布与回退 | 显式 Prompt 版本注册表、按租户试用绑定、任务级版本持久化与回放 |
| [ADR-0074](0074-legacy-chat-provider-routing.md) | 旧客服 Chat 的租户供应商选择 | 意图、RAG 与质检严格遵循租户所选供应商，故障时不跨供应商静默漂移 |
