# ADR-0025：受控建单API与提交事实查询

日期：2026-11-21。接续ADR-0024，仅接现有customer服务内的签名窄API；不同时接Python SDK、模型WRITE工具、HITL、未知写核销或默认路由。当前业务事务已通过真实PG，复用它而不另写业务逻辑。

选择独立的`/api/customer/internal/runtime/events/{event}/runs/{run}/ticket-create`，POST建单、GET只读回执。不能使用`tickets/create`等保留字，因为既有`tickets/{ticketNo}`允许这些真实工单号。复用RuntimeSourceAccessFilter的原始字节HMAC、单header/过期/方法/无query/正文上限/重复JSON检测。POST闭合参数只允许snapshot_id/owner/fence/arguments_sha256/title/content/version，权威引用来自服务签名路径与tenant，操作固定ticket.create；不允许模型传客户/租户/优先级/类别/指派人。

建单开关`SUPPORTFLOW_BOT_RUNTIME_TICKET_CREATE_ENABLED`独立且默认false；只读回执复用已有read-tools开关。关闭新写仍可查已提交事实，不依赖AI授权服务存活，不授予当前工单状态读取权限。API不开启时在读正文/访问DB前拒绝；仅开启写同样需要独立可用服务密钥。AI端另有默认关闭的TICKET_ACTIONS开关，不通过Java开关隐式启用。

POST先短事务查匹配已提交回执，再在无JDBC连接/行锁的阶段取得TICKET_CREATE许可，最后用5秒业务事务调用AgentTicketCreations。并发前检未命中不影响业务内再次幂等核验。授权RPC只发送引用与摘要，不发送标题/正文、用户JWT。GET不调用AI，仅返回固定schema/operation、tenant/event/run/snapshot/SHA和工单ID/编号；所有int64引用显式十进制字符串，不依赖全局序列化配置。

异常响应不回传SQL/上游错误或正文；数据库错误503，业务冲突/资源撤权按现有稳定码映射，未知提交不能鼓励盲重试。回执查询404只表示此刻未找到，不保证在途POST没有提交。当前只具备受控API，不冒充审批/Agent闭环或通用exactly-once。

验收计划：签名/方法/字段/Hash/大小/默认关闭负例；实际受限PG、真实TicketService与授权HTTP，证明RPC时无连接/行锁、一次业务/回执、并发重复、授权故障/当前来源撤权、回执权限失败整体回滚、只读查询在禁写/AI故障后仍可用；实际JAR验证路由/序列化/默认配置兼容。回滚关闭新写开关，保留GET/历史/Customer009，不删工单/回执或放宽旧工具权限。

启用仅供受控宿主：Java建单需SUPPORTFLOW_BOT_RUNTIME_TICKET_CREATE_ENABLED=true；AI对应SUPPORTFLOW_AI_RUNTIME_TICKET_ACTIONS_ENABLED=true。来源登记/快照需要原source开关，查询回执需要原read-tools开关。以上都不是模型可选参数，默认不切换既有Workflow；读取回执仍需新的精确路径/tenant服务签名，不能拿别的端点签名反射。只读回执不访问AI授权，也不要求Java建单开关仍开启。

本地结果：新7项权限单元（连旧共34）、新10项真实PG（客服共139、KB19）与全模块verify通过。实际JAR/AI/受限PG三例通过：一次业务提交、终态确认、丢弃确认后只GET核对但UNKNOWN保持、来源接管0工单；实际默认配置POST/GET503与匿名401保持。夹具首轮字段version/schema_version错误及修正、完整旧链路回归和原始日志见reliability-verification.md。不以协议/事务结果外推性能、模型质量或生产运营；Python SDK/模型工具仍是后续批次。
