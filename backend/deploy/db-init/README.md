# 数据库初始化

SupportFlow 使用六个 PostgreSQL 数据库。初始化需要 PostgreSQL 17、pgvector 和有建库及安装扩展权限的账号。

| 文件 | 用途 |
| --- | --- |
| `db-init.sql` | 新环境的一次性初始化。从 `postgres` 开始，创建并切换六个库，再执行各库的结构和基础数据。 |
| `ai_db.sql` 至 `user_db.sql` | 各库的独立脚本，也可用于按库检查和部署。 |
| `25-runtime-security.sql` | 执行 customer 服务资源目录中的 v31 RLS 与平台权限迁移。 |
| `30-usage-batches.sql` | 客服计费批次账本与租户隔离的待发记录，新建及升级共用。 |
| `40-runtime-roles.sql` | 从环境读取八个独立密码，配置非 owner、非超级用户的运行账号及最小授权。 |

从仓库根目录运行以下命令。也可使用 [Compose 启动流程](../../../docs/local-development.md#1-初始化数据库)在空卷上自动初始化。

```bash
psql -h 127.0.0.1 -p 5432 -U supportflow -d postgres -f backend/deploy/db-init/db-init.sql
```

默认库名是 `ai_db`、`billing_db`、`customer_db`、`ops_db`、`tenant_db`、`user_db`。初始化包含套餐、平台角色、权限及索引和约束。创建首个管理员见[本地运行指南](../../../docs/local-development.md#4-创建本地平台管理员)。

这组脚本用于全新环境。已有数据库需要应用相应的增量迁移。单独执行基线 SQL 不能完成运行安全配置。新建 Compose 空卷会自动应用 v31 和分离账号；已有 Compose 数据库按运行指南执行 `python scripts/upgrade-runtime-security.py`，不得用删除数据卷代替升级。

## 就绪检查与迁移边界

AI 的 `/api/ai/health`、`/api/ai/health/live` 只表示进程存活；直连内部 AI 服务的 `/api/ai/health/ready` 对登记结构和列读取权限执行只读检查，未就绪返回 503。已认证业务请求同样拒绝进入模型。此探针不经公共 Gateway allowlist 开放，运维直接访问内部服务。

运行期不执行 SQL 补结构；旧 SUPPORTFLOW_AI_AUTO_MIGRATE 和 SUPPORTFLOW_AI_SCHEMA_DIR 不再生效。缺表/缺列时先核对目标数据库与本目录真实文件，不能尝试不存在的根级 schema 或 migrate-*.sh，也不能给运行角色增加 DDL 权限。已有数据库不得重新执行含 CREATE TABLE 的基线来修复。完整版本历史/checksum/迁移锁和 Java Readiness 尚在 PR-7 后续批次中，当前脚本不能冒充完整版本化迁移体系。


customer_db/billing_db 已有增量现由 backend/deploy/migrations/manifest.json 登记，原升级入口已接入 checksum/事务/锁 Runner；使用 `python scripts/db-migrate.py verify` 只读核对历史。新建六库基线与其他四库版本采纳尚未完全统一，不得将增量 verify 当成全库 schema 验证。详细执行与失败恢复见 ../migrations/README.md。
