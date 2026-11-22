# ADR-0050 租户模型金额预留与结算

AI 014 支持按租户配置 UTC 日度和月度估算金额上限。两种金额上限可独立或同时启用，也可与日度、月度 Token 额度合用。金额与费率采用整数计算。预算每个货币单位为 10¹² 个 pico，价目里的每百万 Token 价格以百万分之一货币单位填写。这个输入单位在计算时等于每 Token 的 pico 数。

Runtime 在同一租户锁内校验现用价目和额度，再原子写入模型步骤、Token 预留和金额预留。Chat 用单次 Token 预留乘输入和输出费率中的较高值，Embedding 只使用输入费率。账目保存本次使用的币种、价目版本、文件内容的 SHA-256 和费率。供应商报告用量后，按预留时的费率结算到原 UTC 日期。超时、取消或缺少回执时保留原金额。供应商报告的用量超过预留时完整记账，并用于后续准入。

价目无效、租户或模型配置不匹配、Chat 缺输出费率、当月已有其他币种账目或当前预算不足时，当前 Run 在发送模型请求前结束。费用估算与真实账单各自保留自己的来源。新金额策略从启用后的预留开始计价。以前未定价的 Token 预留继续保存原用量。已有同币种金额记录纳入该期额度。

先执行 migrate、provision、verify，并升级处理目标租户的全部 Worker。以下示例的月度额度为 10 USD，单次预留 2,000 Token。示例费率为合成值。将模型哈希替换为对应 `ChatProfile` 或 `EmbeddingProfile` 的 `definition_hash()`，并按实际模型合同填写价目。需要日度额度时同时设置 `daily_cost_limit_picos`。

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('supportflow:model-capacity:TENANT_A', 0));
INSERT INTO public.agent_model_quota(tenant_code,max_active_steps,tokens_per_attempt,monthly_cost_limit_picos,price_book)
VALUES ('TENANT_A',4,2000,10000000000000,$price$
{"schema_version":"runtime-price-book-v1","tenant":"TENANT_A","version":"example-v1","currency":"USD",
 "rates":{"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":
   {"input_micros_per_million":2000000,"output_micros_per_million":7000000}}}
$price$)
ON CONFLICT (tenant_code) DO UPDATE SET tokens_per_attempt=EXCLUDED.tokens_per_attempt,
 monthly_cost_limit_picos=EXCLUDED.monthly_cost_limit_picos,price_book=EXCLUDED.price_book;
COMMIT;
```

同币种更新费率可在相同租户锁内进行。在途调用使用保存的费率。该租户当前 UTC 月已有记账时，新配置须使用同一币种。金额上限为 0 时，只允许通过价目明确标为免费的调用，Token 与并发额度仍参与准入。

查看一个任务的估算明细。`charged_picos` 在缺回执时等于预留金额。Runtime 账号只读本租户的配置和日汇总，模型步骤的历史预留只允许追加。

```sql
BEGIN READ ONLY;
SET LOCAL app.tenant_code = 'TENANT_A';
SELECT r.step_no,r.budget_day,r.currency,r.price_book_version,r.price_book_sha256,r.reserved_cost_picos,
       u.input_tokens,u.chat_output_tokens,
       coalesce(u.input_tokens::numeric*r.input_rate + coalesce(u.chat_output_tokens,0)::numeric*coalesce(r.output_rate,0),
                r.reserved_cost_picos) AS charged_picos
FROM public.agent_model_token_reservation r
LEFT JOIN public.agent_model_usage u USING (tenant_code,run_id,step_no)
WHERE r.run_id='00000000-0000-0000-0000-000000000000'::uuid AND r.currency IS NOT NULL
ORDER BY r.step_no;
COMMIT;
```

回退应用前，在租户锁内清空两个金额上限和 `price_book`。如果两个 Token 上限也为空，同时清空 `tokens_per_attempt`。保留原始账目、汇总及 AI 014 迁移。迁移会变更额度表、预留表的列和约束，应安排在低写入时段，遵守现有迁移锁和执行超时。
