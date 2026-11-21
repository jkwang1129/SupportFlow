# ADR-0028：工单未知写核销的不可变审计存储

日期：2026-11-21。接续有界建单工具，B3先补AI005存储/权限/readiness/升级验收，不同时实现核销服务、HITL、模型Loop或默认路由。已提交但无确认的WRITE必须保留UNKNOWN；没有回执不能重发。

复用现有终态回复核销的语义：只由显式可信宿主读取业务回执、核操作/来源/参数，原失败任务保持FAILED，保留取消、原Step结果、原预算/开始结束时间，核销只增加审计并解除未知副作用阻断。不将失败任务重置为PENDING或把查询成功冒充重新执行成功。应用确认逻辑下一批复用既有机制，不复制一套调度器。

AI005新增agent_ticket_reconciliation，按tenant/run唯一，同租户Run/Step复合FK，固定ticket.create/ticket-create-v1，记录观察到的fence、来源event/snapshot、参数SHA/intentHash、工单ID/编号、可信operator_ref、原SIDE_EFFECT_UNKNOWN及原Step引用结果、确认时间。tenant/ticket_id唯一，避免同租户同一工单重复核销。confirmed_at是确认记录时间，不是建单提交时刻。SQL FK/Hash不能代替真实业务回执与当前终态/Step/版本/主体核验；跨库不能通过物理FK证明建单，应用必须走签名GET与严格SDK。

沿用FORCE RLS和permissive+restrictive租户策略，空scope/PLATFORM拒绝；运行AI身份与能力组只SELECT/INSERT，无UPDATE/DELETE/TRUNCATE。唯一40-runtime-roles.sql重复provision后仍保持不可变，不给其他业务身份新权限。Runtime启用时readiness同时检查新表/列、RLS/归属与权限，disabled不触发运行时DDL。

保留AI004及原agent_reply_reconciliation表/接口，避免重命名表、改旧checksum、兼容视图/触发器和旧任务迁移的大范围改动。AI004→005必须精确保留现有Run/Step/payload/回复核销数据。结构从95表增至96表，视图1/受控函数5不变；按migrate→provision→verify升级，备份恢复/生产升级演练仍属M12。

验收计划：真实受限AI角色的租户/其他角色隔离、约束/FK、重复与并发、事务回滚、不可变权限、宽permissive不能绕过restrictive、不安全能力组拒绝、再次provision逐行不变；旧库升级/结构漂移/新建库/Runtime和三套完整服务回归。回滚停用后续核销调用，保留005/审计/业务回执/Run历史，不能删表恢复盲重试。此ADR为编码前决策，存储不等于已完成工单核销。

25 项真实 PostgreSQL 账本测试通过。八路并发插入留下一条记录，两次权限配置后审计行精确一致。17 项迁移测试、96 张表结构核对、三个完整服务场景均通过。工单终态确认入口随 ADR-0029 单独交付。复现命令和原始日志见阶段验收文档。
