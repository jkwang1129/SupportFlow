# 版本化迁移与基线采纳

`manifest.json` 登记 customer_db/billing_db 增量，以及 ai_db/user_db/tenant_db/ops_db 的既有结构基线。SQL 继续引用仓库中已有的唯一执行文件，不复制第二套内容。首次升级会执行可重复的现有增量并登记版本；后续校验 checksum，已应用 SQL 不再重复执行。

```powershell
python -m pip install "psycopg[binary]>=3.1,<4"
python scripts/db-migrate.py migrate
python scripts/db-migrate.py verify
python scripts/db-migrate.py verify --database customer_db
```

从仓库根运行。管理员连接由本机忽略配置中的 `SUPPORTFLOW_DB_HOST/PORT/PASSWORD` 提供，用户名默认 `supportflow`，可用 `SUPPORTFLOW_MIGRATION_DB_USER` 指定独立迁移身份。运行账号不能 apply；`verify` 使用只读事务配置核对历史/checksum，并与 current-schema.json 对照实际结构；不创建表、不重放 SQL，不是业务数据或完整权限审计。缺少历史或有待执行版本会失败。

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

当前六库均有版本计划：客服四条增量、计费一条增量，其余四库各一条基线采纳。AI 与五个 Java 数据服务已有只读 Readiness。统一 init 命令仍属于 PR-7 后续子批次；不能据此宣布整个六库迁移与生产部署已经验收完成。

## 四库基线采纳

AI/User/Tenant/Ops 的 `001` 为 baseline 操作，不执行建表 SQL。JSON 契约位于 baselines/，由隔离 PostgreSQL 17 实际运行原 `db-init.sql` 后读取 PG catalog 生成，附源文件路径与 SHA256。首次采纳要求登记的表、列类型、非空约束、索引定义/有效状态与表约束匹配，额外对象允许存在；缺失或不一致拒绝登记。通过后只写 checksum 历史回执，保留既有数据，不重放非幂等 CREATE TABLE。运行角色不能执行采纳，也不能修改回执。

契约属于不可改写的历史版本。`--write-new-contracts` 只用于首次生成缺失文件，已存在即失败，不能覆盖契约来绕过错误。日常验收运行 `python scripts/test-schema-baselines.py`；该脚本启动独立 PG，检查 35 张表基线和原始种子表行数保留，注入缺表/缺列/类型/索引不兼容，检查失败不写历史。Runner 通用事务、checksum、锁、权限与 CLI 用例见 `python scripts/test-versioned-migrations.py`。

这是首次采纳时的兼容检查。已经应用的 baseline 不会在每次 migrate/verify 重放结构检查；后续 baseline 本身不重放，但 verify 会另以当前 desired-state 契约执行结构漂移检查。现有 AI/Java Readiness 只核最小结构/读取权限，不能替代发布前完整漂移检查。基线不声称 RLS/权限正确，原受限角色和严格隔离验收继续独立运行。

回退需停业务并使用已测试的版本；本批扩展了历史计划，退到仅认识两库的工具后不可用于核对四库。保留四库历史，不能删除行或改 checksum 来退版本。业务结构不因采纳发生改变；数据库内容恢复始终使用独立验证的备份，不自动清库。

## 当前实际结构漂移

`python scripts/db-migrate.py verify` 同时核对六库历史和 current-schema.json 中登记的 **87 张应用表、1 个视图、4 个 supportflow_ 函数**。检查列集合、类型/默认值/非空/identity/generated、索引定义及有效状态、约束、RLS ENABLE/FORCE、策略集合/主体/操作/谓词，以及视图和受控函数定义。额外的未登记表/列/索引/约束/策略/视图/受控函数也会拒绝；不会自动把数据库当前状态登记为正确状态。迁移 manifest 的版本/checksum 必须与 desired-state 契约一致，避免新 SQL 配旧快照。

公开 schema 中的迁移历史表由独立历史/权限检查负责，pgvector 扩展函数不属于 supportflow_ 函数范围。此核验不覆盖所有授权、owner、触发器、扩展版本、业务数据、外部对象或备份；数据库身份/租户权限仍须运行独立安全测试。应用 Readiness 不会逐请求执行本完整检查；部署必须在启动业务前执行 verify，未知策略等漂移会停止发布。

升级入口在配置运行角色前和配置后分别 verify，防止给未知表批量授予 DML。已知旧策略由既有增量替换；遇到额外未知策略时停止，由管理员核查和迁移，不自动删除或信任。完整服务历史库夹具模拟已知旧策略被改宽；独立 RLS 安全测试仍保留额外宽松策略对 restrictive guard 的验证，结构负面测试则确认未知额外策略会被拒绝。

复现 `python scripts/test-schema-drift.py`：六库只读正例、14 个实际 SQL 漂移故障、3 个 catalog 元数据负面例、旧 desired-state/新 manifest 不一致、实际 CLI 失败和修复后通过。测试只操作独立 PG17，事务回滚故障，不修改本机业务库。

合法改表时先增加新迁移，再用 `python scripts/test-schema-drift.py --write-new-contract --output .local/schema-candidate.json` 从隔离初始化/升级生成候选，审查差异后更新 current-schema.json 并跑全部验收。候选文件已存在时拒绝覆盖。不得修改历史 SQL/checksum、关闭检测，或从有未知漂移的真实数据库直接覆盖契约来让测试变绿。
