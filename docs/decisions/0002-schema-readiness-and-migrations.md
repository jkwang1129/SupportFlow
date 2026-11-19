# ADR-0002：只读 Readiness 与版本化迁移边界

日期：2026-11-19。PR-7 分批实施，不把当前探针当成完整迁移系统。

## 决策

1. 应用运行账号不执行 DDL。移除 AI 根据旧 AUTO_MIGRATE/SCHEMA_DIR 寻找 SQL 并自动执行的逻辑，这两个旧配置不能恢复自动升级。
2. 存活、依赖就绪分离。`/api/ai/health` 保持兼容的存活检查，新增 `/live` 与 `/ready`。就绪探针只返回 UP/DOWN，数据库结构名和 DSN 不进入公开响应。已通过内部认证的业务请求仍必须通过 schema gate，失败不进入模型。
3. 当前检查覆盖 AI 的 8 张关键表、登记的关键列、KB 所需结构及列读取权限。使用受限 AI/KB 账号读取 metadata，连接与语句各有 2 秒上限，在线程池中执行，不阻塞事件循环。检查不读取租户业务行。
4. 先保留已有 SQL 初始化和安全升级入口；后续以按数据库登记版本、源文件 checksum、事务和迁移锁的 SQL Runner 统一增量。角色/密钥配置作为显式 provisioning，不把密码写进迁移历史，无需再引入一套 ORM 或微服务。

## 实施状态与限制

本子批次只完成 AI 只读检查和拒绝接流量、修正失效路径。Java Readiness、完整 schema/types/index/RLS 验证、迁移版本历史、checksum、迁移锁与全量 init/migrate/verify 命令仍待下一批，PR-7 不因此关闭。

现有运行身份启动防护保持不变：危险身份或启动时关键连接不可用仍可能阻止进程启动，不能宣称这种情况下存活探针也已经可用。进程启动后的 schema/依赖异常由 Ready 与业务 gate 拒绝处理。每次受保护业务请求会读取 metadata，本轮未测量吞吐/延迟影响，后续需结合实测决定缓存与探测频率。

## 回滚

无数据库迁移。同步回退 AI gate/探针/测试即可，但会恢复结构未就绪仍接流量的行为；不得通过恢复自动 DDL 或给运行角色加迁移权限回退。健康检查部署配置要明确 `/health` 只是存活，不可作为可接流量的依据。

## 后续子批次 2

customer_db/billing_db 增量现已进入 manifest + checksum/事务/锁 Runner，接入原升级入口和实际服务验收，详见 `backend/deploy/migrations/README.md`。这是后续增量，不改变上述只读 Readiness 子批次的验证范围；Java、其他四库基线及全量 schema 漂移检查仍待后续实施。

## 后续子批次 3：Java 就绪闸门

复用现有 Actuator，公共 JDBC 后置自动配置为五个数据服务注册 schema HealthIndicator 与 Servlet gate；不为无数据库的 Gateway/Realtime 增加虚假 schema 检查。使用每服务最小表/列契约，查询 PG catalog 并检查 schema USAGE 和列 SELECT 权限。表在 information_schema 可见并不能证明可读，因此不将 INSERT/UPDATE 当读取权限。TenantSchemaGuard 保留启动诊断，但委托同一检查器，删除重复的 SchemaMapper 和失效的根目录/迁移脚本提示。

Liveness 不依赖数据库；Readiness 与业务 gate 均不缓存，故障修复后自动恢复。查询设置 2 秒 timeout，连接池 timeout 继承现有配置；该值不是所有网络故障下的绝对端到端上限。未测量元数据查询的性能影响，不声称全量 schema/index/RLS 漂移检测或所有下游依赖检查。故障响应只提供状态和通用提示，不含对象名/DSN/SQL/密钥。

无需数据库 DDL。需要回退时同步回退公共自动配置、服务契约、探针分组和流量闸门，并使用前一版兼容的探针配置；会恢复结构错误时仍接流量的风险，不能提高数据库身份权限回退。PR-7 尚缺其他四库基线、统一 init 与完整漂移核验。

## 后续子批次 4：四库基线采纳

不复制四套建表 SQL，也不重放已经存在的表。以隔离 PG17 执行原 init 后的实际 catalog 生成不可变 JSON 契约，记录源 SQL SHA256。首次采纳核对 35 张表的列/类型/非空、约束与索引有效定义，成功只登记历史；不兼容不登记。允许额外对象，支持已有环境的后续扩展。该操作沿用同一个 Runner、管理员、advisory lock、事务回执和 checksum 语义，不引入第二套迁移系统。

四库各一条 001 baseline，与原 customer/billing 五条 SQL 历史并存；角色 provisioning 对六库历史统一限制为本库运行角色 SELECT，不能因 ALL TABLES 批量授权使历史可写。后续结构演进应添加新 SQL 版本，不改 baseline。首次采纳不证明持续漂移/RLS/权限安全，原安全回归仍独立执行。后续 verify 不重放 baseline catalog 检查，以免未来合法迁移删除旧列后被历史基线阻断；完整漂移验收属于下一子批次。

无业务 DDL/数据修改，保留历史记录回退工具版本；不能删除迁移记录让旧工具“兼容”。新旧库升级及完整服务验收必须包含所有六库历史权限。

## 后续子批次 5：发布前实际结构漂移

历史 checksum 只能证明执行过哪个源文件，不能证明当前表/索引/策略没有被改写。新增 current-schema.json desired-state 契约：从隔离 PG 执行权威 init 和全部已登记迁移后读取 catalog，覆盖六库 87 张表、1 视图、4 个 supportflow_ 函数。复用 baseline 的 catalog 读取，补充默认值/identity/generated、RLS flags/策略和视图/函数。期望结构与迁移 manifest 的版本/checksum 绑定，额外未知对象/策略默认拒绝。

CLI verify 与升级入口使用只读连接，设置语句 5 秒/锁 2 秒 timeout；角色批量 DML 配置前后都验证，不能顺手给未知表授予运行权限。旧 baseline 仍保持不可变历史；desired-state 是当前版本的可审查状态，只能随合法新迁移演进，不能从漂移库覆盖。运行期 Readiness 保留最小查询，不增加完整结构快照的逐请求成本。

完整服务历史夹具改为模拟已知旧策略被改宽，由原迁移替换；未知额外策略在新的真实 SQL 负面集里必须使 verify 失败。独立 P0 RLS 回归仍保留“额外 permissive 策略不能突破 restrictive guard”，没有撤销安全负面覆盖。

无业务数据/DDL 变更。回退工具/契约应保持同版本；不能修改历史 checksum 或关闭 RLS 回退。此检查不代替 owner/grants/触发器/扩展版本/业务数据/备份审计，安全验收和生产运维仍有独立职责。统一 init 留下一子批次。
