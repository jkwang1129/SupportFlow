# SupportFlow 系统架构与核心链路

本文描述仓库当前真实落地的系统架构、服务边界、多租户安全模型与源码模块映射。环境准备与启动命令见 [本地运行指南](local-development.md)，各模块实现完成度与待收口边界见 [Agent 工程能力矩阵](agent-engineering-plan.md)。

---

## 1. 微服务与存储拓扑

系统采用 **Java 业务确定性控制面 + Python AI 编排与推理面** 分离架构：Java 服务排他持有业务事实、RBAC/资源权限、数据库事务与外部通知；Python 服务负责意图识别、知识检索、有界 Agent 决策循环与工具调用提议，禁止绕过受控业务 API 直接修改核心业务表。

| 组件 | 端口 | 核心职责 | 对应数据库 / 存储 |
| --- | --- | --- | --- |
| `supportflow-gateway` | `9090` | 外部 HTTP 统一入口、JWT 解析、租户守卫、内部 `/api/ai/**` 路径访问分级。 | 无状态（独立计量走网关统计链路） |
| `supportflow-realtime-service` | `9096` | 访客与坐席 WebSocket 长连接、消息 `seq`/ACK、断线增量补拉、跨节点工单确认推送。 | 无本地库（签名调用客服服务） |
| `supportflow-user-service` | `9091` | 平台与企业成员登录认证、密码哈希与角色权限。 | `user_db`（运行角色 `supportflow_user`） |
| `supportflow-tenant-service` | `9092` | 企业入驻申请与证照核验、租户配置、套餐额度与结构升级守卫。 | `tenant_db`（运行角色 `supportflow_tenant`） |
| `supportflow-customer-service` | `9093` | 会话与原子认领、客户 360、工单与 SLA、知识库文档生命周期、AI 事务 `Outbox`、短期写许可与不可变业务回执。 | `customer_db` + `RustFS`（业务角色 `supportflow_customer`、平台限定角色 `supportflow_customer_platform`） |
| `supportflow-billing-service` | `9094` | 认证用量批次幂等账本与租户日维度会话/消息/AI 调用统计。 | `billing_db`（运行角色 `supportflow_billing`） |
| `supportflow-ops-service` | `9095` | 平台运营面板、AI 月度预算闸门判定与跨服务指标读取。 | `ops_db`（运行角色 `supportflow_ops`） |
| `ai-service` (FastAPI) | `9100` | 客服大脑 Workflow、Agentic RAG 2.0、持久化 Agent Runtime、受控工具执行器、模型网关与用量预留。 | `ai_db`（`supportflow_ai`）+ 只读/切片受限访问 `customer_db`（`supportflow_kb`） |

---

## 2. 双模式 AI 执行链路

### 2.1 确定性客服大脑 + Agentic RAG 2.0（常规问答主链路）
1. **入站与并发保障**：访客消息经 `SessionService#appendMessage` 获取会话行锁并基于唯一索引幂等落库（消除并发重发 `25P02` 事务中止），每会话轮次通过引用计数锁 `SessionSerialExecutor` 保证进程内严格串行。
2. **上下文构建（Context Engineering）**：历史消息按当前有效客户回合锚定截断，支持可选字节预算（`2 KiB` / `4 KiB`）裁剪早期历史，保留结构化意图槽位并支持当前轮明确订单号确定性覆盖。
3. **Agentic RAG 编排**：执行 `retrieve -> grade -> rewrite（最多 1 次）-> answer / abstain`。
   - **可见性与一致性**：访客问答强制限定当前租户“已发布且索引完成”的文档，返回前二次复核发布状态与原文切片（`ADR-0042`）；知识重索引通过 `kb_chunk_stage` 暂存表生成并在 Java 文档写锁事务内原子切换，支持上一版索引按版本 UUID 一键回滚（`ADR-0082`～`0085`）。
   - **事实支撑与拒答**：改写耗尽仍低于阈值时确定性拒答（零生成调用）；生成结果剔除越界引用，可选启用结构化答复逐句原文核查、同口径数值冲突拦截及政策人工核验建议（`ADR-0055`、`0081`）。

