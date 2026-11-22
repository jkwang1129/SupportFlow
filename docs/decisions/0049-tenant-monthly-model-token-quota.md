# ADR-0049 租户日度和月度模型 Token 额度

AI 013 增加可选的 `monthly_token_limit`。Runtime 在租户事务锁内读取最多 31 条 UTC 日汇总并检查新预留。每日额度单独配置时，只读当天汇总。日度与月度同时配置时，新预留须符合两个上限。并发额度与 Run 调用预算也继续生效。

当月用量包含从 UTC 月初到当前日期的原始预留。有用量回执时按报告值结算。缺失回执的预留计入其原日期和原月份，已知用量超过预留时完整计入后续判断。同一租户共用配置，Chat 与 Embedding 走相同准入。新预留超限时，当前 Run 返回 `MODEL_TOKEN_QUOTA_EXCEEDED`，本次不触达模型供应商，模型调用与故障重试计数不增加。

完成 migrate、provision、verify 并升级处理目标租户的全部 Worker，再启用月度额度。此前仅有并发控制且未产生 Token 预留的调用没有对应账目。已有的当月预留和回执纳入额度。示例同时启用两种额度。

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('supportflow:model-capacity:TENANT_A', 0));
INSERT INTO public.agent_model_quota(tenant_code,max_active_steps,daily_token_limit,monthly_token_limit,tokens_per_attempt)
VALUES ('TENANT_A', 4, 40000, 800000, 2000)
ON CONFLICT (tenant_code) DO UPDATE SET max_active_steps=EXCLUDED.max_active_steps,
 daily_token_limit=EXCLUDED.daily_token_limit,monthly_token_limit=EXCLUDED.monthly_token_limit,
 tokens_per_attempt=EXCLUDED.tokens_per_attempt;
COMMIT;
```

仅启用月度额度时将 `daily_token_limit` 留空。额度为 0 时暂停新模型调用。停用月度额度时取得相同租户锁，将 `monthly_token_limit` 置空。如果日度额度也为空，同时清空 `tokens_per_attempt`。应用回退前先停用月度策略，保留迁移、日汇总及原始记录。日汇总核对参见 [ADR-0048](0048-model-token-day-accounting.md)。
