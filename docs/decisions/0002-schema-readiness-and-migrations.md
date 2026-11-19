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
