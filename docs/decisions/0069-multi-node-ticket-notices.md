# ADR-0069 多节点工单通知

工单、回执和访客确认消息仍在同一客服数据库事务提交。提交后，客服服务向配置中的每个实时服务节点分别发送已签名的消息引用。节点回读已提交消息，并按当前租户和会话将其发给本地访客连接。访客切到另一个节点后仍可用消息序号从历史补拉。

启用 `supportflow.bot.runtime-ticket-notice-push-enabled=true` 的部署可设置 `supportflow.realtime.ticket-notice-base-urls`，例如 `http://realtime-a:9096,http://realtime-b:9096`。列表指定直接节点地址，最多四个不同的 HTTP(S) 源。列表为空时使用原 `supportflow.realtime.base-url`。每个节点分别签名和发送，连接、读取超时仍分别为 300 和 1200 毫秒。节点独立处理响应，失败节点的访客可用 `SYNC` 补拉已经落库的消息。每个节点请求一次，访问地址由部署配置固定。

该配置适用于节点地址已知的小规模部署。进程和节点滚动切换时，由部署更新地址列表。坐席任务时间线仍走客服分页接口，访客通知的业务凭据仍是已提交回执。

## 复现与回退

- 使用 JDK 25 执行 `mvn -B -f backend/pom.xml verify`。以 `python scripts/test-customer-rls.py --with-ai` 验证提交、回滚、签名和单节点失败的独立性。
- 执行 `python scripts/test-agent-runtime.py --with-java-delivery`。夹具启动两个独立实时服务 JAR，将同一会话连到两端，检查同租户其他会话及外租户同号会话。再向节点 A 注入 HTTP 503，检查节点 B 正常收到，节点 A 用 `SYNC` 读取同一条消息。
- 验收客户端以单调时钟记录两组成功场景。首事件从发起签名访客消息计至首次通过坐席时间线接口读到 `run_started`。完成从启动执行已批准任务的 Worker 计至首次读到 `run_completed`。数据包含测试中的 CLI 启动与业务请求耗时，可用来复核该环境的完整链路。
- 应用回退时可清空地址列表，恢复走一个原配置地址；有故障时也可关闭通知推送开关。访客历史补拉继续读取已提交的业务记录。无需数据库迁移。
