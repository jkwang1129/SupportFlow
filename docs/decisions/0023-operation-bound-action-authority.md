# ADR-0023：操作专属的短期写许可

日期：2026-11-21。为B2建单做权限前置，沿用现有AI受限Run/Step/租约/预算及短期HMAC许可，不新增通用授权框架或业务端点。只接受session.reply/reply-v1与ticket.create/ticket-create-v1两个固定配对，拒绝未知操作/版本或交叉配对；许可继续是action-v1格式，旧回复签名字段/顺序/字面测试向量不变。

AI存储共用一个内部核验过程，公开窄方法分别签发回复和建单许可；必须存在精确匹配当前tenant/Run/来源/主体/owner/fence/快照/参数Hash/操作/版本的唯一RESERVED TOOL WRITE Step，所有行锁后再核DB时间/取消/租约/墙钟。授权不新建Step、不返还或增加预算。新增建单授权开关默认false，独立于Runtime接收入口，显式测试夹具才启用。

Java许可Request增加封闭Operation枚举并保留原七参数构造器默认SESSION_REPLY；网络客户端分别提供authorizeReply与authorizeTicketCreate，传错类型在HTTP前拒绝。校验响应/HMAC必须匹配请求操作，不能把新操作许可转成回复许可。现有AgentResultReplies在写入前额外检查SESSION_REPLY，纵使其他来源/参数Hash全部相同且MAC有效，TICKET_CREATE也不得写消息。

这批只完善授权边界，不创建工单、不放开模型WRITE工具、不实现HITL或未知建单核销。许可不能代替业务方当前来源/资源/参数/幂等回执核验；网络在业务事务外，业务提交前后仍核数据库时钟的短期有效性。已签许可有短暂在途窗口，取消不能撤销已经提交的副作用；保留未知结果阻断与后续显式核销策略。

验收覆盖旧签名兼容、操作/版本混用、默认禁用、非法参数在持久化前拒绝、真实PG精确WRITE匹配/租约/取消/预算不变、Java有效MAC跨操作拒绝及实际Java→AI→受限PG联调；原回复发布/恢复链路继续回归。回滚关闭建单授权，保留原reply与存储/预算/历史，不恢复跨操作宽松判断。

结果：Python新13+原23共36项、Java新3+原15共18项定向通过；AI普通784通过/262条件skip/1原xfail，24项拒答门禁通过；新16项Runtime PG随257项专项通过，真实Java两种许可与原全部组合联调保持；客服PG新1项随113项+19 KB通过，全Java构建与三套完整服务通过。许可签发/取消不恢复预算，TICKET_CREATE即使其他引用/Hash和MAC合法也不能写回复。命令/日志与边界见reliability-verification，本批不创建实际工单。
