# 安全修复进度与验证记录

审查基线：`52bc9d3164bb`。以下记录对应本次 P0 修复批次；远程验证以 GitHub Actions 运行记录为准。日期：2026-11-19。

## 状态边界

- P0-2：AI 服务授权、网关路由分级和 Java 调用身份已实现，通过本地自动化与实际服务链路验证。
- P0-1：已完成本地修复与验收。v31 严格策略和八个受限运行账号已接入默认部署；新库与带历史数据旧库均通过 SQL 回归和完整服务链路验收。
- PR-0：已增加 GitHub 验证配置；尚未推送，不能声称远程 CI 已通过。
- P1 并发、计费、持久化 Agent、工具执行、恢复和评测任务尚未完成，本轮没有重构这些模块。

## 已完成的子项

| 子项 | 修改文件与设计 | 回归证据 |
| --- | --- | --- |
| 客服内部服务鉴权 | `CustomerInternalCredential.java`、`CustomerInternalAccess.java`、`CustomerInternalCall.java`、内部控制器和实时/运营/租户三个客户端。不同调用方使用独立密钥，签名绑定受众、操作、路径、查询、租户及短有效期；未分类内部端点默认拒绝。 | `CustomerInternalAccessTest`：4 项通过，覆盖合法身份、服务权限、过期凭据及声明租户与实际参数不匹配。 |
| 异步图片处理 | `SessionAttachmentService.java`、`VisionService.java`：每个预处理任务绑定自己的租户；读取前核对图片归属、会话及业务类型，避免下载其他租户或其他会话的对象。 | `SessionVisionTenantTest`：3 项通过，含线程复用、上下文恢复、会话与业务类型拒绝及历史文件路径校验；下载入口授权见本轮图片子项。 |
| 知识库受限账号适配 | `ai/rag/store.py`、`ai/api/kb.py`、`KbAiClient.java`、`KbVectorHealer.java`。事务内绑定租户，检索关联文档同时约束租户；修复清单改为客户服务专用的逐租户接口。修复列表缺失时跳过，避免整库重建。 | Python 真实受限 PostgreSQL 知识库测试 4 项通过；`KbVectorHealerTenantTest` 2 项通过。KB 角色只能读取检索所需文档列，不能读取原文内容或会话表。 |
| 定时任务租户范围 | `RoutingService.java`、`MetricsService.java`、`CustomerTagRuleService.java`、`RealtimeQaService.java`、`TicketService.java`、`MailInboundPoller.java`。从租户服务取得有效租户清单，逐租户绑定后处理。手工 SLA 扫描只处理当前企业。 | `ScheduledTenantScopeTest` 3 项、`MailInboundTenantTest` 1 项通过，包含局部失败后继续处理、历史回填和上下文退出检查。 |
| 访客与原生回调入口 | `ChannelTenantResolver.java`、`channel-tenant-lookup.sql`、两个访客控制器、`ChannelNativeWebhookService.java`。通过限定查询函数解析有效渠道，在进入事务前绑定租户；函数不开放渠道整表查询，拒绝过期、停用及重复密钥。 | `VisitorTenantEntryTest` 1 项与 `NativeWebhookTenantTest` 2 项通过；真实 PostgreSQL 测试验证普通业务账号可以执行限定查询，知识库账号无执行权限。 |
| 回调认证缺口 | `NativeSignature.java`、`WeChatCrypt.java`、公众号与小程序回调。未配置原生签名密钥时拒绝；小程序控制器正确传入明文签名；明文验签失败或加密签名缺失均不进入业务处理。 | `WeChatCallbackAuthenticationTest` 3 项通过；原生签名的有效控制、缺少密钥、内容不匹配、过期和格式错误已纳入回归。微信失败响应仍保持原接口约定，但不会分发消息。 |
| 管理员与业务连接分离 | `CustomerMigrationConnection.java`、`TenantSchemaUpgradeService.java`。结构升级仅使用显式配置的独立管理员凭据，缺失或复用业务用户名时拒绝；业务连接池不再执行升级 DDL。业务角色只获得升级日志的查询与追加权限。 | `CustomerMigrationConnectionTest` 2 项通过，覆盖未配置、共享账号拒绝以及升级连接和日志写入。实际数据库账号分离用例纳入独立 PostgreSQL 脚本。 |
| 微信配置与处理范围 | `WeChatConfigService.java`、`WeChatInboundService.java`、`WeChatOAuthService.java`、`ChannelTenantResolver.java` 及渠道查询 SQL。先解析有效 AppID 所属租户，再读配置、执行入站处理及授权后的客户绑定；已有租户上下文不得切换到另一租户的配置。 | `WeChatTenantLookupTest` 覆盖配置读取与入站去重日志的租户范围；真实 PostgreSQL 用例确认无上下文时不能整表读取回调 Token。 |

