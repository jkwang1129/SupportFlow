# 本地运行

准备 JDK 25、Maven 3.9、Python 3.11 或更高版本、Node.js 22 或更高版本，以及 PostgreSQL 17 + pgvector。以下命令从仓库根目录开始，使用 PowerShell。Linux 或 macOS 下可改用 `export` 设置环境变量、用 `.venv/bin/python` 代替 Windows 的虚拟环境解释器。

## 1. 初始化数据库

使用 Docker Compose 时，先生成本机配置。脚本只补充缺失项，保留已有值；根目录 `.env` 和 AI 的 `.env` 均不提交到 Git。

```powershell
python -m pip install "psycopg[binary]>=3.1,<4"
python scripts/setup-local-env.py
docker compose up -d --wait postgres
python scripts/upgrade-runtime-security.py
```

新建空数据卷会依次执行六库基线、渠道查询/公开头像函数、v31 严格 RLS、计费批次账本/待发记录、运行账号配置。`supportflow` 仅用于建库与迁移，表仍由它持有；Java 各服务使用 `supportflow_user/tenant/customer/billing/ops`，AI 使用 `supportflow_ai`，知识检索使用 `supportflow_kb`，平台限定操作使用 `supportflow_customer_platform`。八个运行账号都不是 owner、超级用户或 BYPASSRLS；不能建库、建角色或执行表 DDL。账号仅能连接自己的数据库，customer/KB/平台三者分别获得不同权限。不要手动把管理员账号填到应用连接配置中；客服和 AI 会拒绝危险的运行身份。

已有数据卷不要删除。先按下文“已有数据库升级”处理，不能重新生成一个数据库管理员密码来替代原密码。外部 PostgreSQL 安装需要先完成基线和增量表结构，再由管理员执行相同权限迁移。[数据库初始化说明](../backend/deploy/db-init/README.md)

## 2. 启动 Java 服务

让 `JAVA_HOME` 指向 JDK 25，确认 `mvn -v` 显示 Java 25。启动脚本自动读取根目录 `.env`，并只向子进程分发各自需要的数据库与服务签名凭据，不传建库密码。

客服服务验证三个 `SUPPORTFLOW_CUSTOMER_*_SECRET` 密钥。实时、运营、租户服务分别只使用自己的密钥；这些密钥与 AI 签名密钥及 JWT 密钥独立。部署时同步更新客服服务和三个调用方，进程间使用内部网络和加密传输。

平台的数据库结构升级使用独立的 `SUPPORTFLOW_CUSTOMER_MIGRATION_USER` 和 `SUPPORTFLOW_CUSTOMER_MIGRATION_PASSWORD`，由部署管理员配置。升级账号必须与 `spring.datasource.username` 指定的业务账号不同；未配置时，平台的结构升级操作会拒绝执行，不会借用业务连接池提升权限。普通客服流程不需要迁移凭据。

```powershell
mvn -B -f backend/pom.xml clean package
python scripts/run-backend.py
```

脚本在同一终端管理七个 Java 进程，并把各服务日志写入 `.local/logs/`。用 `Ctrl+C` 停止这次启动的进程。服务端口为 `9090` 到 `9096`，见[后端说明](../backend/README.md)。

如果 `9090` 已被占用，在启动 Java 服务的终端设置 `$env:SUPPORTFLOW_GATEWAY_PORT = '19090'`。启动控制台时设置 `$env:VITE_PROXY_TARGET = 'http://127.0.0.1:19090'`，启动官网预览时设置 `$env:SUPPORTFLOW_API_BASE = 'http://127.0.0.1:19090/api'`。

