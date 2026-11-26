# 历史批次推进归档（A0–E27）

> 本文归档自 `docs/agent-engineering-plan.md` 在 `A0` 至 `E27`（截至提交 `b9f46b1`）期间逐批追加的提交与 CI 记录，以便保持主计划文档清晰可读。详细测试数据、分母口径与报告哈希见 [可靠性改造验收记录](../reliability-verification.md) 及 [ADR 索引](../decisions/README.md)。

---

## B 阶段与 C/D 早期推进记录

- 阶段 A 已完成退出条件，B1/B2/B3 仍部分实现。现有默认客服流程继续运行。Runtime 已有预算、租约、Outbox、签名 API、正文检查点与进程恢复、有界 Loop 和报告发布。回复与建单支持按回执核销，缺回执继续阻断。
- `3d644cb`（[CI 44854145977](https://github.com/jkwang1129/SupportFlow/actions/runs/44854145977)）：文档范围/空选集、诊断内容排除和访客发布/索引就绪策略完成本地与远端验收（Customer 008 / AI 004）。
- `ad66e75`（[CI 44856556934](https://github.com/jkwang1129/SupportFlow/actions/runs/44856556934)）：Runtime 查询 Embedding 具备 MODEL 共享预算、原子向量 Checkpoint 与恢复（ADR-0018）。
- 客服窄 POST 知识 API（ADR-0019）及 `914767a`（[CI 44859565743](https://github.com/jkwang1129/SupportFlow/actions/runs/44859565743)，ADR-0020）：交付 `KnowledgeClient` 严格类型、来源、请求与投影摘要核验。
- `85c0b89`（[CI 44861323394](https://github.com/jkwang1129/SupportFlow/actions/runs/44861323394)，ADR-0021）：`search_knowledge` 注册入 `ToolRegistry` 并接入来源复核、签名 SDK 与共享 MODEL 预算。
- `1dc48d6`（[CI 44862361047](https://github.com/jkwang1129/SupportFlow/actions/runs/44862361047)，ADR-0022）：Customer 009 不可变建单回执存储落地。
- `45151c6`（[CI 44876389932](https://github.com/jkwang1129/SupportFlow/actions/runs/44876389932)，ADR-0023）：新增操作专属短期写许可（`reply` / `ticket` 严格隔离）。
- `610ad79`（[CI 44878260746](https://github.com/jkwang1129/SupportFlow/actions/runs/44878260746)）、`2e5cd53`（[CI 44880321165](https://github.com/jkwang1129/SupportFlow/actions/runs/44880321165)）、`6799b6c`（[CI 44881610275](https://github.com/jkwang1129/SupportFlow/actions/runs/44881610275)）、`8f65bf2`（[CI 44882505302](https://github.com/jkwang1129/SupportFlow/actions/runs/44882505302)）、`b703d4f`（[CI 44883937143](https://github.com/jkwang1129/SupportFlow/actions/runs/44883937143)）：依次完成 Java 原子 Agent 建单、受控 `POST/GET` API、Python 建单 SDK 及 `create_ticket` 写工具执行器（ADR-0024～0027）。
- **B3 核销与 HITL 审批（ADR-0028～0034、0038）**：交付 AI 005 工单核销审计账本与终态核销、AI 006 `WAITING_APPROVAL` 状态与审批表、一次性审批消费与写步骤原子预留、AI 007 过期审批发现、`1e278f4`（[CI 44892566490](https://github.com/jkwang1129/SupportFlow/actions/runs/44892566490)）坐席角色审查/预览/工作台接入、单租户只读工单 Worker、影子访客消息登记及明确 `/ticket-request` 提案闭环。
- **B2 MCP 客户端（ADR-0036）**：增加可选 MCP 工单只读客户端，复用全部权限、预算与结果核验。

---

## C 阶段：上下文工程与 RAG 改进（C1–C7）

- **C1（ADR-0035）**：工单规划历史结构化压缩（合成样本从 `4,907` 字节减至 `120` 字节）。
- **C2**：生成回答越界引用拒答（引用与拒答门禁共 34 项通过）。
- **C3（ADR-0040）**：默认关闭的 RAG 逐句原文复制核查模式。
- **C4（ADR-0042）**：访客问答返回前二次复核文档租户、发布/索引状态与原文切片。
- **C5（ADR-0043）**：客服意图分析可选消息字节预算（配对样本 `13,638` 字节降至 `1,632` 字节）。
- **C6**：客服大脑入口历史消息锚定至当前有效客户提问位置截断。
- **C7**：支持客户当前回合明确更正订单号并即时同步结构化记忆与持久化。

---

## D 阶段：评测、全链路追踪、回放与工作台交互（D1–D26）

- **D1–D3**：受限数据库导出单个 Runtime 轨迹、离线步骤与未知写门禁回放、固定工程故障矩阵门禁。
- **D4–D7**：Java 异步接待保留请求号、真实投递与独立 Worker 请求号贯穿、按租户和请求号检索定位 Run。
- **D8–D10（ADR-0051～0053）**：冻结 25 题 RAG 质量评测集、隔离 PG 配对基准、`qwen-plus` 33 次用量回执与 50 条逐题语义复核。
- **D11（ADR-0054）**：9 道固定题意图上下文预算实测（`4 KiB` 保留 `8/8` 意图与订单号，Token 减少 `14.63%`）。
- **D12（ADR-0055）**：访客结构化答复与原文核查对照（应拒答 `8/10 -> 10/10`，应答题正确 `9/13 -> 12/13`）。
- **D13（ADR-0056）**：分离系统规则与不可信数据字段，固定 10 项输入与权限边界 PostgreSQL 门禁。
- **D14（ADR-0057）**：跨服务关联查询 Java Outbox、Python Run/Step 与审批时间线（10 个关键状态点）。
- **D15（ADR-0058）**：工单只读规划录制与离线无副作用受控回放（4 个联调案例）。
- **D16（ADR-0059）**：签名访客入口同会话先查历史工单再审批建单及 4 类异常零写入验收。
- **D17–D26（ADR-0060～0069）**：工作台审批进度刷新与整页重载恢复、访客 Widget WebSocket 消息补拉、获批工单同事务确认消息与多实时节点在线推送、`agent_run_event` 序号游标自动补拉、坐席取消已批准任务竞争处理。

---

## E 阶段：模型网关、成本治理、容灾运维与知识生命周期（E1–E27）

- **E1–E4（ADR-0037、0039、0041）**：单 Run 已观测 Token 统计、独立 PG17 六库恢复演练、已授权审核员任务进度查询、双租户积压竞争回归。
- **E5–E11（ADR-0044～0050、0070）**：持久化模型 Token 回执、版本化价目快照费用估算、租户数据库模型并发配额、UTC 日度/月度 Token 预留与日汇总账本、日/月金额预留与结算、旧客服 AI 月预算降级闸门。
- **E12–E15（ADR-0071～0074）**：视觉模型与旧知识库向量化接入共用有界传输、工单只读 Worker Prompt 版本发布与回退、旧客服 Chat 严格按租户路由供应商。
- **E16–E20（ADR-0075～0079）**：六库与 RustFS 附件联合备份恢复、恢复目标挂载真实客服与 AI 验证读取与结构故障恢复、附件就绪探针与 RustFS 暂停演练、租户积压与分位延迟快照告警 CLI、AI 016 终态过期检查点限批清理。
- **E21–E27（ADR-0080～0086）**：冻结 12 题政策留出集、同口径数值冲突拦截与原文人工核验路由、知识文档写入按文档加锁串行化、`kb_chunk_stage` 暂存与原子切换、闲置租户过期暂存切片维护清理、上一版知识索引 UUID 一键回滚、37 题关键词/真实向量/RRF 混合检索指标与延迟实测对照。
