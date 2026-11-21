# ADR-0033 单租户只读工单 Worker

运营人员为独立 Worker 进程指定一个租户和一个供应商，沿用部署中的模型、地址与密钥配置。默认客服流程不启动该进程。入站消息和模型输出无法选择租户、供应商、凭据或服务地址。

运行时构造一个固定的 `ChatProfile`，复用原有工具注册表、签名 SDK、工单只读 Loop、Run 预算和持久化检查点。模型请求没有跨供应商回退。启动检查 AI 数据库的 Runtime 角色、RLS 和迁移状态。Worker 只对指定租户检查过期与领取任务，跳过全局租户发现。不注册业务写工具，也不发布回复。

从 AI 服务目录运行 `python -m ai.runtime.ticket_worker --tenant TENANT_A --provider qwen`。API 开关需显式设置 `SUPPORTFLOW_AI_RUNTIME_API_ENABLED=true`，并配置受限 AI 数据库、客服服务签名与可读来源、工单工具 API。供应商配置沿用 `SUPPORTFLOW_AI_QWEN_*` 或 `SUPPORTFLOW_AI_DEEPSEEK_*`。只处理一项已就绪任务时使用 `--once`。退出信号停止领取新任务，正在执行的任务仍受已有外部请求期限和 Run 预算约束。

本批可人工启动单租户进程，用于已有可信来源的只读工单任务。自动登记、动态配置、跨进程并发配额和业务结果发布各自验收。回滚停止进程，保留已提交的任务、步骤、预算和报告。替换模型和 Prompt 时继续遵守已有检查点绑定。
