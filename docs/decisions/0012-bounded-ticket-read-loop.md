# ADR-0012：最小有界模型—工单只读工具循环

日期：2026-11-20。在既有RuntimeChatClient/Source与Ticket工具、Run/Step/正文上增加TicketReadLoop，不引入LangGraph新图或第二套状态机。模型以严格JSON选择tool/report/clarify，最多4轮；这是内部结构化决策协议，不冒充供应商原生tool_calls或MCP支持。

宿主显式注入来源访问器、工单工具Executor、租户Model Profile；没有自动装配/路由/Worker启动。来源先经已有Source工具当前核权，模型只看user角色的原问题。稳定INTERNAL绑定保存loop版本/完整System政策与工具catalog/Model策略/来源快照/轮数Hash，恢复不允许换政策或来源。Model步骤键含轮次和上下文Hash，成功输入相同就复用；受信工具的新版本使后续上下文改变时，重新预留MODEL仍受原Run总预算约束。

模型可选已注册工单工具和参数，不能授予Scope、选URL/tenant或执行未知工具。每轮只执行一个Call，保存模型结果/工具正文并重建局部观察；没有工具结果时不得report。工单结果传给模型的投影只有ticket_no/status/updated_at，内部tenant/run/snapshot/Hash留宿主校验，不直接倾倒全部工具DTO。新增其他工具须先定义独立上下文投影，当前Loop仅接受get_ticket_status执行v2；get_current_message是宿主私有bootstrap访问器。

report必须引用本次循环成功的工单观察slot，再用同一逻辑工具键进行当前核权/版本刷新。最终正文由宿主状态枚举固定模板生成，并核对工单与bootstrap的source event/run/snapshot/hash；模型不能提交答案正文、已退款/已创建等完成声明。状态在规划期间改变则采用最后核验的当前状态；归属撤销时拒绝旧观察。clarify输出固定询问工单号的模板，不假称转人工或创建工单。

报告以working-state-v1/ticket-report-v1原子持久化，Run只保存ready引用。报告恢复用规范JSON Hash核对所有字段，防止Python的True==1或2.0==2使类型篡改被误判相同。它是私有报告产物，不是已发送的业务消息、已创建工单、前端可见报告或完整业务发布链路。未知WRITE核销/审批/消息发布仍独立后续，不用此Loop处理副作用。

首次正常样例使用原默认预算：2次MODEL、5次TOOL（含来源和最终核权）、10个Step；503一次后恢复可用3 Model/6 Tool/12 Step，不清空计数。预算不足、轮数用尽、无效模型协议/参数/工具/证据、取消或坏Checkpoint明确终止，不能通过无账本重试“修复”。每次缓存取用仍有当前核权成本。Byte边界沿用模型输入32KiB/输出8KiB，尚无长历史/压缩策略；报告核权是读取时点，不承诺在途瞬时撤权。

本批核心验收使用真实PG与确定性模型/业务HTTP协议Stub，不能替代新Loop与实际模型协议TCP/客户JAR的组合联调；下一批专门补该边界。没有真实模型任务正确率、生产吞吐、Token成本或客户收益数据。回滚不注册本Loop/回退handler，保留模型/工具/报告/绑定/预算；无SQL迁移、默认Workflow不切换，不删除UNKNOWN或把新版本计划当旧版本执行。

## 组合联调与真实进程退出（独立验收子批次）

新增实际本机chat/completions协议服务器→RuntimeChatClient/Worker→签名SDK→新客户JAR→受限PG九例，模型决策/usage为预设协议Stub，业务事实与权限由真实JAR/数据库核验。覆盖正常报告、规划后状态改变、归属撤销、租户参数、假证据、自由写完成声明、503一次后按真实ready_at恢复、轮数限制；SQL逐案核实只有原来源消息/一张原工单/零结果回执。

第九例用真正Worker子进程：成功提交首次MODEL正文后os._exit(23)，Run保持RUNNING；新进程等待数据库租约自然过期后接管，不改deadline/计数/租约时间，恢复同一已成功规划。夹具显式租约3秒（生产默认30秒不变），共2次实际模型HTTP/2次MODEL预留、6次TOOL/11Step/retry1，报告成功；模型0未再请求。子进程只获受限AI DB凭据、当前测试服务凭据与合成模型key，环境allowlist不复制开发者模型密钥/客户库管理员账号。

来源/客户/工单由明确SQL夹具建立、Run由受限Store登记，未把该setup称为自动业务触发；真实Java Outbox登记另有既有独立验收。Machine report仅引用/状态/计数与NOT_MEASURED性能/费用，保留预期失败。至此本地组合读链路已验证，仍无真实供应商质量或业务消息发布/工单创建/审批，默认Workflow/Worker启动不切换。
