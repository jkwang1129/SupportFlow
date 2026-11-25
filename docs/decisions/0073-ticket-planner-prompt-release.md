# ADR-0073 工单 Runtime Prompt 发布与回退

工单只读规划使用代码内不可变的版本表。版本记录变更原因、发布日期、兼容供应商和回退版本。唯一模板变量是宿主生成的工具目录。渲染只接受一条 `get_ticket_status/ticket-status-v2` 的只读能力，并限制为现有模型系统消息的 16 KiB 上限。用户消息和工具结果仍通过独立数据消息进入模型。

默认使用 `ticket-plan-v1`。已校验旧版本的固定文本和目录序列化与上一提交一致。试用版 `ticket-plan-v2` 增加对多编号和含糊编号先澄清的指示。Qwen 作用域可显式启用试用版，DeepSeek 继续使用 v1。试用版共享原有决策格式、租户核权、用量和步骤预算；变更属于 Prompt 指示，模型实际效果需评测。

版本在任务首次写入工单规划绑定步骤时确定。后续领取、重试或回退从该步骤恢复实际版本。稳定版沿用原 `ticket-loop-v1` 绑定，试用版绑定记录显式版本。MODEL 步骤、检查点和运维时间线显示实际版本，继续校验模型配置摘要和原请求摘要。若同一任务的历史绑定与当前可用供应商不兼容，Worker 在发模型请求前结束该任务。

旧回放记录按 v1 校验。新版回放格式要求明确版本，并校验渲染后的 Prompt 摘要和逐次请求摘要。回放使用记录的结果，效果和安全性评估需独立测试。

## 发布与回退

- 先升级处理目标租户的所有 Worker，使其能够读取两个版本。受版本影响的运行时模型金额规则按模型配置摘要匹配，启用金额额度时先将两个摘要对应的费率纳入配置。用量和价格记录沿用原账本。
- 使用 `python -m ai.runtime.ticket_worker --tenant TENANT_A --provider qwen --prompt-version ticket-plan-v2` 启用单租户试用。为确保新任务的版本一致，处理该租户新任务的 Worker 使用同一发布配置。
- 回退时将参数改为 `ticket-plan-v1` 或按默认值启动。已绑定的 v2 任务继续在兼容的模型配置下完成。需要回退应用二进制时，先完成或取消已绑定 v2 的任务。版本切换无需数据库迁移。
- 在 `ai-service` 下运行 `python -m pytest -q tests/test_ticket_worker.py tests/test_ticket_replay.py tests/test_ticket_loop.py`。从仓库根运行 `python scripts/test-agent-runtime.py --with-java-delivery`，验收真实数据库的版本持久化、检查点恢复，以及真实 Java 业务服务和合成模型 TCP 下的试用启动。

本批提供工单只读 Worker 的租户级手动试用与回退。按流量百分比自动分配、真实模型质量对照和扩展到其他任务仍待推进。
