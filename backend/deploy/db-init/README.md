# 数据库初始化

SupportFlow 使用六个 PostgreSQL 数据库。初始化需要 PostgreSQL 17、pgvector 和有建库及安装扩展权限的账号。

| 文件 | 用途 |
| --- | --- |
| `db-init.sql` | 新环境的一次性初始化。从 `postgres` 开始，创建并切换六个库，再执行各库的结构和基础数据。 |
| `ai_db.sql` 至 `user_db.sql` | 各库的独立脚本，也可用于按库检查和部署。 |
| `generate.sh` | 上游导出工具，需要上游的 `schema/` 和 `scripts/migrate-*.sh` 源文件。 |

从仓库根目录运行以下命令。也可使用 [Compose 启动流程](../../../docs/local-development.md#1-初始化数据库)在空卷上自动初始化。

```bash
psql -h 127.0.0.1 -p 5432 -U supportflow -d postgres -f backend/deploy/db-init/db-init.sql
```

默认库名是 `ai_db`、`billing_db`、`customer_db`、`ops_db`、`tenant_db`、`user_db`。初始化包含套餐、平台角色、权限及索引和约束。创建首个管理员见[本地运行指南](../../../docs/local-development.md#4-创建本地平台管理员)。

这组脚本用于全新环境。已有数据库需要应用相应的增量迁移。部署环境应单独为业务连接设置账号和授权，按需要检查 `customer_db` 的 RLS 策略。