上述 Java 文件位于 `backend/` 对应服务的 `src/main/java/` 与 `src/test/java/` 下，Python 文件位于 `ai-service/`。

## 本地验证

- 最终 `mvn -B -f backend/pom.xml verify`：九个模块通过，包含升级连接与微信租户范围子项。
- 最新客服模块 35 项通过，9 项数据库用例在普通构建中跳过；网关 5 项、租户证照引用 1 项通过。数据库用例另由隔离脚本运行，不将跳过计为通过。
- `python scripts/test-customer-rls.py --with-ai`：启动独立 PostgreSQL 17，使用非超级用户验证隔离。最新 Java 13 项通过，Python 知识库 4 项通过。
- P0 Python 回归（含健康、内部授权、KB 状态、运行身份防护）：12 项通过，2 项需要隔离 PostgreSQL 的用例在该命令中跳过，由上面的脚本执行。
- 最新实际服务验收：网关匿名管理请求被拒绝；内部知识库路由不对公网关开放；直连 Python 的匿名请求被拒绝；企业登录、知识文档入库、两个租户的检索隔离、访客 WebSocket 消息及机器人回复通过。新增图片上传与读取：当前访客与同企业坐席正常读取；匿名、其他租户、同租户其他访客及其他会话均被拒绝；图片响应禁止缓存。企业上传机器人头像后，匿名访客仍能正常读取头像。
- 本地服务验收使用随机测试凭据、独立数据库和附件存储、离线模型；不计作真实模型质量或外部微信/话务/邮箱验收。
- `git diff --check`：通过。远程 CI 尚未运行。

## 迁移与回滚

新建 Compose 数据库自动安装查询函数、v31 严格 RLS 和八个分离账号。已有数据库先备份，停止应用后执行 `python scripts/upgrade-runtime-security.py`，不能删除数据卷代替升级。步骤、未知上传人处理、独立 schema 边界与回退限制见 [本地运行指南](local-development.md)。不能以恢复超级用户、BYPASSRLS 或空租户放行作为回滚手段。

## P0-1 关闭批次：证照、升级、默认身份与实际服务

| 子项 | 修改文件与设计 | 实际回归 |
| --- | --- | --- |
| 企业证照所有权与平台审核 | `EnterpriseLicenseFileService`、`CustomerPrivilegeFilter`、`PlatformAccess`。证照按申请人稳定私有命名空间隔离，读取校验 creator；平台访问使用独立角色，强制写审计；MIME 类型按允许的扩展名生成。 | `LicensePrivilegeTest` 3 项；实际服务验证本人/审核员读取，匿名、跨用户和伪造租户头拒绝，开通前后归属保持。 |
| 资料提交引用校验 | `InternalLicenseController`、`LicenseOwnershipClient`、`EnterpriseOnboardingService`。签名绑定文件/申请人路径；写企业资料与审核记录前向客服核验；核验服务不可用时拒绝提交。 | `LicenseReferenceTest` 1 项；实际 HTTP 暂存和提交别人证照均返回 40301，合法开通通过。 |
| 历史数据库与角色迁移 | `customer_db_runtime_security.sql`、`25-runtime-security.sql`、`40-runtime-roles.sql`、`upgrade-runtime-security.py`、基线/RLS 副本。严格策略加角色专属 restrictive guard，文件 ID/对象 key 不变，未知上传人拒绝迁移。 | `test-runtime-security.py` 新库 28 条、旧库 29 条安全断言通过；每个场景重复迁移两次。验证历史 permissive 策略不能扩权、DDL/TRUNCATE/跨库/SET ROLE 拒绝、历史引用保留。 |
| 默认运行身份与防误配置 | 五个 Java 数据服务 `application.yml`、AI `config.py`、`RuntimeDatabaseGuard`、`runtime_roles.py`、`local_env.py` 与启动脚本。默认不连接 bootstrap 账号，客服/AI 拒绝危险身份，平台连接逐次检查角色；不向运行进程传建库密码或其他服务数据库密码。 | Java/Python 启动防护回归，2 项凭据分发单元测试；SQL 连接快照确认五个 Java 数据服务使用独立受限账号。 |
| 平台支持工单与质量聚合 | 专用角色仅获证照、平台支持工单/事件、通知写入与质量聚合所需列，不能读取会话、KB 原文或聊天文件。 | 两套实际服务均完成企业提单、平台认领/回复、质量聚合、企业调用平台接口拒绝；每套 START/END 审计匹配 6 对。 |

