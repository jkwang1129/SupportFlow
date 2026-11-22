# ADR-0046 租户模型并发配额

AI 010 在 `agent_model_quota` 中保存可选的租户上限。Runtime 按租户串行核对配额并预留模型步骤。核对、记入原 Run 的调用预算、创建 `RESERVED` 步骤在同一 PostgreSQL 事务完成。Chat 与 Embedding 共用该额度。不同租户使用各自的锁，事务提交后 Worker 执行外部调用。

配额统计处于 `RESERVED` 的模型步骤。完成、失败、取消或旧 Worker 的租约被接管后，原步骤离开该状态。失去租约的 Worker 无法提交迟到结果。取消时，已送出的请求仍可能在供应商侧继续执行，关联用量按原规则保持未知。

达到配额时，Run 进入 `RETRY_WAIT`，约一秒后可再次领取，仍受原截止时间约束。等待额度的轮次消耗零模型调用和零故障重试。嵌套知识工具在恢复时按既有规则重新核权，相关读取继续计入工具预算。已完成的模型检查点按原机制复用。

Runtime 账号只读本租户的配额。部署按 migrate、provision、verify 顺序，并在该租户的 Worker 全部升级后配置额度。通过有权限的数据库运维会话执行下列事务可将示例租户配置为 2 个名额。

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('supportflow:model-capacity:TENANT_A', 0));
INSERT INTO public.agent_model_quota (tenant_code, max_active_steps)
VALUES ('TENANT_A', 2)
ON CONFLICT (tenant_code) DO UPDATE SET max_active_steps = EXCLUDED.max_active_steps;
COMMIT;
```

额度范围为 0 到 100，0 暂停新模型步骤。降低配额时，已预留步骤照常收尾。没有配置记录的租户继续按 Run 预算执行。关闭配额可在同一租户锁下删除相应配置记录，保留 AI 010 和历史执行数据。Token 和金额预留属于后续计费阶段。
