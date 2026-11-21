# ADR-0027：在既有执行器中加入有界建单工具

日期：2026-11-21。拟接续建单SDK，仅将固定create_ticket接入现有Registry/Executor，不同时改模型Loop、HITL、默认路由或未知写核销。当前ReadTool路径与Checkpoint保持；不把WRITE伪装成可缓存READ，也不另建一套执行器/网络代理。

注册独立WriteTool契约，复用闭合ToolModel、Scope/版本/身份、参数和输出验证与有界适配器。模型参数只有title/content；tenant/run/event/snapshot/owner/fence、客户/策略与服务地址都来自可信上下文/窄SDK。已有8KiB ToolCall整体大小上限继续生效，标题/正文另按业务字节/码点约束，不截断。

执行前固定本Run的工具/参数/服务地址与策略Hash绑定（不含可轮换密钥）、计数一次当前来源READ授权，再构造ticket.create/ticket-create-v1的精确业务摘要意图。每Run每操作只有一个绑定，换调用key不能改变内容。必须在预留TOOL WRITE后调用SDK；业务结果只以核验过的回执引用完成Step，不将完整工具结果用payload_step缓存为已成功写入。不接受模型把Scope、操作版本、主体、授权引用或幂等键当参数。

WRITE网络错误/超时等未知结果不自动POST重试；允许一次独立计预算的只读回执查询，只有完整匹配的已提交事实可完成当前WRITE。无回执/查询错误/参数不符仍让既有Runtime保留UNKNOWN、预算与同会话阻断。成功Step恢复只查询绑定的历史回执，核原操作/版本/来源/参数摘要与结果，不重新发POST；不把当前来源过期误当已提交事实不存在。未知终态的人工核销另批实现。

这是项目的受控业务策略：显式宿主ticket:create授权仅可建立未指派、固定优先级2的内部工单；不是所有写工具免审批。Java与AI开关仍默认false，可信触发/租户Profile/模型WRITE决策Loop尚未接入；涉及退款/外部动作时仍需B3限时审批与当前资源再鉴权。

实现保持ReadTool路径，新增WriteTool契约及既有ToolExecutor写分支；只共用Schema/Scope/身份/回调边界，不给WRITE开放payload缓存。固定策略Hash绑定服务地址、Schema和工具时限，排除可轮换密钥。成功写恢复先核绑定和旧意图/结果，再独立计READ查回执；当前任务存在任何UNKNOWN/RESERVED写时不开始新副作用。

验收：新增42项工具单元与原63项只读共105通过；AI普通923通过/281条件skip/1原xfail，RAG24项通过。隔离PG Runtime专项首轮275过/1夹具协议错误，按原来源HTTP200业务信封修正后新增19项全部通过（原257项首轮通过），保留分轮原始数据；精确CI继续全跑276项。真实Java/AI/受限PG组合最终结果见reliability-verification.md。不用这些工程测试外推模型决策准确率/性能/生产客户；模型WRITE Loop和UNKNOWN终态核销尚未实现。

回滚不注册WriteTool或回退本批执行器分支，保留Java原子业务、已提交工单/回执、Runtime成功/未知Step和原预算，不删成功Step或放宽来源权限。默认Workflow、Java/AI写开关保持关闭。
