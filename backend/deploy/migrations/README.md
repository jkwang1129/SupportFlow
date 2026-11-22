# 版本化迁移与基线采纳

`manifest.json` 登记 customer_db/billing_db 增量，以及 ai_db/user_db/tenant_db/ops_db 的既有结构基线。SQL 继续引用仓库中已有的唯一执行文件，不复制第二套内容。首次升级会执行可重复的现有增量并登记版本；后续校验 checksum，已应用 SQL 不再重复执行。

```powershell
python -m pip install "psycopg[binary]>=3.1,<4"
python scripts/db-migrate.py init  # 仅用于六个应用库全部不存在的空 PostgreSQL
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

当前六库均有版本计划。客服九条增量，计费一条增量，AI 一条基线及 002～007 增量，User/Tenant/Ops 各一条基线采纳。AI 与五个 Java 数据服务具备只读 Readiness。统一 init/migrate/verify 已通过本地验收，备份恢复、生产部署与运维实测归阶段 E。

## 四库基线采纳

AI/User/Tenant/Ops 的 `001` 为 baseline 操作，不执行建表 SQL。JSON 契约位于 baselines/，由隔离 PostgreSQL 17 实际运行原 `db-init.sql` 后读取 PG catalog 生成，附源文件路径与 SHA256。首次采纳要求登记的表、列类型、非空约束、索引定义/有效状态与表约束匹配，额外对象允许存在；缺失或不一致拒绝登记。通过后只写 checksum 历史回执，保留既有数据，不重放非幂等 CREATE TABLE。运行角色不能执行采纳，也不能修改回执。

契约属于不可改写的历史版本。`--write-new-contracts` 只用于首次生成缺失文件，已存在即失败，不能覆盖契约来绕过错误。日常验收运行 `python scripts/test-schema-baselines.py`；该脚本启动独立 PG，检查 35 张表基线和原始种子表行数保留，注入缺表/缺列/类型/索引不兼容，检查失败不写历史。Runner 通用事务、checksum、锁、权限与 CLI 用例见 `python scripts/test-versioned-migrations.py`。

这是首次采纳时的兼容检查。已经应用的 baseline 不会在每次 migrate/verify 重放结构检查；后续 baseline 本身不重放，但 verify 会另以当前 desired-state 契约执行结构漂移检查。现有 AI/Java Readiness 只核最小结构/读取权限，不能替代发布前完整漂移检查。基线不声称 RLS/权限正确，原受限角色和严格隔离验收继续独立运行。

回退需停业务并使用已测试的版本；本批扩展了历史计划，退到仅认识两库的工具后不可用于核对四库。保留四库历史，不能删除行或改 checksum 来退版本。业务结构不因采纳发生改变；数据库内容恢复始终使用独立验证的备份，不自动清库。

## 当前实际结构漂移

`python scripts/db-migrate.py verify` 同时核对六库历史和 current-schema.json 中登记的 **97 张应用表、1 个视图、5 个 supportflow_ 函数**。检查列集合、类型/默认值/非空/identity/generated、索引定义及有效状态、约束、RLS ENABLE/FORCE、策略集合/主体/操作/谓词，以及视图和受控函数定义。额外的未登记表/列/索引/约束/策略/视图/受控函数也会拒绝。迁移 manifest 的版本和 checksum 必须与结构契约一致。

公开 schema 中的迁移历史表由独立历史/权限检查负责，pgvector 扩展函数不属于 supportflow_ 函数范围。此核验不覆盖所有授权、owner、触发器、扩展版本、业务数据、外部对象或备份；数据库身份/租户权限仍须运行独立安全测试。应用 Readiness 不会逐请求执行本完整检查；部署必须在启动业务前执行 verify，未知策略等漂移会停止发布。

升级入口在配置运行角色前和配置后分别 verify，防止给未知表批量授予 DML。已知旧策略由既有增量替换；遇到额外未知策略时停止，由管理员核查和迁移，不自动删除或信任。完整服务历史库夹具模拟已知旧策略被改宽；独立 RLS 安全测试仍保留额外宽松策略对 restrictive guard 的验证，结构负面测试则确认未知额外策略会被拒绝。

复现 `python scripts/test-schema-drift.py`：六库只读正例、14 个实际 SQL 漂移故障、3 个 catalog 元数据负面例、旧 desired-state/新 manifest 不一致、实际 CLI 失败和修复后通过。测试只操作独立 PG17，事务回滚故障，不修改本机业务库。

合法改表时先增加新迁移，再用 `python scripts/test-schema-drift.py --write-new-contract --output .local/schema-candidate.json` 从隔离初始化/升级生成候选，审查差异后更新 current-schema.json 并跑全部验收。候选文件已存在时拒绝覆盖。不得修改历史 SQL/checksum、关闭检测，或从有未知漂移的真实数据库直接覆盖契约来让测试变绿。

## 统一 init / migrate / verify

`init` 复用原 db-init.sql 的六个数据库段，以受控数据库名创建库，不执行旧 psql 建库前导。管理员必须有 CREATEDB 和安装 pgvector 等所需权限；运行身份不能初始化。全局 session advisory lock 避免并发初始化。只要六个目标库任意一个已存在，即在创建/改表前拒绝，不能覆盖已有或部分完成环境。`--database` 仅供 migrate/verify，init 必须初始化全部六库。

每库基线 DDL 和种子数据在独立事务中完成，然后使用相同 manifest migrate 和只读结构 verify。CREATE DATABASE 和多个数据库不构成一个事务；失败库基线回滚、前序完成库保留，不自动 DROP DATABASE。部分完成状态会明确报错并拒绝再次 init；管理员必须核对状态，恢复到独立干净环境重新初始化或制定受审查的恢复步骤，不能靠删除已有业务库/卷重试。已有环境只用 migrate/verify。

init 只承担结构和版本，不配置运行密码，结果明确标记 NOT_PROVISIONED_BY_INIT。运行角色/密码 provisioning 仍使用唯一的 40-runtime-roles.sql，不进入迁移历史。默认本地 Compose 已自动创建六库，应继续使用本地 Compose 专用 upgrade-runtime-security.py 完成版本登记/角色配置/验证，不要在已有 Compose 上再次 init。独立空 PostgreSQL 使用 init 后，管理员通过 psql 在同一目标服务器执行该角色脚本，再 verify 后启动应用；不能把本地 Compose 专用升级助手用于其他远端服务器。应用进程仍只获得各自受限凭据。

`python scripts/test-database-init.py` 验证真实 CLI 空库初始化、97 张表核验、重复初始化拒绝、无关数据与种子保留、锁竞争、无 CREATEDB 运行身份拒绝及中途 SQL 失败回滚。`python scripts/test-security-flow.py` 另验证默认新库、历史库升级、空服务器经 CLI init 三套真实 Java/Python 服务与受限账号业务闭环，所有数据均为隔离合成验收。

## AI 002：Runtime 存储底座

AI 001 是原 8 张表的历史基线，保持不变；002 新增 agent_run/agent_step 与受控队列发现函数。先 migrate，再通过唯一 40-runtime-roles.sql 给既有 AI 登录账号授予 NOLOGIN 能力组并收回删除/清空权限，最后 verify。该底座尚未接现有客服入口；不得因表已创建就切换任务路由。

未知写步骤恢复/取消进入 SIDE_EFFECT_UNKNOWN 并阻断同会话后续任务，等待后续显式核验能力。回退时保留 Run/Step/历史，不删除表或改已应用 checksum；首批可停用新模块，原业务 Workflow 不受影响。细节见 docs/decisions/0003-durable-runtime-boundary.md 与 docs/reliability-verification.md。

## Customer 005：Agent 源端事件存储

新增agent_event_outbox，保存业务事件/消息/会话/主体引用和Trace，不复制消息正文或登录凭据。必须先migrate，再使用唯一40-runtime-roles.sql收回新表DELETE/TRUNCATE，verify后启动。Java AgentEventOutbox要求可信租户在事务前绑定，支持原事件重发、投递租约/fence、按会话顺序领取、接收回执校验、8次尝试上限和HELD保留。

已有存储API、显式单次Dispatcher和Java→真实AI接收验收，尚无SessionService自动登记或调度，不能据此开启新业务触发。DELIVERED表示AI已经持久化接收，不表示任务成功。HELD阻断同会话后续投递，不能直接清表、跳过或换eventId重发。回滚保留表/历史/外键和已有事件，待兼容版本接管。

## Customer 006：不可变来源快照

新增agent_source_snapshot，源端Outbox增加event+Run唯一约束，使快照外键绑定已交付回执。仅保存当前文本及必要业务引用，文本与可信资源引用分区；有明确UTF-8/序列化大小和数据库时钟TTL。先migrate，再以唯一40-runtime-roles.sql保证运行账号仅SELECT/INSERT、无UPDATE/DELETE/TRUNCATE，最后verify；不能修改已应用的005或其他SQL来追加新表。

存在快照也要重新核验当前消息/会话/主体/Run回执；人工接手、结束、删除、隐藏或来源变更均拒绝。过期不原地刷新。已有默认关闭的来源API和Python加载器及真实双向联调；启用`SUPPORTFLOW_BOT_RUNTIME_SOURCE_ENABLED=true`只开放受服务鉴权的快照读/建，不启用Worker或自动消息路由。AI的customer_runtime_url配置为该customer服务固定地址，使用这一对服务已有的独立CUSTOMER_SECRET及新audience，不使用JWT。TTL不等于已完成数据清理，M12仍需保留/归档策略。回滚停用组件并保留表/历史；不清表、不改旧checksum。设计见docs/decisions/0004-source-snapshot-boundary.md。

## AI 003 / 004：持久化正文与终态回复核销

003增加agent_payload，正文与MODEL/READ/INTERNAL Step成功在同一事务内提交，运行账号只SELECT/INSERT；有Hash/大小/TTL，WRITE结果仍以客户业务回执为准。004增加同租户Run/Step复合外键约束的agent_reply_reconciliation；AI登录账号/Runtime能力组只SELECT/INSERT，无UPDATE/DELETE/TRUNCATE。AI Runtime readiness核对两表结构和权限。必须migrate→唯一40-runtime-roles.sql provisioning→verify，默认关闭的Runtime入口不因迁移而自动开启。

ReplyReconciler为显式可信宿主控制面，无公开路由/自动轮询/模型工具。只读一次客户回复回执，严格绑定原tenant/event/run/snapshot/参数Hash；确认既成提交后，短事务追加不可改核销记录、确认原WRITE、把Run错误从SIDE_EFFECT_UNKNOWN改为SIDE_EFFECT_CONFIRMED。Run保持FAILED，取消/完成时间/调用预算不变，无新租约、不重发消息。原未知错误/Step结果、观察fence、operator_ref/消息引用/DB确认时间保留在账本。找不到回执或查询失败继续阻断后续同会话任务，不当成可以重做的证据。

003→004升级保留已有Run/Step/预算及正文/Hash/有效期；旧002→003升级用例仍保留。候选schema从隔离PG生成审查，只增加新表与004版本；该批次六库94张表，当前结构见上文。回滚停用核销组件，保留账本和历史；不删迁移、不撤销已确认业务提交、不恢复旧执行权。不能将SIDE_EFFECT_CONFIRMED解读为整项任务成功或外部渠道送达。设计与适用边界见docs/decisions/0014-terminal-reply-reconciliation.md。

## Customer 008：知识发布状态列的最小读取权限

新版访客RAG仅检索status=3且index_status=3的文档，原KB能力组只可读id/tenant/title/status/is_deleted，不能直接使用新谓词。008只追加index_status列SELECT，校验KB能力组NOLOGIN/非owner/无管理员权限；不授予整表SELECT、正文读取或文档修改，不更改RLS。角色provisioning保留能力组授权，无需复制第二份GRANT。AI只读readiness要求该列可读，必须migrate→provision→verify后启动；不能编辑旧003/checksum或让AI改用管理员绕过。

007→008测试保留原业务来源/快照/消息、草稿文档/切片精确不变，确认新增一列读取且正文和修改仍拒绝；3种不安全能力组状态拒绝。008不建表，引入时为94表/1视图/5函数。回滚停止访客知识答复或转人工，保留历史/数据和最小列授权，不回退到允许草稿答复；完整知识版本/ACL及默认Runtime业务切换仍另行验收。

## Customer 009：内部工单创建回执存储

新增agent_ticket_receipt，以tenant/run主键及tenant/event、tenant/ticket唯一约束记录同一次建单；复合外键绑定同租户不可变来源快照和ticket。ticket增加tenant/id唯一引用，保留原全局ID主键，不改已有行。回执仅保存参数Hash、操作/协议/Step/fence、工单引用和DB记录时间，不含正文或模型凭据；created_at不是精确事务提交时刻。能力组必须NOLOGIN/非owner/无管理员属性或角色继承；新表FORCE RLS，空租户/PLATFORM不放行，仅customer能力组SELECT/INSERT。唯一40-runtime-roles.sql重复provision后仍无UPDATE/DELETE/TRUNCATE。

遵循migrate→provision→verify；必须增加009，不改旧007或历史checksum。`python scripts/test-ticket-receipts.py`用隔离PG17/实际运行身份核约束、跨租户与其他角色拒绝、事务回滚、八路重复元数据插入单一回执、宽松permissive策略不绕过restrictive guard、八类不安全能力组及重复provision保留数据/权限。`test-versioned-migrations.py`保留008→009前后已有工单、来源、消息和知识全部字段；结构契约增至95表。

本子批次只有存储层，没有新Agent建单业务方法、端点或模型WRITE工具。FK/Hash不能代替主体/会话/客户关联、来源TTL、参数正文或AI写许可核验。后续必须复用TicketService在同一事务创建业务行/事件并写回执，再接API和未知结果查询；当前不能宣称Agent端到端幂等建单已经完成。回滚停用后续新调用，保留已提交工单/回执和009历史，不清数据或恢复无回执重试。见ADR-0022与reliability-verification。

## AI 005 工单写入核销账本

`agent_ticket_reconciliation` 按租户和任务记录确认工单已提交的审计事实，以复合外键绑定同租户执行步骤。每个工单在租户内最多核销一次。记录固定操作和版本、原参数摘要、工单引用、确认主体及原步骤结果。`confirmed_at` 表示确认时间，工单业务提交以客服服务的持久化回执为准。

按 migrate → provision → verify 升级。启用 Runtime 后，Readiness 检查表、列、强制行级隔离和不可变权限。运行账号只允许 SELECT 和 INSERT。004 → 005 的测试逐项比对已有租约、步骤、正文和已确认的回复核销记录。回滚时停用核销调用并保留 005 和现有审计记录。设计见 [ADR-0028](../../../docs/decisions/0028-ticket-reconciliation-ledger.md)。


## AI 006 限时审批存储

`agent_approval` 保存内部工单的主体、用途、版本、来源快照、参数摘要和有效期。运行账号可读、可追加，只有决定、关闭和消费列可更新。等待审批的任务在数据库中必须释放所有租约字段。现有的同会话顺序约束继续适用。005 → 006 升级验证逐字段保留历史任务、正文和两类核销记录。应用层暂停、恢复和开始执行的权限检查分别按 ADR-0030 交付。

## AI 007 审批到期调度

沿用 Runtime 的受限租户发现函数，将过期且未消费的审批加入到期队列。Worker 按租户最多处理 16 条到期记录，通过行锁与跳锁避免互相等待，保留原决定、原预算和未知写阻断。升级保留已有暂停任务和审批数据。回滚时要保留 007 历史和现有记录，并由可信宿主显式处理剩余到期审批。

## AI 008 按请求号定位任务

008 只添加 `agent_run_trace_lookup_idx`。索引前两列匹配租户和请求号，后两列用于取最新的候选任务。沿用原账号、表权限和 RLS。建议在低写入窗口依序执行 migrate、provision、verify。索引创建使用迁移器已有的 2 秒锁等待和 30 秒语句上限，达到上限时事务回滚并可重试。已经应用后，应用回退应保留索引和迁移历史。

在配置受限 Runtime 数据库账号的 AI 服务目录运行 `python -m ai.runtime.trace_export --tenant TENANT_A --trace-id REQUEST_ID`。每次精确匹配一个请求号，最多列出最新的 20 个任务并标记是否截断。找到任务后用同一租户的 `--run RUN_UUID` 导出步骤。请求号只用于查找，操作员需已有目标租户的授权，此入口为运维命令行。

## AI 009 模型用量回执

009 新增 `agent_model_usage`，以复合外键关联原租户、任务和模型步骤。回执和完成步骤同事务提交，检查点清理后继续保留已知 Token。按 migrate、provision、verify 升级，原记录逐字段保留。Runtime 就绪门禁要求新表和受限权限。应用版本回退时保留 009 和现有回执。详见 [ADR-0044](../../../docs/decisions/0044-durable-model-usage-receipts.md)。
