# ADR-0033 单租户只读工单 Worker

运营人员为独立 Worker 进程指定一个租户和一个供应商，沿用部署中的模型、地址与密钥配置。默认客服流程不启动该进程。入站消息和模型输出无法选择租户、供应商、凭据或服务地址。

运行时构造一个固定的 `ChatProfile`，复用原有工具注册表、签名 SDK、工单只读 Loop、Run 预算和持久化检查点。模型请求没有跨供应商回退。启动检查 AI 数据库的 Runtime 角色、RLS 和迁移状态。Worker 只对指定租户检查过期与领取任务，跳过全局租户发现。不注册业务写工具，也不发布回复。

从 AI 服务目录运行 `python -m ai.runtime.ticket_worker --tenant TENANT_A --provider qwen`。API 开关需显式设置 `SUPPORTFLOW_AI_RUNTIME_API_ENABLED=true`，并配置受限 AI 数据库、客服服务签名与可读来源、工单工具 API。供应商配置沿用 `SUPPORTFLOW_AI_QWEN_*` 或 `SUPPORTFLOW_AI_DEEPSEEK_*`。只处理一项已就绪任务时使用 `--once`。退出信号停止领取新任务，正在执行的任务仍受已有外部请求期限和 Run 预算约束。

本批可人工启动单租户进程，用于已有可信来源的只读工单任务。自动登记、动态配置、跨进程并发配额和业务结果发布各自验收。回滚停止进程，保留已提交的任务、步骤、预算和报告。替换模型和 Prompt 时继续遵守已有检查点绑定。

## 独立批准执行进程

运营侧从 AI 服务目录执行 `python -m ai.runtime.ticket_worker --tenant TENANT_A --approved-tickets-only`。该模式与模型供应商选项互斥。它要求启用 Runtime API 和建单许可 `SUPPORTFLOW_AI_RUNTIME_TICKET_ACTIONS_ENABLED=true`，以及客服受控建单与来源读取。启动复用现有运行账号及结构检查，执行无需配置模型密钥。

批准路径只领取当前租户已有批准记录或消费后可接管的 Run。领取在数据库锁内重验审批，后续复用原快照、参数摘要、一次性消费和真实写许可。未知写与成功写恢复继续沿用回执规则。未审批的只读任务由原进程消费。取消、过期或失去来源权限时，已有检查继续阻止业务写入。

回滚停止批准执行进程和建单许可，保留审批、检查点与回执。现有开关可以分别关闭新审批与执行，便于受控排空。可信业务提案入口仍是后续工作。
