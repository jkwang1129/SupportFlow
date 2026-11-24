# ADR-0062 获批工单的访客确认消息

试点租户由客服服务的 `SUPPORTFLOW_BOT_RUNTIME_TICKET_NOTICE_TENANT` 指定。空值关闭。客服处理该租户的新建单请求时，要求 AI Runtime 签发审批专用的 `action-v2` 许可。AI 在同一读取事务中核验当前 Run、写步骤及已消费的审批记录，校验绑定的来源快照、参数摘要和步骤号。客服验证签名、许可版本和有效期。常规建单和会话回复继续使用原 `action-v1`。

客服锁定来源会话、核查当前来源，并使用同一 PostgreSQL 事务提交工单、工单事件、回执和访客可见系统消息。消息根据实际工单号生成，正文固定为“工单已创建，编号：{工单号}”。同一 Run 的重复请求返回已有回执。消息键冲突或后置许可校验失败时，事务回滚。已有工单保持原历史。

## 复现与回退

- JDK 25 和独立 PostgreSQL 下，从仓库根目录先执行 `mvn -B -f backend/pom.xml verify` 生成当前 JAR，再运行 `python scripts/test-customer-rls.py --with-ai` 与 `python scripts/test-agent-runtime.py --with-java-delivery`。后者录制数据库、回执和访客视图的断言，生成 `.local/verification/runtime-ticket-approval.json`。
- 从 `frontend` 运行 `npm run test:browser`，使用受控 WebSocket 响应复现场景。先未收到通知，再在重连快照和增量消息中收到同一条通知，最终只显示一次。
- 启用试点前部署支持审批专用许可的 AI 服务。启用后该租户的新建单统一需要审批。清空试点租户配置可恢复原策略；已有工单及通知保留。无数据库迁移。

通知通过已有访客会话历史及补拉可见。本批验收重连读取，在线主动推送与第三方渠道送达留待单独验证。
