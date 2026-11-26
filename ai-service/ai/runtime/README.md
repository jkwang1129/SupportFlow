# Python Agent Runtime 模块导览 (`ai.runtime`)

`ai-service/ai/runtime/` 实现独立于固定客服状态图之外的**多租户持久化有界 Agent 执行引擎**。模块按职责分为五层：

## 1. 执行内核与状态存储层（Execution Kernel）
- `types.py` — 核心值对象（`RunInput`、`Limits`、`Lease`）、标识符/指纹校验与运行时异常体系
- `store.py` — PostgreSQL 持久化存储（`agent_run` / `agent_step` 准入、会话顺序、fenced 租约领取、心跳与配额事务）
- `payloads.py` — 步骤检查点正文的版本化编码、体积限制与 Schema 校验
- `payload_retention.py` — 终态过期检查点正文按租户限批预览与清理 CLI
- `worker.py` — 单槽持久化 Worker（步骤复用、模型/只读重试、未知写 `SIDE_EFFECT_UNKNOWN` 阻断与心跳守护）

## 2. 受控工具引擎与协议接入层（Controlled Tooling & MCP）
- `tools.py` — 闭合 Pydantic Schema `ToolRegistry` 与 `ToolExecutor`（禁止租户/身份参数覆盖、实时授权复核、超时控制）
- `source.py` / `customer_tools.py` — 业务来源会话快照签名读取与 `get_current_message` 只读工具
- `knowledge.py` / `knowledge_tools.py` — 客服窄知识检索签名客户端与 `search_knowledge` 工具
- `tickets.py` / `mcp_ticket.py` — 工单状态签名客户端及可选受控只读 MCP 客户端适配
- `ticket_creation.py` / `ticket_write_tool.py` — 幂等创建工单客户端（单次 `POST` + 只读回执 `GET` 恢复）与 `create_ticket` 写工具

## 3. 人工审批、业务发布与终态核销层（HITL & Reconciliation）
- `authority.py` — 操作专属（`reply` / `ticket`）短期业务写许可签发与校验
- `approvals.py` — `WAITING_APPROVAL` 持久化审批、参数摘要绑定、坐席预览/决定/取消及一次性审批消费
- `reply.py` / `result.py` / `publication.py` — 受控回复结果提交、回执校验与核验报告原子发布
- `reconciliation.py` — 基于不可变业务回执核销回复与工单未知写入状态

## 4. 工单规划循环与后台宿主（Bounded Agent Loop）
- `ticket_prompts.py` — 版本化规划器 Prompt 注册表与供应商兼容性声明
- `ticket_loop.py` — 有界工单决策循环、结构化规划历史压缩与待审建单提案暂停
- `ticket_worker.py` — 按租户启动的只读工单 Worker 与已批准工单执行 Worker 宿主

## 5. 可观测追踪、回放与成本计量层（Observability & LLMOps）
- `models.py` / `embeddings.py` — 绑定租户 Profile 的受控对话与查询向量化客户端（接入共享模型预算与检查点）
- `model_usage.py` / `usage_pricing.py` — 独立持久化模型 Token 回执汇总与版本化价目快照费用估算
- `trace_export.py` / `trace_timeline.py` — 单库轨迹导出与跨 Java Outbox / Python Run / 审批时间线联合查询
- `trace_replay.py` / `ticket_replay.py` — 运行轨迹离线契约校验与工单规划录制无副作用重演
- `metrics.py` — 租户任务积压、过期租约与首次领取/终态分位延迟快照及阈值告警 CLI
