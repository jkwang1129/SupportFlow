# ADR-0004：业务来源快照与恢复时的资源校验

日期：2026-11-20，B1后续子批次。以下先实现customer侧持久化快照存储，不启用外部接口或业务触发。

Java保留业务事实与授权。仅当可信tenant下的Outbox事件已DELIVERED、Run回执匹配，且原访客文本消息、会话、序号、主体引用仍一致且可见，才允许创建/读取快照。人工接手、结束、软删除、改为内部消息或来源引用变化都关闭访问；不能仅凭Python提交的message_id加载任意会话。校验与快照写入在同一短事务中完成，不在事务内调用模型或其他服务。Run回执授权的是这次上下文读取，不代表Run正在执行，也不是业务写入许可。

首版只冻结当前来源文本及必要的租户/会话/客户引用，明确将文本放在untrusted_input区，与trusted_source分开。不复制服务配置中的JWT/API密钥或客户完整画像，不提前制造历史压缩/长期记忆机制。用户文本作为原始不可信输入保留；敏感输入处理与模型出站策略仍须后续完善，字段分区不等于语义注入防御。原消息最多8192 UTF-8字节、序列化快照最多65536字节，超限明确拒绝，不静默裁剪。长历史与Token预算/压缩仍归阶段C。

新增customer_db 006及只S/I、无U/D/TRUNCATE的不可变快照表，强制RLS与restrictive tenant guard。外键绑定源端event+Run回执，源端补相应唯一约束；每个event只保留一份。保存精确JSON文本和SHA-256，避免jsonb重排造成跨语言hash不一致；JSON对象/长度/hash格式由数据库约束。重复同来源返回原快照，来源内容或关键归属变更冲突，不覆盖原数据。TTL固定1小时，以数据库时钟判断；过期不能原地刷新或换快照ID绕过，需要后续显式的新任务/审批策略。

每次读取仍重新核对当前资源权限和来源摘要，存在旧快照不授予永久访问。TTL不等于已完成清理：本批运行账号不能删历史，保留/脱敏/清理策略由M12后续实施，公开验收不存真实客户正文。未知写操作、审批、Python checkpoint与恢复后的工具授权仍归B2/B3，不由快照替代。

验证采用受限真PG：稳定重复/并发、事务回滚、Run/主体/消息/会话绑定、关闭/人工/删除/可见性撤销、来源变更、期限与边界、跨租户与额外宽松策略、更新/删除/清表/DDL拒绝。同步更新权威manifest/desired-schema、角色和新旧库验收；不修改已应用SQL/checksum。回滚停用新存储组件并保留快照/回执/迁移，不清表、不改旧迁移。

## B1 第八子批次：服务接入

Java在`/api/customer/internal/runtime/events/{event}/runs/{run}/snapshot`提供POST空JSON创建和GET重读，默认`supportflow.bot.runtime-source-enabled=false`。使用现有customer↔AI这一对服务的独立CUSTOMER_SECRET，使用全新audience `supportflow-customer-runtime:v1`和固定caller `ai`进行方向/操作隔离；不使用JWT或其他服务的密钥，不新增同一信任对的重复配置。该密钥必须与realtime/ops/tenant来访密钥不同。复用密钥不意味着复用旧协议：旧AI/Java内部凭据不接受此操作，新凭据也不能反射到旧入口。

凭据格式`runtime:ai:expiry:body_sha256:signature`。规范串为audience、caller、expiry、method、raw path、raw query、tenant、body SHA-256，换行连接。先验证签名/过期，再最多读取8192字节验证body摘要，防止匿名请求消耗正文读取或数据库检查；新过滤器在Readiness前。只允许规范UUID路径、业务租户、无query；GET无body，POST单一JSON Content-Type且必须为空对象。认证后的请求属性再由内部接口拦截器核验，编码路径/转发不能绕过身份与资源绑定。

Python加载器仅按已持久化Run的tenant/event/run引用构建固定路径，不接受模型提供的URL、主体或消息ID。总deadline≤10秒、网络分段timeout、响应≤128KiB并拒绝压缩/重定向，校验严格信封、精确payload SHA-256、来源字段与Run一致、版本与文本边界；异常只暴露稳定分类，不保留SQL/原始错误/凭据。恢复时GET重读必须再次走Java资源权限，不能仅依赖本地缓存的snapshotId。

此子批次仍不启动Worker或自动消息路由，也不提供结果写入/工具执行能力。已用实际Java JAR/Python加载器/受限PG联调；读取接口不替代下一批业务写入的执行权/幂等校验。Python恢复读取可额外绑定已记录snapshotId/Hash，合法但不同的快照也不得静默替换。验收结果见阶段报告。
