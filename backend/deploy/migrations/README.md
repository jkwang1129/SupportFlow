# 已登记的增量迁移

`manifest.json` 是目前 customer_db/billing_db 增量的版本清单。SQL 继续引用仓库中已有的唯一执行文件，不复制第二套内容。首次升级会执行可重复的现有增量并登记版本；后续校验 checksum，已应用 SQL 不再重复执行。

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

当前增量仅登记 customer_db/billing_db 的现有五个数据库迁移单元。其他四库基线版本采纳、全量类型/索引/RLS 漂移检测、统一 init 命令、Java Readiness 仍属于 PR-7 后续子批次；不能据此宣布整个六库迁移与生产部署已经验收完成。
