# ADR-0005：结果写入的执行权、短期许可与幂等

日期：2026-11-20，B1后续分批交付。来源读取权限不是写入许可；默认仍不切换业务入口。

Java不能仅信任AI回调中的runId/fence、有效服务密钥或过去的Run接收回执。业务写入前先在无客户库事务/行锁的情况下，主动调用已配置AI控制面，核对最新Run/租约/owner/fence/cancel/deadline与已RESERVED的WRITE步骤。操作、来源主体、snapshot引用和精确参数SHA必须匹配持久化意图；先前已成功/未知/READ步骤都不能取得新的写许可。首版只为session.reply/reply-v1授权，不是通用任意工具签发接口。

AI在短事务内以DB时钟重查，返回最多5秒且不超lease/deadline的签名许可，绑定tenant/run/source event/owner/fence/步骤/操作/snapshot/参数摘要。使用独立audience，不把服务请求凭据或上下文hash当成此许可。签名不等于一次性nonce；重复签发不重置任何Run预算。Java消费端必须验签、按客户DB时钟检查过期、绑定实际参数，并重新检查业务资源权限。

后续Java结果写事务：先按稳定tenant+run键串行并核对已有回执；已有同参数回执只确认，不再写，异参数冲突。没有回执时才校验仍有效许可和当前来源/快照/会话状态，调用现有SessionService落一条BOT文本消息及回执并原子提交。最终reply角色/目标session等由Java派生，不能由模型自由指定。回执只返回业务引用，不返回消息原文。模型或网络异常不得生成新run/event绕过去重。

取消只能停止新的有效操作，不能撤销已经授权/在途/提交的动作。必须在外部写前登记WRITE意图；取消和失联仍按现有SIDE_EFFECT_UNKNOWN保留不确定性。许可在租约过期前失效，新的owner不能让旧fence续权；跨主机时钟边界、网络TLS和运维时钟监控由M12补充，不声称已获多区域保证。已经完成的结果可通过受权限保护的只读回执核验；未知写不得直接自动重放。

先交付并测只读执行权签发，再交付Java消费/回执与实际互通，最后接业务handler/源端自动路由。每批有独立负面测试、阶段记录和回滚说明；许可接口本身不执行业务动作，不等于工具层、HITL或完整Agent Loop已实现。

Java接入进一步拆分：先扩展现有AgentRuntimeClient的有界传输，支持主动请求并验证许可；复用同一整体deadline/8KiB响应上限/禁重定向/不隐式重试，不创建第二套HTTP栈。AgentActionPermit只可由验签路径创建，返回字段集合/严格整数/全部身份引用/操作/过期均核验。拒绝持有业务事务时发起控制面调用。获取时先用本机时钟拒绝明显过期或超过5秒的许可；后续写事务必须再次用客户DB时钟调用requireValidAt，并重新验证资源和事务幂等，不能把本机校验当作最终写入许可消费。此子批次不写消息或回执表。

结果存储子批次沿用customer版本迁移新增007。回执以tenant+run主键稳定去重，引用同tenant/event/run/snapshot和实际消息；运行账号只有S/I、FORCE RLS及restrictive guard。正文仍只写既有session_message，不复制到回执。参数摘要为UTF-8换行连接`supportflow-session-reply:v1,tenant,run,event,snapshot,sha256(精确回复UTF-8)`再SHA256，无尾换行；正文1～8192字节，拒绝空白、NUL和非法UTF-16，不做隐式trim/截断。owner/fence不放进稳定内容摘要，故重试不能通过更换owner来修改回复。

存储组件只接受已验签许可与可信tenant事务，不自动装配或暴露新业务API。写前按tenant+run事务advisory锁串行，已提交同内容回执直接返回引用（即便后来人工接手/来源过期），异内容409；这不执行新动作或暴露正文。新写先锁session UPDATE，再用已有快照组件重验来源/主体/快照，避免多写者先SHARE后升级的死锁；用客户DB时钟检查许可，复用SessionService以固定BOT/文本/全可见和稳定clientMsgNo落库，同时插入回执，再检查有效期。任一步失败全事务回滚，计费等afterCommit只执行一次。最终提交仍属在途动作，不承诺跨库事务或取消可撤销已授权写。后续API应先短事务核对已有回执，再在无事务时请求当前AI许可，最后新事务执行上述存储；不要为重试已提交结果要求已终态Run重新取得新写许可。
