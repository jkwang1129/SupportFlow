# ADR-0047 租户每日模型 Token 预留与结算

AI 011 保留 AI 010 的租户并发上限，并增加可选的 `daily_token_limit` 和 `tokens_per_attempt`。以 PostgreSQL UTC 日历日统计。一个租户的 Chat 与 Embedding 共用额度，仍受原 Run 预算和租户并发配额约束。

- 同一租户的模型预留与已知用量写回使用 AI 010 的事务锁。预留新步骤前核对当日用量。额度足够时，同一事务记录 Run 计数、`RESERVED` 步骤和 Token 预留。已提交的步骤通过原检查点复用。
- 有用量回执时，账上占用改为供应商报告的输入与输出 Token 之和。Embedding 只需输入用量。回执和检查点原子提交，清理检查点正文后回执仍然有效。缺用量、超时、取消及租约接管后的未知结果继续保留原预留额度，直到 UTC 当日结束。晚到的回执归属原预留日期。
- 新预留超限时返回 `MODEL_TOKEN_QUOTA_EXCEEDED` 并结束该 Run，新增模型调用和故障重试均为零。若当日后续结算释放额度，可用新事件重新发起。已有不确定写操作继续按原规则处理。

`tokens_per_attempt` 是运维估计的预留量，对同租户的两种请求统一生效。若供应商报告的实际用量超过预留，系统保留完整耗用并按剩余额度判断后续请求。上线前应按已启用的模型和上下文上限配置合适的预留量。文件中的金额估算、供应商账单与本额度独立。当前按索引查询当前租户当日的明细，日内大量小调用下的准入性能与长期归档还需实测。

先完成迁移、账号授权及校验，并升级处理该租户的全部 Worker，再使用运维账号配置策略。既有租户的两列默认留空。开启时仅对后续预留记账。当天关闭再开启仍会计入之前已持久化的当日预留。每日上限可设为 0，暂停该租户的新模型调用。

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('supportflow:model-capacity:TENANT_A', 0));
INSERT INTO public.agent_model_quota(tenant_code,max_active_steps,daily_token_limit,tokens_per_attempt)
VALUES ('TENANT_A', 4, 40000, 2000)
ON CONFLICT (tenant_code) DO UPDATE SET max_active_steps=EXCLUDED.max_active_steps,
 daily_token_limit=EXCLUDED.daily_token_limit,tokens_per_attempt=EXCLUDED.tokens_per_attempt;
COMMIT;
```

回滚时在同一租户锁内将两个 Token 参数同时设为 `NULL`，保留并发配额及历史数据。需要回退应用时先停用受影响租户的 Token 配额，保留已应用迁移。