`python scripts/test-security-flow.py` 已完成新库默认部署、带历史数据旧库原地升级两套完整服务验收。两套均跑通企业申请/审核、文件权限、平台工单、知识入库/双租户检索、访客 WebSocket 和机器人回复。测试环境为 Windows 11、JDK 25、PostgreSQL 17 + pgvector、Python 与 RustFS，使用随机凭据、合成数据和离线模型。测试依赖、复现命令见运行指南和 `scripts/security/requirements.txt`。

权限迁移针对共享 `customer_db.public`；独立 schema 的 v31 平台升级会在执行 DDL 前拒绝，需管理员单独部署验收。数据库运行凭据被攻破后的任意 SQL/租户变量伪造不属于本次 RLS 的保护边界。测试不代表真实第三方渠道或生产安全认证；前端已构建，通过 HTTP 验证媒体权限，尚未执行浏览器交互回归。

## 图片与头像子项：2026-11-19

- 修改 `ChatAttachmentAccess.java`、`VisitorTokenService.java`、`SessionAttachmentController.java`、`SessionAttachmentService.java`、`VisionService.java`。上传和读取校验会话身份；文件核对租户、会话和业务类型，视觉识别执行相同的文件归属检查。新上传关联会话 ID，历史数据严格核对租户与会话目录。
- 修改 `frontend/src/components/SecureChatImage.vue`、访客页面、工作台和图片上传 API。使用请求头传身份、浏览器临时地址展示图片，保持缩略图和同页预览；不会把登录令牌拼进图片链接。通过 TypeScript 检查和生产构建，尚未做浏览器交互回归。
- 修改 `BotAvatarService.java`、`BotAvatarController.java`，新增 `backend/deploy/db-init/public-bot-avatar.sql`。头像作为公开品牌资产通过限定函数读取；不向业务账号开放文件表全量读取，也不允许通过该函数读取私有聊天图片、营业执照或已删除头像。
- 增加 `ChatAttachmentAccessTest`、`BotAvatarUploadAccessTest`，扩展 `SessionVisionTenantTest` 和 `TenantRlsPostgresTest`，覆盖匿名拒绝、访客令牌类型、会话不匹配、文件业务类型、历史目录归属、头像公开投影与底层文件表隔离。
- 部署需同步更新前后端，并先安装渠道查询 SQL 与公开头像 SQL。旧的匿名图片链接将不再直接返回私有图片；现有前端会从旧消息中的文件编号或本地旧链接提取编号，以已授权请求重新加载。外部自定义客户端需按 [运行指南](local-development.md) 补充身份头和会话参数。

## 提交范围

本次只提交 P0 源码、自动化回归、部署配置和必要运行文档。原始文章、审查输入清单、简历草稿、评测原始资料与本机凭据留在本地。工作区中的 RAG 评测与已知引用校验缺口属于另一任务，不作为 P0 关闭依据。P1 的消息重复请求、并发、计费和持久化 Agent 仍需单独处理。
