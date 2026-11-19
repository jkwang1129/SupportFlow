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
