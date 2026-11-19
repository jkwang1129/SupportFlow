# 客服数据库 SQL 资源

本仓库不存在根目录 schema/、scripts/migrate-customer-db.sh 或 scripts/check-schema-copies.sh，不要依据旧路径执行升级。

- 新建六库：backend/deploy/db-init/db-init.sql，仅用于空环境。运行方法见 backend/deploy/db-init/README.md。
- 当前共享 customer_db 的严格租户/平台权限 SQL：本目录 customer_db_runtime_security.sql（v31）。Compose 和 scripts/upgrade-runtime-security.py 均读取这一份文件。
- 渠道/公开头像与运行账号、计费批次增量：backend/deploy/db-init/ 中对应 SQL。既有环境需管理员先备份并停止业务，再按 docs/local-development.md 升级。
- 本目录其余 SQL 是现有 TenantSchemaCatalog 使用的结构资源，不能当作已具有 checksum/顺序管理的全局迁移系统。独立 schema 的 v31 升级仍会拒绝，需要管理员另行验收。

运行账号不执行 DDL。现有客服/计费增量已由 backend/deploy/migrations/manifest.json 和 SQL Runner 登记；六库基线统一、全量结构验证仍在 PR-7 后续批次建设，状态见 docs/agent-engineering-plan.md；不能用重复初始化已有数据库代替迁移。
