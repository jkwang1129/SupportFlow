# 版本化迁移与基线采纳

`manifest.json` 登记 customer_db/billing_db 增量，以及 ai_db/user_db/tenant_db/ops_db 的既有结构基线。SQL 继续引用仓库中已有的唯一执行文件，不复制第二套内容。首次升级会执行可重复的现有增量并登记版本；后续校验 checksum，已应用 SQL 不再重复执行。

```powershell
python -m pip install "psycopg[binary]>=3.1,<4"
python scripts/db-migrate.py migrate
python scripts/db-migrate.py verify
python scripts/db-migrate.py verify --database customer_db
```

从仓库根运行。管理员连接由本机忽略配置中的 `SUPPORTFLOW_DB_HOST/PORT/PASSWORD` 提供，用户名默认 `supportflow`，可用 `SUPPORTFLOW_MIGRATION_DB_USER` 指定独立迁移身份。运行账号不能 apply；`verify` 只读历史和 checksum，不创建表、不重放 SQL，不等于全量结构/数据验证。缺少历史或有待执行版本会失败。

新建空环境仍由 `backend/deploy/db-init/db-init.sql`/Compose 初始化六库。初始化与增量不是一个动作；首次启动业务前运行 `python scripts/upgrade-runtime-security.py`，它执行版本化增量、独立的运行账号配置，再 verify。旧环境先备份并停业务，使用同一升级命令，不能重跑非幂等基线、删除数据卷或清空迁移历史。

## 保证与失败处理

- 每个数据库一个 session advisory lock，竞争时立即拒绝；锁不会把多个数据库变成同一事务。
- 每条迁移的 SQL 与 checksum 回执同事务提交。失败回滚当前迁移，前面已完成的数据库/版本保留，修正原因后重跑继续；不自动删除用户数据“回滚”。
- 版本必须唯一且有序；历史必须是清单的连续前缀。未知新版本、缺口、已应用 checksum 变化均拒绝，防止默默降级。
- 已登记执行后的 SQL 不得直接改写。需要变更时增加新版本文件/清单项，并更新新建环境的部署入口；不要修改历史 checksum 让检查变绿。换行符规范化，不因 Windows CRLF 改变 checksum。
- role/password provisioning 不进入结构历史，不记录密码。历史仅对本库运行账号开放 SELECT，不授予 INSERT/UPDATE/DELETE；KB、平台和其他业务角色不获得修改权限。
- 只支持清单内后端 SQL 和受控的数据库段；拒绝任意 psql include、变量执行和非后端 SQL 文件。原 SQL 的单层 BEGIN/COMMIT 由 Runner 的事务替代。

验收：`python scripts/test-versioned-migrations.py` 使用独立 PostgreSQL 17，验证原文件引用保留、首次升级、重复执行、只读 verify、SQL 失败回滚、checksum 篡改、历史缺口、锁竞争、外层事务误用、运行身份拒绝及 CLI。真实服务验收额外检查新旧库启动、迁移历史与最小权限。

## 尚未覆盖

当前六库均有版本计划：客服四条增量、计费一条增量，其余四库各一条基线采纳。AI 与五个 Java 数据服务已有只读 Readiness。全量持续类型/索引/RLS 漂移检测与统一 init 命令仍属于 PR-7 后续子批次；不能据此宣布整个六库迁移与生产部署已经验收完成。

## 四库基线采纳

AI/User/Tenant/Ops 的 `001` 为 baseline 操作，不执行建表 SQL。JSON 契约位于 baselines/，由隔离 PostgreSQL 17 实际运行原 `db-init.sql` 后读取 PG catalog 生成，附源文件路径与 SHA256。首次采纳要求登记的表、列类型、非空约束、索引定义/有效状态与表约束匹配，额外对象允许存在；缺失或不一致拒绝登记。通过后只写 checksum 历史回执，保留既有数据，不重放非幂等 CREATE TABLE。运行角色不能执行采纳，也不能修改回执。

契约属于不可改写的历史版本。`--write-new-contracts` 只用于首次生成缺失文件，已存在即失败，不能覆盖契约来绕过错误。日常验收运行 `python scripts/test-schema-baselines.py`；该脚本启动独立 PG，检查 35 张表基线和原始种子表行数保留，注入缺表/缺列/类型/索引不兼容，检查失败不写历史。Runner 通用事务、checksum、锁、权限与 CLI 用例见 `python scripts/test-versioned-migrations.py`。

这是首次采纳时的兼容检查。已经应用的 baseline 不会在每次 migrate/verify 重放结构检查；后续 verify 仍只核历史/checksum。现有 AI/Java Readiness 只核最小结构/读取权限，均不能替代后续完整漂移检查。基线不声称 RLS/权限正确，原受限角色和严格隔离验收继续独立运行。

回退需停业务并使用已测试的版本；本批扩展了历史计划，退到仅认识两库的工具后不可用于核对四库。保留四库历史，不能删除行或改 checksum 来退版本。业务结构不因采纳发生改变；数据库内容恢复始终使用独立验证的备份，不自动清库。
