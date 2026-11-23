# ADR-0057 Java 投递与 Python 执行时间线

新增运维命令 `ai.runtime.trace_timeline`，把 Java 的 `agent_event_outbox` 和 Python 的 Run、Step、审批记录读成一份版本化 JSON。记录的请求号来自访客消息入库时的关联号。源事件、Run 和投递回执的关系由两库的实际记录确认。[D7 原请求号查询](../reliability-verification.md#d7-通过原请求号定位-runtime-任务2026-11-22)

## 读取和关联规则

- 命令用显式的客服库和 AI 库连接，只读事务、活动行级安全和事务级租户绑定。连接从运维会话的 `SUPPORTFLOW_TRACE_CUSTOMER_DB_DSN` 和 `SUPPORTFLOW_TRACE_AI_DB_DSN` 读取。需要使用已有受限角色或同等读取授权。租户参数是有权限的运维操作者选择的过滤条件。
- Event 和 Run 按租户、源事件、消息、事件序号、请求号、Runtime 版本核对，已确认的投递回执还必须指向同一个 Run。匹配不一致时失败。内部核对所需的消息 ID、事件序号、主体和会话信息不进入导出。步骤仅包含顺序、操作及版本、状态、稳定错误码和时间，审批仅包含状态、时间以及已消费步骤编号。
- `--event` 在 Python 接收前也能定位已知的 Java 事件。`--run` 定位具体任务。`--trace-id` 复用现有租户内的精确索引搜索，最多关联最新 20 个已接收任务，并标记截断。尚没有 Run 的事件用事件 UUID 定位。
- 分别观察 `outbox_only`、`awaiting_receipt`、`matched`、`runtime_only` 和 `receipt_without_runtime`。两库是前后读取的独立快照。在途任务可重新查询。单库的 Run、步骤和审批使用同一只读可重复读快照。既有 `trace_export` 输出继续使用原版本。

## 时钟与范围

按数据库记录保留 Outbox 创建、接收、回执、Run 结束、步骤完成与审批时间。耗时只计算同一数据库的已闭合区间，缺失或逆序时间留空。Run 总时间包含排队、等待审批和重试。步骤编号给出逻辑执行顺序。Java 入口的真实 HTTP 关联由联调核对 header，入库前的网络时间与未持久化的内部 Span 需要另行采集。

## 复现与回退

使用已获授权的数据库连接，在 `ai-service` 目录运行。参数也支持 `--event` 或 `--run`，每次选择一种。

```powershell
python -m ai.runtime.trace_timeline --tenant TENANT_A --trace-id REQUEST_ID
```

`python scripts/test-agent-runtime.py --with-java-delivery` 在临时 PostgreSQL、实际 Java 客服服务和 Python Worker 上验收。`runtime-cross-service-trace.json` 随 CI 测试报告归档。模型响应由本地协议替身提供。

回退 CLI 与验收改动即可恢复原查询方式，现有事件和任务无需迁移。停用新命令即可停止导出联合时间线。