### 2.2 持久化有界 Agent Runtime + HITL 工单闭环（复杂任务受控链路）
针对需要跨步业务读取与受控副作用的任务，`ai-service/ai/runtime/` 提供独立的持久化执行引擎（当前默认按租户显式开启，正在推进与主对话路由的统一 `Intent Router` 合流）：
1. **可靠投递与多 Worker 协调**：Java 在消息事务内写入 `agent_event_outbox` 并带 `fence` 投递至签名接收端；Python Worker 基于数据库时钟领取租约、定期心跳、超时自动接管，同会话任务严格保序。
2. **原子步骤检查点与预算**：每次模型推理（`MODEL`）、只读工具（`READ`）或写操作（`WRITE`）前原子预留步骤并扣减 `max_steps / max_model_calls / max_tool_calls / wall_seconds` 预算；已完成只读/模型步骤的正文检查点在进程崩溃重启后直接复用，不重复扣费或重跑。
3. **受控工具层与 MCP 接入**：`ToolRegistry` / `ToolExecutor` 采用闭合 Pydantic Schema，禁止模型传入租户/身份/URL 等特权字段；每次执行重新向 Java 核验来源会话快照与资源归属（已接入来源消息读取、知识检索、工单状态查询、只读 MCP 客户端及幂等创建工单）。
4. **限时人工审批（HITL）与进度续传**：写操作提案进入 `WAITING_APPROVAL` 持久化暂停状态，绑定参数 SHA-256 摘要、来源快照与过期时间；主管/管理员在工作台预览并审批，审批通过后一次性原子消费许可；工作台支持按事件游标自动补拉执行进度、整页重载恢复及任务取消竞争处理。
5. **未知副作用阻断与回执核销**：若写请求响应丢失或进程中断，步骤标记为 `SIDE_EFFECT_UNKNOWN` 并阻断同会话后续任务，严禁盲目重发 POST；恢复或终态核销仅通过只读查询 Java 不可变回执表（`agent_result_receipt` / `ticket_create_receipt`）确认真实提交结果。

---

## 3. 多租户安全与数据库治理

- **权限最小化**：建库管理员 `supportflow` 仅用于初始化与版本化迁移；5 个 Java 数据服务、AI Runtime、知识检索与平台审核分别使用 **8 个独立 `NOSUPERUSER NOBYPASSRLS` 角色**，客服与 AI 启动时强制校验连接身份，拒绝超级用户或表 Owner 直接运行业务。
- **Fail-closed RLS**：租户表同时启用 `ENABLE / FORCE ROW LEVEL SECURITY` 与角色专属 `RESTRICTIVE` 守卫策略；未设置 `app.tenant_code` 或普通业务角色伪造 `PLATFORM` 时一律拒绝读写。平台跨租户操作必须经独立角色先写入 `platform_access_audit` 审计对。
- **权威迁移与就绪门禁**：`scripts/db-migrate.py` 统一管理六库 **106 张表**的初始化、增量迁移（会话级 Advisory Lock + SHA-256 校验和）与全量结构/索引/RLS 漂移检查；Java 与 Python 均分离存活（Liveness）与只读结构/对象存储就绪（Readiness）探针，缺表、缺权限或 RustFS 故障时返回 `503` 而不泄漏敏感信息。

---

## 4. 观测、评测与成本治理（LLMOps）

- **跨服务追踪与安全回放**：请求号从入站贯穿 Java 异步线程、Outbox 与 Python Worker；`trace_timeline.py` 支持按租户隔离联合查询 Java 投递、Python Run/Step 与审批时间线；`ticket_replay.py` 支持基于录制磁带的离线只读规划回放（篡改输入摘要立即触发门禁失败，绝不触发真实写操作）。
- **多层预算与配额预留**：Runtime 支持租户级模型并发配额、UTC 每日/月度 Token 原子预留与回执结算、基于版本化价目表的日/月金额预留；旧客服链路接入 Ops 月度预算闸门，无法确认额度时安全降级。检查点正文到期清理后，脱敏审计轨迹与独立 Token 用量回执仍完整保留。

---

## 5. 核心源码导览

| 领域 | Python (`ai-service/ai/`) | Java (`backend/`) |
| --- | --- | --- |
| 客服大脑与上下文 | `agents/brain.py`、`services/brain.py` | `customer/.../service/bot/BotBrainService.java`、`SessionSerialExecutor.java` |
| Agentic RAG 与知识库 | `rag/graph.py`、`rag/grounded_reply.py`、`rag/store.py`、`rag/stage_retention.py` | `customer/.../service/bot/KbService.java`、`mapper/KbChunkMapper.xml` |
| Runtime 内核与状态机 | `runtime/types.py`、`runtime/store.py`、`runtime/worker.py`、`runtime/payloads.py` | `customer/.../service/bot/AgentEventOutbox.java`（及投递调度器） |
| 工具执行、MCP 与建单 | `runtime/tools.py`、`runtime/knowledge_tools.py`、`runtime/tickets.py`、`runtime/mcp_ticket.py`、`runtime/ticket_write_tool.py` | `customer/.../security/Runtime*Command.java`、`service/ticket/TicketService.java` |
| HITL 审批与实时进度 | `runtime/approvals.py`、`api/runtime.py` | `customer/.../controller/session/`、前端 `TicketApprovalReview.vue` / `TicketApprovalTimeline.vue` |
| 模型网关、配额与追踪 | `core/chat_transport.py`、`core/llm_chat.py`、`runtime/models.py`、`runtime/model_usage.py`、`runtime/trace_timeline.py` | `ops/.../service/ObservabilityService.java`、`customer/.../internal/CostGuardClient.java` |
