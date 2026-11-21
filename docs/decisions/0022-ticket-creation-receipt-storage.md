# ADR-0022：建单业务回执的持久化边界

日期：2026-11-21。B2下一步是幂等建单；先单独交付Customer009不可变回执及新旧库/角色/事务约束测试，再复用TicketService.createFromChannel接业务事务、AI写许可、窄API/SDK和未知结果查询。当前不开放建单端点、不注册模型WRITE工具、不更改默认业务触发。

一个Run最多成功创建一个内部工单，表agent_ticket_receipt以tenant/run为主键，tenant/event和tenant/ticket也唯一。回执绑定来源事件/Run/快照四元组、参数SHA、固定ticket.create/ticket-create-v1、执行Step/fence与工单ID/编号。tenant/ticket_id复合FK必须指向同租户ticket，来源复合FK沿用Customer007已建立的不可变快照唯一引用，不能仅凭全局ticket ID关联他租户。

回执只证明业务数据库已提交建单，不证明模型理解正确、客户已读或外部通知恰好一次。下一批业务层必须在同一事务中创建ticket/业务事件与写回执，按tenant/run串行核对已提交回执；参数冲突拒绝、失败整体回滚，丢确认先查回执，不能盲目再建。先有回执表不是已完成这条业务闭环。

复合FK只保证同租户资源引用存在，不能代替当前主体/会话/客户关联、来源TTL、参数正文与AI写许可核验；这些必须在后续业务事务和API里实现。数据库持有者仍是可信业务服务，回执不是防恶意数据库管理员的签名证明。

沿用现有迁移manifest/校验和/desired-schema，不修改历史SQL。新表强制RLS，租户空值/PLATFORM拒绝，只有受限customer能力组SELECT/INSERT，无UPDATE/DELETE/TRUNCATE；KB、AI和平台身份不获读取。唯一角色分发脚本在重复provision后保持不可变权限。新增ticket(tenant_code,id)唯一约束支持复合FK，原全局ID主键不变；升级不改已有工单/会话/消息/来源/知识数据。

验收计划：规范新建/008→009升级/重复执行/失败回滚/结构与策略漂移；受限实际角色的跨租户读写/缺省scope/PLATFORM/外键/唯一性/参数形状/事务回滚/并发单一回执和重复provision；原完整服务流程保留。回滚停止新建单调用、保留已提交工单与回执/迁移记录，不删除业务数据或退回无回执的重试。本批没有实际Agent建单、HITL、性能或业务收益成果。

实际结果：存储38项真实受限角色PG通过，重复provision保留13条回执及不可变权限；版本迁移17项、95表漂移/CLI初始化、三套完整实际服务、112项客服PG+19项KB全部通过。升级逐字段保留已有工单/来源/知识，SQL事务回滚与8路重复插入验证仅针对存储；不能当业务方法已实现。首轮大ID夹具溢出及最终命令/日志、Hash候选审查与边界见reliability-verification。
