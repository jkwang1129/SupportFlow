# ADR-0006：步骤正文与完成状态原子持久化

日期：2026-11-20。现有Run/Step只允许小型引用，ai_usage_record仅保存用量/延迟等best-effort计量，session_message保存业务消息；它们都不能恢复尚未写入业务的模型动作/检索结果。不能把日志当Checkpoint，也不能在恢复时无预算地再次调用模型。沿用同一AI服务/PG，先补存储基础，不新增服务/中间件，不自动切换Workflow。

新增ai_db版本003的agent_payload：同tenant/run/step复合FK、一Step一正文；schema仅model-output-v1、tool-output-v1、working-state-v1。精确JSON文本与SHA，根对象、纯JSON类型、有限数值、32层/4096节点/64KiB上限；不接受Provider响应对象/配置对象，拒绝NUL/非法Unicode/非有限数。DTO repr隐藏正文。载荷是不可信数据，Hash只证明存储完整性，不证明事实、审批或执行权限；业务调用者仍须按schema解析、重新核验来源/知识ACL与工具参数，不把原始header/JWT/配置密钥放进载荷。

只有已RESERVED且当前有效租约/fence的MODEL/NONE、TOOL/READ、INTERNAL/NONE步骤可以调用complete_payload_step。正文INSERT与Step SUCCEEDED/result_ref在一个事务里完成；重复相同schema/内容只返回原引用，修改冲突，不重置预算。WRITE工具继续使用业务回执协议，不用这张表推断外部动作是否提交。数据库故障使两个写一起回滚；进程在提交后丢响应，重读应仍见相同正文与SUCCEEDED步骤。当前先交付存储/权限/负面测试，再独立接入Worker，最后接B2工具与bounded loop。

表仅S/I、FORCE RLS/restrictive guard，默认七天读取TTL，不自动物理清理、不刷新过期值；清理/容量治理和备份由M12交付。每次读取限定tenant/run/payload，重算Hash并严格解码；不接受其他Run/租户的引用。无新公开API。Runtime Readiness增查列、SI且无U/D/TRUNCATE、非owner及FORCE RLS；新003继续拒绝被提权的Runtime能力组。

回滚停止使用新存储/后续Worker接口，保留003及全部引用/正文/Run/Step；不改历史SQL、不清除模型调用计数或UNKNOWN。不在本批声称完成Worker正文恢复、长上下文压缩、工具Registry、HITL或语义质量评测。

## Worker接入（独立子批次15）

ExecutionContext.payload_step复用原step的逻辑键、意图摘要、fence、预留预算和重试路径，仅在成功结果存取处接入正文。恢复时严格绑定当前tenant/run/step/schema，重算完整性并重新检查租约；返回新解码对象，局部修改不会改持久化内容。正文缺失、损坏、版本不符或过期明确失败，不自动重调模型。WRITE仍拒绝此接口。

正文原子提交位于外部调用异常处理之外：提交已成功但数据库回执丢失，不能把Step降回UNKNOWN。数据库OperationalError/InterfaceError只记录稳定错误码，经原retry预算转移；已成功步骤复用，不确定的MODEL/READ需新预留并继续消耗原总预算。不确定WRITE仍保持阻断。若转移时数据库持续不可用，异常向监督者传播，Run保持租约态等待后续接管，不伪造RETRY_WAIT或成功；尚无监督进程或网络分区自动修复承诺。

真实PG和子进程故障验收见阶段记录。此接口尚未注册真实模型/工具业务handler，不等于完成Agent Loop、知识权限重验、HITL或业务自动调度。回滚停用payload_step调用并回退Worker二进制，保留003/正文/步骤与全部计数；不要把旧模型输出视为新权限。
