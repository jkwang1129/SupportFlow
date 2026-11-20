# ADR-0013：核验报告接已有原子业务回复

日期：2026-11-20。只读Loop已能生成私有核验报告，但尚未完成会话消息落库。沿用既有reply-v1/AI当前写许可/customer007消息+回执事务，不再实现另一套发消息或幂等账本，不把写操作暴露成模型可任意调用的工具。

TicketReadLoop增加prepare返回PreparedReport（经过当前来源/工单验证的固定报告及只读引用）；原__call__继续返回原refs，兼容只读用法。报告字段均不可变标量、mapping冻结、repr隐藏正文。TicketReplyHandler只调用该宿主产物，按既有reply canonical摘要绑定tenant/run/event/snapshot/text，先预留TOOL/WRITE，再用ResultClient提交固定sender/target消息；Java仍核验当前Run/Step permit、来源和会话状态、最终DB时钟，消息/seq/业务回执同事务。

成功Step/Run只保存reply-committed、消息引用、snapshot/参数Hash，表示客户库提交已确认，不等于外部渠道恰好送达一次。工单报告是最后读取授权时点的事实，不声称工单状态与随后消息提交在同一跨服务事务内永不变化；回复事务保护当前会话/来源与写授权。若需写时绑定完整事实版本，须另立版本化协议，不能偷换当前reply-v1含义。

POST瞬时异常可能发生在提交后。只允许再预留一个TOOL/READ查询同一Run/snapshot/正文Hash的业务回执；观察到匹配回执才完成原WRITE，不盲重发POST。404/None不证明未执行；查询故障、预算不足、永久拒绝或已失租约时，原UNKNOWN/会话顺序阻断机制保留。当前保守地把失败WRITE都视为未知，不把4xx直接当成可安全重做的授权；细化确定性无副作用拒绝须单独契约与验收。

若WRITE Step已成功但后续Runtime持久化回执丢失，恢复先确认原业务回执并核对已成功Step，直接返回过去已提交的引用；不重新规划/读取已变化工单，更不以新文本再次POST。确认历史提交不是获得新的业务写权限；旧来源关闭/接管也不会变成第二次发消息。metadata损坏/不匹配明确失败。

成功报告发布常见2 Model/6 Tool/11 Step；POST丢回执后一次确认读为2/7/12，预算不够不会免费查询或抹掉不确定写。取消/到期不能撤销已提交消息：失租约之后即使客户端见到回执，不能擅自把Runtime终态改成成功；终态UNKNOWN核销仍属后续受控恢复。副作用恢复不使用payload表推断外部是否提交。

组件显式注入，不自动注册Worker/开关/业务路由；尚无创建工单、HITL、Profile Resolver或渠道回执闭环。回滚停止使用TicketReplyHandler/关闭result开关，保留消息、007回执、Run/Step/报告及预算；不删除UNKNOWN、不换event/run绕过幂等。无SQL/Java修改，旧Workflow与只读Loop入口保留。
