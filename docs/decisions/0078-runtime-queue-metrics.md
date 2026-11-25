# ADR-0078 Runtime 积压与延迟快照

运维命令 `python -m ai.runtime.metrics --tenant TENANT_A` 复用已有轨迹连接 `SUPPORTFLOW_TRACE_AI_DB_DSN`。每次只查看指定租户，在同一个只读、可重复读事务内使用 PostgreSQL 的事务开始时间。两张表均要求行级安全实际生效。输出只有租户标识、时间、数量、分位数和固定告警码。

积压包含待执行、重试等待、审批等待和执行中。`due` 统计已到 `ready_at` 的待执行与重试等待任务，`oldest_due_ms` 从其中最早的 `ready_at` 起算。同会话顺序、审批路由或并发锁仍可能影响领取。`expired_leases` 和 `expired_deadlines` 是重叠诊断计数，用来排查接管和超期。

完成样本按 `finished_at` 新到旧取最多 1,000 条，默认观察 60 分钟，最多 24 小时。报告标明采样数及截断。首次启动延迟从提交任务到第一个 `run_started` 事件，完成延迟从提交任务到终态。缺失或倒序的时间样本从对应分位数剔除。分位数用最近秩法，至少两条有效样本才输出 P95。成功率分母为成功加失败，取消单列，分母为空时返回 `null`。

按运维策略传入 `--alert-due-count` 或 `--alert-due-seconds`。达到任一阈值时仍输出完整 JSON，退出码为 3。正常为 0，连接或权限等运行错误为 1，参数错误为 2。脚本可在现有监测任务中调用。查询沿用已有表和索引，每条 SQL 受 5 秒时限保护。长历史和高频采集需通过部署数据核对查询计划及成本。

用 `python scripts/test-agent-runtime.py` 复现 PostgreSQL 回归。CI 一并保存 `runtime-metrics.json` 示例。回退时停止调用该命令，原 Worker 和数据库版本继续使用当前配置。调用者使用已有授权的运维身份选择租户。
