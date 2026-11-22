# ADR-0048 租户模型 Token 日汇总

每个租户的 UTC 日汇总来自原始模型预留和用量回执。已知用量按输入与输出 Token 之和计入。缺失回执时保留原预留，收到回执后按实际用量替换。跨日或跨月到达的回执计入原预留日期。

AI 012 在同一迁移事务中锁定两张来源表的写入，回填既有账目并安装两个插入触发器。后续每笔预留或回执与日汇总原子提交，事务回滚时两者一并撤销。已有回执但后来才导入预留时，直接按已知用量计入。来源表继续保存原始明细，Runtime 账号只读本租户的汇总，触发器不能被该账号直接执行。部署 `verify` 核对表结构和触发器函数，Runtime 就绪检查核对两个触发器处于启用状态。

本批启用日汇总的写入与核验，额度判断继续读取明细。先部署迁移和运行权限，再检查历史账目和新写入的一致性。迁移需要能跨租户读取两张来源表的数据库运维身份。回填持有来源表写锁，应安排在低写入时段并遵守迁移器的锁等待和语句时间上限。失败会回滚整个迁移事务。

回退应用时保留 AI 012、触发器和汇总记录，原版本追加明细时也会写入日汇总。已启用汇总的数据库应通过正规的备份恢复流程迁移数据。仅重放明细时应从原始预留与回执重新聚合，避免同时重放已汇总的账目。

使用受限账号设置目标租户并执行下列只读核对。结果包含来源明细与汇总有差异的日期。

```sql
BEGIN READ ONLY;
SET LOCAL app.tenant_code = 'TENANT_A';
WITH source_days AS (
  SELECT r.tenant_code,r.budget_day,
         sum(coalesce(u.input_tokens::numeric + coalesce(u.chat_output_tokens,0),r.reserved_tokens)) AS expected
  FROM public.agent_model_token_reservation r
  LEFT JOIN public.agent_model_usage u USING(tenant_code,run_id,step_no)
  GROUP BY r.tenant_code,r.budget_day
)
SELECT tenant_code,budget_day,s.expected,d.charged_tokens
FROM source_days s FULL JOIN public.agent_model_token_day d USING(tenant_code,budget_day)
WHERE s.expected IS DISTINCT FROM d.charged_tokens
ORDER BY tenant_code,budget_day;
COMMIT;
```