文件上传和下载需要 S3 兼容存储。在启动 Java 前可运行 `docker compose up -d rustfs`，本地 API 默认为 `127.0.0.1:9000`，客服服务会创建 `supportflow` 存储桶。切换到自己的存储服务时，使用 `SUPPORTFLOW_RUSTFS_ENDPOINT`、`SUPPORTFLOW_RUSTFS_PORT`、`SUPPORTFLOW_RUSTFS_ACCESS_KEY`、`SUPPORTFLOW_RUSTFS_SECRET_KEY`、`SUPPORTFLOW_RUSTFS_BUCKET`、`SUPPORTFLOW_RUSTFS_SECURE`。[RustFS 容器说明](https://docs.rustfs.com/en/installation/container)

## 3. 启动 Python AI 服务

在根目录的新终端运行下列命令。启动脚本读取根目录的运行凭据，为 AI 和 KB 分别生成受限账号连接串，同时保留 `ai-service/.env` 的模型供应商配置。

```powershell
python -m venv ai-service/.venv
.\ai-service\.venv\Scripts\python -m pip install -r ai-service/requirements.txt -r ai-service/requirements-ai.txt
if (-not (Test-Path ai-service/.env)) { Copy-Item ai-service/.env.example ai-service/.env }
.\ai-service\.venv\Scripts\python scripts/run-ai.py
```

联调真实模型时，在 `ai-service/.env` 填写供应商密钥，并把 `SUPPORTFLOW_AI_LLM_DEFAULT_PROVIDER` 设置为 `qwen` 或 `deepseek`。仅做启动检查时可只安装 `requirements.txt`，用 `/api/ai/health` 探活。供应商调用和文件解析依赖见 [AI 服务说明](../ai-service/README.md)。

AI 服务的四个签名密钥必须各自不同，每个至少 32 字节。网关、客服、运营和租户服务分别使用自己的密钥，AI 服务验证全部四个；这些值和模型供应商密钥、登录 JWT 签名密钥分开。缺少签名密钥时探活仍可用，其他 AI 请求会被拒绝。部署时按服务单独分发密钥，并限制 9100 端口只允许内部访问。

## 4. 创建本地平台管理员

在保留数据库环境变量的终端里，从根目录执行。脚本会提示输入密码，将 BCrypt 哈希和平台管理员角色绑定写入 `user_db`。

```powershell
.\ai-service\.venv\Scripts\python -m pip install bcrypt
.\ai-service\.venv\Scripts\python scripts/create-platform-admin.py --email admin@supportflow.example.com
```

首次登录仍需页面验证码。注册企业账号后，用平台管理员审核，再进入企业控制台配置网站渠道和机器人。知识库上传样本在 [`fixtures/knowledge-base/`](../fixtures/knowledge-base/)。

## 5. 启动控制台与官网

分别在两个终端运行以下命令。

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
```

```powershell
node website/scripts/serve.mjs
```

打开 `http://localhost:5173` 访问控制台，或 `http://localhost:5180` 访问官网。Vite 把 `/api` 代理到 `9090`，把 `/ws` 代理到 `9096`。

## 验证

```powershell
mvn -B -f backend/pom.xml verify
python scripts/verify/test-customer-rls.py --with-ai
python scripts/verify/test-runtime-security.py
python -m unittest discover -s scripts/security -p "unit_*.py" -v
python scripts/verify/test-security-flow.py
npm --prefix frontend run build
Push-Location ai-service
.\.venv\Scripts\python -m pip install pytest
.\.venv\Scripts\python -m pytest tests/unit -q
Pop-Location
node website/scripts/build.mjs
```

运行时可检查 `http://localhost:9090/actuator/health`、`http://localhost:9100/api/ai/health`，并从网站渠道发起访客消息，在工作台验证回复与人工接待。模型、附件以及外部渠道需要各自的运行配置。

RLS 和运行权限脚本使用独立的 PostgreSQL 17 容器并自动清理。`scripts/verify/test-customer-rls.py --with-ai` 验证真实 JDBC 连接复用、事务回滚和 KB 隔离；`scripts/verify/test-runtime-security.py` 验证新库及带历史数据的旧库重复升级、空/PLATFORM 拒绝、历史宽松策略重放、文件迁移和权限最小化。测试用的数据库管理员仅用于准备样本与读取验收结果。

完整服务回归另需 `python -m pip install -r scripts/security/requirements.txt`，先完成 Java 打包，再执行 `python scripts/verify/test-security-flow.py`。它分别启动新库和旧库升级后的七个 Java 服务、Python AI、PostgreSQL、RustFS，验证企业申请/审核、文件权限、平台工单、知识入库/双租户检索、访客 WebSocket 与机器人回复。使用随机凭据、合成数据和离线模型，不连接真实模型，不验收外部微信、邮箱或话务系统。需要空闲的 15432、19000、19090、9091–9096、9100 端口；运行期间不要同时启动另一套本地 SupportFlow。日志位于 `.local/security-services/`，场景结束后自动清理测试容器、数据卷和管理员临时口令。

## 已有数据库升级

以下脚本用于本仓库 Compose 管理、已完成基线表结构的数据库。先停止业务进程并备份六库，保留原管理员密码；将其填入根目录 `.env` 的 `SUPPORTFLOW_DB_PASSWORD`。运行 `python scripts/setup-local-env.py` 只补充八个新的运行密码和服务密钥。备份示例（其他五库同理）：

```powershell
New-Item -ItemType Directory -Force .local/backups | Out-Null
docker compose exec -T postgres pg_dump -U supportflow -Fc customer_db -f /tmp/customer-before-p0.dump
docker compose cp postgres:/tmp/customer-before-p0.dump .local/backups/customer-before-p0.dump
python scripts/upgrade-runtime-security.py
```

升级不删除数据卷。它安装受控查询函数、迁移证照命名空间、替换 RLS 并配置八个分离账号，可重复执行。证照保留原文件 ID、业务引用和对象存储 key，仅按上传人转换 `tenant_code=L+用户ID的36进制`。如有效证照缺少可核实的正整数 `creator`，迁移会回滚并报错，必须由管理员先核对补齐，不能把未知附件分配给任意用户。`L` 命名空间为附件保留，业务租户由现有 `T` 编码生成器产生。

升级后同步部署 Java、Python 和前端，使用 `run-backend.py`、`run-ai.py` 启动，并重跑核心/异常回归。不要通过恢复超级用户、BYPASSRLS、空租户放行或关闭 FORCE RLS 来兼容旧版本。需要回退时先停服务，使用经验证的备份恢复到隔离环境；旧应用版本不能直接作为严格权限配置下的回滚版本。v31 面向共享 `customer_db.public`；独立 schema 的平台升级请求会明确拒绝，需管理员单独部署与验收。

平台审核证照、平台支持工单、AI 质量聚合使用独立角色，必须先写 `platform_access_audit` 才能执行业务，结束/失败再写回执。该角色不能读取会话、知识库原文或私有聊天附件。普通业务账号即使将租户变量设成 `PLATFORM` 也不会获得跨租户权限。数据库租户变量仍由可信应用身份设置，不能防御数据库运行账号本身被攻破后主动伪造其他有效租户变量；部署还需保护数据库凭据和内部网络。

该函数只返回一个有效、未过期、仍启用的渠道密钥对应的租户；不会返回渠道配置或开放整表读取。跨租户重复密钥会被拒绝。函数由迁移管理员持有，普通角色不能修改它；公开执行权限已撤销，知识库账号也没有执行权限。

聊天图片上传需要 `X-Visitor-Token` 中的访客会话身份，或同租户的企业登录身份；读取还必须携带 `sessionNo`，文件必须属于该会话。图片地址不再是可匿名访问的链接。前端使用带身份的请求加载图片，再通过浏览器临时地址显示与预览，令牌不进入图片 URL。历史图片没有业务关联 ID 时，服务端严格核对原有租户及会话目录。机器人头像继续公开显示，`public-bot-avatar.sql` 提供只读投影，仅允许读取头像业务类型及头像目录中的有效资源。

原生渠道的 App、邮件回调、呼叫中心事件和 TTS 必须配置相应的回调签名密钥；缺少密钥会拒绝请求。公众号与小程序的明文推送必须带有效 `signature`；加密推送必须带有效 `msg_signature`。不再支持“验签失败但继续处理”的联调方式。无效推送会按微信接口的既有约定返回 `success`，但不会进入会话处理或产生业务副作用。

`.github/workflows/verification.yml` 配置 Java 全模块验证、独立 PostgreSQL 隔离测试、AI 授权测试和前端构建。AI 测试隔离本机配置与在线模型凭据，不依赖人工填密钥。还会运行新旧数据库升级及真实服务链路回归，远程结果以具体 GitHub Actions 运行记录为准。

## 计费批次协议

客服用量通过稳定 UUID 批次上报。先在 customer_db 的 billing_usage_outbox 保存待发记录，再由独立签名的内部请求写 billing_db；接收端的 billing_usage_batch 账本与日统计同事务提交。网络重试及重启恢复沿用批次 ID 和原业务日期。HTTP 200 的业务拒绝不视为确认。

已有环境先备份并停业务，运行 `python scripts/setup-local-env.py` 补充独立的 `SUPPORTFLOW_BILLING_CUSTOMER_SECRET`，再执行 `python scripts/upgrade-runtime-security.py` 安装 `30-usage-batches.sql` 和最小授权，最后同步部署客服与计费。该密钥只分发给这两个服务；不能用旧无认证/无 batchId 的协议继续累加。新 Compose 空卷自动执行同一迁移。

重放验收：`python scripts/verify/test-usage-batches.py`，另有 `python scripts/verify/test-security-flow.py` 的实际调度、落账及匿名拒绝用例。设计和回滚见 [ADR-0001](decisions/0001-usage-batch-delivery.md)。原始内存计数尚未形成并保存批次时仍可能因进程崩溃丢失；网关的 tenant_usage_daily 请求计数是另一条链路，未因本次计费改造获得去重保证。

## AI 存活与就绪

直接检查内部 AI 服务：`http://localhost:9100/api/ai/health/live` 表示存活；`http://localhost:9100/api/ai/health/ready` 只有登记的 AI/KB 结构与读取权限就绪才返回 200，否则为 503。兼容的 `/api/ai/health` 仍只表示存活，不要用它判定可以接流量。内部身份启动防护仍保持启用。

旧 AUTO_MIGRATE/SCHEMA_DIR 配置不会触发运行期 DDL。结构升级由管理员在应用外执行；缺失时探针和业务 gate 都不会通过放宽权限来修复。`python scripts/verify/test-runtime-security.py --with-ai-readiness` 在新旧两种隔离数据库中注入缺列、缺表、权限撤销并验证恢复，管理员只用于测试准备与故障注入，探测始终使用受限账号。范围及后续任务见 [ADR-0002](decisions/0002-schema-readiness-and-migrations.md)。

## 版本化增量检查

`python scripts/upgrade-runtime-security.py` 现通过版本化 Runner 安装已登记的客服/计费增量，再独立配置运行角色并验证。结构历史与 checksum 位于各库 supportflow_schema_history；运行账号只能读取本库历史。仅检查：`python scripts/db-migrate.py verify`。该命令不会执行 DDL，并核对 current-schema.json 中的实际结构/索引/RLS。已应用脚本变更、历史缺口或并发迁移会拒绝继续，不能删除历史绕过。

清单、事务/多库失败语义和未覆盖项见 [迁移说明](../backend/deploy/migrations/README.md)。本地新建、升级和结构验收可按当前清单复现。

## 备份和恢复演练

三个模式都只使用新建的隔离容器、合成数据和临时账号。

- `python scripts/verify/test-database-restore.py` 演练两个 PostgreSQL 17 集群之间的六库恢复。[六库恢复设计](decisions/0039-disposable-postgres-restore.md)
- 加 `--with-attachments` 同时创建独立的 RustFS 源桶与目标桶，核对对象、数据库引用和知识库索引。[联合恢复设计](decisions/0075-attachment-and-database-restore.md)
- 在联合模式再加 `--with-services`。先以 JDK 25 运行 `mvn -B -f backend/pom.xml -pl supportflow-customer-service -am -DskipTests package`，并安装 `scripts/security/requirements.txt`、`ai-service/requirements-test.txt`。这会启动真实客服和 AI 服务，在目标上检查就绪、文件与任务读取，以及结构故障修复后恢复读取。CI 使用同一模式。[应用验收与回退](decisions/0076-restored-service-acceptance.md)

结果写入 `.local/verification/database-restore-drill.json`。出现 503 时先比较两个服务的存活与就绪，检查 `.local/implementation/restore-customer.log` 和 `.local/implementation/restore-ai.log`。联合恢复失败后从源归档重新建立隔离目标。生产切换和恢复目标仍需在授权环境中验证。

## 附件就绪与排障

客服服务的 `/actuator/attachmentreadiness` 检查应用、数据库和配置的对象桶。通用就绪正常而附件就绪返回 503 时，先核对 RustFS 服务、桶和客服服务所用账号的桶访问权限，再用已授权会话重新读取原文件。聊天记录可继续按通用就绪处理。已授权的附件读取遇到存储不可用时返回 HTTP 503 和业务码 `50308`。探测与回退说明见 [ADR-0077](decisions/0077-attachment-readiness.md)。

## Runtime 队列检查

授权运维人员在 `ai-service` 目录使用已有受限轨迹连接 `SUPPORTFLOW_TRACE_AI_DB_DSN`。以下示例观察最近一小时，并在有 20 项任务已到执行时间或最老任务已等 60 秒时返回告警退出码 3。

```text
python -m ai.runtime.metrics --tenant TENANT_A --window-minutes 60 --alert-due-count 20 --alert-due-seconds 60
```

`waiting_approval` 用于判断待人工审批，`running` 和 `expired_leases` 用于排查执行与接管。定位具体任务时可继续按原请求号读取轨迹。以输出的样本数和截断状态解释延迟及成功率。[口径与退出码](decisions/0078-runtime-queue-metrics.md) · [跨服务轨迹](decisions/0057-cross-service-trace-timeline.md)

## Agent 检查点到期处理

使用 AI 016 及具备 `supportflow_payload_maintenance` 权限的专用维护登录。在 `ai-service` 目录用 `SUPPORTFLOW_PAYLOAD_MAINTENANCE_DSN` 指定目标 AI 数据库。先运行以下命令查看一个租户的一批候选数量。

```text
python -m ai.runtime.payload_retention --tenant TENANT_A --limit 100
```

明确授权的实际清理通过 `--apply` 提交当前批次。输出包含候选或删除计数。任务尚未结束、写结果未知及正文尚未到期时继续保留。部署权限、复验和回退见 [ADR-0079](decisions/0079-expired-checkpoint-retention.md)。

## Java 存活与就绪

五个数据库服务（User/Tenant/Customer/Billing/Ops，9091～9095）使用现有 Actuator：`/actuator/health/liveness` 只检查进程存活；`/actuator/health/readiness` 合并应用就绪状态与本服务的 `schema/readiness.properties` 最小结构契约。缺表、缺列、缺少读取权限或数据库连接/查询异常时返回 503；`/api/*` 同时返回通用 503 和 Retry-After，不进入业务处理器。修复后下次请求重新检查，无需重启。

检查只读 PG 元数据，不读取租户业务行，不执行 DDL。探测使用业务连接池及受限身份；语句 timeout 配置 2 秒，连接池借用 timeout 仍由各服务配置控制。每个请求都会检查，尚未测量负载下的开销；该实现不是全量类型、索引、版本或 RLS 漂移检测。原有启动身份防护保留，启动失败时不能承诺探针已经开放。Gateway/Realtime 没有本地数据库，未安装这道 schema gate，也不代表下游依赖已就绪。

`python scripts/verify/test-security-flow.py` 在新库和历史库两套完整服务中，分别对五个数据库注入缺列、缺表和撤销 SELECT（保留 INSERT）故障，检查存活、就绪、业务拒绝与修复后恢复；仅操作有严格命名校验的隔离验收项目。不要在真实数据库上手动执行故障注入。

AI/User/Tenant/Ops 也已纳入同一清单：首次 migrate 验证基线表/列类型、索引和约束后登记 001，保留数据；后续 baseline 不重放，但 verify 另核对当前结构漂移。不得把首次采纳验证当成持续漂移检测。`python scripts/verify/test-schema-baselines.py` 使用隔离 PG 重现四库采纳及不兼容拒绝。契约生成、权限及回退边界见迁移说明。

`python scripts/db-migrate.py verify` 的当前结构契约登记六库 106 张应用表、1 个视图和受控函数，检查实际列类型/默认值、索引/约束、RLS ENABLE/FORCE 与完整策略。额外未知策略也会阻断，不能通过直接覆盖快照放行。`python scripts/verify/test-schema-drift.py` 在隔离 PG 中验证结构/权限策略故障拒绝。范围、合法演进和未覆盖权限项见迁移说明；这不是备份恢复或完整生产验收。

对于不经 Compose 自动初始化的独立空 PostgreSQL，可用 `python scripts/db-migrate.py init` 完成六库结构、版本和实际结构核验。只要目标库任意一个已存在就拒绝覆盖；已有环境使用 migrate/verify。init 不处理运行密码，随后须在同一服务器显式执行 40-runtime-roles.sql，再 verify 后启动受限身份服务。现有本地 Compose 用户继续使用原升级助手；该助手是本地 Compose 专用入口。部分初始化失败不自动删库，处理方式见迁移说明。完整服务验收现在包含默认新库、历史升级和空服务器经 CLI init 三种路径。
