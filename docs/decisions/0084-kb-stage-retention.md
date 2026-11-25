# ADR-0084 闲置租户知识索引暂存清理

知识索引请求失败或响应晚到时，暂存切片可能留在库里。此前只有本租户再次提交索引时才触发清理。本批增加独立维护入口，闲置租户可按明确租户编号定期检查和清理。

Customer 011 将暂存创建时间转换为 `timestamptz`。迁移以原写入使用的客户库会话时区解释已有时间，并保留正文、向量和文档状态。部署时先暂停知识写入，用现有客户库时区配置执行迁移，验证后再恢复写入和开启清理。到期统一以数据库语句开始时间减去 24 小时计算，调用者时区不影响判断。Java 切换也按此时间判断，过期或不足量暂存导致事务回滚。历史正式版本继续使用独立流程治理。

每次最多清理经验证的一个租户下 500 条暂存切片。预览使用只读事务，仅返回候选数。实际清理重新判断期限，并跳过被其它事务锁定的行。并发维护以提交删除数为准，后续批次可补处理跳过项。知识检索与发布继续读取生效表。

数据库管理员给专用登录授予 `customer_db` 的 `CONNECT`、`public` 的 `USAGE` 和 `supportflow_kb_stage_maintenance` 组成员资格。组权限仅允许执行清理函数。使用运维凭证管理配置 `SUPPORTFLOW_KB_STAGE_MAINTENANCE_DSN`，在 `ai-service` 下执行以下命令。调度器按批准的租户清单分别执行，每次记录退出状态和输出计数。

```text
python -m ai.rag.stage_retention --tenant TENANT_A --limit 100
python -m ai.rag.stage_retention --tenant TENANT_A --limit 100 --apply
```

验收执行 `python scripts/test-kb-stage-retention.py`、`python scripts/test-customer-rls.py --with-ai` 和 `python scripts/test-versioned-migrations.py`。停用调度或收回维护组成员资格即可停止定期清理，已应用迁移保持登记。未提交的清理事务可回滚。已提交删除的临时数据只能从仍保留且符合保留策略的备份恢复。生产调度和备份副本保留需按部署环境验收。
