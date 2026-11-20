# ADR-0014：终态未知回复的受控提交核销

日期：2026-11-20。前提为ADR-0013：业务回执已存在，但取消、Worker死亡或确认预算不足可留下FAILED/SIDE_EFFECT_UNKNOWN，阻断同会话后续事件。目标只确认已发生的回复提交；不重发消息、不重新运行任务、不把取消改成成功。当前仅支持session.reply/reply-v1、runtime-v1且恰有一个WRITE的任务。

选择独立、不可更新/删除的agent_reply_reconciliation账本，沿用AI004权威迁移/RLS/受限Runtime角色/只读readiness和schema drift契约。相比把核销信息塞进Run自由文本，这能保留原FAILED/取消/错误/未知Step结果/完成时刻、观察fence、可信操作员引用、业务消息/seq/摘要和DB确认时间，且重复/并发确认只能产生一条记录。相比直接把失败任务改SUCCEEDED，保留任务语义和执行权边界。无新服务或通用事件库。

显式宿主ReplyReconciler仅接预配置ResultClient及可信operator_ref；没有公开/模型工具/自动轮询入口。一次调用只进行一个有界GET，不发POST/模型/工单查询。读取严格匹配原tenant/event/run/snapshot/参数Hash的原子业务回执，None/401/503/不匹配均不核销；不存在回执不能证明未执行。客户回执接口沿用当前独立服务认证，不靠已过期lease恢复业务权限。

数据库短事务按Run→Step锁顺序，重新核对原版本/终态/owner为空/lease为空、fence、唯一WRITE的UNKNOWN状态、原intent Hash及全部来源绑定。确认回执→追加账本→WRITE Step SUCCEEDED及原消息引用→Run仍FAILED但error_code=SIDE_EFFECT_CONFIRMED、fence递增，全部同事务。finished_at/cancel_requested/预算/attempt/retry不变；原SIDE_EFFECT_UNKNOWN保存在账本。新错误码表明未知副作用已确认，可解除现有每会话顺序阻断；不表示任务全部成功或外部渠道送达。失效观察、错租户/错回执、多WRITE或未知版本拒绝。重复相同证明只读返回原记录，不覆盖首次操作者/时间，不再增fence。

核销是显式运维控制面，不消耗或重置已终止Run执行预算；只有一次有界业务回执GET，无模型费用。无回执/错误的探测暂不建无限审计表、无自动高频重试；自动调度/操作者认证UI/组织审计导出另立阶段。追加式账本保留首次成功核销事实，不声称防住已失陷的整个AI运行身份或数据库管理员。

验收：真实PG的确认/无证明/错误绑定/并发/幂等/取消与过期/预算及历史不变、事务故障回滚、RLS/权限/复合FK、readiness权限漂移；旧003升级已有Run/Step/payload逐字保留，六库新建/重复迁移/drift；实际JAR已提交回执与无回执均核实。不引入原始消息/Prompt/密钥入核销账本。

回滚：停用Reconciler，不删除已提交消息、回执或核销账本，不回退原迁移校验和；先停止新版入口后可运行旧业务Workflow。数据库不物理降级；核销后FAILED/SIDE_EFFECT_CONFIRMED本身不赋予执行权，不能用重置fence/预算或新Run重放旧写来回滚。
