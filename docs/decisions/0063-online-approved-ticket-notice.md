# ADR-0063 在线推送已提交的工单确认

在 D19 的试点租户策略上，客服服务可独立开启 `SUPPORTFLOW_BOT_RUNTIME_TICKET_NOTICE_PUSH_ENABLED=true`。新增监听器在事务提交后发起一次内部请求，连接和读取各有明确超时。工单事务、业务回执和持久化消息以 PostgreSQL 为准。推送失败时记录失败类型，访客可通过聊天历史或 D18 的增量补拉读取同一条消息。

内部请求只传租户、会话、序号和消息 ID。复用客服与实时服务间的密钥，签名区分 `CUSTOMER_NOTICE` 方向，绑定方法、路径和租户。实时服务以原签名通道读取一条访客可见消息，校验编号格式、系统消息类型、稳定消息键及序号，再只向与该租户和会话匹配的访客连接发送。浏览器继续按持久化消息 ID 去重。

## 复现与回退

- 先用 JDK 25 执行 `mvn -B -f backend/pom.xml verify`。运行 `python scripts/test-customer-rls.py --with-ai` 验证 Spring 提交事件和 PostgreSQL 回滚。运行 `python scripts/test-agent-runtime.py --with-java-delivery` 验证真实客服、实时服务、浏览器协议和隔离数据库。验收使用独立合成访客身份。
- 从 `frontend` 执行 `npm run test:browser`。使用受控 WebSocket 验证在线消息与重复帧的显示。
- 先部署支持新内部入口的实时服务，再启用客服推送。清空推送开关可恢复只写会话历史的策略；已有工单和消息保留。无数据库迁移。

当前范围为单实例在线访客和读取补偿。推送响应表示实时服务处理了引用，交付证据来自实际 WebSocket 收到消息。跨实例路由、历史消息补发调度和第三方渠道回执属于后续验收。
