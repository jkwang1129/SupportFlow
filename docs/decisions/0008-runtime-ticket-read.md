# ADR-0008：Runtime工单最小状态读取

日期：2026-11-20。既有TicketController/TicketService面向企业成员，详情包含描述、内部时间线、坐席与客户冗余字段，不能把访客伪装成企业成员调用该接口。沿用现有工单数据与客户服务RLS/事务，在同一服务补充窄的访客状态投影；不新增表、业务状态机或第二套工单系统。

新GET `/api/customer/internal/runtime/events/{eventId}/runs/{runId}/tickets/{ticketNo}`。复用独立AI服务凭据、精确method/path/query/tenant/body摘要HMAC，匿名/旧来源签名反射拒绝；无query/body，工单号仅ASCII字母数字下划线/连字符且≤64。新`SUPPORTFLOW_BOT_RUNTIME_READ_TOOLS_ENABLED`独立默认false，原source/result开关不会开启此接口；新开关也不会开启原接口。鉴权先于开关/正文与Readiness。每个请求仍需可信RuntimeCustomerCall、绑定租户后才能开始短事务。

项目访客权限政策：先调用既有AgentSourceSnapshots.capture，重验已DELIVERED event/run、当前会话/来源/主体及快照时效/精确Hash；来源关闭、接管、删除、变更、过期时拒绝。当前source的customer_ref来自SessionService中签名访客身份对应的服务端会话记录，模型不能提供它。非空时须锁定同租户且未删除/匿名化/合并的客户，只允许customer_id完全相同的企业内部工单，包括该客户历史会话工单。为空的合成/历史来源只允许customer_id也为空、source_session_id与session_no都对应当前来源的工单。平台支持工单、其他客户/租户、软删除工单与不存在对象统一不返回内容。这是本项目的最小访客政策，不代表企业坐席权限发生改变。

Source的e/s/m锁、有效客户行SHARE锁与ticket行SHARE锁在同一事务内持有，避免检查归属后在事务内被改给其他人；锁/语句超时沿用来源的2秒/5秒和外层10秒事务。返回后或在途HTTP的数据不能被“追回”，不承诺瞬时全网撤权。

只返回十字段：schema_version、tenant_code、source_event_id、run_id、snapshot_id、source_hash、ticket_no、status（整数1～5）、updated_at（数据库原本无时区的ISO本地时间）、resource_version。不返回客户ID/姓名/电话、工单标题/描述/时间线或坐席。resource_version是这一投影及来源绑定的SHA，不是整行MVCC版本或单调序号：固定前缀supportflow-ticket-status:v1，再以换行依次连接tenant/event/run/snapshot/sourceHash/ticketNo/status/updatedAt，无末尾换行。状态改变即使更新时间相同也改变摘要。摘要本身不授予权限，恢复仍需业务端当前重验。

与既有来源API相同，此读取按当前业务来源授权供受信AI服务准备/核验事实，不额外跨服务查询AI Run租约；Run取消、租约与执行预算仍由Worker执行边界负责。这个读端服务签名不授予业务写许可，也不能代替回复/后续工单创建的写授权协议。

未授权/不存在40404，来源40402/40902/41002分别404/409/410；格式或待核验状态422，数据库失败503且不透传SQL。GET可幂等创建派生来源快照，绝不创建/修改工单、回复消息、计费或结果回执。既有业务服务与RLS账号保持不变；不把它描述为只具SELECT权限的数据库角色。

本批只交付业务API及真实PG/认证测试，后续独立接Ticket SDK/ReadTool与实际Worker联调；没有实际模型选择工具、MCP、HITL或业务自动触发。回滚关闭read-tools开关/回退二进制，保留派生快照与Run/Step/计数；无SQL回滚、不修改已应用迁移。
