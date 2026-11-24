# ADR-0066 工单任务事件与序号补拉

AI 迁移 015 新增 `agent_run_event`。任务状态与步骤变更用同一事务追加事件。序号按租户和 Run 从 1 开始，在已有 Run 行锁下分配。两个工具交错完成时，序号按实际持久化顺序递增。回滚会同时撤回状态和对应事件，读取在一致性快照中进行。

事件协议 `agent-run-events-v1` 包含任务开始、步骤及工具开始与结束、等待审批，以及成功、失败和取消。每条事件只保存序号、类别、数据库记录时间、可选步骤号和可选步骤结果。事件记录时间发生在事务内。步骤未知后的人工核销仍从现有核销账本读取。迁移从切换点开始记录后续状态变化，历史时间线保留在原任务、步骤和审批表中。

坐席使用 `GET /api/customer/agent-approvals/events/{eventId}/runs/{runId}/timeline?afterSeq=0` 获取第一页，每页至多 16 条。获取下一页时把上次处理成功的 `next_seq` 传入 `afterSeq`。`has_more` 表示这次读取时还有已提交记录；空页保留原游标，超前游标返回 409。连接中断时可重发同一请求，并按 Run 和事件序号去重。浏览器订阅与自动重连后续接入。

客服服务每页检查管理员或主管角色，并在 AI 请求前后核对当前租户和来源快照。AI 的签名请求绑定租户、Run、来源事件及快照。运行角色对事件表只有读取权限，且受强制行级隔离约束。客服服务校验收到的连续序号、固定事件类型及字段后再返回。协议只传任务状态相关元数据。

## 验收、发布与回退

- 从仓库根运行 `python scripts/test-versioned-migrations.py`、`python scripts/test-schema-drift.py` 和 `python scripts/test-database-init.py` 验证旧库升级与新库初始化。
- JDK 25 下运行 `mvn -B -f backend/pom.xml verify`，再运行 `python scripts/test-customer-rls.py --with-ai` 与 `python scripts/test-agent-runtime.py --with-java-delivery`。结合隔离 PostgreSQL、签名 API 和实际 Worker 验证乱序完成、回滚、断点补拉、重复读和授权失效。
- 依次完成迁移、角色配置、只读校验、AI 服务及客服服务部署。应用回退先恢复客服服务再恢复 AI，保留 015 和已写事件。若事件投影本身需要修正，停用时间线读取后以新迁移修复。
