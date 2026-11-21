# 可靠性改造验收记录

基线 `dca4b8e614adbce7ade8ba72323b2a4e6da4470e`；2026-11-19，Windows、Temurin JDK 25.0.5、Maven 3.9.16、隔离 PostgreSQL 17 + pgvector。测试使用合成数据/模型 Stub，无线上客户或收费模型调用。路线与完整能力矩阵见 [工程计划](agent-engineering-plan.md)。未提交变更的报告以本地工作区为准。

## A0：本轮基线

- 完整阅读两份工程输入、README、架构、开发及安全验收文档，检查 HEAD、Git 状态、CI 配置与相关实现。未跟踪的历史评测不算本次新增能力。
- Python：`python -m pytest -q --junitxml=../.local/implementation/baseline-python.xml`，在 `ai-service` 执行：**18 passed、2 skipped、1 xfailed**。跳过的 PostgreSQL 用例另运行；xfail 是既有引用正文未删除越界编号的问题，不能计为通过。
- PostgreSQL：`python scripts/test-customer-rls.py --with-ai`：**Java 13 passed、Python KB 4 passed**。一次性容器、非超级用户，保持现有 fail-closed 与 KB 隔离；容器已清理。
- 首次 Java 全量命令在执行期间收集到了新加入的竞态复现测试，因此出现预期失败，**不能写成未修改基线全绿**。失败与专项复现均保留日志；修复后的全量结果在下节记录。
- 原始输出：`.local/implementation/baseline-python.log`、`baseline-postgres.log`、`baseline-java.log`。历史安全 CI 只作来源参考，本轮尚未推送新 CI。

## A1 / PR-3：会话锁生命周期

修改：

- `backend/supportflow-customer-service/src/main/java/io/supportflow/customer/service/bot/BotBrainService.java`
- 同目录新增 `SessionSerialExecutor.java`
- 对应 `src/test/java/.../service/bot/BotBrainConcurrencyTest.java`、`SessionSerialExecutorTest.java`

根因：A 执行完删除 Map 项时，B 已持有旧锁并等待；C 创建新锁，B/C 同时进入同一会话。仅条件删除 Map 项仍不能修复等待者生命周期。

选择：使用 `(tenantCode, sessionNo)` 结构化键；在等待前通过 Map.compute 注册引用，引用覆盖执行者与等待者，最后一人退出才回收。每键公平 ReentrantLock，可中断等待；执行异常也释放引用。无全局执行锁，不阻塞无关租户/会话。服务保留中断信号。没有引入 Redis 或改变 AI/业务协议。

复现与回归：

```powershell
mvn -B -f backend/pom.xml -pl supportflow-customer-service -am '-Dtest=BotBrainConcurrencyTest,SessionSerialExecutorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -f backend/pom.xml verify
```

- 修复前：实际 BotBrainService + 模型 Stub，Latch 固定 A 执行、B 等待、A 完整退出、C 到来。最大模型调用并发 **2**，断言期望 1 失败；日志 `.local/implementation/pr3-before.log`。
- 首次修复后专项：5 项通过（随后补充服务中断信号用例，最终 6 项由全量验证覆盖）。包含原始复现、异租户/会话并行、分隔符键碰撞、异常清理、中断等待、8 线程累计 800 次互斥检查；最大并发 **1**，结束后活跃锁键 **0**。这些是合成并发正确性检查，不是吞吐性能数据。
- 最终全量结果：**九个 Maven 模块成功**；客服 50 项中 **41 passed、9 skipped**（数据库用例由隔离脚本运行），网关 5 项、租户 1 项通过。PR-3 的最终 6 项均通过。原始输出 `.local/implementation/pr3-full-verify.log`。

迁移与回滚：无数据库/配置迁移；回退这一批 Java 改动即可恢复原版本，但会重新引入竞态，不建议作为稳定部署版本。业务 API 与消息格式不变。

剩余风险：仅进程内互斥，不保证跨实例串行、按消息序号排序、持久化任务或崩溃恢复；内存线程池/CallerRuns 背压问题由 PR-8 处理。等待中断会停止该轮，当前仍无持久化补偿机制。真实模型质量、外部渠道和生产容量不在该批验收范围。

## A2 / PR-4：PostgreSQL 消息幂等

修改：客服 `SessionService.java`、`SessionMapper.java/xml`、`SessionMessageMapper.java/xml`；新增 `config/SessionMessagePostgresTest.java`，由 `scripts/test-customer-rls.py` 和既有 CI 的数据库步骤执行。

实际复现：旧业务代码使用真实 MyBatis Mapper、Spring 事务与非超级用户 PG。两个请求经 barrier 同时查到不存在后写入：一方的唯一冲突令事务中止，随后 SELECT 报 **SQLSTATE 25P02**。已软删除的同键消息也走到同样错误。另测外层事务回滚，发现内存计费已提前增加。首批 4 个新用例中 1 通过、2 失败、1 错误；原 13 个 RLS 用例通过。日志 `.local/implementation/pr4-before.log`。测试框架接入期间的编译错误已纠正，不作为缺陷复现证据。

设计：

- 保留已提交消息的快速幂等读取。新消息在同一事务内取得目标会话行锁，重新核对会话状态和客户端消息号，再分配序号；各实例通过数据库协调。锁住会话后显式清理 MyBatis 本事务缓存，避免复用等待前的空结果。
- 客户端消息使用针对既有部分唯一索引的 `INSERT ON CONFLICT ... DO NOTHING RETURNING id`；其他主键/消息号错误不被吞掉。软删除消息的客户端键仍被原索引保留，返回受控业务冲突并回滚序号，既不暴露旧内容也不重用键。
- 计费仅在事务成功提交后计数；已有 QA、机器人 afterCommit 与事务事件监听保持原边界。重复消息不再次触发这些副作用。
- 补充兼容测试时实际撞到旧 `M+秒级时间+四位随机数` 消息号的唯一键（`uk_tenant_msg_no`），日志另保留为 `pr4-message-number-collision.log`。新消息号改用 `M+该消息已有雪花ID`，保留旧数据，不再引入独立的小随机空间；多实例仍须正确分配 worker ID。
- 沿用数据库默认 READ COMMITTED；没有宣称适配任意隔离级别。锁覆盖本事务，不在事务内调用真实模型。

复现命令：`python scripts/test-customer-rls.py --with-ai`。隔离容器安装生产基线/严格 RLS；实际生产 Mapper 和业务方法，不以 H2 或模拟 SQL 代替 PostgreSQL。模型和通知为 Stub；观察提交后的消息、序号、计费回调、机器人提交及事务事件。

修复后结果：**新增 7 项 + 原 RLS 13 项 Java 全通过，Python KB 4 项通过**。覆盖同键并发、不同键并发/连续序号、重复 ACK、事务回滚、已删除键、会话关闭竞争、跨租户拒绝和无客户端键的兼容写入。日志 `.local/implementation/pr4-after.log`。数据库容器已自动清理。

迁移/回滚：无需 DDL，使用已有 `uk_session_client_msg`。必须一起部署业务代码与 Mapper；回退这批文件可还原，但会重新引入 PG 事务错误。没有关闭 RLS 或扩大运行角色权限。

边界：提交后计数/通知仍可能在进程崩溃时丢失；PR-6/8 负责持久化计量与可靠任务，不宣称 exactly-once 投递。软删除键返回业务冲突，需要客户端使用新键。该批证明消息事务正确性，不证明真实模型质量或系统吞吐。

## A3 / PR-5：坐席原子认领

修改：`SessionService#claimSession`、`SessionMapper#claimUnassigned` 及 XML；`SessionClaimPostgresTest`。消息与认领测试共用 `SessionPostgresFixture`，加载生产 Mapper、事务和拦截器，避免复制隔离环境。继续由现有 PostgreSQL 脚本/CI 执行。

旧代码真实 PG 复现：5 个认领用例中 **4 失败、1 通过**。两坐席均返回成功；同一坐席并发重试产生两套事件/系统提示；初读后被关闭的会话被认领重新打开；无效坐席 ID 未拒绝。原消息与 RLS 20 项通过。日志 `.local/implementation/pr5-before.log`。

设计：原子 UPDATE 同时要求 tenant、session id、未删除、无负责人、状态为排队/机器人接待；只有影响 1 行的事务生成事件和系统消息。0 行后重新读取，已关闭拒绝、相同坐席且人工接待状态幂等返回，其他状态冲突；null/非正数 ID 提前拒绝。UPDATE 清理 MyBatis 本地缓存，失败竞争者能读到最新结果。沿用可信服务侧的坐席授权，不把任意正数当作已授权身份。

实际结果：`python scripts/test-customer-rls.py --with-ai`，**认领 5 + 消息 7 + 原 RLS 13 = 25 项 Java 全通过，Python KB 4 项通过**。断言实际数据库负责人、事件及系统消息数量；已关闭会话和跨租户访问不产生副作用。日志 `.local/implementation/pr5-after.log`。测试容器已清理。

迁移与回滚：无 DDL/API 格式变化，Mapper 与业务代码一起部署/回退；回退会恢复竞态。剩余边界：坐席在线状态、工作量和路由策略不是此原子竞争补丁的验收目标；真实外部小程序通知仍未在本轮测试。

## A6 / PR-12 首批：低证据拒答与最小 Eval/Trace

修改：`ai-service/ai/rag/graph.py`；`tests/test_rag_abstention.py`；`fixtures/evaluation/rag-abstention-v1.json`；`requirements-test.txt`；`scripts/report-rag-contract.py` 和 CI。已有模型调用、检索层与客服业务路由继续复用。

设计：只要最终 grade 不足且唯一改写预算耗尽，进入确定性的 abstain 终态，不进入生成节点。无引用，提示补充问题或联系人工；不在 RAG 层直接更改 Java 会话负责人。LangGraph 与线性 fallback 使用同一策略。新增 decision/reason 字段及 steps 的 sequence、traceId、schemaVersion，复用现有请求 ID，未宣称实现持久化 Run/Step Trace。

12 条版本化**合成路由契约**案例：向量/关键词空结果与低分、边界阈值、足够资料、检索异常、改写恢复。两种引擎均实际执行，生成/检索为 Stub。已有阈值保持不变，未根据这组阈值案例宣称语义质量提升。

```powershell
# ai-service 目录中，先安装 requirements-test.txt
python -m pytest -q --junitxml=../.local/implementation/final-python.xml
# 返回仓库根
python scripts/report-rag-contract.py --junit .local/implementation/final-python.xml --baseline .local/implementation/pr12-before.xml --output .local/implementation/rag-contract-report.json
```

实际结果：改造前专项 **10 passed、14 failed**（不充分资料仍进入生成节点）；改造后 **24/24 passed**。完整 Python **42 passed、2 skipped、1 xfailed**；两个 KB 数据库用例另由 PG 脚本运行。报告门禁要求版本化案例及两引擎完整且全部通过；输入旧失败报告时实际退出 1，不能把失败基线发布为通过。机器报告保存数据集 SHA256、HEAD、工作区状态、每条结果/测试耗时与失败原因。

剩余风险：该集衡量路由契约，不衡量回答正确率、事实支持、真实 Recall/Token/费用/供应商延迟，均为 `NOT_MEASURED`。检索分数本身不是语义充分性的证明；冲突、过期、文档 ACL、注入与引用事实支持仍归 C 阶段。既有越界引用正文 xfail 保留，不将 PR-12 标为全部关闭。仍无安全执行回放平台。

回滚：单独回退 graph/对应契约测试及 CI 门禁即可，无数据迁移；回退会恢复低证据生成风险。新接口字段为增量字段，旧业务客户端继续读取原字段。

## 本批次综合回归与剩余工作

- `mvn -B -f backend/pom.xml verify`：**九模块成功**；客服 **41 passed、21 skipped**，网关 **5 passed**、租户 **1 passed**。跳过项为 9 项 PG 隔离 + 7 项消息 + 5 项认领，由隔离脚本另跑，不计为普通 Maven 通过。日志 `.local/implementation/final-java.log`。
- 最终共享测试夹具收尾后再跑 `python scripts/test-customer-rls.py --with-ai`：**Java 25 passed，Python KB 4 passed**，日志 `final-postgres.log`。
- Python **3.11.5**，LangGraph **1.2.22**，pytest **9.2.0**；完整本地测试 **42 passed、2 skipped、1 xfailed**，包括原有未跟踪的故障测试。CI 测试依赖固定本轮 LangGraph/pytest 版本；本轮尚未推送，不能宣称远程 CI 已通过。
- 报告门禁负面自动测试：`python -m unittest discover -s scripts/tests -p 'test_*.py' -v`，**5 passed**；缺项、重复、失败、跳过都拒绝。日志 `evaluation-gate-tests.log`。模型/Token/成本未测试的字段不会被填写成实测数字。
- `python scripts/test-runtime-security.py`：**新库 28、旧库 29，共 57 条数据库安全断言通过**，各重复迁移两次。日志 `final-runtime-security.log`。
- 凭据分发：`python -m unittest discover -s scripts/security -p 'unit_*.py' -v`，**2 passed**。日志 `final-credential-tests.log`。
- `python scripts/test-security-flow.py`：**新库默认部署、带历史数据旧库升级两套实际服务全部通过**；网关/内部鉴权、企业开通审核、文件权限、平台工单、知识入库与双租户查询、访客 WebSocket 及机器人回复。确认五个 Java 数据服务均使用受限账号，平台 START/END 审计匹配；两套容器、数据卷及网络已清理。日志 `final-service-flow.log`。
- 业务服务回归使用隔离 PG/RustFS、真实 Java/Python HTTP/WebSocket 和离线模型；不代表在线模型质量或外部微信/话务/邮箱验收。前端未修改，本轮未执行浏览器 UI 验收。

本批完成 A1、A2、A3 和 A6 的低证据拒答首批；**阶段 A 尚未全部退出**：PR-6 计费批次、PR-7 权威版本迁移/readiness 待做。下一批先统一客户服务和网关发送端的稳定批次/失败重试语义，再实现消费侧去重与统计事务，随后完成迁移体系。PR-8～10 的 Runtime、Tool Calling、HITL 仍未实现，必须等基础不变量和迁移方案稳定后推进；不能将本批并发修复写成生产级 Runtime 已完成。

## 分批提交

2026-11-19 按任务拆分本地提交：PR-3 `08bc0d2`；PR-4 `e98eda8`；PR-5 `80e94a6`。PR-12 拒答与契约门禁、能力盘点和综合回归记录随下一独立提交保存。本轮不包含原始文章、两份改造输入、简历和历史评测原始资料。

## A4 / PR-6：客服计费批次（2026-11-19）

设计：[ADR-0001](decisions/0001-usage-batch-delivery.md)。修改公共 UsageBatch/签名、billing 内部上报控制器/UsageMeterService/Mapper、客服 Collector/Reporter/Client 与 BillingUsageOutbox；新增 30-usage-batches.sql，接入 Compose/升级脚本、最小授权和凭据分发、CI 与回归测试。

- 旧代码真 PostgreSQL：同一份用量 3 的批次重报 9 次，实际累计 **27**，应为 3；首批测试 4 失败、1 通过。原始输出 `.local/implementation/pr6-before.log`。
- 修复后账本去重和日统计同事务；同键异内容拒绝，聚合 SQL 失败回滚批次记录。客服先持久化批次，直到收到同 ID 的明确业务确认才删除；未知落库结果、远端响应丢失及本地确认失败都保留原 ID/日期。每租户最多一条持久化待发记录，避免故障时无界积压。
- `python scripts/test-usage-batches.py`：**Outbox 8、计费账本 6，共 14 项真 PG 用例通过**；受限非 owner 账号、严格 RLS、跨租户拒绝、并发竞争、重建 Reporter 后恢复、账本不可删除。脚本重复迁移两次并清理容器。输出 `pr6-postgres-final.log`。
- `mvn -B -f backend/pom.xml verify`：九模块通过，包含 **签名/参数 3 项、实际本地 HTTP 客户端 4 项**。随后新增计数并发收尾测试，专项 `BillingUsageCollectorTest,BillingUsageClientTest` **5 项通过**；8 个线程各累加 1000 次、并发 drain 后每类计数均 8000，仅为合成正确性测试。输出 `pr6-java.log`、`pr6-counter-client.log`。
- 凭据分发单元测试 **3 项通过**；新密钥只到客服和计费，已通过 setup-local-env 补充本机配置，没有显示/提交密钥。
- `python scripts/test-security-flow.py`：**新库/旧库两套实际服务通过**；客服定时任务确实完成签名上报和账本落账；网关与直连匿名写入均 HTTP 403；原企业开通、平台工单、媒体权限、知识检索、访客 WebSocket/机器人流程保持通过。运行账号不能删除去重账本；容器/卷/网络已清理。输出 `pr6-services.log`。测试脚本最初把现有 API 的 Long 字符串当整数比较，纠正后重跑通过，未改变 API 数字序列化契约。

边界：原始内存增量/尚未落库的候选仍可能在崩溃时丢失；这批保证已持久化批次的幂等重报，不是业务事件到计费的端到端 exactly-once。停用/删除租户的待发记录保留供管理核查。网关请求量进入 tenant_usage_daily，是另一条链路，后续单独改造。认证/永久内容冲突不会丢弃批次，需修正配置或管理员核查；完整死信运营界面尚无。

迁移及回滚见运行指南和 ADR。已有数据库先备份、停业务，安装同一 SQL/最小授权后同步部署发送与接收端；不能退回无签名、无批次协议或清空账本。后续 PR-7 将该增量纳入版本化迁移。未推送，不将本地结果称远程 CI 成功或生产表现。

## PR-7 子批次 1：AI 只读 Readiness（2026-11-19）

基线 e16bd3e。修改 ai/core/schema_guard.py、db.py、main.py、internal_auth.py、api/health.py；新增 schema 单元/PG 故障测试，修正原授权测试夹具以隔离 schema gate；CI 新增实际数据库 Readiness 回归。开发/初始化及客服 SQL 资源文档移除不存在的执行路径。设计与回滚见 [ADR-0002](decisions/0002-schema-readiness-and-migrations.md)。

- 修复前 4 个复现用例全部失败：开启旧配置可触发 DDL；新存活/就绪探针不存在；结构缺失时已认证业务仍进入模型。日志 `.local/implementation/pr7-readiness-before.log`。
- 修复后 Python **46 passed、6 skipped、1 xfailed**。6 个跳过为 KB 2 项和实际 Readiness 4 项，均另由隔离脚本运行；已知引用正文 xfail 不作为通过。输出 `pr7-readiness-python.log`。
- `python scripts/test-runtime-security.py --with-ai-readiness`：新/旧库各 **4 项实际 Readiness 用例**通过，原数据库安全断言仍 **28+29=57** 通过。运行账号查询结构；管理员只在隔离环境中重命名列/表或撤销读取权限，finally 恢复。验证 INSERT/UPDATE 权限仍存在时，撤销 SELECT 也不能错误就绪。输出 `pr7-readiness-postgres.log`。
- `python scripts/test-security-flow.py`：新/旧库实际服务全部通过，包含原有核心链路、受限账号、双租户/媒体权限和新增签名计费链路。输出 `pr7-readiness-services.log`；测试容器、数据卷、网络均清理。

行为：运行账号只读取 metadata，不论旧 AUTO_MIGRATE/SCHEMA_DIR 如何配置都不执行 DDL。认证和操作授权先于 schema gate；未授权仍拒绝，已认证但结构未就绪返回 503 且不调用模型。存活与就绪分离，公开响应不包含 DSN 或结构详情。

范围：仅 AI/KB 登记结构与读取权限检查，不等于全量版本/类型/索引/RLS 验证。危险身份/启动关键连接仍会触发原有启动防护；没有宣称所有数据库故障下进程都能先启动。每请求 metadata 开销未进行性能评测。Java Readiness、版本历史/checksum/锁、完整 init/migrate/verify 仍待 PR-7 下一子批次，PR-7 尚未关闭。无需 DDL/数据迁移；不得用恢复自动 DDL 或放宽账号权限回退。

## PR-7 子批次 2：现有增量的版本历史与迁移锁

基线 9dd9acb。新增 backend/deploy/migrations/manifest.json、scripts/migration_engine.py、db-migrate.py 和测试 Runner；接入 upgrade-runtime-security.py、运行角色历史表权限、CI 与实际服务启动。原 SQL 不复制，按数据库段载入并规范化换行符计算 checksum。

设计：每库 session advisory lock，竞争立即拒绝；每条 SQL 与历史回执同事务提交。拒绝 checksum 漂移、历史缺口、未知更新版本、错误目标库、外层事务误用与不支持的 psql 指令/非后端 SQL 文件。运行角色不能 apply，verify 不执行 DDL。角色/密码 provisioning 单独执行，不保存到版本历史；只给本库运行身份 SELECT 历史，不给 DML。

实际验证：

- `python scripts/test-versioned-migrations.py`：**15 项通过**（5 项解析/清单 + 10 项实际 PG），并执行真实 migrate/verify CLI、运行账号 CLI 拒绝、首次升级和重复核对。验证失败 SQL 连同回执回滚、修复后重试只执行一次；checksum 篡改、锁竞争、历史缺口、只读 verify、外层事务都覆盖。保留旧证照 ID/对象 key，归属按现有严格策略升级。输出 `.local/implementation/pr7-migrations-final.log`。
- `python scripts/test-security-flow.py`：新库与带历史数据旧库均先执行版本化增量、独立角色配置、verify，再启动完整服务，全部通过。确认 customer 历史 **4** 条、billing 历史 **1** 条，checksum 长度正确，本库运行账号不能修改历史；原核心/隔离/签名计费链路不退化。输出 `pr7-migration-services.log`。
- parser/evaluation 普通单元测试中，实际数据库用例会跳过，不能计为通过；上面的专用脚本在隔离 PostgreSQL 17 中单独执行，容器最终清理。

部署步骤与恢复见 backend/deploy/migrations/README.md。新建六库后也应通过升级入口登记现有增量。多库不构成一个原子事务：前库已成功、后库失败时保留前库结果，停止业务并修复后重跑，从待执行版本继续；不要删除历史、改 checksum 或恢复宽松 RLS 回退。无生产吞吐/耗时改进声明。

范围：当前登记的是 customer_db/billing_db 的五个现有增量单元；verify 核对历史/checksum，不代表全量 schema/index/RLS 漂移检查。其他四库基线采纳、统一 init、Java Readiness 仍待下一子批次，PR-7 尚未整体关闭。

## PR-7 子批次 3：五个 Java 数据服务 Readiness

基线 7c6a7ac。修改 common 的只读 SchemaReadiness、JDBC 后置自动配置与 Servlet gate，五个数据服务的最小 schema 契约和 Actuator 分组；TenantSchemaGuard 委托同一检查器，移除重复 SchemaMapper 和不存在的迁移脚本提示。Gateway/Realtime 无本地 DB，不注册 schema gate。设计、边界与回滚见 ADR-0002 及运行指南。

实际验证：

- `mvn -B -f backend/pom.xml clean verify`：九模块成功，新增 SchemaReadinessTest **7 项通过**；覆盖全部登记列、数据库异常脱敏、只读/权限感知/查询 timeout、未就绪不进入处理器、探针可达、就绪正常放行、无 DataSource 不安装配置。普通 Maven 跳过的 PG 集成用例不计为通过。输出 `.local/implementation/pr7-java-ready-clean-build.log`。
- `python scripts/test-security-flow.py`：**新库/历史库两套完整服务通过**，每套五个数据服务各注入缺列、缺表和撤销 SELECT（保留 INSERT）三类故障，共 **30 个故障场景执行**。每次核对 Readiness=503、Liveness=200、业务 gate=503；修复后探针恢复 200、业务 gate 重新放行，无需重启。最终输出 `pr7-java-ready-final-services.log`。
- 原网关/内部鉴权、企业开通审核、平台权限、私有媒体、双租户知识入库查询、访客 WebSocket/机器人及签名计费链路保持通过；实际五个 Java 数据服务均使用受限角色，审计回执匹配。两套测试容器/数据卷/网络已清理。
- 初次单元测试受本地 JDK 25 无法动态附加 Mockito agent 影响，复用仓库现有 mock-maker-subclass 配置后重跑；这不是产品缺陷复现。新旧库首次故障回归通过后，因为移除重复检查器和补充恢复断言，最终对代码进行了 clean 构建及完整重跑。

本批只读检查最小表/列与 schema USAGE/列 SELECT 权限，不执行 DDL、不读取业务行；每请求查询，不声称性能改善。没有覆盖完整类型/索引/RLS 漂移、所有网络故障或所有下游依赖。PR-7 尚未关闭，下一批为其他四库基线采纳，然后统一 init 与结构核验。无需数据迁移；回退时同步回退公共配置/契约和探针部署设置，不能扩大数据库权限。

## PR-7 子批次 4：另外四库的基线采纳

基线 c8b5a8d。新增 schema_contract.py、四个不可变 JSON 契约和 test-schema-baselines.py；扩展 manifest/Runner 的 baseline 操作、role provisioning 的历史权限及 CI。复用现有 SQL/事务/锁/checksum，不重建已有四库。修改迁移单测与实际服务历史断言以覆盖六库。设计和回退见 ADR-0002/迁移说明。

实际验证：

- `python scripts/test-schema-baselines.py --write-new-contracts`：首次在隔离 PG17 执行权威 init 后生成 AI 8、User 8、Tenant 7、Ops 12，共 **35 张表**的基线（列类型/非空、索引定义/有效状态、约束）；源 SQL SHA256 附在契约中。该选项拒绝覆盖已有文件，不是日常修复命令。输出 `.local/implementation/pr7-baselines-generate.log`。
- `python scripts/test-schema-baselines.py`：四库首次采纳、重复 migrate、verify 通过，逐表核对种子数据行数不变；每库缺表/缺列/类型不匹配/索引不匹配以及 Runner 不兼容采纳五类负面场景，共 **20 类场景执行**，全部拒绝；失败不写历史、恢复后可采纳。输出 `pr7-baselines-final.log`。
- `python scripts/test-versioned-migrations.py`：**17 项通过**（7 解析/清单 + 10 真 PG），以及实际六库 CLI migrate/verify、运行账号 CLI 拒绝。原 customer/billing 历史版本/checksum 没有改写；历史证照 ID/对象 key 保留。输出 `pr7-sixdb-migrations.log`。
- `python scripts/test-runtime-security.py --with-ai-readiness`：新/旧库 **28+29=57 条数据库安全断言**仍通过，AI/KB Readiness 真 PG 用例各 **4 项**通过。输出 `pr7-sixdb-security.log`。
- `python scripts/test-security-flow.py`：新/旧库完整实际服务和 Java Readiness 故障回归均通过；六库均有历史，新增四库历史各一条、checksum 64 位、本库运行账号可 SELECT 而不能 INSERT/UPDATE/DELETE。核心链路/隔离/签名计费不退化，容器/卷/网络清理。输出 `pr7-sixdb-services.log`。

首次 baseline 只读验证结构，成功只新增历史回执，无业务数据/DDL 变更。后续历史 verify 不会重放历史结构快照，不等于持续漂移检查；也不证明 RLS/权限安全，后者继续由独立专项验收。PR-7 尚缺统一 init 与持续全量结构/索引/RLS 核验，阶段 A 仍未退出。没有生产流量、延迟或成本改善声明；本轮仅本地提交，无远程 CI 结论。

## PR-7 子批次 5：实际 schema/index/RLS 漂移核验

基线 6713a42。新增 schema_drift.py、current-schema.json、隔离 PG 夹具和 test-schema-drift.py；CLI verify 在历史核对后验证实际结构，升级和完整服务启动在角色配置前后验证。CI 加入漂移门禁。无业务 DDL，设计/范围/演进/回退见 ADR-0002 及迁移说明。

实际验证：

- `python scripts/test-schema-drift.py`：六库只读正例，**87 张表、1 视图、4 个受控函数**匹配；**14 类实际 SQL 故障**（表/列/类型/默认值/非空/索引/约束/ENABLE/FORCE/缺策略/策略谓词/额外策略/函数权限属性/额外表）、**3 类 catalog 元数据异常**（失效索引、未知视图/受控函数）全部拒绝。另验 stale contract/新 manifest 不一致和实际 CLI 关闭 FORCE 时失败、修复后成功。输出 `.local/implementation/pr7-schema-drift-final.log`。
- `python scripts/test-versioned-migrations.py`：**17 项通过**及六库真实 CLI migrate/verify。版本历史与结构核验共存，原事务回滚/锁/权限/数据保留不退化。输出 `pr7-drift-migrations.log`。
- `python scripts/test-security-flow.py`：新库/历史库均在角色配置前后通过结构校验，再完成实际核心链路、双租户隔离、媒体/平台权限、签名计费及 Java Readiness 三类故障回归。六库历史权限匹配，测试容器/卷/网络清理。输出 `pr7-drift-services.log`。
- 新增候选输出选项允许在 .local 生成待审结构，保留拒绝覆盖行为；独立从第二套 PG 初始化生成的候选与登记 JSON 内容一致，输出 `pr7-schema-candidate.log`。

历史库服务夹具现在模拟已知 file_meta_tenant_isolation 被改宽，由原增量替换；未知额外策略则应阻断 verify，不能自动信任。原独立 P0 测试的额外宽松策略/strict guard 覆盖保留。本子批次没有修改任何已应用 SQL 或 checksum。

当前检查范围为登记 public 应用结构及受控函数；迁移历史另验，不含全部授权/owner/触发器/扩展版本/业务数据和备份。运行期 Readiness 仍是最小结构 gate，不将单次 metadata 检查称生产性能数据。PR-7 还剩统一 init，阶段 A 尚未退出；本地成果不等于远程 CI 或真实上线。

## PR-7 子批次 6：统一 init/migrate/verify 与本地阶段 A 退出

基线 a2bbecd。新增 database_init.py、test-database-init.py，CLI 接入 init，CI 加入初始化负面用例；实际服务夹具增加空服务器经 CLI 初始化路径。原 SQL 和已登记 checksum 未修改，单独的角色/密码 provisioning 继续保留。详细设计/失败状态/回退见 ADR-0002 与迁移说明。

实际验证：

- `python scripts/test-database-init.py`：两套独立空 PG17。第一套执行真实 CLI init，六库 **87 张表**通过实际结构核验；重复 init 拒绝、后续 migrate 无待执行版本、verify 六库成功；无关 postgres 哨兵与业务种子行数保留。覆盖 init 锁竞争、无 CREATEDB 身份拒绝和错误 source 控制语法。第二套注入 billing 基线除零，SQLSTATE 22012；该库 DDL 全部回滚，前序 AI 库/全部六个已创建数据库保留，部分状态重跑明确拒绝。输出 `.local/implementation/pr7-init-final.log`。
- `python scripts/db-migrate.py init --database customer_db`：拒绝单库 init，实际 exit_code=2，未开始数据库操作。输出 `pr7-init-args.log`。
- `python scripts/test-security-flow.py`：**默认 Compose 新库、历史库升级、空服务器经实际 CLI init 三套完整服务**均通过；五个 Java 数据服务继续使用受限账号，六库历史只读，平台审计匹配，原核心/双租户/媒体权限/知识入库/访客 WS/签名计费链路不退化。每套 15 次 Java 结构/权限故障注入，共 **45 次执行**通过，修复后恢复，无业务数据泄露；三套容器/卷/网络清理。输出 `pr7-init-services.log`。

init 本身不配置运行密码、不启动业务，不保证跨库原子性或自动恢复部分建库；生产备份/恢复/部署/运行指标仍属 M12/阶段 E。Java 本批未修改，最近 clean verify 结果见子批次 3。未推送，不声称远程 CI 通过。

阶段 A 的本地退出条件现已满足；PR-7 在本地开发/升级和登记结构范围关闭。PR-8/9/10、M01/03/04 的 Runtime/工具/审批仍未实现，下一阶段按 B1 → B2 → B3 推进。RAG 首批拒答不代表事实支持问题已解决，后续仍按 C/D 做质量与回放；没有生产流量或客户收益数据。

## B1 子批次 1：持久化 Run/Step 存储原语（2026-11-19）

基线 bd4da8c。新增 ai/runtime/{types,store}.py、AI 002 SQL、ADR-0003、34 项类型/真 PG 测试及 test-agent-runtime.py；更新能力组授权、迁移历史保护、manifest/current-schema、依赖受表数影响的夹具与 CI。PR-8/M01 只部分实现，没有接 Worker、API、Java Outbox、动态工具循环、HITL 或完整 Checkpoint。

设计：按 tenant/source_event_id 幂等和不可变输入哈希；租户有界积压准入；同会话递增序列入队，数据库部分唯一索引/领取锁保持一个 RUNNING；数据库时钟、租约、递增 fencing；预留 Step 时原子扣步骤/模型/工具预算，接管/重试不重置。失联调用标 UNKNOWN；WRITE 未知结果进入 SIDE_EFFECT_UNKNOWN 并阻断同会话后续事件，独立会话仍可进展。取消不能谎称撤销已有写。持久化只存白名单业务引用，不接收 JWT/原始 prompt/工具参数正文。

实际验证（本机 Python 3.11.5、pytest 9.2.0、Docker PostgreSQL17/pgvector、随机受限 AI 凭据，合成数据、无模型调用）：

- `python scripts/test-agent-runtime.py`：**34 passed**，其中类型/输入16项、受限角色真PG18项。含8路重复入队/竞争领取/独立会话、40条阻塞会话不饿死独立会话、旧 fence 六类写回拒绝、重建 store 不重置预算、重试预算不双扣、取消/完成10轮竞争、超时、未知写阻断、RLS/错误租户/额外宽松策略/DELETE/TRUNCATE/DDL/管理员SET ROLE/队列函数权限拒绝。另3类危险能力角色（LOGIN/BYPASSRLS/CREATEROLE）各由迁移和provisioning拒绝，共6次负面执行。原始日志 `.local/implementation/b1-runtime-final.log`，JUnit `.local/verification/runtime-store.xml`。
- 初轮 **33 passed/1 failed**：deadline先到而租约未到的故障没有被领取器收敛为终态；修正领取和队列发现条件后全量重跑通过。保留 `b1-runtime-first.log`，没有删除失败样本。
- `python scripts/test-versioned-migrations.py`：**17项通过**及实际CLI，日志 `b1-migrations.log`；`test-schema-drift.py`：**89表/1视图/5函数**匹配，14个SQL故障和3个元数据异常拒绝，CLI失败/修复通过，日志 `b1-schema-drift.log`。历史四库baseline35表保持不变；desired-state仅新增AI两表/一函数和002checksum。
- `python scripts/test-schema-baselines.py`：四库基线采纳/重复/verify、20类负面执行通过，日志 `b1-baselines.log`。夹具明确只验证不可变001基线，新增002由完整迁移/漂移测试负责。
- `python scripts/test-database-init.py`：89表空库初始化、重复拒绝、权限/锁/失败库回滚和部分状态保留通过，日志 `b1-init.log`。
- `python scripts/test-runtime-security.py --with-ai-readiness`：原**57条**新旧库安全断言及每套**4项**AI Readiness真PG通过，日志 `b1-security.log`。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-ai.xml`：**62 passed、24 skipped、1 xfailed**。24跳过为18项Runtime PG、4项Readiness PG和2项KB PG，Runtime/Readiness由上述专用脚本执行，KB另属原测试；已知引用正文xfail继续保留，不计成功。

迁移/回滚：AI 002走既有权威迁移；运行组NOLOGIN/非owner/无管理员能力、仅新表S/I/U，AI登录沿用已有凭据。先migrate、唯一角色provisioning、verify再启动业务，不把业务进程变迁移管理员。保留Run/Step/版本回执，回退不DROP/清表/改checksum；首批未接业务，可停用新模块保留原客服Workflow。

剩余风险：这里只是存储API，store重建不等于真实Worker进程恢复或端到端业务恢复；尚无源端Outbox/顺序派发/结果幂等入口。旧进程物理调用可能继续，fencing只拒绝失效写回，最终写工具必须再鉴权/幂等/核验。未知写目前需要保留待核查，显式恢复界面属B3。队列按已收到事件序列处理，不能修复未收到的更早消息。只有调用计数/重试/时间硬限制，Token/成本硬预算、公平调度与量化性能均未验收。不报告生产流量、准确率或费用收益；CI新增门禁尚未在远端运行。

- B1 存储改动后的 `python scripts/test-security-flow.py` 最终三条路径均通过：默认新库、历史库升级、CLI init；每套15次Java Readiness故障，共45次执行，原企业开通/平台审核/媒体/RLS/知识检索/访客WS机器人/签名计费链路保持通过，AI历史002已登记。日志 `.local/implementation/b1-services-retry.log`。首次运行出现服务启动阶段PG连接异常，未完成验收，记录 `b1-services.log`；并行PG夹具结束后完整重跑通过，原因未确认，不冒充已定位产品问题。三套隔离容器/卷/网络已清理。Java源码本批未改，复用前述clean verify产物；本轮没有重新声称Java全量构建。

## B1 子批次 2：有界 Worker、步骤复用和真实进程故障（2026-11-19）

基线 fb337d1。新增 ai/runtime/worker.py、test_runtime_worker_postgres.py、可杀死的runtime_worker_process.py测试夹具；RuntimeStore默认连接timeout2秒，队列查询也设置SQL/锁timeout；扩展同一Runtime测试脚本。无新迁移/服务依赖，不自动启动Worker，未接客服业务入口。设计和限制见ADR-0003第二子批次。

实际验证：

- `python scripts/test-agent-runtime.py`：**49 passed**（原16项类型、18项真PG存储，加15项真PG Worker故障/行为），额外6次危险角色负面执行仍通过。日志 `.local/implementation/b1-worker-final.log`，JUnit `.local/verification/runtime-store.xml`。首轮46项已通过后，补充3项负面用例再完整重跑，不选取有利子集。
- Worker覆盖正常完成/同意图步骤复用、模型瞬态错误有限重试且前置已完成步骤不重复、未知写即使瞬态分类也不重试、永久/未知异常脱敏、未知版本拒绝、单槽无额外任务排队、I/O期间自动心跳、取消后晚到结果拒绝、零模型预算不调用、循环步骤预算终止、本地租户轮转/已停止轮询不领取、心跳DB异常撤销执行权与后续接管、跨重试逻辑步骤参数变化拒绝。
- 两项真实进程故障：独立Python子进程完成前置步骤并在外部步骤RESERVED后被父进程kill，等待真实PG租约过期。MODEL场景由新Worker接管，已完成前置步骤不重跑、旧步骤UNKNOWN、新模型尝试计入原预算，最终SUCCEEDED，model_calls=2/retry_count=1/attempt=2；WRITE场景不调用新handler，FAILED/SIDE_EFFECT_UNKNOWN，tool_calls仍1。没有真实业务副作用或付费模型调用。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-worker-ai.xml`：**62 passed、39 skipped、1 xfailed**。跳过=33项Runtime真PG+4项Readiness真PG+2项KB真PG；Runtime33由上述专项实际执行。既有RAG/认证单元通过，已知引用正文xfail仍未修复。

本批仅Python执行原语，未改SQL/Java/HTTP路由；前一批六库迁移/结构/全服务回归结果不冒充本批新跑。无新的端到端业务恢复结论。下一批必须补Java消息事务Outbox、按会话顺序可靠派发、真实受鉴权的AI接收入口和幂等结果回写，然后才能关闭B1。

剩余风险/回滚：Python无法强杀阻塞线程，handler必须有界I/O并协作取消，fencing只控制有效写回。未知写仍需后续B3显式核验。仅本地轮转，不代表跨进程租户并发/费用硬额度。Token/成本、生产吞吐和真实模型成功率未测。回退可停用Worker模块保留Run/Step历史和原Workflow；接入前不修改原触发路径。本批CI复用已登记专项命令，尚无远端运行结果。

## B1 子批次 3：Java 事务 Outbox 存储与投递原语（2026-11-19）

基线1ba4c5e。新增customer_db 005、AgentEventOutbox、AgentEventOutboxPostgresTest；复用生产SessionService/Mapper与受限PG事务夹具。更新唯一角色脚本的DELETE/TRUNCATE收回、manifest/current-schema、现有CI调用的test-customer-rls.py和结构/历史断言。迁移单测的合成版本及cleanup边界改由当前最后版本计算，避免新增005后误删真实回执；没有改已有迁移SQL/checksum。

设计：来源只允许本租户可见访客消息，持久化消息/会话/主体引用、Trace、不可变版本和UUID，不保存消息正文/JWT。与消息同一个Spring事务提交/回滚；只有同tenant/message_id返回原事件。短事务准入锁、积压上限，按会话顺序领取、投递lease/fence、回执eventId+Run UUID校验、有限8次重试，耗尽保留HELD，阻断同会话后续投递而允许独立会话进展。每次操作要求先有可信租户Scope再开事务，并设置LOCAL lock_timeout=2s/statement_timeout=5s。网络调用必须在领取事务外进行。

当前仅提供存储API和事务组合测试，**没有自动接入SessionService、没有Dispatcher/真实AI接收协议，没有切换原机器人路径**。测试里外层事务组合生产appendMessage和outbox.enqueue，不冒充产品已经自动登记事件或端到端持久化回复。

实际验证（JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17，离线模型和合成数据）：

- `python scripts/test-customer-rls.py --with-ai`：**37项Java实际PG测试全部通过**（原25项、新Outbox12项），另**4项KB PG**通过。日志 `.local/implementation/b1-outbox-scoped-final.log`。新增覆盖同事务提交/回滚、六路重复事件、版本冲突、缺Scope/无事务/错租户、非法来源/内部可见消息、顺序派发/错误回执、丢响应同event重领与旧fence拒绝、六路Dispatcher一名胜者、显式重试/崩溃8次HELD、独立会话进展、队列满回滚消息、异常Trace替换、无/错租户与额外宽松策略/RLS/删除/清表/DDL拒绝。
- 首轮新增9项中3项失败，是测试覆写cleanup后遗漏@AfterEach导致前一测试事件残留，修正夹具后34项全通过；再增加3项负面/并发场景并增加SQL/锁超时后，最终37项完整重跑通过。失败日志 `b1-outbox-first.log` 保留，不把夹具错误描述成产品缺陷。
- `mvn -B -f backend/pom.xml clean verify` 九模块成功（`b1-outbox-java.log`）；补充最后的LOCAL timeout后再次 `mvn -B -f backend/pom.xml verify` 九模块成功（`b1-outbox-java-final.log`），PG跳过仅由上述专项计入通过。构建无新增依赖。一次开发期间Maven专项/clean并行造成报告覆盖的可能性，最终验证使用独立顺序命令，原始日志与最后代码对应。
- `python scripts/test-versioned-migrations.py`：**17项通过**及CLI，`b1-outbox-migrations.log`。`test-schema-drift.py`：六库**90表/1视图/5受控函数**匹配，14个SQL/3个metadata故障及CLI失败/修复通过，`b1-outbox-drift.log`。审查JSON只有新增Outbox表及005 checksum，原结构/旧回执不变。
- `python scripts/test-database-init.py`：90表空库初始化、重复拒绝、锁/权限/失败库回滚和部分状态保留通过，`b1-outbox-init.log`。
- `python scripts/test-agent-runtime.py`：**49项通过**（含真实Worker进程kill恢复）、另6次危险能力角色负面执行通过，`b1-outbox-runtime.log`。确认新增customer005不破坏既有AI Run/Worker原语。
- 最后构建后 `python scripts/test-security-flow.py`：默认新库、历史升级、CLI init三套真实服务全部通过，新增核对受限customer身份对Outbox仅S/I/U、无DELETE/TRUNCATE，customer历史5条/AI历史2条。原开通审核/平台权限/私有媒体/知识/访客WS机器人/签名计费以及45次Java Readiness故障不退化；测试容器/卷/网络均清理。日志 `b1-outbox-services.log`。

边界/回滚：DELIVERED只表示接收回执，不是Agent任务成功。HELD尚无运营核验/重驱界面；缺业务鉴权接收、Dispatcher、结果幂等与用户取消传播，B1未完成。需要后续用带body绑定的鉴权防止事件/主体被篡改，Java仍需最终资源权限与写操作幂等检查。新表外键保留事件对消息/会话的引用，数据保留/归档方案仍归阶段E。回滚停止新组件并保留事件/表/历史，不删HELD、不用新UUID绕过去重。无生产客户/流量/准确率/成本或性能提升声明；以上为当时本地结果，远端验证见下节。

## 首轮开发分支推送与 CI 差异（2026-11-19）

14个阶段提交已推送到`codex/p1-reliability`，首轮[CI 44550858743](https://github.com/jkwang1129/SupportFlow/actions/runs/44550858743)对应f373666。前端成功；后端构建、真实PG隔离/计费/Runtime/迁移/结构/初始化、AI认证与RAG门禁、运行角色与凭据分发步骤通过。实际服务验收在默认新库的`POST /customer/tickets/platform`遇到500，故整轮CI失败，不能以本地通过宣称远端验收通过。

原CI未上传服务异常，当前先补结构化失败诊断：只读取固定服务日志，输出异常类、项目/PG栈位置及有限数据库错误类别；不复制异常消息、SQL、请求、响应、环境或凭据文件，写入既有`.local/verification/`报告目录。失败时仍执行原清理，诊断不更改验收断言、权限或重试次数。`python -m unittest discover -s scripts/security -p 'unit_*.py' -v`：5项通过，其中2项新增，验证敏感消息/SQL/未知文件不进入报告，最多180事件/服务。根因尚待复现；本批不混入未完成的Runtime接收API。回滚只需撤销诊断脚本及调用，无数据库改动。

4b2c306推送后的[CI 44552635433](https://github.com/jkwang1129/SupportFlow/actions/runs/44552635433)已全部成功：前端和backend-and-isolation两个job均成功，后者包括三种本地部署初始化路径的实际服务验收。首轮500未复现、根因未确定；这是重跑结果，不是根因已修复的证据。结构化诊断留作下次失败取证，main保持不合并。

## B1 子批次 4：受鉴权的持久化接收端（2026-11-20 整理，测试于11月19日执行）

基线4b2c306。新增`ai/core/runtime_auth.py`、`api/runtime.py`及对应单元/真PG测试，复用RuntimeStore；更新认证安装、路由、配置与只读Readiness。Java新增`AgentRuntimeProtocol`和3项协议测试。协议与边界见ADR-0003第四子批次，默认开关关闭，无新SQL、无Dispatcher或业务触发切换。

设计：仅customer独立服务密钥可签发；新audience隔离旧凭据，签名绑定method/raw path/query/tenant和原始body SHA-256，35秒有效窗口。ASGI在解析前将body限制8192字节，拒绝重复JSON/认证头/租户头/Content-Type和额外字段；只保存严格事件引用及固定runtime-v1预算。Java克隆同一wire bytes用于签名和HTTP body，固定跨语言签名样本已互验。202只是入队，重复同输入返回原Run，变更冲突409，背压429、故障503；状态/取消只按可信tenant访问，未知写取消仍保留SIDE_EFFECT_UNKNOWN。启用后Readiness检查新表所需列、有效S/I/U、无DELETE/TRUNCATE、RLS/FORCE/owner/角色/队列函数权限，故障关闭业务，live保持200。

实际验证（本地JDK25.0.5+8、Maven3.9.16、Python3.11.5、隔离PG17，合成数据/离线模型）：

- `python scripts/test-agent-runtime.py`：**64 passed**（原49+新增15项HTTP/真PG），额外6次危险能力组负面执行通过。日志`.local/implementation/b1-receiver-pg-final.log`、JUnit`.local/verification/runtime-store.xml`。覆盖八路同事件接收仅一Run、改变主体/资源/Trace冲突、不刷新预算、跨租户状态与取消404、重复终态仍终态、积压满只拒新事件、未知写取消不谎称撤销，以及缺列/表、撤S/I/U/函数执行/能力组、BYPASSRLS、额外DELETE和NO FORCE故障的503/live200/原地修复。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-receiver-ai.xml`：**102 passed、54 skipped、1 xfailed**，日志`b1-receiver-ai.log`。新增38项接收协议/Schema/负面与2项开关元数据测试；54跳过包括48项Runtime真PG、4项Readiness真PG、2项KB真PG，48项由上述专项执行。既有引用正文xfail保留，不计通过。未发真实模型请求。
- `mvn -B -f backend/pom.xml verify`：九模块成功，新增协议测试**3/3通过**，日志`b1-receiver-java-final.log`。验证固定Python签名样本、已发送body字节与签名一致/不受调用方后续数组修改影响、10秒请求超时、query/URL凭据/未知端点/平台租户/超大body/短密钥拒绝。
- `python scripts/test-security-flow.py`：默认新库、历史升级、CLI空库初始化三套实际服务均通过，包含原核心流程、异常权限、45次Java Readiness故障、迁移/角色/Outbox权限核对；测试容器/卷/网络清理。日志`b1-receiver-services.log`。此项使用默认关闭的新接收API，验证原客服路径不退化；新入口本身由上述真实PG HTTP fixture验证。
- 首轮接收单元有1项断言把有效异租户签名/正文不符的403误当签名篡改401，修正为实际篡改header后通过。首轮59项PG有1项注入仅撤继承组INSERT、遗漏业务账号直接授权，无法构成有效权限缺失；改为撤双重授权后通过，并补充其他负面故障，最终64项完整重跑。失败日志`b1-receiver-pg-first.log`保留；这些是测试构造问题，不声称产品漏洞修复。

剩余风险/回滚：没有Java Dispatcher、业务快照/幂等结果、用户取消授权传播或Python实际业务handler，B1仍未闭环。服务签名不代替资源授权；后续Java最终写入仍须核验主体、租户/资源、当前状态、执行权及幂等键。Readiness不是完整策略漂移审计，发布仍须权威schema verify。回滚先停后续派发/Worker，再关闭接收开关，保留全部事件/Run/Step；不清表、不换eventId绕过去重。没有生产吞吐、准确率、费用或端到端恢复的新增声明。

0906c03推送后的[CI 44657155805](https://github.com/jkwang1129/SupportFlow/actions/runs/44657155805)两个job均已成功；本地接收协议、Runtime专项与原三条业务部署路径的远端门禁通过。首轮f373666工单500仍未复现，保留既有未确定根因边界。

## B1 子批次 5：显式单次可靠派发（2026-11-20）

基线0906c03。新增`AgentEventDispatcher`、`AgentRuntimeClient`、真PG派发测试和回环HTTP故障夹具/单元测试；Outbox增加受lease/fence约束的hold及专用失权异常。扩展既有`test-customer-rls.py`并复用CI，不新增服务、依赖、SQL或迁移版本。两类不是自动启动组件，仍没有调度器/消息自动登记，接收API默认关闭。

设计：单执行槽tryLock满时立即BUSY；先短事务领取并提交、再HTTP、最后另开短事务确认/重试/挂起。已有事务/错Scope/非法tenant拒绝；领取后、网络前再次检查中断。网络总deadline≤10秒（包括body）、连接≤2秒、响应≤8192字节；不跟随重定向、无JWT转发，复用body-bound签名并恢复持久化Trace头。202回执必须匹配tenant/event/canonical Run UUID/合法状态；坏回执及永久HTTP错误HELD，408/425/429/5xx/网络瞬态有限退避，最多8次投递；中断保留SENDING等待接管。旧lease的成功/失败回执均返回STALE，不覆盖新执行者。DB提交异常原样交调用者，重新检查持久状态，不能自动生成新事件。

实际验证（本地JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17、合成数据）：

- `python scripts/test-customer-rls.py --with-ai`：**50项Java真实PG测试通过**（原37+新13），另**4项KB PG通过**；日志`.local/implementation/b1-dispatcher-pg-final.log`。首次10项派发完成47项全通过，补异常回执/HTTP中断后49项全通过，最后补领取提交后的停止边界后50项完整通过；每次均执行全组，没有选取有利子集。
- 13项PG派发覆盖：网络期间Hikari活跃连接为0且另连接可NOWAIT锁源行；禁止外层业务事务/错tenant/预先中断；领取提交后中断不发HTTP；瞬态失败保持源顺序；永久HELD不阻塞独立会话；8次耗尽保留事件；丢响应后沿用event及原Run回执；坏租户回执不能释放后续消息；HTTP中断保留原lease；同Dispatcher立即BUSY；另Dispatcher接管后旧成功和旧失败都STALE；确认事务回滚后可重发；真实提交已完成但注入“提交结果丢失”异常后，重调只见DELIVERED且不再发HTTP。
- `AgentRuntimeClientTest` **7项**、`AgentRuntimeProtocolTest` **3项**通过。回环HTTP覆盖13种状态分类、重复/畸形/错身份回执、非JSON、8193字节响应、正文晚到/响应头晚到的整体timeout、禁止redirect、中断标记、连接失败和非法配置；验证实际请求只有引用、无Authorization，Trace来自源事件，发送字节与签名一致。故障超时断言只是注入延迟的停止边界，不是延迟/吞吐基准。
- `mvn -B -f backend/pom.xml verify`：九模块成功，日志`b1-dispatcher-java-final.log`；PG条件测试由上述专项实际执行，普通构建的跳过不计通过。本批原语与测试不依赖付费模型或真实客户数据。

边界/回滚：上述派发的HTTP目标是受控故障服务；Python真接收、RLS/重复/取消已在前批独立验收，单个Java→真Python交付仍留下一子批次。没有实际业务handler、自动调度、业务快照/结果幂等或自动触发，DELIVERED不等于Agent成功，B1未关闭。HELD缺运营核验入口，租户公平性与费用硬额度未完成。此批未改现有业务触发/认证/SQL，本地完整服务沿用前批证据，不冒充本批新跑；远端全流程由推送CI验证。回滚停止使用新Dispatcher/client，保留全部Outbox/Run/Step与HELD；不清表或换UUID绕过去重。

01378f7推送后的[CI 44659519837](https://github.com/jkwang1129/SupportFlow/actions/runs/44659519837)两个job全部成功，包括新增50项PG Java、Runtime、原三种实际服务部署路径等门禁。未合并main。

## B1 子批次 6：Java→真实AI→PG交付验收（2026-11-20）

基线01378f7。仅补测试基础设施：`AgentEventDeliveryPostgresTest`、`runtime_receiver_process.py`、`runtime_delivery_fixture.py`及原Runtime脚本的可选`--with-java-delivery`，CI调用此选项；生产实现、默认开关、SQL/迁移均不变。真实FastAPI应用保留原鉴权/Readiness/受限账号检查，显式mock模型及空模型密钥，不读开发者.env；来源数据为合成消息，Java使用正式supportflow_customer、AI使用正式supportflow_ai/KB身份。不是替换RuntimeStore的Stub。

`python scripts/test-agent-runtime.py --with-java-delivery`最终通过：**64项Python类型/真PG/Worker/HTTP专项 + 6次危险能力组负面执行 + 2项实际跨语言Java交付**，日志`.local/implementation/b1-delivery-final.log`，Python JUnit仍为`.local/verification/runtime-store.xml`，Java报告位于customer-service的surefire-reports。

两项新用例：正常Java Outbox领取/签名接收/持久化回执，重发仍原Run，实际AI状态PENDING/零model和tool计数，匿名401、跨租户GET404；丢回执用例由回环代理将原始凭据/body转给真AI后延迟body，第一次实际为DELIVERY_TIMEOUT，第二次仍同event/Run，源attempt=2。脚本最后从customer_db与ai_db逐项核对tenant/event/run/message/seq及每事件一Run，两行均匹配；不以HTTP成功代替持久化证据。AI进程与一次性PG由脚本清理，用户原项目容器未操作。

首轮正常场景通过、故障场景失败；新增脱敏错误码/代理状态诊断后定位上游实际400，而非注入的超时。代理默认HTTP版本与产品客户端不同；只将代理设成同样的HTTP/1.1后，原1秒deadline/4秒故障延迟及所有认证/去重断言保持，2项通过，再为匿名探测补有界请求后全组重跑通过。失败日志`b1-delivery-first.log`、`b1-delivery-diagnostic.log`、`b1-delivery-diagnostic-2.log`保留；`b1-delivery-http1.log`为修正后的首轮通过。该结果说明夹具传输配置不一致，不声称修复了产品协议漏洞，也不把故障deadline当真实延迟基准。

剩余边界/回滚：这里只闭合持久化交付，Run仍PENDING，未启动业务Worker、未执行模型或工具、未自动登记真实访客触发、未写回业务结果。B1还缺可信业务快照/结果幂等和最终接入；B2/B3及C～F保持原计划。测试入口可移除可选flag/CI调用回退，不回退已应用SQL或删除事件历史。性能、Token/费用、实际任务成功率均未在本批测量。

e21c400对应的[CI 44661219385](https://github.com/jkwang1129/SupportFlow/actions/runs/44661219385)两个job全部通过，真实跨语言交付已纳入远端门禁；不只是本机结果。

## B1 子批次 7：不可变业务来源快照（2026-11-20）

基线e21c400。新增customer 006/`AgentSourceSnapshots`/15项PG测试/ADR-0004；更新manifest、review过的desired-schema、唯一角色脚本及既有验收脚本。快照持久化在customer_db，只复制当前访客文本与必要业务引用，可信来源和不可信文本分区；原文≤8192 UTF-8字节、JSON≤65536字节，显式拒绝超限，不静默截断。精确JSON文本/Hash/版本/快照ID/DB时钟TTL可重读，但每次重查事件已DELIVERED、Run匹配、消息/会话/序号/主体一致且仍可见、未结束/未被人工接手。内容或客户绑定变化冲突，过期不重建；表仅S/I、强制RLS和restrictive guard。此批不是长历史压缩、Checkpoint或对AI开放的来源接口。

实际验证（JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17、合成数据/离线模型）：

- `python scripts/test-customer-rls.py --with-ai`：**65项Java真实PG + 4项KB PG通过**，日志`.local/implementation/b1-source-pg-final.log`。新增15项覆盖：交付/Run前置、稳定精确UTF-8哈希、6路并发一份快照、事务回滚、Scope/错租户、源内容/客户绑定变更、删除/隐藏/人工/关闭/类型撤销、主体/消息seq替换、8193字节拒绝和8192边界、过期不刷新、S/I与更新/删除/清表/DDL拒绝、额外宽松策略不能放行、数据库FK错Run=23503/非法JSON和hash=23514、快照篡改拒绝、文本不能改写可信JSON字段。最后补SQLState断言后完整65项重跑通过。
- `mvn -B -f backend/pom.xml verify`：九模块成功，`b1-source-java.log`。新表未注册成运行时迁移/自动启动业务组件。
- `python scripts/test-versioned-migrations.py`：**17项及真实CLI通过**，`b1-source-migrations.log`。首轮只有manifest预期列表仍为001～005而失败，改为明确001～006后全组通过；旧SQL/checksum未改，失败日志`b1-source-migrations-first.log`保留。
- `python scripts/test-schema-drift.py`：六库**91表/1视图/5受控函数**、14个SQL/3个metadata故障、CLI失败/修复通过，`b1-source-drift.log`。候选只由隔离规范初始化生成，审查差异仅新快照表、Outbox回执唯一约束/索引及006回执；原4库历史baseline不变。
- `python scripts/test-database-init.py`：91表空库初始化、重复拒绝/数据保留、CREATEDB拒绝、锁、失败库回滚/部分状态保留通过，`b1-source-init.log`。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**64项Python专项 + 6次能力组负面 + 2项实际Java→AI交付通过**，`b1-source-runtime-delivery.log`。新增源快照结构不破坏接收/Run/Worker或交付去重。
- `python scripts/test-security-flow.py`：默认新库、历史升级、CLI空库初始化三套实际服务全通过，包含45次Java Readiness故障和原业务/权限/计费/媒体/知识/WS流程，新增核对customer历史6条及快照有效权限S/I、无U/D/TRUNCATE；一次性容器/卷/网络清理，`b1-source-services.log`。

首次PG62项有1项测试嵌套两个Spring事务模板，内层rollback-only导致外层UnexpectedRollback；改为单个真实事务边界验证回滚后，加负面用例至65项通过。不是数据已提交或产品回滚失效，初始日志`b1-source-pg-first.log`保留。

剩余风险/回滚：只提供受可信tenant+事务约束的存储API，没有对AI的认证来源接口、结果回写、业务handler、自动调度/消息触发，B1未闭环。TTL固定一小时只是读取策略，不等于自动物理清理；真实数据保留/归档和审批等待跨过期策略待M12/B3。后续恢复必须重新验权，不能仅按snapshotId返回内容。回退停用新组件并保留表/Source/Outbox/Run/迁移；不删除快照、不回写旧SQL。无生产吞吐/客户/收益或模型成功率声明。

bda38bd对应的[CI 44664049211](https://github.com/jkwang1129/SupportFlow/actions/runs/44664049211)两个job全部通过，包括91表结构/初始化、来源快照PG及原三条实际服务部署路径。

## B1 子批次 8：受鉴权的来源API与Python恢复加载（2026-11-20）

基线bda38bd。新增Java RuntimeSourceAccessFilter/RuntimeCustomerCall/注解/RuntimeSourceController，复用原内部接口拦截器作为第二层认证标记检查；SourceClient/Python测试、HTTP/PG测试、实际JAR来源联调夹具。新增的只是只读来源建/读接口及配置，不改SQL或现有访客触发。设计见ADR-0004服务接入部分，默认runtime-source-enabled=false。

身份使用已有customer↔AI这一信任对的独立CUSTOMER_SECRET，新audience/caller/格式隔离旧协议，必须与其他来访服务密钥不同。先校验带body摘要的MAC/短有效期，再有界读取/解析正文，认证过滤器先于Readiness；只允许规范event/run UUID的snapshot路径、业务tenant、无query、GET空body/POST空JSON。controller核对已认证资源后才绑定Scope、开短事务、调SourceStore；SQL/权限故障映射503且不暴露错误原文。API响应使用精确payload JSON和hash，来源读取不是Run执行权或写入许可。

Python仅按可信Run引用构造固定路径，拒绝任意URL/用户信息/query配置；总deadline≤10秒、响应≤128KiB、不重定向/不解压，严格验证信封、Hash/版本/来源与Run一致及文本边界，异常保留稳定分类。恢复GET可绑定先前snapshotId/Hash，不接受合法但不同快照。DTO repr隐藏正文。没有在此层声称提供Token预算、上下文压缩或语义注入防御。

实际验证（本地JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17，合成数据/离线模型）：

- `mvn -B -f backend/pom.xml verify`九模块成功，`.local/implementation/b1-source-api-java.log`。新来源认证9项、原内部认证含新增“伪造标记头不等于请求属性”共5项，合计14项通过；固定Python MAC样本与Java实际过滤器互验。验证匿名/坏签名及关闭接口不会读body、方法/path/query/tenant/body/expiry篡改、重复头/错误角色/旧协议、非规范资源、8KiB上限、严格JSON和密钥冲突。未用跳过替代PG实测。
- `python scripts/test-customer-rls.py --with-ai`：**70项Java真实PG + 4项KB PG通过**，`b1-source-api-pg-first.log`。新5项用真实controller/filter/interceptor/事务与受限PG验证创建/重读同快照、错Run/tenant与非法body、认证先于缺schema的Readiness、资源撤销/源变更、INSERT撤销503与原地恢复、错误无SQL/原文泄露。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-source-api-ai.xml`：**134 passed、54 skipped、1 xfailed**，`b1-source-api-ai.log`。其中Source SDK **32项**，包括固定Java MAC、只发引用/不发JWT、资源/版本/hash/日期/主体替换、重复字段/超大/压缩/重定向/网络/正文卡住、稳定错误分类、恢复ID/hash不匹配拒绝。54跳过中的48项Runtime真PG由下面专项实际执行；既有引用正文xfail仍保留。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**64项Python专项 + 6次能力组负面 + 2项实际Java→AI交付 + 2条实际Python→customer JAR来源流程**通过，`b1-source-api-real-final.log`。保持原FastAPI与Spring应用、鉴权、Readiness和受限账号，无存储mock；来源流程含相同snapshot/hash、恢复绑定、匿名/错密钥/跨租户拒绝，以及人工接手后原快照重读撤销。实际客户JAR测试只验收文本来源接口，对象存储未作为此夹具依赖；媒体功能另由完整服务回归验证。
- `python scripts/test-security-flow.py`：默认新库、历史升级、CLI init三套实际Java/AI/RustFS服务均通过，含原用户/企业审核、平台/媒体权限、知识/WS机器人、签名计费、45次Java Readiness故障及91表/6条customer历史与Snapshot最小权限；新来源接口默认关闭，证明原闭环不退化，`b1-source-api-services.log`。所有一次性资源清理，未操作用户其他项目容器。

本子批次各组首轮均通过；后补恢复绑定/禁读body/真实恢复引用校验后分别重跑对应单元、实际互通及完整回归。服务密钥、bootstrap密码与原始私有日志不进入仓库/报告；公开内容使用合成数据。未计生产流量/用户/收益，没有将4条桥接流程等同Agent任务成功率。

剩余风险/回滚：没有结果回写、写入执行权校验/幂等、业务handler或自动触发，B1未闭环。Run可处PENDING时读取来源用于准备输入，此权限不能用于业务写操作。SchemaReadiness是最低列/SELECT检查，未代替完整漂移与DML权限核验；缺INSERT时API明确503，发布仍须migrate/provision/verify。恢复/审批、长历史策略、敏感用户输入出站策略与物理清理留后续。回滚关闭runtime-source-enabled并保留快照/回执；原FAQ/图片Workflow不受此开关控制，不清表或重用新eventId。

fb26a1b对应的[CI 44668976501](https://github.com/jkwang1129/SupportFlow/actions/runs/44668976501)两个job全部通过；源码及阶段记录已推送开发分支，未合并main。

## B1 子批次 9：当前执行权的短期许可签发（2026-11-20）

基线fb26a1b。新增ADR-0005、`ai/runtime/authority.py`、`RuntimeStore#authorize_reply`、鉴权路由POST `/api/ai/v1/runtime/runs/{run_id}/authorize`；扩展原Runtime脚本纳入27项真实PG权限测试。没有新服务、依赖或SQL，不改现有业务触发；Runtime原默认关闭开关仍生效。

customer服务须使用原body-bound Runtime鉴权。服务身份仅允许发起核查；AI在受限tenant事务里重查RUNNING/未取消/runtime-v1、owner/fence/source-event，并锁定唯一匹配的RESERVED TOOL/WRITE意图。操作固定session.reply/reply-v1，资源来自Run主体，snapshot/精确参数SHA绑定持久化意图。READ/MODEL/完成或UNKNOWN步骤不签发；多条同意图也拒绝。DB时钟在Run/Step锁均获得后检查lease/deadline，许可最多5秒并提前100ms失效，绑定所有引用和步骤，使用独立audience的HMAC。只读签发不增加/重置Run预算，不执行业务写。

本地环境仍为Python3.11.5/JDK25.0.5+8/Maven3.9.16/隔离PG17，全部合成数据和离线模型：

- `cd ai-service; python -m pytest -q tests/test_runtime_authority.py tests/test_runtime_api.py tests/test_runtime_source.py`：**93项通过**，含新23项许可协议/API测试；`.local/implementation/b1-authority-unit.log`。覆盖字段签名、其他角色/旧凭据拒绝、参数与body/path/method/tenant篡改、严格Schema、关闭不连接DB、409/503不泄露原始错误。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-authority-ai.xml`：**157 passed、81 skipped、1 xfailed**；`.local/implementation/b1-authority-ai.log`。75项Runtime真PG跳过由专项实际执行，余下为4项readiness/2项KB外部条件测试；既有引用正文xfail未消失。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**91项Python专项通过**，含新27项执行权真PG；6次危险角色负面执行、2项真实Java→AI持久化交付及2条实际Python→customer JAR来源流程仍通过，`.local/implementation/b1-authority-real-final.log`。新测试核对租约/取消/终态/旧fence/错主体意图/READ与未知写拒绝、跨tenant不可见、重复签发不改任何Run/Step；实际锁等待跨过lease后仍拒绝，不使用锁前时钟。

首轮专项**90通过、1失败**：锁等待测试在同一观测事务重复读pg_stat_activity，却未刷新统计快照，未观测到等待连接。夹具改为专用application_name且每轮pg_stat_clear_snapshot，保留原900ms租约/2s锁超时和原拒绝断言，再跑91项通过；初始日志`b1-authority-real-first.log`保留。没有按重跑成功宣称业务权限缺陷修复，也没有放宽权限规则。

剩余风险/回滚：本批只有签发，没有Java验签/数据库时钟消费、结果幂等回执或业务落库，不能称结果回写已完成。后续必须主动查询控制面、重新校验业务资源、原子写消息和稳定Run回执；短期许可不能保证撤销已在途动作，取消仍保留SIDE_EFFECT_UNKNOWN。没有时钟跨区域、性能/成本/模型质量数据。回退停用Runtime接口或移除此新路由，保留Run/Step/意图历史和现有业务；不清表、不重置预算、不重新生成event绕过不确定写。

59140ca对应的[CI 44670897424](https://github.com/jkwang1129/SupportFlow/actions/runs/44670897424)两个job全部成功，包含新27项真实PG权限测试和原三条实际服务部署路径。

## B1 子批次 10：Java主动核验执行权（2026-11-20）

基线59140ca。新增`AgentActionPermit`及8项Java协议/HTTP负面测试、1项实际Java→AI→PG执行权联调，扩展原AgentRuntimeClient/Protocol和Runtime夹具，Python固定MAC测试增加与Java相同的字面签名。复用已有有界BodySubscriber/HTTP客户端、body-bound请求签名与默认关闭Runtime；没有新SQL或业务入口切换，设计补充见ADR-0005。

Java在业务事务外请求固定Run的authorize端点，只传主体/版本/快照/参数摘要等引用，不传JWT或答复正文。许可要求精确13字段、严格正整数及64位范围、固定版本/操作、全部请求引用、Step与HMAC、当前时间后且最多5秒；重复JSON/尾随对象/过期/超远期/字段替换均拒绝。只能通过验签创建许可对象，后续写事务仍必须用客户DB时钟再次requireValidAt、重验资源并执行幂等。HTTP共享原整体deadline（最多10秒，含body）/8KiB响应上限/无重定向/无隐式重试，区分稳定AUTHORITY错误码且不保留服务错误正文。检测到活跃业务事务即在发HTTP前拒绝。

实际结果（与前批相同本地工具链/一次性PG17/合成数据/离线模型）：

- `mvn -B -f backend/pom.xml verify`：**九模块成功**，新8项AgentActionPermitTest及原7项Client/3项Protocol共18项通过，日志`.local/implementation/b1-authority-client-java.log`。真实PG和联调的条件跳过另由专项执行，不计通过。
- `cd ai-service; python -m pytest -q tests/test_runtime_authority.py`：**23项通过**，`b1-authority-client-python.log`；Python和Java固定`action-v1`数据共同核对MAC `93e4...c18`，不是仅各自用自身函数自证。
- `python scripts/test-customer-rls.py --with-ai`：**70项Java真实PG + 4项KB通过**，`b1-authority-client-customer.log`；共享HTTP传输提取后，原Dispatcher/Outbox/快照/来源接口与会话并发不退化。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**91项Python专项 + 6次角色负面 + 原2项Java交付/2条实际来源流程 + 新1项Java执行权联调通过**，`b1-authority-client-real-final.log`。新联调运行实际AI应用/认证/Readiness/受限PG，Java不获bootstrap或AI数据库密码；有效WRITE意图取得并验签许可，错tenant/fence/hash/服务密钥分别404/409/409/401，实际取消后新签发409。脚本再查PG：Run为FAILED/cancel_requested/SIDE_EFFECT_UNKNOWN，步骤UNKNOWN、model=0/tool预算=1/retry=0；没有业务消息写入，也不把这项测试当作任务成功率。

首轮Java测试编译因测试中Jackson旧put重载返回JsonNode而失败，改为BigInteger重载后九模块成功，保留`b1-authority-client-unit-first.log`。第一次真实联调中获取/验签与身份拒绝均通过，辅助取消请求400；该辅助HttpClient误用默认版本，改为与生产客户端一致的HTTP/1.1后保持原断言全组通过，`b1-authority-client-real-first.log`保留。未改产品权限或放宽timeout，也不把夹具调整描述成产品缺陷修复。

剩余风险/回滚：Java目前只“取得并验证”许可，不是事务内写入消费；结果回执/资源重验/SessionService原子写入、业务handler与调度仍待下一批。取消后已在途许可可能在短窗口有效，不承诺即时撤销或已写动作回滚。没有新准确率/Token/生产流量/客户或收益数据。回滚停止调用authorizeReply，原enqueue/Dispatcher及业务入口保持；保留全部来源/Run/Step，不删除不确定意图，不重新生成Run绕过去重。

a839f15对应的[CI 44672099556](https://github.com/jkwang1129/SupportFlow/actions/runs/44672099556)两个job全部通过，包含实际Java许可获取/验签、取消后拒绝，以及原三种部署服务验收。

## B1 子批次 11：回复与结果回执的事务幂等（2026-11-20）

基线a839f15。本批交付显式结果存储组件，尚未接入结果HTTP接口或自动业务触发。设计见ADR-0005。修改文件：

- `AgentReplyArguments.java`/`ai/runtime/reply.py`：固定reply-v1的精确UTF-8参数摘要；拒绝空白/NUL/非法Unicode/超过8192字节，不trim、不归一化或截断，DTO日志隐藏正文。
- `AgentResultReplies.java`：可信tenant事务内按Run锁去重；先UPDATE锁会话，再复用SourceSnapshots重验来源/快照/人工接管/关闭，用客户DB时钟消费许可；固定BOT/文本/全可见，复用SessionService写消息与稳定clientMsgNo，同事务写回执，返回前再查许可时效。已提交同内容回执只返回引用，异内容冲突；既有消息的角色/内容/目标也必须核验。
- customer `007_agent_result_receipt.sql`、manifest、`40-runtime-roles.sql`、current-schema和customer readiness：回执只S/I、FORCE RLS及restrictive guard；复合FK绑定同tenant/event/run/snapshot与同tenant消息，不复制回复正文。只新增007，未改已应用SQL/历史baseline。审查候选差异仅回执表、来源/消息的两个引用唯一约束/索引和007历史，六库92表/1视图/5受控函数。
- `AgentResultRepliesPostgresTest`、`AgentActionPermitFixtures`、`AgentReplyArgumentsTest`、`test_runtime_reply.py`；customer PG、migration/init/security验收脚本及版本预期；本阶段计划和ADR/本记录。

本地环境：JDK25.0.5+8、Maven3.9.16、Python3.11.5、一次性PG17；合成消息/密钥，不调用付费模型、不操作用户业务库。

| 复现命令 | 实际结果/原始证据 |
| --- | --- |
| `mvn -B -f backend/pom.xml verify` | 九模块成功，含新增4项Java参数协议；`.local/implementation/b1-result-java-final.log` |
| `python scripts/test-customer-rls.py --with-ai` | **84项Java真PG + 4项KB通过**，新14项结果存储；`b1-result-pg-final.log` |
| `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-result-ai.xml` | **173 passed、81 skipped、1 xfailed**；新16项摘要协议/负面，`b1-result-ai.log`；外部PG跳过另由专项执行，原引用正文xfail保留 |
| `python scripts/test-versioned-migrations.py` | **17项+CLI通过**；额外从006种入有效消息/已DELIVERED事件/不可变快照，007升级后三行原样保留、结果表为空、重复迁移no-op；`b1-result-migrations-final.log` |
| `python scripts/test-schema-drift.py` | **92表/1视图/5函数**，14个SQL及3个metadata故障、CLI失败/修复通过；`b1-result-drift.log` |
| `python scripts/test-database-init.py` | 六库92表、CREATEDB拒绝、锁、部分失败回滚及拒绝覆盖原数据通过；`b1-result-init-reported.log` |
| `python scripts/test-agent-runtime.py --with-java-delivery` | **91项Python、6次角色负面、2项实际Java交付、2条实际来源流程、1项真实许可联调通过**；`b1-result-runtime.log` |
| `python scripts/test-security-flow.py` | 默认新库/历史升级/CLI初始化**三套实际服务通过**；含原业务/媒体/知识/WS/计费、45次Readiness故障、7条customer历史及回执有效S/I、无U/D/TRUNCATE；`b1-result-services.log` |

新14项PG包含：正常消息/回执/seq/角色/可见性；8路并发相同回调一条回复/回执/序号和一次计费回调；同内容重试在许可过期/会话关闭后仍只确认原结果；不同内容/快照冲突；主动回滚后无消息/回执/seq/afterCommit副作用；过期许可、过期来源、新写时接管/关闭/隐藏/删除/篡改拒绝；真实插入消息后等到许可过期，最终检查使整笔事务回滚；错Scope/无事务/错许可拒绝；其他角色预占clientMsgNo拒绝；撤销回执INSERT后业务消息回滚；FK拒绝外租户消息/错快照，RLS拒绝跨租户INSERT，额外宽松策略仍不可跨租户读，运行账号U/D/TRUNCATE拒绝。

首次Maven编译仅测试的泛型assertEquals重载歧义，明确Integer断言后通过，保留`b1-result-java-first.log`。首轮PG82项全部通过，补来源过期/复合FK两项后84项通过，初始`b1-result-pg-first.log`保留。首次init断言仍为旧91而失败，更新明确92及报告输出后完整通过，`b1-result-init.log`保留；没有放松漂移或权限断言。

边界/风险：存储PG测试使用合成签名许可，实际控制面签发/Java验签由独立真实联调验收；尚未把真实许可、结果HTTP接口和业务落库串在一起，不能称完整Agent链路已完成。计费/渠道事件只验证既有afterCommit的调用次数，未证明外部渠道送达exactly-once；原始计数best-effort、日志脱敏和归档/物理清理留M11/M07/M12。取消/到期不能撤销在途或已提交动作，Run保留UNKNOWN语义。没有性能/模型成功率/Token费用/生产流量/客户或收益数据。

回滚：此组件尚未自动装配或路由业务；停止新组件/回退二进制即可，保留007表、FK、消息与回执，已应用007不得改写或删除历史。未来数据清理需管理员按子表顺序并另立保留策略，不给业务账号DELETE；不换run/event绕过幂等或未知写。

0cc1492对应的[CI 44695373096](https://github.com/jkwang1129/SupportFlow/actions/runs/44695373096)两个job全部通过，包括新增结果事务PG、007数据保留升级及原三套服务部署路径。

## B1 后续回归发现：工单整分钟时间导致HTTP500（2026-11-20）

结果API子批次完整服务回归在FRESH DEFAULT的`POST /platform/tickets/{no}/claim`遇到500，不能把结果接口单元/PG通过代替原业务回归。实际堆栈确定为TicketService.format→toEventVO→PlatformTicketService.detail/claim；`LocalDateTime.withNano(0).toString()`在seconds=0时省略秒，只剩16字符，固定substring(0,19)越界。实际业务时间接近整分钟时才暴露，但现在有不依赖墙钟的确定性复现。先前首轮CI的平台创建500无原始堆栈，仍不把它认定为相同根因。

此问题单独提交：`TicketService.java`改为不可变DateTimeFormatter固定`yyyy-MM-dd HH:mm:ss`，仍保留null、不四舍五入、不修改业务时间或工单号；`TicketTimestampTest`走真实toEventVO转换验证整分钟、午夜、闰日/纳秒及普通秒和null。结构诊断`service_diagnostics.py`补识别Logback的`~[classes/:na]`等后缀，只输出类名/源码位置，后缀与消息仍丢弃；新增脱敏负面单测。

本地JDK25/Python3.11：修复前`TicketTimestampTest` **5项中3项StringIndexOutOfBoundsException**，`ticket-time-before.log`；修复后`mvn -B -f backend/pom.xml verify`九模块成功、**5项全部通过**，`ticket-time-java-after.log`。`python -m unittest discover -s scripts/security -p 'unit_*.py' -v` **6项通过**，`ticket-time-diagnostics.log`。本次原始失败保留`b1-result-api-services.log`，私有客户服务日志/脱敏堆栈另存`.local/implementation/ticket-time-*`，未上传正文/密钥。本地构建工作区还包含未提交的结果API，修复提交只包含上述Ticket/诊断和回归测试；提交后由远端CI验证精确提交，完整服务会在本地继续重跑。没有把重跑成功当作根因修复证据。

回滚可单独revert本修复提交（将重新暴露已复现边界错误），不涉及SQL或数据改写。新结果API仍为独立未完成批次，不混入这个缺陷提交。

## B1 子批次 12：鉴权结果API与提交回执查询（2026-11-20）

基线0cc1492；验收途中另行提交eb44d06工单时间修复，以下结果来自包含该修复的工作区。新增`RuntimeReplyController`/`RuntimeReplyCommand`，扩展既有RuntimeSourceAccessFilter，提取AgentResultReplies的受Scope约束只读get；application.yml增加独立默认关闭结果开关。修改7项API PG/7项认证单元测试与合成签名夹具、customer PG/实际JAR验收脚本、ADR-0005/阶段矩阵。没有新SQL、依赖、服务或业务入口自动切换。

GET/POST固定event/run的reply路径，只允许原Runtime AI服务凭据与业务tenant，无JWT/query/任意角色/工具/URL字段。POST精确6字段并验证owner/fence/规范UUID/版本/正文UTF-8字节与参数SHA；64KiB原始JSON上限用于转义开销，正文仍≤8192字节。来源接口保持原8KiB/空对象。认证先于body/Readiness，收完body后再核过期和摘要；命令经服务端属性传递，不重读请求流。

controller先短事务查询已提交回执；同内容直接确认，无需终态Run重新取得写许可。新写在事务外向既有AI bot-base-url的固定Runtime路径请求许可（3秒deadline），验签后新事务资源重验/DB时钟消费/写消息和回执。GET只返回10个版本化结果引用，不返回内容；404表示“尚未观测到回执”，不证明外部动作永不发生，UNKNOWN不可据此自动盲重放。业务冲突、权限/资源撤销和过期返回明确HTTP状态，基础设施错误不透传SQL或上游正文。

实际验证（JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17、合成数据/离线模型）：

- 窄认证/参数回归 **33项通过**，`b1-result-api-unit-first.log`；含新7项result filter，严格字段/角色/版本/摘要绑定、8KiB正文与64KiB wire边界、双开关独立、关闭不读body、旧snapshot凭据不能反射到reply、正文读取途中到期拒绝。后续Maven亦重跑这些用例。
- `python scripts/test-customer-rls.py --with-ai`：**91项Java真实PG + 4项KB通过**，新7项API，`b1-result-api-pg-first.log`。实际controller/filter/interceptor/客户端/受限PG：正常创建/GET/丢回执重试，内容冲突不再核权，4路同时POST一条消息/回执/计费，跨tenant/错event/匿名拒绝，认证先于缺表Readiness，控制面401/404/409/503不写也不泄露正文；核权期间人工接管后拒绝；回执INSERT撤销导致消息/seq回滚，恢复后可重试。核权HTTP服务断言Hikari活跃连接为0，并用另连接NOWAIT取得会话锁；证明该RPC期间不持有业务连接/锁。
- `mvn -B -f backend/pom.xml verify`九模块通过，`b1-result-api-java.log`；工单修复后再跑九模块通过，`ticket-time-java-after.log`。条件PG测试由上述专项实测，不计普通构建跳过为通过。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**91项Python、6次角色负面、2项真实Java交付、2条实际来源流程、1项实际许可联调通过**，`b1-result-api-runtime.log`。真实customer JAR来源开启时，匿名reply仍401、有效凭据的reply默认503/Retry-After5，验证新接口未随来源开关意外开启。
- `python scripts/test-security-flow.py`：修复工单时间边界后，**默认新库/历史升级/CLI初始化三套实际服务全部通过**，含原业务/媒体/知识/WS/计费、45个Readiness故障、customer 7条历史及回执SI权限，`b1-result-api-services-final.log`；第一次失败及确定性修复见上一节，不能省略该失败。一次性资源均清理。

边界/风险：新7项API PG测试的AI控制面是合成HMAC/HTTP故障服务；真实AI控制面核权在独立夹具中验收，结果API尚未与实际AI+JAR串成同一回复写入测试，也没有Python结果SDK/Worker业务handler/自动调度。原afterCommit渠道送达与原始计数、日志治理、时钟监控/生产TLS仍按M07/M11/M12保留；不宣称取消能撤销已在途写。无任务成功率、性能/Token费用或生产业务指标。下一批完成结果SDK与实际双向写入、回执丢失/UNKNOWN核验，再接B2工具层。

回滚关闭Java进程`SUPPORTFLOW_BOT_RUNTIME_RESULT_ENABLED`（默认false）；source开关可单独保留。保留007消息/回执和Run/Step，停止新写但不要删除或更换事件；既有已提交结果可在受鉴权/Scope约束下核验。二进制回滚遵守原schema兼容性，不回写已应用SQL。

eb44d06工单时间修复的[CI 44697476589](https://github.com/jkwang1129/SupportFlow/actions/runs/44697476589)、5b51616结果API的[CI 44697848121](https://github.com/jkwang1129/SupportFlow/actions/runs/44697848121)两个job分别全部成功，包含原三套实际服务流程。

## B1 子批次 13：Python结果SDK与真实双向写入/回执丢失验收（2026-11-20）

基线5b51616。新增`ai/runtime/result.py`/38项结果SDK测试/`scripts/result_delivery_fixture.py`，在原Runtime联调入口增加两条真实写入场景。SourceClient与ResultClient复用抽取的私有_CustomerTransport（原有HTTPX、每操作client生命周期、统一deadline/禁重定向/identity/响应上限），来源接口32项契约保持。Java仅明确reply响应seq为十进制字符串，并同步API PG断言；没有SQL/账号/默认开关/业务自动触发改变。ADR-0005和实施矩阵更新。

ResultClient按可信RunInput/Lease构造固定reply路径与六字段正文，先校验租户/owner/fence/内容/SHA。POST回执与请求的tenant/event/run/snapshot/hash/版本/操作逐项绑定；GET可绑定原snapshot/hash。message_id/seq严格正的规范十进制字符串且≤int64，Python用int无损接收；拒绝bool、浮点、数字型seq、前导零、越界、重复字段和未知字段。成功信封≤8KiB，整体deadline≤10秒，不隐式重发写请求，不复制上游错误正文。超时可能已经提交；GET返回None不证明未执行，UNKNOWN仍需独立核验和恢复策略。

本地工具链/PG17、合成消息与独立测试密钥，真实AI测试进程显式Mock且不读开发者.env；客户JAR只获customer/platform账号及服务凭据，Java所有AI目标均指向本次Mock服务。新结果用例在JAR就绪后才用真实受限RuntimeStore登记Run/认领/预留WRITE，客户来源由隔离SQL构造；不改120秒deadline、不重置预算，不冒充自动业务触发。

| 复现命令 | 实际结果/原始证据 |
| --- | --- |
| `cd ai-service; python -m pytest -q tests/test_runtime_source.py tests/test_runtime_result.py tests/test_runtime_reply.py` | **86项通过**＝来源32+结果38+摘要16；`b1-result-sdk-unit-wire.log` |
| `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-result-sdk-ai-final.xml` | **211 passed、81 skipped、1 xfailed**，`b1-result-sdk-ai-final.log`；75项Runtime PG由专项实测，原正文引用xfail保留 |
| `python scripts/test-customer-rls.py --with-ai` | **91项Java真PG + 4项KB通过**，包含明确字符串seq的API断言，`b1-result-sdk-pg.log` |
| `mvn -B -f backend/pom.xml verify` | 九模块成功，`b1-result-sdk-java.log` |
| `python scripts/test-agent-runtime.py --with-java-delivery` | **91项Python专项+6次角色负面+原2项Java交付/2条来源流程/1项真实许可联调+新2条真实回复场景通过**，`b1-result-sdk-real-clean.log`；真实SDK→客户JAR→真实AI控制面→受限PG，不是替换Store或控制面Stub |

两条新实际场景：①正常回复，相同POST和GET得到同一消息；修改内容409，终态SUCCEEDED后确认仍同回执，跨tenant查无回执/错误服务密钥401；②回环代理把原始签名/body转给真JAR，收到已提交HTTP200后延迟body 5秒，SDK整体2秒超时且只有一次HTTP尝试，断言超时前已收到上游提交回执。随后真实Runtime取消把Step标UNKNOWN/Run FAILED/SIDE_EFFECT_UNKNOWN；SDK按原snapshot/hash查询业务回执，再用原fence确认相同回复，仍同一消息且Runtime保持FAILED。逐案从客户库核对每Run一回执、一条BOT回复、总两条消息/seq=2及精确文本；Runtime均只有1个WRITE预留、model=0、tool预算=1、retry=0。该计数是合成协议验收，未调用真实模型，不是任务成功率、生产吞吐或费用报告。

补充生成机器可读`.local/verification/runtime-results.json`（由现有CI artifact步骤收集）：从实际查询结果记录逐案Run/event/snapshot/message引用、SHA、消息/回执数、Runtime状态/预算，并明确performance/token_cost为NOT_MEASURED，不含正文/密钥。报告生成后的完整专项再跑通过，`b1-result-sdk-real-report.log`；两例分别SUCCEEDED与FAILED/SIDE_EFFECT_UNKNOWN，数据没有被统一改成成功。

首次SDK测试25项因MockTransport响应构造预消费body而失败，改为与现有来源测试一致的未消费ByteStream后82项通过；`b1-result-sdk-unit-first.log`/`unit-final.log`保留。第一次实际写入联调拒绝回执：实际Spring的LongToStringJacksonConfig把seq变为字符串，standalone MockMVC的默认mapper之前输出数字。明确Java wire字段为字符串、SDK只收规范十进制字符串，并增加JS安全整数以外的精度/格式负面用例后，86项及真实写入通过；`b1-result-sdk-real-first.log`保留，没有通过接受任意数值类型放松验证。首个成功故障场景在Windows的预期连接中止后产生代理keep-alive读取日志；固定代理Connection:close并只处理预期断连后，保持原2秒/5秒时限和所有断言，完整专项再跑通过（`real-final.log`与`real-clean.log`均保留）。

边界/回滚：仍无实际模型/检索工具循环、业务handler或源端自动触发；UNKNOWN业务回执已能核验，但不自动把失败Run改成成功或恢复后续任务。Run/Step目前只持引用，模型/检索中间正文的可恢复持久化尚需补齐，再接B2。原三套完整服务本批未在本地重复，沿用上批记录，当前提交的远端CI将重跑；本批真实启用结果API的JAR和默认关闭来源JAR均已专项实测。网络信任/生产TLS、外部渠道送达、物理清理和费用硬额度仍属后续。回滚关闭结果开关/停止新客户端，保留007消息/回执和Run/Step；不因超时生成新run/event、不删除UNKNOWN或重置预算。

c1a2978对应的[CI 44701513973](https://github.com/jkwang1129/SupportFlow/actions/runs/44701513973)两个job全部通过，包括新实际写入/回执丢失场景、机器可读结果报告及原三套实际服务流程。

## B1 子批次 14：中间正文与步骤成功原子存储（2026-11-20）

基线c1a2978。源码核查：ai_usage_record仅best-effort计量，不存恢复用的模型/检索正文；session_message是业务事实，不能提前写模型中间动作。因此沿用AI库新增003/agent_payload，不建新服务或另起Checkpoint真相。设计见ADR-0006；先交付存储基础，Worker接口在下一批独立实施。

修改：`ai/runtime/payloads.py`/RuntimeStore.complete_payload_step与payload读取；AI003/manifest/40-runtime-roles/current-schema；AI schema_guard的列/权限/RLS检查；17项JSON单元、20项真实PG与5项新增Readiness故障；更新Runtime PG清理、版本/init/security验收脚本及本计划/记录。没有Java源码、依赖或业务入口改动。

表与Step同tenant/run/step复合FK、一Step一正文，schema按MODEL/NONE、TOOL/READ、INTERNAL/NONE分别匹配；WRITE不能用此表代替业务回执。纯JSON根对象、有限int64/float、32层/4096节点/64KiB上限，流式编码时有界计数，拒绝NUL/非法Unicode/任意Python对象/重复键，Hash验证精确UTF-8；DTO repr隐藏正文。完成时先验当前租约/fence，再将正文和SUCCEEDED/result_ref同事务提交，最后再次查DB时钟；重复相同内容返回原ref、修改冲突，预算和TTL不刷新。读取限定tenant/run/payload，七天读取期限、不做物理清理。SI-only、FORCE RLS、restrictive guard及Runtime能力组安全检查，Readiness拒绝意外UPDATE权限。Hash不是事实/权限/审批保证，载荷仍是不可信数据。

本地Python3.11.5/PG17、合成结构化输出，不调用模型：

- `cd ai-service; python -m pytest -q tests/test_runtime_payloads.py tests/test_runtime_types.py`：**33项通过**（新JSON17+原类型16），`b1-payload-unit-first.log`。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-payload-ai-final.xml`：**228 passed、106 skipped、1 xfailed**，`b1-payload-ai-final.log`；外部PG条件跳过另由专项执行，引用正文xfail仍保留。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**116项Python专项+6次危险角色负面**，原真实Java交付/来源/控制面/双向回复全部通过，`b1-payload-runtime-final.log`。其中新PG20：三类正文与Step共同成功、预算不变；8路相同提交仅一正文；内容/版本冲突；WRITE/非RESERVED/取消/过期/旧fence拒绝；等待Step锁越过lease后无正文；INSERT权限失败；触发器在“本事务已可见INSERT正文”后故障使正文/Step一起回滚；提交完成但注入丢DB回执后重读成功、重试同引用不增加调用；过期不刷新/Hash损坏拒绝；FK/跨租户RLS/额外宽松策略/U-D-TRUNCATE-DDL拒绝。新Readiness5例覆盖缺列/无FORCE/误授UPDATE/撤销S-I并在恢复后原地开放。
- `python scripts/test-versioned-migrations.py`：**17项+CLI**通过；额外AI002→003升级保留真实Store建立的已租约Run/已RESERVED模型Step及扣过的调用计数，payload表为空，原customer006→007数据保留仍通过，`b1-payload-migrations.log`。
- `python scripts/test-schema-drift.py`：六库**93表/1视图/5受控函数**、14个SQL及3个metadata故障/CLI修复通过，`b1-payload-drift.log`；候选审查只有AI新payload表/索引/策略和003历史，其他五库及旧baseline不变。
- `python scripts/test-database-init.py`：六库93表、CREATEDB拒绝/锁/失败回滚/拒绝覆盖通过，`b1-payload-init.log`。
- `python scripts/test-security-flow.py`：**默认新库、历史升级、CLI初始化三套实际服务通过**，原业务/媒体/知识/WS/计费与45次Java Readiness故障全部保留；额外核对AI历史3条、payload有效SI且无U/D/TRUNCATE，`b1-payload-services.log`。本批Java未改，使用上一批已验证九模块JAR，专项仍实际执行Java交付/许可JUnit；不重复记一次Maven九模块构建。

本批首轮专项115项均通过；增加锁等待到期与过期不刷新断言后116项通过，初始`b1-payload-runtime-first.log`保留。各迁移/初始化/结构/完整服务组首次通过；没有删负例或放宽资源限制。

边界/风险：这里只提供存储与严格读取，尚未接RuntimeWorker的正文步骤，不能宣称模型/检索已在实际Agent中完成恢复；无自动Unknown写核销、审批、工具Registry或bounded loop。七天是读取TTL，不是物理清理或法规保留承诺；日志/脱敏、知识ACL重新校验、模型输出语义验证与生产运维仍按原阶段实施。数据均为合成样例，未计真实Token/费用/任务成功率或性能。

回滚停用新存储方法/后续开关，保留AI003及payload/Step引用/Run计数；不改旧SQL或用日志拼凑已成功模型输出，不清空UNKNOWN或重置预算。若载荷缺失/损坏/过期，应明确失败或待核验，不能静默重调模型冒充恢复。

23b3faf对应的[CI 44704656499](https://github.com/jkwang1129/SupportFlow/actions/runs/44704656499)两个job全部通过，含AI003、旧数据升级保留和三套完整业务部署路径。

## B1 子批次15：Worker持久化正文恢复（2026-11-20）

基线23b3faf。修改`ai/runtime/worker.py`、新增`test_runtime_worker_payloads_postgres.py`、专项入口及ADR-0006/本计划记录；无新SQL/配置/依赖/Java改动。payload_step沿用原逻辑键/意图/fence/Step预留算法，将MODEL/NONE、TOOL/READ、INTERNAL/NONE的成功结果原子存储并解码恢复；WRITE拒绝。恢复校验tenant/run/step/schema/精确Hash与读取TTL后重验租约，返回新对象。缺失/损坏/过期/引用错Step明确失败，不悄悄重调模型。

正文提交在执行异常处理之外，避免“成功提交但丢数据库回执”被降为UNKNOWN。OperationalError/InterfaceError经已有retry计数与稳定RUNTIME_PERSISTENCE_UNAVAILABLE转移，不保存异常正文；如果转移时数据库仍离线，则传播异常保留RUNNING，等待租约接管，不伪造状态。未提交的输出要重做必须重新预留且继续消耗原调用预算；不确定WRITE仍保持原阻断策略。

环境Python3.11.5/隔离PG17/受限角色，模型为合成回调，不调用收费模型。实际验证：

- `cd ai-service; python -m pytest -q tests/test_runtime_payloads.py tests/test_runtime_types.py`：33项通过，`.local/implementation/b1-worker-payload-smoke.log`。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**130项Python专项+6次危险角色负面通过**，原真实Java交付、来源、许可控制面、双向写入/丢业务回执场景全部通过，`b1-worker-payload-runtime-first.log`。新增14项均为真实PG：同逻辑步骤重复访问不再调用/扣费且局部修改不污染存储；下游READ重试不再调用已成功模型；缺失/Hash损坏/schema改变/过期停止；成功提交丢DB回执后重试复用；提交前丢输出在模型预算1时失败/预算2时重新预留成功；持续离线不虚构转移、恢复后接管复用；执行中取消无迟到正文；另Step引用拒绝；NaN永久失败/WRITE接口执行前拒绝；**真实子进程写成功后os._exit(23)，新Worker租约接管复用同正文且model_calls=1、step_count=1**。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-worker-payload-ai-final.xml`：**228 passed、120 skipped、1 xfailed**，`b1-worker-payload-ai-final.log`。新增14项条件PG与其余Runtime外部条件测试由前述专项实测，不把skip计为通过；现有RAG引用正文xfail保持。

本批首轮均通过；不重复迁移/九模块构建/三套完整服务的本地验收（无相应改动），远端CI会对本次精确提交重跑。工程故障测试数不等于任务质量/生产成功率、性能、Token或费用指标。没有业务handler/真实模型与工具循环/自动触发，不能宣布B1整体完成。读取TTL仍不做物理清理；数据库长期故障、监督进程、网络分区运维属后续。回滚停用payload_step/回退Worker，保留AI003、正文、Step/Run和预算，不删除UNKNOWN、不重置调用数。

a9dd99c对应的[CI 44706510968](https://github.com/jkwang1129/SupportFlow/actions/runs/44706510968)两个job全部成功，含Worker真实进程恢复和三套实际服务流程。

## B2 子批次1：内部只读工具Registry/Executor（2026-11-20）

基线a9dd99c。新增`ai/runtime/tools.py`、`test_runtime_tools.py`、`test_runtime_tools_postgres.py`、ADR-0007；更新Runtime专项入口/矩阵/本记录。只复用已有Pydantic、ExecutionContext、Run/Step/payload与总预算，不新增依赖/SQL/Java改动，不自动切换Workflow。固定版本的宿主注册只读工具、最多32个、串行；参数与结果导出JSON Schema，严格类型/禁止多余字段与开放嵌套对象；提案仅name/version/arguments，≤8KiB且重复字段拒绝。身份从Run/Lease重建，模型不能选择tenant/principal/Scope/URL/headers；静态Scope仅第一道白名单，资源授权仍由mandatory authorize落实。

每次访问先新预留一个TOOL/READ完成当前资源授权，成功才能取/复用工具正文，再次检查结果Schema与资源对应关系（mandatory纯代码verify）。首次读取通常消耗2次工具预算，恢复缓存仍消耗1次，不把权限RPC藏在预算之外。输出Schema合法并不证明它是请求的工单。限定超时/明确不可用/限流可重试，其他错误稳定失败且不保存异常正文；无本地隐式重发。适配器必须落实Deadline至实际I/O，返回后拒绝迟到结果；没有强杀同步Python代码的承诺。通用WRITE不开放，已有业务回执协议保留。

本地Python3.11.5/隔离PG17，工具/资源ACL是确定性合成适配器，无实际模型调用：

- `cd ai-service; python -m pytest -q tests/test_runtime_tools.py`：首轮59项通过（保留`b2-tools-unit-first.log`），随后增加返回资源绑定/注册必选verify及校验器不污染返回值，最终**62项**随全量通过。首轮bytes字面量触发无效转义告警，改raw bytes后消失。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-tools-ai-final.xml`：**290 passed、133 skipped、1 xfailed**，`b2-tools-ai-final.log`。原引用正文xfail保留；条件PG由专项实际执行，不把skip算通过。
- `python scripts/test-agent-runtime.py --with-java-delivery`：最终**143项Python专项+6次角色负面通过**，原真实Java交付/来源/AI许可/双向写入/丢回执场景全部通过，`b2-tools-runtime-final.log`。新13项真PG覆盖授权+工具2次计数/正文仅引用、恢复缓存重新核当前ACL及撤权阻断、预算1在实际调用前停止/预算2不允许免费重验缓存、超时按原Run重试、非法输出不存成功正文、资源归属拒绝、授权中取消无工具调用、完整性有效但Schema或返回资源被改仍拒绝、参数更改不套用旧结果、连续限流在原max_retries=2后停止（实际3次尝试/6次工具计数）。首轮142项通过，补完整性有效但资源错误的缓存负例后143项；`b2-tools-runtime-first.log`保留。

边界：本批未把工单/知识HTTP接到Registry，真实Java授权不由合成ACL测试替代；也不是实际模型Tool选择准确率/性能或Token成本。ReadTool适配器必须可信，强制终止、出站网络策略、脱敏保留、MCP动态接入与HITL留原后续任务。没有SQL/Java改动，本地不重复迁移或全栈三场景，当前精确提交仍由远端CI验证。回滚停止新工具注册/调用，原Workflow保持；保留Step/payload与预算，不删除UNKNOWN或重置计数。

## B2 子批次2：实际业务来源工具与当前授权证据（2026-11-20）

基线d0725c9。新增`ai/runtime/customer_tools.py`、`test_runtime_customer_tools.py`、`scripts/source_tool_fixture.py`；tools.py增加不可变ReadAuthorization引用证据、纯verify接收本次证据；更新契约夹具、原source/runtime联调入口与ADR-0007/矩阵/本记录。无新SQL/Java源码/依赖/默认开关变化，不增加业务权限接口。get_current_message空参数，只从可信Run选来源；复用SourceClient向既有固定snapshot接口capture当前授权、read获取内容。capture幂等冻结派生快照，不创建回复或工单。输出排除customer_ref/完整JSON/内部记录；只含来源/快照版本引用、role=user及≤8KiB正文。

授权证据包含当前snapshot/sourceHash/文本SHA，落Step的仍只有引用；缓存正文需与本次业务证据匹配，不允许一个结构合法的旧/被改正文替代当前来源。适配器用asyncio.timeout包住SDK整体I/O，仍不接受模型URL/凭据/主体。Registry未接任意MCP或WRITE。

环境Python3.11.5/隔离PG17/JDK25真实客户JAR，合成访客文本和一次性密钥，显式Mock AI、无付费模型：

- `cd ai-service; python -m pytest -q tests/test_runtime_tools.py tests/test_runtime_customer_tools.py`：首轮**75项通过**，`b2-source-tool-unit-first.log`；随后补缓存正文篡改/当前快照变化两例，最终来源适配15项+原工具62项随全量通过。SDK与Executor共同验证固定签名路径、POST/GET、新旧快照/正文/源Hash差异、四类模型越权参数、业务撤权/变更/过期、async transport忽略HTTPX超时时仍受外层deadline约束、证据不可变/只含引用、缓存必须匹配当前业务证明。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-source-tool-ai-final.xml`：**305 passed、133 skipped、1 xfailed**，`b2-source-tool-ai-final.log`；条件PG另由专项执行，原引用正文xfail保持。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**143项Python专项+6次角色负面、原真实Java交付/来源/控制面/双向写与丢回执全部通过**，`b2-source-tool-runtime-first.log`。额外2条实际Worker→Executor→签名SourceSDK→客户JAR→PG，不替换Store、SDK或业务授权为Stub：正常Run先读后RETRY_WAIT，恢复先当前授权再复用正文，终态SUCCEEDED且handler收到2次同正文；另一Run先读后人工接管，再尝试时SOURCE_REVOKED/FAILED，handler只曾收到第一次正文。逐Run从真实PG核对tool_calls=3（2个授权+1个读取）、model_calls=0、step_count=3、retry_count=1、payload_rows=1。终态失败是预期负例，不被改成成功。生成`.local/verification/runtime-source-tools.json`，仅引用/状态/计数、performance和token_cost均NOT_MEASURED，现有CI artifact自动收集。

本批首次实际专项通过；新增两个单位负例没有改变实测适配器/HTTP夹具代码。没有重复本地SQL/Java九模块/三套完整部署验收，无相关源码改变；远端CI重跑精确提交。边界：get_current_message是现有业务来源读取，工单/知识工具仍待接入；没有真实模型Tool选择/任务质量、性能/费用数据，也未启动业务handler或自动Worker。撤权阻止后续读取/缓存交付，不能追回此前已交付内容；存储TTL不是物理清理。回滚停止注册此工具或关闭source开关，保留快照/payload/Step/预算，不删除UNKNOWN/失败证据。

d0725c9的[CI 44707981353](https://github.com/jkwang1129/SupportFlow/actions/runs/44707981353)、1e55f8e的[CI 44708950147](https://github.com/jkwang1129/SupportFlow/actions/runs/44708950147)两个job分别全部成功，包括来源工具的实际Worker撤权验证和原三套完整服务部署路径。

## B2 子批次3：Java工单最小状态业务API（2026-11-20）

基线1e55f8e。原TicketService.detail只允许企业成员且返回完整描述/内部时间线，不能让访客伪装为成员。新增`service/bot/AgentTicketQueries.java`与`controller/internal/RuntimeTicketController.java`，复用现有工单表、客户服务、SourceSnapshot和同一RLS事务；扩展RuntimeSourceAccessFilter只接受精确ticket路径GET、独立默认关闭read-tools开关；新增5项窄认证测试/12项真实PG，更新PG和真实JAR默认关闭夹具、ADR-0008/矩阵/本记录。没有SQL迁移、凭据/账号、默认业务路由或新依赖变更。

当前服务端source的customer_ref非空且客户未删除/匿名化/合并时，只读同一客户的企业内部工单；匿名/合成来源只读customer_id为空且source_session_id和session_no都对应当前来源的工单。平台工单、其他租户/客户、软删除统一无内容。读取先重验当前已交付来源/会话/消息/快照时效/摘要，再锁客户和工单，锁在同一事务持有。结果严格十字段状态投影（status整数1～5、无时区ISO原时间、当前来源绑定和投影SHA），不返回客户ID/姓名/电话或标题/描述/内部时间线。投影SHA不是权限或整行MVCC版本。来源准备期间可读，AI租约/取消仍由Worker执行层核验，读服务签名不是写许可。GET可能幂等创建派生快照，不改工单/业务消息/计费/结果回执。

本地JDK25.0.5+8/Maven3.9.16/Python3.11.5/隔离PG17、合成源/工单/客户、Mock模型：

- `mvn -B -f backend/pom.xml -pl supportflow-customer-service -am test -Dtest=RuntimeTicketAccessFilterTest,RuntimeSourceAccessFilterTest,RuntimeReplyAccessFilterTest -Dsurefire.failIfNoSpecifiedTests=false`：**21项通过**（新5+来源9+结果7），`b2-ticket-read-unit-first.log`。精确ticket/source/body绑定、三开关独立/关闭不读body、非GET/query/body/非法路径拒绝、默认凭据/共享其他角色密钥拒绝。
- `python scripts/test-customer-rls.py --with-ai`：首轮**102项客服PG+4KB通过**，`b2-ticket-read-pg-first.log`；补同事务真实行锁观察后最终**103项客服PG+4KB通过**，`b2-ticket-read-pg-final.log`。新12项含匿名精确会话归属、客户本人历史、其他客户/租户、平台/删除/匿名化/合并、来源接管/关闭/删除、变更409/过期410、状态改变摘要变化（即使更新时间相同）、6路并发同一快照且无消息/结果回执新增、认证先于缺表Readiness、撤销SELECT后的503无SQL/恢复可读、缺事务/租户拒绝；真实管理员连接对已授权ticket/customer分别FOR UPDATE NOWAIT得到55P03，确认SHARE锁实际持有至事务完成。
- `mvn -B -f backend/pom.xml verify`：**九模块成功**，`b2-ticket-read-java.log`。追加行锁PG测试单独由最终PG脚本编译执行，未把普通Maven的条件skip当通过。
- `python scripts/test-security-flow.py`：**默认新库、历史升级、CLI初始化三套实际服务全部通过**，含原业务/媒体/知识/WS/计费和45项Java Readiness故障，`b2-ticket-read-services.log`。本组独立执行，期间没有并行其他验收。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**143项Python专项+6次危险角色负面**、原Java交付/来源/当前权限证明/真实Worker缓存撤权/许可控制面/双向回复与丢回执全部通过，`b2-ticket-read-runtime.log`。实际新客户JAR仅开启source时，ticket GET匿名401、有效签名503/Retry-After5，证明旧开关未开启新工具API；result默认关闭仍验证。Python实现未改，普通AI结果沿用上一批305/133/1，当前远端CI仍会重跑。

所有本地组首轮通过；追加行锁测试由最后一次专项通过，没有放松权限/删负例。边界：本批尚无Ticket SDK/ReadTool或实际JAR启用工单API的Worker联调，不能把MockMVC+真PG冒充完整Agent工单链路；下一批独立完成。没有性能/模型质量/Token/费用或生产业务数据。回滚关闭SUPPORTFLOW_BOT_RUNTIME_READ_TOOLS_ENABLED/回退二进制，保留已产生快照、Run/Step及预算，无迁移回退；工单控制台和旧Workflow权限不变。

## B2 子批次4：工单SDK/ReadTool与实际Worker联调（2026-11-20）

基线b2819d6。新增`ai/runtime/tickets.py`、`test_runtime_tickets.py`、`scripts/ticket_tool_fixture.py`；customer_tools.py接get_ticket_status；SourceClient私有_CustomerTransport增加固定只读ticket路径/错误域组合白名单；原实际联调入口和ADR-0008/矩阵/本记录更新。无SQL/Java源码/依赖/开关默认值改变，使用上一批九模块验收后的JAR，不把它记成新一轮九模块构建。

SDK只GET空body、固定Run来源及ASCII工单路径、禁止模型URL/JWT；单操作client/整体deadline/不重定向/不信任环境代理/≤8KiB响应。回执十字段严格绑定tenant/event/run/ticket/schema、UUID/Hash格式、整数1～5、本地无时区ISO时间并重算投影SHA；业务错误码与HTTP状态对应，稳定分类不含上游正文，无内部隐式重试。ReadTool仅ticket_no参数，当前授权证据与缓存/新正文的snapshot/sourceHash/ticketNo/投影SHA对应，正文再次重算Hash，输出不含客户/描述或tenant字段。

环境Python3.11.5/隔离PG17/真实JDK25客户JAR，一次性合成客户与工单/服务密钥；Java所有AI目标指向本次显式Mock AI，未读取开发者.env/调用付费模型。每例在JAR就绪后由受限RuntimeStore enqueue/Worker预留执行，后台源/客户/工单仅为隔离SQL夹具，真实120秒deadline和预算不改。

- `cd ai-service; python -m pytest -q tests/test_runtime_tickets.py tests/test_runtime_customer_tools.py tests/test_runtime_source.py tests/test_runtime_result.py`：**142项通过**＝新工单57+来源适配15+原Source32+Result38，`b2-ticket-sdk-unit-first.log`。包括路径/签名/无JWT、资源/版本/类型/时间/Hash/额外字段、任意网络路径拒绝、HTTP/业务错误一致性/重复JSON、响应大小/超时、当前投影变化时实时及缓存结果均拒绝、私有Transport不能把ticket域用于旧/写入口。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-ticket-sdk-ai-final.xml`：**362 passed、133 skipped、1 xfailed**，`b2-ticket-sdk-ai-final.log`。外部PG由专项实际执行，原RAG正文引用xfail保持。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**143项Python专项+6次角色负面**，原实际Java交付/来源/Worker缓存撤权/许可/双向回复与丢回执全部通过，`b2-ticket-sdk-runtime-first.log`。新3条实际Worker→Executor/SDK→read-tools开启而source/result关闭的客户JAR→PG：①本人历史工单status2，首次读取后重试，当前授权相同才复用，SUCCEEDED且handler收到2次status2；②首次后工单改给别的客户，再读TICKET_REVOKED/FAILED，handler仅收到初次值；③首次后数据库status改4、更新时间不变，新授权投影SHA变化，旧缓存TOOL_RESULT_INVALID/FAILED，status4没有被错误替换为缓存status2向后交付。错租户/错误服务密钥实际HTTP拒绝。逐Run核实tool_calls=3、model_calls=0、step_count=3、retry_count=1、payload_rows=1、原来源消息=1、结果回执=0。生成`.local/verification/runtime-ticket-tools.json`，机器证据保留三例不同终态/实际工单状态/handler收到的值；performance/token_cost为NOT_MEASURED。

本地首轮均通过。Java/SQL未改，本批不重复客服PG103/KB4/九模块/三套完整服务（上批已有实测，远端CI重跑精确提交）。边界：仍无真实模型工具选择/任务质量或生产数据。当前投影变化明确失败，不自动刷新缓存和继续任务，下一项补预算约束的可变只读失效策略；不能把该预期失败写成“已自动恢复”。之后再接知识/写工具、bounded loop、HITL/自动触发。回滚停止注册新Ticket工具/关闭read-tools开关，保留Step/正文/预算/快照，不换Run绕过失败；既有业务工单与Workflow保持。

b2819d6的[CI 44732054985](https://github.com/jkwang1129/SupportFlow/actions/runs/44732054985)、a7a53c3的[CI 44733196937](https://github.com/jkwang1129/SupportFlow/actions/runs/44733196937)两个job分别全部通过，含工单最小接口/SDK三例真实Worker验证和原三套完整部署流程。

## B2 子批次5：受预算约束的可变只读缓存失效（2026-11-20）

基线a7a53c3。新增ADR-0009；修改tools.py的ReadTool/cache_policy、Executor稳定请求绑定与按当前授权版本选择正文键；工单执行版本ticket-status-v2，HTTP/schema仍ticket-status-v1；补纯契约/真PG、实际工单夹具四例、矩阵/本记录。无SQL/Java源码/依赖/默认开关或自动触发变化，不建第二套Checkpoint/缓存数据库。source工具默认immutable不变。

先INTERNAL/NONE参数绑定Step固定逻辑key→工具/版本/主体/参数SHA，恢复参数替换在新授权前拒绝；再新TOOL/READ当前核权。current-version须有明确资源引用/64位小写Hex当前版本证明，只有True/缺证据不够。正文键包含逻辑key及本次证明：不变复用，变化须再预留TOOL/READ、核对当前证明并原子存新正文。绑定也消耗Step总预算；授权不能变成免费数据返回，刷新也不重置Run调用/重试预算。各版本正文保留审计。旧v1工具提案不被新Registry静默当v2执行。

环境Python3.11.5/隔离PG17/上一批JDK25真实客户JAR，模型全Mock/合成工具适配器与实际业务接口分别注明：

- `cd ai-service; python -m pytest -q tests/test_runtime_tools.py tests/test_runtime_tickets.py`：**121项通过**（工具63+工单58），`b2-versioned-read-unit-first.log`；含旧版本拒绝、非法缓存政策、当前版本变化读取新正文。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-versioned-read-ai-final.xml`：**364 passed、142 skipped、1 xfailed**，`b2-versioned-read-ai-final.log`。条件PG由专项执行；原引用正文xfail保留。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**152项Python专项+6次角色负面通过**，原实际交付/来源/许可/双向回复/丢回执均通过，`b2-versioned-read-runtime-first.log`。新增9项真PG：版本不变/改变分别复用/新读；max_tools=3在新读前失败不交付新数据；参数替换在第二次授权前拒绝；三种缺/非法资源版本证明拒绝；反复切换两个版本最终仍在tool_calls=8预算处停止（仅两次真正invoke、每次缓存访问均新授权）；max_steps=2使绑定+授权后在读取前停止。合成资源ACL不冒充业务端权限测试。
- 同一专项内新增更新后的**4条实际Worker→Executor/TicketSDK→新客户JAR→受限PG**：①相同版本SUCCEEDED，tool=3/step=4/payload=1，handler=[2,2]；②归属撤销FAILED/TICKET_REVOKED，tool=3/step=4/payload=1，handler=[2]；③状态改4（更新时间未改）自动新预留读取后SUCCEEDED，tool=4/step=5/payload=2，handler=[2,4]；④同样改4但max_tool_calls=3，FAILED/BUDGET_EXHAUSTED，tool=3/step=4/payload=1，handler=[2]。均retry=1/model=0，业务来源消息=1/结果回执=0。真实SQL状态与收到的值分别记录在`.local/verification/runtime-ticket-tools.json` v2；旧v1三例原报告保留`.local/implementation/b2-ticket-sdk-results-v1.json`。performance/token_cost均NOT_MEASURED。

本地首轮全部通过；没有把状态变化旧失败记录改写成旧版本已成功。Java/SQL未改，不重复上批103客服PG/4KB/九模块/三套完整服务，精确提交由远端CI重跑。边界：若资源在“本次授权→实际新读取”之间再次改变，仍拒绝不一致结果；尚无跨在途网络的即时撤权/无限刷新保证。无真正模型循环/知识Embedding预算/写工具/审批自动触发；下一项先核查模型单次调用入口再接最小循环，完整Gateway/上下文/知识工程沿用原计划。回滚停用v2注册/关闭read-tools，保留绑定/授权/所有版本正文/Run计数，不能把v2计划指向v1代码或删除UNKNOWN。

4c9746e对应的[CI 44734373667](https://github.com/jkwang1129/SupportFlow/actions/runs/44734373667)两个job全部成功，包括工单v2实际刷新/预算拒绝和原三套完整服务部署路径。

## B1 模型接入前置子批次：复用现有单次Chat入口（2026-11-20）

基线4c9746e。源码核查：真正HTTP入口为core.llm_chat.call_chat，内部一次POST无重试，但原client.post/response.json无整体正文deadline或大小边界，错误正文写日志与RuntimeError。旧llm_gateway.py是Mock占位，这批不另建第二真实入口，也不包旧多模型Workflow。新增`core/chat_transport.py`、`tests/test_chat_transport.py`；call_chat委托私有传输/保留原调用签名和默认best-effort计量，RAG故障夹具迁移、ADR-0010/矩阵/本记录更新。无Java/SQL/依赖/默认业务路由修改。

受信配置验证baseURL/密钥/模型一致性；请求纯JSON/64层/16384节点/256KiB预检查流式编码；HTTPX明确无环境代理/重定向，一次POST与整体deadline覆盖准备/连接/响应头/完整正文。JSON/identity实际响应≤256KiB，重复键/NaN/非对象等拒绝；稳定ModelCallFailure(code/retryable/status/attempted)不含上游正文/URL/密钥。配置/请求校验不当作HTTP尝试，HTTP尝试保留旧计量一次且失败不阻断结果；Runtime未来可显式关闭旧best-effort计量，在原子MODEL结果保留usage后统一结算，不宣称硬Token或金额额度。此处尚未接Runtime模型适配器。其他消费者/视觉/Embedding/旧Mock网关/计量器的日志与策略不冒充已统一。

环境Python3.11.5/JDK25/隔离PG17，全部模型响应为协议Stub，未调用收费模型：

- `cd ai-service; python -m pytest -q tests/test_chat_transport.py`：初始52项Mock协议通过，`b2-model-transport-unit-first.log`；增加3条真实回环TCP后最终**55项通过**，`b2-model-transport-unit-real-final.log`。覆盖精确POST/模型/身份header、无敏感正文日志、HTTP429/5xx/认证/请求分类、无内部重试、URL/密钥/时限预校验、JSON/Unicode/大小边界、重复/MIME/压缩/实际Content-Length谎报、慢正文/网络异常、旧计量一次/失败不阻断、Runtime opt-out不读开发者Settings。真实TCP3例：正常成功，302不跟随，已收到POST后延迟正文整体超时，设置无效环境代理仍直达固定回环目标；usage=11/7是合成供应商信封，不是实际Token费用数据。
- 首次真实TCP慢正文用50ms整体时限，在Windows客户端准备阶段已到期、服务端未收到请求，正确超时但未验证到正文阶段；`b2-model-transport-unit-final.log`保留54通过/1夹具断言失败。改为1秒整体时限/服务端最多2秒正文阻塞，仍断言确有一次POST并在正文完成前超时，客户端退出后释放夹具，不改产品限制或重试策略。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-model-transport-ai-corrected.xml`：最终**419 passed、142 skipped、1 xfailed**，`b2-model-transport-ai-corrected.log`。首轮415通过/4失败因旧RAG故障夹具仍注入llm_chat.httpx（已抽为私有传输），`b2-model-transport-ai-final.log/xml`保留；改从现有rag.call_chat注入未消费HTTP流，明确断言MODEL_UNAVAILABLE/TIMEOUT/RESPONSE_INVALID，空答案仍单独拒绝，没有把Provider/JSON错误当生成成功。引用正文已知xfail限制raises=AssertionError，防止夹具/协议异常被误算成该质量缺口。
- `python scripts/report-rag-contract.py --junit .local/implementation/b2-model-transport-ai-corrected.xml --output .local/implementation/b2-model-transport-rag-contract.json`：**24项RAG契约门禁通过**；`python -m unittest discover -s scripts/tests -p 'test_*.py' -v`：**22项中12通过、10项外部条件跳过**，其中报告门禁5项负面通过，`b2-model-transport-gate-tests.log`。
- `python scripts/test-security-flow.py`：**默认新库/历史升级/CLI初始化三套实际服务全部通过**，含原业务/媒体/知识/WS/计费与45次Java Readiness故障，`b2-model-transport-services.log`。本组独立运行；使用上一批已验证JAR，未记为新九模块构建。这里模型显式Mock，不把全栈通过冒充真实供应商兼容性证明。

边界/回滚：无真实模型质量/性能/Token/成本或生产流量数据，供应商路由/隐式全局选择/视觉/Embedding/完整计量治理仍未完成；旧同步计量器不在HTTP deadline内。某些非规范上游若返回压缩/非JSON/重复字段/超大正文会被明确拒绝，尚未实测付费供应商。下一批接Runtime模型适配与有界循环，保留原预算，真实Token/金额治理仍属M11。回滚本传输和call_chat委托，保留Run/Step/正文/计量；无SQL回滚。不能因为回退方便就把旧错误正文日志作为生产推荐。

7bfb661对应的[CI 44736537509](https://github.com/jkwang1129/SupportFlow/actions/runs/44736537509)两个job全部成功，含受限Chat传输/更新后的RAG故障和三套完整服务路径。

## B1 模型适配子批次：显式Profile/单次预留/正文与usage恢复（2026-11-20）

基线7bfb661。新增`ai/runtime/models.py`、`test_runtime_models.py`、`test_runtime_models_postgres.py`、ADR-0011；core/chat_transport仅提取原配置校验私有函数供Profile复用、算法不变；Runtime专项入口/矩阵/本记录更新。无SQL/Java/新依赖/默认业务入口改变，不包装旧Workflow或使用旧Mock网关作真实调用入口。

ChatProfile由可信宿主注入且绑定tenant，凭据隐藏/不进定义Hash；端点/模型/Prompt/预算策略入Hash，密钥轮换可复用原同策略结果。上下文固定首条system、后续user/assistant、闭合role/content，≤32条/每条16KiB/总编码32KiB，超限拒绝无截断。先预留MODEL/NONE才通过既有call_chat发送固定profile单次HTTP，稳定错误走同一Run重试，无选择其他provider/隐式再请求。成功单一stop/assistant有界正文≤8KiB、原生tool_calls/拒绝/截断/空值/非法Unicode/当前凭据原样回显拒绝；chat-result-v1及供应商usage随Step SUCCEEDED同事务。缺usage保留未知而非零。Runtime不再隐式写旧best-effort账本，失败/取消/提交前丢输出的未知费用结算仍待M11。恢复重新校验策略/request/版本/正文/usage，沿用原fence/预算。

本地Python3.11.5/隔离PG17/JDK25旧已验收JAR；模型HTTP均确定性MockTransport，无真实付费模型/开发者.env：

- `cd ai-service; python -m pytest -q tests/test_runtime_models.py tests/test_chat_transport.py`：**112项通过**＝新模型57+原共用传输55，`b1-model-adapter-unit-first.log`。配置Profile/租户与Lease、角色/结构/字节边界、单次HTTP与无第二账本、供应商结束/角色/正文/usage错误、缺usage未知、稳定瞬时/永久错误、缓存策略与请求Hash重验、凭据轮换和策略改变分别处理。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-model-adapter-ai-final.xml`：**476 passed、158 skipped、1 xfailed**，`b1-model-adapter-ai-final.log`。外部条件PG由专项实际执行，正文引用已知xfail仍保留且只允许AssertionError。
- `python scripts/test-agent-runtime.py --with-java-delivery`：**168项Python专项+6次角色负面通过**，新16项真PG及原Java交付/Source/工单v2当前版本刷新/许可/双向回复与丢回执全部通过，`b1-model-adapter-runtime-first.log`。模型PG16例：同步骤两次访问仅一次HTTP/预留/正文及13/7合成usage原子保存；下游READ重试不再调模型；新Worker对象/换密钥复用；503后max_model=1在第二HTTP前预算拒绝/预算2成功；401永久失败不重试；截断/凭据回显不存成功正文；执行中取消不保存迟到输出也不退预留；正文提交成功但丢DB回执后复用且HTTP仍1；策略/消息改变不套旧逻辑步骤；Hash有效但Profile/usage/凭据正文被改仍拒绝；错租户Profile在Step/Model预留前拒绝。没有把MockTransport+真PG说成真实模型协议网络或模型质量验证，网络边界由前一批真实TCP三例另有证据。

本批首轮均通过。成功usage=13/7只是合成供应商信封，不是项目Token实测或费用；模型次数是预留计数，不等于供应商确认付费次数。无Java/SQL改变，本地不重复九模块/103客服PG/4KB/三套完整服务（上批已实测，当前CI会重跑）。边界：无租户Profile Resolver、自动Worker或完整Agent loop；HTTP可在途到profile超时，取消不能保证供应商停止收费；当前配置密钥回显保护不等于通用PII/密钥识别，其他上下文/供应商/金额治理仍后续。回滚停用模型适配，保留MODEL步骤/正文/usage/调用计数；不把新策略计划指向旧版本或重置预算，旧Workflow不切换。

0d4db15对应的[CI 44738498124](https://github.com/jkwang1129/SupportFlow/actions/runs/44738498124)两个job全部成功，含模型Profile/正文与usage恢复和三套完整部署路径。

## B1 有界只读Loop核心子批次（2026-11-20）

基线0d4db15。新增`ai/runtime/ticket_loop.py`、`test_ticket_loop.py`、`test_ticket_loop_postgres.py`、ADR-0012；扩Runtime专项入口/矩阵/本记录。复用同一Model/Tool Executor与Run/Step/正文/预算，不新增SQL/Java/依赖/状态机，不自动注册或切换业务。

宿主私有来源bootstrap当前核权；固定政策/catalog/Profile/来源Hash的INTERNAL绑定；最多4次严格JSON模型决策（tool/report/clarify），工具仅既有get_ticket_status v2。模型只看原问题与工单最小三字段投影，不能直接更改租户/主体/Scope/URL或声明业务写已完成。report须指向成功观察，再次当前核权，状态变化可刷新，来源/工单绑定重验后固定模板产出ticket-report-v1并原子存working-state；Run只ready引用，没有发送业务消息/创建工单。上下文不同的模型步骤需新预留，整体仍原Run预算。报告规范JSON Hash防止True==1的类型混淆。

Python3.11.5/隔离PG17，模型和业务HTTP为确定性协议Stub，无付费模型/开发者.env：

- `cd ai-service; python -m pytest -q tests/test_ticket_loop.py`：首轮**17项通过**，`b1-ticket-loop-unit-first.log`；后续共享夹具扩展真实Run引用/503状态脚本，17项随全量通过。含动态查询/停止/固定澄清、最后状态变化/归属撤权、无效JSON/重复字段/幻觉写完成字段、未知工具/越权参数/假观察引用、轮数限制、来源Hash不同但各自投影Hash有效仍拒绝报告。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b1-ticket-loop-ai-final.xml`：**493 passed、173 skipped、1 xfailed**，`b1-ticket-loop-ai-final.log`；外部条件PG专项实测，原正文引用xfail保留且仅AssertionError。
- `python scripts/test-agent-runtime.py --with-java-delivery`：首轮**182项Python+6次角色负面、原全部实际Java交付/来源/工单/许可/双向回复联调通过**，`b1-ticket-loop-runtime-first.log`。本批新Loop的模型/业务网络仍Stub，不把原工具的实际联调替代新Loop组合联调。追加澄清真实PG后`python scripts/test-agent-runtime.py`：最终**183项Python+6次角色负面通过**，`b1-ticket-loop-runtime-final.log`。

新15项真PG：正常Loop2Model/5Tool/10Step、5正文产物/固定事实；澄清1Model/2Tool/5Step且无工单查询；规划期间状态2→4后新核权报告4（模型此前只看到2）；归属撤销无报告；4类坏模型动作（假观察/未知shell/租户参数/自由完成声明）在工单请求前停止；503一次后保原预算3Model/6Tool/12Step/retry1完成；模型预算1无免费第二调用；工具预算4不能免费用缓存跳过最终核权；轮数1不隐式续问；MODEL提交丢DB回执后不重复已成功规划且原预算完成；模型应答阶段取消不保存迟到计划/报告；报告提交丢DB回执后管理员把status=1改true并同步Hash，恢复仍REPORT_CHECKPOINT_INVALID，模型HTTP保持2次（此故障用例显式Limits16Step/12Tool）。没有假称执行shell或写业务成功。

本地首轮全部通过；新增澄清专项补测由最终PG通过。无Java/SQL变化，不重复九模块/103客服PG/4KB/三套完整服务，基线CI已有通过，当前CI仍重跑精确提交。边界：目前只有协议Stub模型选择，不是AI语义评测/真实供应商质量；新Loop尚未组合实际模型HTTP/客户JAR，下一批做该验收。报告私有存储不等于渠道回复，知识/写工具/审批/UNKNOWN核销/可信触发/长历史/成本结算仍缺，不宣布B1整体完成。回滚不注册Loop/回退handler，保留所有绑定/模型/工具/报告与预算，无SQL回滚、不清空UNKNOWN。

c4ff0e0对应的[CI 44741139233](https://github.com/jkwang1129/SupportFlow/actions/runs/44741139233)两个job全部成功，含只读Loop核心/报告Checkpoint负例和三套完整部署路径。

## B1 只读Loop实际组合验收子批次（2026-11-20）

基线c4ff0e0。新增`scripts/ticket_loop_fixture.py`、`ticket_loop_process.py`，接入原runtime_delivery_fixture；ADR-0012/矩阵/本记录更新。没有产品Python/Java源码、SQL、依赖、默认路由/开关改变，复用上一批已验收JAR。模型为真实本机chat/completions HTTP协议服务器、预设JSON决策和usage；来源/工单/权限为真实客户JAR/受限PG，不替换SDK/Store/业务授权为Stub。模型决策不是实际LLM能力评测。

每例在JAR就绪后由受限RuntimeStore登记Run，客户/来源消息/已交付Outbox/工单显式SQL夹具；不把setup说成自动入队或真实用户上线。Java所有AI目标仍指向显式Mock AI，模型profile仅指回环Stub，Worker不读开发者.env或真实模型密钥。JAR仅开启source/read-tools，result默认关闭；产物为私有报告，不发业务回复或建工单。

复现`python scripts/test-agent-runtime.py --with-java-delivery`，本地Python3.11.5/隔离PG17/JDK25：

- 首轮**183项Python+6次角色负面、原全部实际交付/来源/工单/许可/回复丢回执通过**，新增组合8例通过，`b1-ticket-loop-real-first.log`；原8例机器数据保留`.local/implementation/b1-ticket-loop-real-eight-cases.json`。
- 增补真实Worker进程退出/接管后，最终同命令**183项Python+6次角色负面及所有既有实际场景+新9例全部通过**，`b1-ticket-loop-real-final.log`；首次即通过，没有修改原120秒deadline/调用计数/ready_at/数据库租约时刻。

实际九例逐案从AI与客户库读事实：

| 场景 | 实际终态 | 模型HTTP/Model预留 | Tool预留/Step/retry | 报告 |
| --- | --- | --- | --- | --- |
| 正常当前状态 | SUCCEEDED | 2/2 | 5/10/0 | 1，status2 |
| 规划期间实际SQL状态2→4 | SUCCEEDED | 2/2 | 6/11/0 | 1，status4，固定模板 |
| 规划期间实际SQL归属撤销 | FAILED/TICKET_REVOKED | 2/2 | 5/9/0 | 0 |
| 模型传其他tenant参数 | FAILED/TOOL_ARGUMENTS_INVALID | 1/1 | 2/4/0 | 0 |
| 模型假观察引用 | FAILED/REPORT_EVIDENCE_INVALID | 1/1 | 2/4/0 | 0 |
| 模型自由宣称已退款 | FAILED/PLANNER_DECISION_INVALID | 1/1 | 2/4/0 | 0 |
| HTTP503一次 | SUCCEEDED | 3/3 | 6/12/1 | 1；等待实际ready_at，不回写时间 |
| max_turns=1 | FAILED/PLANNER_TURN_LIMIT | 1/1 | 4/7/0 | 0 |
| 模型Checkpoint提交后真实进程os._exit(23) | SUCCEEDED | 2/2 | 6/11/1 | 1；新进程attempt2 |

进程故障首个子进程留下MODEL_CHECKPOINT_COMMITTED/exit23，Run实查RUNNING/model1；第二个真实进程等待3秒夹具租约自然到期后接管、复用首次模型输出，实际HTTP总数仅2（原规划+后续报告），不是重新规划3次。生产默认lease30秒不改；两次子进程只收allowlist环境、受限AI DSN/当前服务测试凭据/合成模型key，不传客户库/管理员/开发者真实模型密钥。私有原始stdout/stderr为`loop-crash-process.log`、`loop-resume-process.log`。

所有例实际客户库均只有原来源消息1、原工单1、结果回执0；`runtime-ticket-loop.json`机器证据保留4个成功/5个预期失败、实际状态/计数/报告数/进程重启标志；source_enrollment=EXPLICIT_FIXTURE_SETUP、publication=NOT_IMPLEMENTED、provider_usage=SYNTHETIC_VALUES、performance/token_cost=NOT_MEASURED。已有CI artifact自动收集，无正文/凭据。纯测试/文档变化，本地不重复493普通AI/103客服PG/4KB/九模块/三套完整服务，当前精确提交CI仍重跑；不把条件skip计为通过。

边界：这是实际本地协议/业务服务组合验收，不是付费模型质量/业务收益/生产性能；只读报告完成不等于渠道消息送达。可信触发/租户Profile Resolver、知识Embedding预算、业务回复发布、建工单/审批、UNKNOWN核销与完整成本/上下文治理仍后续。回滚移除新验收入口/不注册Loop，默认Workflow不变；不删业务/Runtime历史或预算，无SQL回滚。

## B1 核验报告发布子批次（2026-11-20）

基线2f30a92。新增`ai/runtime/publication.py`、`test_publication.py`、`test_publication_postgres.py`、`scripts/publication_fixture.py`、ADR-0013；TicketReadLoop提取不可变PreparedReport/prepare，原只读__call__保留；Runtime专项与实际组合入口更新。无SQL/Java/依赖/默认路由改变。宿主仅发布经过当前来源/工单核验的固定模板，模型没有任意写工具或自由业务成功声明权限。

复用reply-v1与现有签名当前写许可/消息+回执原子事务，预留TOOL/WRITE再POST。瞬时失败后，单独预留TOOL/READ检查匹配Run/event/snapshot/正文Hash的回执，确认后才能完成原WRITE；无回执、查询故障、预算不足、取消/失租约都不证明未执行，不重发POST。已成功WRITE恢复先确认历史回执，不重新规划已经改变/接管的来源。成功引用outcome=reply-committed，只表示业务数据库提交已核实。

Python3.11.5/隔离PG17/JDK25，模型为合成协议，未使用真实模型或开发者.env：

- 单元`cd ai-service; python -m pytest -q tests/test_publication.py tests/test_ticket_loop.py`首轮27项通过，`b1-publication-unit-first.log`。发布10例覆盖固定报告、规范正文Hash/WRITE意图、丢响应只查回执、不确定/永久拒绝不重发、过去提交恢复跳过重新规划、损坏引用及不可变产物。
- AI普通全量：503 passed、183 skipped、1 xfailed，`b1-publication-ai-final.log/xml`。外部条件PG在专项执行，原RAG正文引用xfail仍保留。
- `python scripts/test-agent-runtime.py --with-java-delivery`核心首轮193项Python+6次角色负面、所有原实际交付/来源/工单/只读Loop九例/许可/回复联调通过，`b1-publication-runtime-first.log`。新增10项真PG：正常2Model/6Tool/11Step；澄清1/3/6；丢POST响应查询确认2/7/12；无回执/查询503/授权拒绝均保留FAILED/SIDE_EFFECT_UNKNOWN且后续同会话不可领取；工具预算6不足时无免费确认GET；提交后取消不变Runtime成功；Step提交丢DB确认后恢复不重复模型/业务POST、原2/7/12/retry1；提交引用损坏阻断恢复。
- 新实际模型协议TCP→Worker→客户JAR→真实AI写许可→受限PG五例均通过，机器证据`runtime-publication.json`：正常SUCCEEDED/2Model/6Tool/11Step；POST提交后丢响应SUCCEEDED/2/7/12；代理未转发写请求FAILED/SIDE_EFFECT_UNKNOWN/2/7/12、无业务回执；真实提交后取消FAILED/SIDE_EFFECT_UNKNOWN/2/6/11、存在一条业务回执；成功WRITE Step丢DB确认后恢复SUCCEEDED/2/7/12/retry1。每例模型HTTP均2次；已提交例客户库恰有原来源+BOT回复共2条、seq2/回执1；未观察到提交例来源1/seq1/回执0。成功步骤恢复前实际接管来源，仍只查旧回执、不重新生成/再次发消息。无将取消后的真实提交伪装为撤销或Runtime成功。

首次新增组合运行在新五例全部通过后，旧result_delivery_fixture的2秒客户端窗口先于上游提交观察，断言失败，保留`b1-publication-real-first.log`。只调整该故障注入夹具为6秒总窗口/提交确认后延迟正文10秒，保留实际提交时间、超时类别、请求次数、消息/回执断言；产品HTTP/写许可/租约/Run预算均不改变。完整重跑结果将在核实后记录。

适用边界：这是本地协议/服务/事务验收，不是LLM正确率、真实Token成本或生产性能。报告事实为最后核权读取时点，当前reply-v1未把工单事实版本绑定进随后跨服务提交事务；不声称外部渠道已送达/恰好一次。终态UNKNOWN仍需受控核销；没有自动可信路由、租户Profile Resolver、知识Embedding预算、创建工单或HITL。回滚停用TicketReplyHandler/关闭result开关，保留历史消息/回执、Run/Step/预算/UNKNOWN，不删数据或更换Run绕过幂等。ADR-0013记录设计取舍。

完整最终重跑`b1-publication-real-final.log`退出0：193项Python专项+6次角色负面，原交付/来源/工具/九例Loop、五例报告发布、当前写许可和双向回复全部通过。旧丢响应用例核实提交后超时、只一条消息/回执；没有通过删除断言或改产品超时绕开失败。

7370c5c对应的[CI 44763844891](https://github.com/jkwang1129/SupportFlow/actions/runs/44763844891)两个job全部成功，含193项Runtime专项、五例核验报告发布及三套完整部署路径。

## B1/B3 终态未知回复核销子批次（2026-11-20）

基线7370c5c。新增`ai/runtime/reconciliation.py`、`test_reconciliation_postgres.py`、AI004增量及ADR-0014；RuntimeStore增加原子核销/一致性读，ResultClient原typed receipt核验提取为共享receipt_reference，发布逻辑调用它而无协议改变。manifest/当前schema契约/唯一账号provisioning/readiness、原fixture清理、迁移/初始化/全服务断言更新；实际publication_fixture追加显式核销场景。没有公开核销API、模型工具、自动轮询、重发业务请求或新框架。

设计：仅runtime-v1、FAILED/SIDE_EFFECT_UNKNOWN、无owner/lease、恰一个session.reply/reply-v1 UNKNOWN WRITE可核销。可信宿主注入预配ResultClient/operator_ref，单次有界GET严格核对tenant/event/run/snapshot/参数Hash；无回执/异常保留阻断。短事务按Run→Step锁，重验观察fence/来源/原意图Hash/唯一WRITE；插入不可改账本、原Step确认成功、Run保持FAILED而error变SIDE_EFFECT_CONFIRMED并递增fence。保留cancel_requested、finished_at、原Step completed_at、全部预算/attempt/retry；原未知错误/Step结果、操作者、业务消息/seq/DB确认时间入账。只有未知副作用顺序阻断解除，取消任务不复活，也不声称渠道送达。重复/六路并发同一证明只一条记录/一次fence变化，首次操作者/时间不覆盖。最终幂等分支另重验完整来源与物理Step key，防止只比摘要漏掉错观察。

本地Python3.11.5/隔离PG17，模型与客户HTTP在PG专项为协议Stub，实际JAR组合另记：

- AI普通全量：503 passed、204 skipped、1 xfailed，`b1-reconciliation-ai-final.log/xml`。21项新增PG在下面执行，未把skip当通过。
- `python scripts/test-agent-runtime.py`：新增21项在214项专项中全部通过，`b1-reconciliation-runtime-second.log`。取消且已提交后核销，预算/时间/取消不变且后续事件可领取；无回执/503保持UNKNOWN；7种错回执/类型；错租户/非终态；过时fence/损坏意图；多WRITE拒绝；六路并发一致/改证明拒绝；最终Run更新故障时审计/Step/Run全回滚；过期后只确认过去事实、丢核销提交回执后不多发GET；损坏已核销Run拒绝；RLS无上下文/PLATFORM/跨租户/额外放宽策略仍隔离；无UPDATE/DELETE/TRUNCATE/DDL及复合FK，readiness拒绝UPDATE权限漂移；严格candidate类型防止bool冒充整数。
- 最终补强幂等分支完整来源/Step key后，复用相同隔离初始化/账号配置只运行`tests/test_reconciliation_postgres.py`：21项通过，`b1-reconciliation-targeted-final.log/xml`（本机辅助入口`.local/implementation/run_reconciliation_tests.py`）。标准完整复现仍是Runtime专项命令；没有替换Store/跳过角色防护/用管理员执行产品SQL。
- `python scripts/test-versioned-migrations.py`最终17项通过，`b1-reconciliation-upgrade-final.log`。保留002→003已认领Run/预留Step/预算精确不变，同时验证003→004原leased/reserved/committed Run、全部Step、正文/Hash/TTL/预算精确不变，新核销表为空；六库重复/只读校验、锁/校验和/历史/回滚/运行账号拒绝继续通过。
- `python scripts/test-schema-drift.py`：94表/1视图/5函数、14个实际SQL和3个metadata负例通过，`b1-reconciliation-drift-final.log`。候选从独立canonical PG生成，审查差异只有新核销表与004清单Hash，旧SQL/checksum未修改。
- `python scripts/test-database-init.py`：真实CLI空库94表、重复初始化拒绝、无关种子保留、锁/运行身份拒绝、故障库DDL回滚和部分init拒绝通过，`b1-reconciliation-init-final.log`。
- `python scripts/test-runtime-security.py --with-ai-readiness`：新/旧环境分别28/29条数据库安全断言、每套4项AI/KB就绪测试通过，`b1-reconciliation-roles-final.log`。新表保持受限账号只SELECT/INSERT，无运行时DDL。

首轮Runtime在角色故障注入阶段发现AI004缺少最新增量应具有的能力组安全检查，触发DuplicateTable而未按预期安全拒绝；补齐与003相同的NOLOGIN/非owner/非管理员检查，保留`b1-reconciliation-runtime-first.log`。首次升级主体已成功，但原版本测试仍只期望001～003而失败；更新明确清单断言为001～004，保留`b1-reconciliation-upgrade-first.log`。未修改旧迁移、角色约束或跳过负面用例。

边界/回滚：核销只确认已有reply-v1提交，不裁定没有回执的写是否执行、不自动重试、不支持任意业务写、多WRITE或未知版本；手工控制面一次GET独立于已终止Run预算，不恢复/退还执行额度，没有模型费用。无回执/失败探测尚无独立运维审计流/自动策略；将来接运维UI须独立操作员认证和限流。追加账本不可改不等于防止已失陷的完整AI运行身份/DBA；已有角色的Run/Step写权限保持原边界。停用Reconciler、保留004/账本/原业务消息和回执，不物理降级/删历史或清空UNKNOWN；FAILED/SIDE_EFFECT_CONFIRMED不授予执行权。无真实LLM、Token费用、吞吐、业务收益数据。

实际组合复现`python scripts/test-agent-runtime.py --with-java-delivery`最终退出0，`b1-reconciliation-real-final.log`：214项Python专项、6次角色负面、原全部实际Java交付/来源/工具/九例Loop/五例发布/许可/双向回复与新增两例核销通过。`runtime-reconciliation.json`按场景保留：未转发写且无回执→FAILED/SIDE_EFFECT_UNKNOWN、审计0、后续事件不可领取；真实提交后取消再显式核销→仍FAILED/SIDE_EFFECT_CONFIRMED、审计1、后续事件可领取；两例预算/结束时间/取消标记保持，新业务消息均0、模型HTTP仍2。确认已有提交前实际坐席接管来源也不会重新发消息。该脚本PG全量进程启动早于最后的幂等来源/Step key补强；当前版本补强由上述独立21项最终PG和后续实际组合部分覆盖，精确提交CI仍会重跑完整专项。

最终独立运行`python scripts/test-security-flow.py`退出0，`b1-reconciliation-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整实际服务路径均通过，包含登录/企业授权、知识索引与A/B隔离、访客WS→AI→BOT回复、坐席认领/工单/计费批次、附件权限、五个Java服务readiness故障恢复、六库版本历史及新核销表SELECT/INSERT/无UPDATE/DELETE/TRUNCATE实查。完整服务验收期间未并行运行其他Maven或服务验收。

d8a65ad对应的[CI 44767901532](https://github.com/jkwang1129/SupportFlow/actions/runs/44767901532)两个job全部成功，含精确提交的214项Runtime专项、两例实际业务回执核销和三套完整部署路径。核销批次本地/远端退出条件已完成；仍不代表B1/B2/B3整体完成。

## B2 知识检索前置：文档范围在降级路径保持一致（2026-11-21）

基线d8a65ad。发现向量SQL有doc_ids筛选，但内置/LlamaIndex四条关键词兜底未传范围；问答图/API及独立检索API把[]归成None。新增`rag/document_scope.py`、`test_rag_document_scope.py`、`test_rag_document_scope_postgres.py`、ADR-0015；更新两API、graph/pipeline/llama_engine/store和既有customer-rls测试入口。无SQL/Java/依赖/默认Worker/业务触发修改。

None=保持调用方当前租户范围；[]=空选集，图/检索/存储都短路，无Embedding/数据库/生成且不改写；正int64列表≤200个，拒绝bool/字符串/零负数/超限，按首次出现去重。问答与独立检索API都保留此区别，现有Java调用省略docIds仍走原租户范围。内置与LlamaIndex所有关键词兜底传原doc_ids，SQL参数化ANY与同租户JOIN/RLS、is_deleted条件同时生效；Llama全零向量与内置策略一致退回有范围关键词。该筛选只缩小选择，不授予文档/租户权限；发布/撤权/版本及完整知识工具还未完成。

本地Python3.11.5/隔离PG17/JDK25，Embedding/生成均为合成Stub，无真实供应商或.env：

- 首批39项范围单元/图/API测试在旧代码下33失败、6通过，`b2-knowledge-scope-baseline.log`；修复后连原24项拒答门禁共63项通过，`b2-knowledge-scope-unit-first.log`。覆盖六条关键词退化组合、空范围零I/O、7类非法范围在I/O前拒绝、LangGraph/linear一致、API区分省略/null/空/非空。
- 追加独立KB检索API九项，旧入口7失败、2通过，`b2-knowledge-scope-kb-baseline.log`；补齐该入口后新增范围测试共48项，随最终全量通过。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-knowledge-scope-ai-final-v2.xml`：551 passed、211 skipped、1 xfailed，`b2-knowledge-scope-ai-final-v2.log/xml`。KB入口补齐前原始全量542通过/211skip/1xfail保留在`b2-knowledge-scope-ai-final.log/xml`。原引用正文已知xfail未放宽。
- `python scripts/test-customer-rls.py --with-ai`：103项真实Java PG全部通过；Python11项通过=原4项知识诊断/存储测试+新7项范围PG，`b2-knowledge-scope-pg-final.log`。真实pgvector/关键词SQL证明选中文档仅返回自己、错租户/已删除/空选集无结果、None保留租户范围；两引擎本地/零向量/向量无命中后真实关键词不扩到其他文档。连接为非superuser/非BYPASSRLS且非owner KB身份，产品SQL不用管理员；管理员仅构造隔离语料。
- 首轮PG的103项Java已通过，Python10过/1失败，夹具全零向量误写1023维而库为1024维；修正夹具，并对两引擎全零输入走关键词加实际mode断言，保留`b2-knowledge-scope-pg-first.log`。未放宽SQL文档/租户筛选或跳过负面测试。
- 最终JUnit输入`python scripts/report-rag-contract.py --junit .local/implementation/b2-knowledge-scope-ai-final-v2.xml --output .local/verification/rag-contract.json`：24项全通过/无skip、gate_passed=true，`b2-knowledge-scope-gate-final-v2.log`。
- `python -m unittest discover -s scripts/tests -p 'test_*.py' -v`：Ran22、12通过/10需独立PG条件而skip，`b2-knowledge-scope-scripts-final.log`；不是22项全部执行通过。PG迁移由上批精确CI已验，本批无SQL改动。

知识引用事实支持、发布/下线/版本ACL、知识工具及Embedding预算尚待后续；None的全租户检索仍依赖可信调用方和租户隔离，不把文档选择等同授权。已知原RAG日志/错误响应可能含问题或原异常文本，接知识工具前仍需收敛；没有宣称完整PII保护或生产级知识治理。回滚先停受限文档调用，保留数据和Runtime历史；不能把空列表改为None或在失败时去掉范围作为兜底。无语义质量/性能/Token/费用改进数据。

完整服务首轮`b2-knowledge-scope-services-final.log`三套路径通过（当时独立KB API语义尚未补齐）；补齐KB入口后重新运行`python scripts/test-security-flow.py`最终退出0，`b2-knowledge-scope-services-final-v2.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套均通过。真实Java省略docIds的默认调用、知识索引/检索、访客WS→AI→BOT回复、权限/计费/工单及只读readiness负面恢复保持；未并行Maven或其他服务验收。当前提交CI仍会重跑Runtime专项和六库完整检查，本批本地未无故重跑未改动的214项Runtime/SQL迁移。

2bca997对应的[CI 44850593648](https://github.com/jkwang1129/SupportFlow/actions/runs/44850593648)两个job全部成功，含48项新增范围测试/551项AI普通、103项客服PG+11项KB Python、214项Runtime和三套完整实际服务，文档范围批次已完成本地/远端退出条件。

## B2/M04 知识工具前置：应用诊断与已处理错误响应（2026-11-21）

基线2bca997。修改`api/{kb,rag}.py`、`rag/{embedding,graph,llama_engine,pipeline,retriever,store}.py`及`services/rag.py`的诊断/已处理错误边界；新增`test_rag_diagnostic_privacy.py`、ADR-0016。不新建日志/观测框架，无SQL/Java/依赖/默认路由改变。

问题/文件名/原始异常不再进入所测ai.*应用日志；保留现有Trace/租户/文档引用、长度/数量、模式/命中/分数/耗时、固定错误码与errorType。取消原异常正文和exc_info，已处理HTTP错误from None；解析400/其他500语义保持，提示改为固定文本。retriever仍error模式，error字段改固定RAG_RETRIEVAL_FAILED。原Embedding/Reader/关键词/无模型降级和正常回答/引用/索引文件元数据保持，非从授权业务结果删除正文。

Python3.11.5，全部问题/文件名/凭据与上游响应为合成标记/Stub，无真实模型/.env：

- 修正测试夹具后18项在旧代码上全部失败，`b2-rag-diagnostics-baseline-final.log`；修复后`cd ai-service; python -m pytest -q tests/test_rag_diagnostic_privacy.py`首轮18项通过，`b2-rag-diagnostics-unit-first.log`。包括两API原始错误/问题、解析400、成功问题日志、retriever固定错误、两引擎关键词、grade、两种Embedding异常、本地无模型降级、DB探活、两类诊断查询异常、索引API/两引擎正常文件元数据和可选Reader异常。
- 首版夹具`b2-rag-diagnostics-baseline.log`为14失败/4通过，其中2项Stub缺字段/函数名错误，长问题标记也超出原40字截断而未被完整匹配；修正为短标记与真实split_text/Chunk字段，再在未改产品代码时得到18项实际负面失败。不把测试基础设施错误计为产品泄露证据。
- `cd ai-service; python -m pytest -q --junitxml=../.local/implementation/b2-rag-diagnostics-ai-final.xml`：569 passed、211 skipped、1 xfailed，`b2-rag-diagnostics-ai-final.log/xml`。不将PG条件skip算通过，既有引用正文xfail保留。
- `python scripts/report-rag-contract.py --junit .local/implementation/b2-rag-diagnostics-ai-final.xml --output .local/verification/rag-contract.json`：原24项全部执行通过、gate_passed=true，`b2-rag-diagnostics-gate-final.log`。

边界：此处只覆盖所列ai.*应用日志/已处理错误响应，不保证第三方HTTP/访问/代理日志、未处理异常、Java可选payload日志、其他AI模块或模型上下文均无敏感信息；未清历史日志。不是通用PII检测/全系统脱敏/生产泄露报告。原业务正文仍按现有权限保留，后续Trace/评测导出和保留策略需M04/M07/M12治理。回滚保留固定错误与最小诊断，不恢复原正文输出；无业务数据/Runtime历史/预算回滚。新知识工具/发布版本与Embedding硬预算仍未完成，无质量/性能/费用成果。

实际复现`python scripts/test-security-flow.py`最终退出0，`b2-rag-diagnostics-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整真实服务路径均通过，含知识索引/检索、访客WS→受保护AI→BOT回复、权限/工单/计费、六库版本与readiness负面恢复。日志调用的占位参数与降级正常路径经实际服务验证；测试期间无并行Maven/服务验收。无SQL/Runtime改动，本地不重复未改动的PG专项/214项Runtime；精确提交CI继续全跑。

c224389对应的[CI 44852709345](https://github.com/jkwang1129/SupportFlow/actions/runs/44852709345)两个job全部成功，含569项AI普通/18项诊断内容排除、完整PG/Runtime和三套服务。该批本地/远端退出条件已完成。

## B2/M05 访客知识的发布/索引就绪策略（2026-11-21）

基线c224389。新增`test_rag_published_policy.py`、`test_rag_published_policy_postgres.py`、ADR-0017；可信宿主brain/旧chat固定published_only=True，用户问题/状态同名值不能覆盖。策略贯穿RAG图的改写重检、retriever、内置/LlamaIndex向量/关键词，与doc_ids求交；SQL增加d.status=3 AND d.index_status=3，保留同租户JOIN/RLS/删除过滤。后台现有问答/检索默认False继续支持编写预览，无新访客请求字段。严格bool，不把0/字符串当权限策略。

真实受限KB账号揭示index_status列无SELECT，不能仅靠单元Stub验收。新增Customer008权威增量，只给NOLOGIN/非owner/非管理员KB能力组该列SELECT；没有整表/正文SELECT或文档修改权限，不改历史003。AI schema_guard的KB必读列加index_status；manifest/current-schema、迁移测试与独立SQL夹具/版本断言更新，仍94表/1视图/5函数。唯一40-runtime-roles.sql无需复制授权，原能力组列授权在provision后保留。

本地Python3.11.5/隔离PG17/JDK25；向量/模型为合成Stub，无真实供应商/.env：

- 新26项在旧代码下全部失败（原无该宿主策略/参数，含不支持参数的TypeError；不把这些当生产泄露事件），`b2-published-policy-baseline.log`。实现后新26+48范围+24拒答共98项通过，`b2-published-policy-unit-first.log`。访客入口硬选择/伪造状态，两图改写不丢策略，两引擎向量/三种关键词路径保留策略+文档范围，8种非bool调用在I/O前拒绝。
- AI普通全量595 passed、219 skipped、1 xfailed，`b2-published-policy-ai-final.log/xml`；新8项PG由专项执行，既有引用正文xfail保留。
- 首轮`python scripts/test-customer-rls.py --with-ai`：103项Java通过，Python11过/新8失败，真实KB角色查询index_status被拒，`b2-published-policy-pg-first.log`。新增最小008授权后同命令103项Java及Python19项全部通过，`b2-published-policy-pg-final.log`。19=原4知识诊断+7范围+新8发布策略PG。新8覆盖真实向量/关键词只返已发布且索引就绪，草稿/审核/下线/未索引/索引中/失败/删除/异租户均不返；后台默认仍可见同租户草稿；限定doc_ids与发布策略交集；下线/失败/索引中/重新发布后的下一查询反映当前状态；两引擎local/zero/vector miss后关键词不漏策略/范围。
- `python scripts/test-versioned-migrations.py`：17项通过，`b2-published-policy-upgrade-final.log`。原006→007与AI002→003→004数据保护保留；新增007→008前后业务来源/快照/消息及草稿/切片全字段精确不变，index_status读取False→True、content SELECT和文档INSERT/UPDATE/DELETE仍False，3种不安全KB能力组状态拒绝。迁移重复、只读verify/checksum/历史/锁/回滚继续通过。
- `python scripts/test-schema-drift.py`：94表/1视图/5函数、14 SQL+3 metadata负例通过，`b2-published-policy-drift-final.log`。从隔离canonical生成候选后审查current-schema差异只有008版本/Hash四行，既有结构/SQL校验和不变。
- `python scripts/test-database-init.py`新建CLI/拒绝覆盖/部分失败回滚通过，`b2-published-policy-init-final.log`；`python scripts/test-runtime-security.py --with-ai-readiness`新/旧分别29/30条安全断言、每套4项AI/KB只读readiness测试通过，`b2-published-policy-roles-final.log`。
- 最终JUnit的RAG报告门禁24项全部通过，`b2-published-policy-gate-final.log`。

边界/回滚：这是SQL查询时点的访客发布可见性，不是文档用户级ACL/版本快照/并发撤权后的最终输出重验，也不能撤回已经发给模型的内容。未引入RAG结果缓存；新查询重新核条件。Embedding仍旧实现，可先于查询发生，不能声称无发布文档时整轮零模型费用；Runtime知识工具的来源核权与显式Embedding预算仍下一步。停止访客知识答复/转人工即可回退，不重新开放草稿、不撤销历史008/清数据；后台编写功能保留。无语义正确率/性能/Token费用或生产收益结论。

最终实际`python scripts/test-security-flow.py`退出0，`b2-published-policy-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整真实服务路径均通过，含显式发布索引文档后的Java检索/访客WS→实际brain→BOT回复、后台默认预览链路、权限/计费/工单、六库历史Customer008和各readiness故障恢复。无并行Maven/服务验收。原Run/Step/预算无改动，本地未重复214项Runtime；精确提交CI全跑。

3d644cb对应的[CI 44854145977](https://github.com/jkwang1129/SupportFlow/actions/runs/44854145977)两个job全部成功，含595项AI普通/访客发布策略、103项客服PG+19项KB、Customer008升级/只读权限与214项Runtime/三套完整服务。该批本地/远端退出条件已完成。

## B2/M08 Runtime单次查询Embedding适配子批次（2026-11-21）

基线3d644cb。新增`ai/runtime/embeddings.py`、`test_runtime_embeddings.py`、`test_runtime_embeddings_postgres.py`、ADR-0018；现有core/chat_transport提取同一有界JSON发送函数，固定chat/completions、embeddings两种操作，原chat包装保持。扩旧TCP测试服务器可返回指定合成响应，Runtime专项加入新PG。无SQL/Java/依赖/自动入口修改，未将旧RAG同步Embedding包装成已具Run预算的调用，也未建第二模型服务。

EmbeddingProfile由宿主显式绑定租户/配置/1024维/查询策略/端点Hash/≤30秒，隐藏凭据repr且不入策略Hash（仅同逻辑策略密钥轮换可复用）。单查询非空UTF-8≤4096字节，不截断，不让配置凭据原样进入文本；固定float/单元素input数组/dimensions1024。先预留MODEL/NONE，一次受限HTTP（无环境代理/重定向/隐藏重试/本地hash），未知/错误操作在HTTP前拒绝。严格模型名/单index0/object、1024有限分量/绝对值≤1e6、既有PG七位小数后仍非零、usage整数和等值总Token。缺usage为未知而非零。向量/供应商reported usage随原model-output-v1成功同事务；恢复重验策略/输入/正文/usage，结果tuple不可变且repr不显示向量。没有第二计费账本或硬金额结算。

协议参考ADR-0018链接的OpenAI/百炼官方接口（2026-11-21核对），这是项目固定兼容子集，不声称所有Embedding模型/批处理均支持；实际验收模型数据为合成，未调用真实供应商或读取开发者.env。

- `cd ai-service; python -m pytest -q tests/test_runtime_embeddings.py tests/test_chat_transport.py tests/test_runtime_models.py`：首轮159项通过＝新47+原55传输+57Chat适配，`b2-runtime-embedding-unit-first.log`；追加Embedding真实TCP正常/重定向/慢正文三例后最终162项通过＝新50+原112，`b2-runtime-embedding-unit-final.log`。请求路由/单次MODEL意图、输入/配置/租户错误在I/O前拒绝、17类坏向量/索引/模型/usage、未知usage、401/429/503/302分类且无第二请求、缓存重验、Key轮换/策略变化、任意操作拒绝及环境代理禁用/总正文超时均核实。
- AI普通全量645 passed、233 skipped、1 xfailed，`b2-runtime-embedding-ai-final.log/xml`；新增14项PG另在专项实测，原引用正文xfail保留。最终JUnit的RAG24项门禁全部执行通过，`b2-runtime-embedding-gate-final.log`。
- `python scripts/test-agent-runtime.py --with-java-delivery`的PG部分228项全部通过，`b2-runtime-embedding-runtime-first.log`。新增14项：同步骤重复访问仅1 HTTP/1MODEL/1原子向量；Embedding与Chat共享预算1时先于Chat HTTP拒绝、预算2时各一次；新Worker+Key轮换复用向量/原计数；503按真实ready_at等待重试，预算1阻第二Embedding HTTP、预算2请求2次成功；执行中取消保留预留且不存迟到向量；向量提交丢DB确认后恢复仍仅1HTTP；有效Hash但向量/usage/策略损坏3例拒绝且不再请求；缺usage持久化unknown；真实TCP Embedding+受限PG Worker两例，成功1次/共享预算拒第二Chat，HTTP均仅1且禁环境代理。

Token8、向量值0.25等均合成协议值，不是项目实际Token成本/检索质量；MODEL次数为预留而非付款次数。输入字节边界不是实际Tokenizer预算。取消/超时不能撤销供应商费用，失败和丢输出费用仍可能未知；硬Token/金额预留/结算后续M11。当前只算单查询表示，不读知识事实或授予文档权限；知识工具仍需当前来源/文档/版本授权。旧RAG Embedding仍原实现，尚无自动Worker/业务切换。回滚不注册新适配/保留原chat包装，保留MODEL/payload/usage/预算，不删除成功步骤重请求，不换策略套旧缓存。无生产/付费模型资格、性能、成本或业务收益结论。

最终`python scripts/test-agent-runtime.py --with-java-delivery`退出0，`b2-runtime-embedding-runtime-first.log`：228项PG/Python专项+6次能力组负面，原全部Java交付/来源/工单/九例只读Loop（含真实进程退出接管）/五例报告发布/两例核销/写许可和丢回执回复全部通过。新14项首轮通过，3例Embedding TCP/14项PG及原Chat TCP正负例保持。公共传输层改动在实际模型协议/客户JAR组合链路核实；无SQL/Java/默认流程变更，本地不额外重复上批已完整通过的三套默认服务/103客服PG/六库迁移，精确提交CI仍全跑。

ad66e75对应的[CI 44856556934](https://github.com/jkwang1129/SupportFlow/actions/runs/44856556934)两个job全部成功，含645项AI普通、228项Runtime专项及全部数据库/三套实际服务。Embedding适配子批次本地/远端退出条件已完成。

## B2 业务端受控知识查询API（2026-11-21）

基线ad66e75。新增`RuntimeKnowledgeCommand`、`AgentKnowledgeQueries`、`RuntimeKnowledgeController`、两类Java测试、ADR-0019及实际HTTP夹具`knowledge_api_fixture.py`；复用RuntimeSourceAccessFilter、既有read-tools开关、服务签名/可信租户绑定与来源快照。不加表、授权、依赖、模型服务或默认Worker入口。Python签名SDK/Registry接入是下一子批次，本批实际HTTP夹具自行签名，不能当已经完成Agent知识工具。

请求只接受六个固定字段，1024维有限非零向量、top_k整数1～5、文档正int64列表≤200且只缩小范围；HMAC覆盖原始请求体。Java在单个≤5秒事务中锁定并核对当前DELIVERED来源/会话/访客消息/快照，SQL限制同租户、发布且索引完成、未删除及精确Embedding模型标签。返回≤5条受限标题/片段/整数百万分评分，不含文件/客户/向量；锁定选中知识行，再核DB时钟来源TTL。响应绑定请求SHA和结果投影SHA；投影Hash不是全库版本或MVCC证明。Embedding须事务外单独计MODEL预算，Java本身不调用模型。当前仅租户公开FAQ策略，不代表用户级文档ACL、撤回已发上下文或最终回答事实支持已完成。

本地Python3.11.5/JDK25.0.5+8/Maven3.9.16/隔离pgvector PG17；凭据/来源/向量均合成，不读.env、不调用真实供应商：

- Java定向测试15项通过＝新`RuntimeKnowledgeAccessTest`6项+原来源Filter9项，`b2-knowledge-api-unit-first.log`。测试含签名/方法/禁用/不可注入权限或URL/严格类型/1024维非零有限边界/重复或尾随JSON/体积/正文篡改，失败在数据库前拒绝。
- `python scripts/test-customer-rls.py --with-ai`：112项真实受限Java PG全部通过＝原103+新`RuntimeKnowledgePostgresTest`9项；19项Python KB全部通过，`b2-knowledge-api-pg-first.log`。新9项含发布/就绪/模型/删除/跨租户/空范围、快照不符/接管/关闭/隐藏/改文/过期、状态变化立即反映、6路并发无业务副作用、实际行锁阻止修改（55P03）、实际SQL后过期重验、撤销SELECT时固定503且不回显SQL。1024个emoji片段为4096 UTF-8字节上限。管理员仅造隔离数据/注入故障，产品查询使用受限角色。
- `mvn -B -f backend/pom.xml verify`全部模块BUILD SUCCESS，`b2-knowledge-api-backend-final.log`；普通Maven中的119项客服条件skip不计通过，PG专项实际执行如上。

回滚：关闭read-tools或停用新调用，保留数据、来源/Run/预算/历史迁移；不回退无发布过滤的直连SQL、不把空列表转全范围。无质量、延迟、Token费用、生产流量或业务收益成果。

`python scripts/test-agent-runtime.py --with-java-delivery`退出0，`b2-knowledge-api-runtime.log`：228项Runtime专项全部通过，原实际Java交付/来源/工单/9例Loop及真实进程接管/5例发布/受控核销/写许可/丢回执回复保持。新增实际customer JAR+受限角色PG知识HTTP夹具验证原始请求签名、Java/Python独立投影SHA一致、发布兼容命中1、空范围0/被排除范围0、跨租户404/错密钥401/人工接管404，无新增消息/回执/模型调用；机器报告`.local/verification/runtime-knowledge-api.json`。该夹具复用已DELIVERED来源，用固定合成向量手动签名，不是知识SDK/Registry、自动调度、向量语义质量或性能成果。

`python scripts/test-security-flow.py`退出0，`b2-knowledge-api-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整实际服务全部通过，含访客WS→现有brain→BOT回复、知识索引/检索、权限/工单/计费、六库历史及readiness负面恢复。验收期间未并行Maven/其他完整服务任务；下一批SDK未改当前运行代码。无SQL变更，未重复未改动迁移/结构专项，精确提交CI继续全跑。

914767a对应的[CI 44859565743](https://github.com/jkwang1129/SupportFlow/actions/runs/44859565743)两个job全部成功，含112项客服PG、228项Runtime、新知识实际HTTP及三套完整服务；知识Java API子批次本地/远端退出条件完成。

## B2 知识查询签名SDK子批次（2026-11-21）

基线914767a。新增`ai/runtime/knowledge.py`、`tests/test_runtime_knowledge.py`、ADR-0020，CustomerTransport仅增固定POST knowledge/search及该操作64KiB请求体上限；旧source/ticket/reply路径/限制不变。实际知识HTTP夹具同时用独立手动签名和产品KnowledgeClient。无SQL/Java/依赖/默认入口修改，SDK本身不预留预算/注册模型工具，下一批才与RuntimeEmbedding/Registry组合。

可信宿主提供来源/Run/快照/来源Hash、查询SHA、兼容索引标签与1024维向量。I/O前严格校验、保留null/空范围/限定列表；对实际序列化字节签名。响应严格核来源/快照/请求/查询/模型/策略绑定，最多top_k项、十进制字符串int64 ID、有限整数评分及顺序、标题200码点/片段1024码点4096字节、重复/范围和跨语言投影SHA。复用总正文超时/无代理/重定向/隐藏重试边界和稳定错误；不回显原错误。返回冻结对象/新投影副本，repr隐藏片段/标题，模型投影不含租户、向量、客户信息。结果Hash不是授权或知识版本，资料正文仍是不可信内容，不代表事实支持已完成。

本地Python3.11.5/JDK25/隔离PG17，仅合成凭据/向量/模型，不读.env：

- 编码前`b2-knowledge-sdk-baseline.log`因新模块尚不存在收集失败；这是缺能力基线，不声称已复现运行时越权漏洞。
- 首轮`b2-knowledge-sdk-unit-first.log`：194通过/2失败；空范围坏响应夹具先索引空列表、TCP 0.3秒期限可能在初始化就超时导致未收到请求。修正夹具并把慢正文期限调1秒（服务器等2秒）；随后`b2-knowledge-sdk-unit-final.log`233过/1失败为坏片段修改了脱离响应的对象。修正夹具为真正改响应内容，未放松SDK检验。
- `cd ai-service; python -m pytest -q tests/test_runtime_knowledge.py tests/test_runtime_source.py tests/test_runtime_tickets.py tests/test_runtime_reply.py tests/test_runtime_result.py`：最终234项通过，新SDK90项+既有144项，`b2-knowledge-sdk-unit-final-v2.log`。新90含真实TCP正常/302不跟随/慢正文总超时三例，并禁环境代理；错误分型仅1请求、坏参数先于HTTP拒绝、原始字节签名、当前来源/请求SHA/投影篡改及非法/重复/越界结果均拒绝。
- AI普通全量735 passed、233 skipped、1 xfailed，`b2-knowledge-sdk-ai-final.log/xml`；PG skip不计通过，既有引用正文xfail保留。`python scripts/report-rag-contract.py --junit .local/implementation/b2-knowledge-sdk-ai-final.xml --output .local/verification/rag-contract.json`：24项全部执行通过，gate_passed=true。

首轮实际`b2-knowledge-sdk-runtime.log`中228项PG及旧交付/来源工具已通过，新SDK在真实JAR失败为KNOWLEDGE_PAYLOAD_INVALID。核源码发现`LongToStringJacksonConfig`将ApiResponse.timestamp的Java long输出为字符串，单元/独立MVC整数夹具未体现。SDK外层元数据改为接受规范十进制int64字符串或整数（仍不进入模型投影、不放宽业务字段），追加字符串时间戳用例。最终AI全量736 passed、233 skipped、1 xfailed（新SDK91项），`b2-knowledge-sdk-ai-final-v2.log/xml`；对应RAG门禁24项通过。保留首轮失败，不把Standalone通过代替实际服务兼容验收。

回滚停用KnowledgeClient，保留Java来源/发布/模型过滤和Runtime历史/预算；不改成任意URL、关闭摘要核验或使用无授权的结果缓存。SDK直接调用不是自动业务触发或完整Agent闭环；暂无Token费用/模型语义质量/延迟或生产业务成果。

修复后`python scripts/test-agent-runtime.py --with-java-delivery`最终退出0，`b2-knowledge-sdk-runtime-final.log`：228项专项及原全部实际交付/来源/工单/9例Loop/真实进程接管/5例发布/核销/写许可/回复联调保持。新增产品KnowledgeClient→实际customer JAR→受限PG通过，Java全局long字符串格式、签名/投影Hash、null/空/有限文档范围、其他租户/错误密钥/当前接管全部验证；机器报告`runtime-knowledge-api.json` v2的agent_sdk=VERIFIED_EXPLICIT_READ_CLIENT。请求SHA由手工字节独立计算，结果SHA由独立公式与SDK/Java交叉核对，无新增消息/回执/模型请求。未把SDK称为已接入Registry/模型决策Loop。

本批无Java/SQL/默认流程变更，本地不重复914767a已全绿CI的三套默认服务/112项客服PG/六库迁移，远端精确提交CI仍全跑。下一批工具文件独立未纳入本次提交及验收。

85c0b89对应的[CI 44861323394](https://github.com/jkwang1129/SupportFlow/actions/runs/44861323394)两个job全部成功，含736项AI普通、228项Runtime、实际知识SDK及完整数据库/三套服务；SDK子批次本地/远端退出条件完成。

## B2 当前授权知识工具与Embedding共享预算（2026-11-21）

基线85c0b89。新增`ai/runtime/knowledge_tools.py`、`test_knowledge_tools.py`、`test_knowledge_tools_postgres.py`、`scripts/knowledge_tool_fixture.py`、ADR-0021；Runtime专项纳入新PG，复用已启用来源/只读工具的实际Loop客服JAR验收新工具。既有RuntimeEmbeddingClient仅新增可信宿主可缩小的单次monotonic截止时间，保持Profile Hash/原Checkpoint/调用方默认行为。不新增表/权限/服务、通用代理、旧RAG隐藏Embedding或自动业务切换。

search_knowledge的模型参数只开放query/top_k/doc_ids；Scope为knowledge:read。每个ExecutionContext构造独立工具，核同服务来源/知识客户端与配置租户，不接受模型传租户/来源/权限/向量/URL/索引模型。Run中绑定Profile定义/服务地址/工具策略（不绑定可轮换密钥），恢复换策略则拒绝。先当前来源核权计授权READ，执行计另一READ，查询表示另预留共享MODEL。来源/查询Hash与有限证据绑定，不把知识正文当系统指令或自动发布答案。

知识尚无完整版本，所以每次授权生成新的nonce引用，沿用current-version策略而每次重新查询当前发布范围；nonce不是知识版本/授权签名。相同策略问题的成功Embedding可以从原子Checkpoint复用，知识正文不能作为授权缓存复用。空范围显式empty-scope，仍核来源/计工具但无Embedding/知识请求，不伪造向量、请求Hash/资源版本或供应商usage。HTTP使用当前工具剩余时间，模型单次时限只能缩小Profile上限；DB仍用既有语句超时，不声称强杀任意同步Python或撤销模型费用。

本地Python3.11.5/JDK25/隔离PG17，来源/知识/模型均为合成样本，无真实供应商/.env：

- 工具先接入时尚无Embedding外层截止参数，`b2-knowledge-tool-predeadline.log`为22过/4失败（新工具的四条模型路径缺该参数，并非旧业务越权漏洞）。补齐截止参数与回归后，`cd ai-service; python -m pytest -q tests/test_knowledge_tools.py tests/test_runtime_embeddings.py`：85项全部通过＝工具26+原Embedding50+新截止9，`b2-knowledge-tool-unit-first.log`。参数/Scope/跨Run/当前凭据拒绝先于对外请求；来源撤权先于MODEL、空范围0模型/知识HTTP、下线后重新查询但向量仅1请求；真实TCP新增外层截止小于Profile的慢正文例。
- AI普通全量771 passed、246 skipped、1 xfailed，`b2-knowledge-tool-ai-final.log/xml`。新13项PG由专项实跑，原引用正文xfail保留。对应RAG报告24项全部执行通过、gate_passed=true。
- `python scripts/test-agent-runtime.py --with-java-delivery`的PG部分241项全部通过＝原228+新13，`b2-knowledge-tool-runtime-first.log`。新13含新Worker恢复后文档下线：首次5Steps/2Tools/1Model，恢复总7Steps/4Tools/1Model/1Retry，来源2/向量HTTP1/知识HTTP2，第二结果为空；来源撤权、MODEL=0/TOOL=1/刷新TOOL=3先于下一外部请求阻断；空范围4Steps/2Tools/0Model且无知识请求；模型中取消不存迟到向量；503在原预算1/2分别阻第二模型或成功，按真实ready_at等待；改Profile在来源/模型HTTP前拒绝、仅轮换Key复用向量；有效Hash但坏向量Checkpoint拒绝；知识正文提交丢确认后恢复仍重新查询当前范围而不返回旧片段。该13项的业务HTTP是确定性Stub，真实Java/pgvector由组合夹具单独验证。

剩余/回滚：工具尚未接模型决策Loop、自动来源登记/默认业务路由，不是完整知识Agent闭环；用户级文档ACL、完整知识版本、在途撤权后的最终输出核验/事实支持、通用PII及Trace导出治理仍未完成。历史payload仍受RLS/TTL管理，不能据此宣称已删除所有旧片段。回滚不注册工具，保留来源/发布过滤、预算/正文/迁移；不删除成功Embedding步骤重请求或放宽缓存。无语义准确率、性能、付费Token成本或生产收益成果。

最终`python scripts/test-agent-runtime.py --with-java-delivery`退出0，`b2-knowledge-tool-runtime-first.log`：241项专项与原全部实际Java/SDK/9例工单Loop/真实进程接管/5例发布/核销/许可/回复组合保持。新增7例实际Worker→Registry/两SDK→真实Embedding协议TCP→customer JAR/受限PG全部通过，`.local/verification/runtime-knowledge-tools.json`保存逐案原始计数。正常/下线后恢复各总7Steps/4Tools/1Model/1Retry，2次知识SDK、仅1次Embedding HTTP；下线命中1→0。接管后来源拒绝且无第二知识/模型；MODEL预算0在Embedding前阻断，刷新TOOL预算3在第二知识前阻断；空范围4Steps/2Tools/0Model/0知识请求；503自然重试总8Steps/4Tools/2Models/1Retry、实际Embedding HTTP2。每案仅原访客消息1、无业务回复/回执/新工单。模型选择工具未接入，来源DELIVERED为显式夹具；向量0.25/usage8是合成协议，不能当语义/成本/生产流量数据。

本批无Java/SQL/默认流程变更，本地不重复刚通过85c0b89精确CI的三套默认服务/112项客服PG/六库迁移；当前精确提交CI仍全部执行。只添加现有实际客服JAR中的知识工具用例，无并行Maven/完整默认服务验收。

1dc48d6对应的[CI 44862361047](https://github.com/jkwang1129/SupportFlow/actions/runs/44862361047)两个job全部成功，含771项AI普通、241项Runtime/7例真实知识工具和全部数据库/三套服务；知识工具子批次本地/远端退出条件完成。

## B2 幂等建单前置：不可变业务回执存储（2026-11-21）

基线1dc48d6。新增Customer009 `agent_ticket_receipt`，manifest追加009，唯一40-runtime-roles.sql保证重复provision后仍只SELECT/INSERT。ticket添加tenant/id复合唯一引用，原全局ID主键及已有行不变；回执绑定同租户来源event/run/snapshot与ticket，tenant/run、tenant/event、tenant/ticket唯一。固定ticket.create/ticket-create-v1、参数SHA、Step/fence及工单引用，FORCE RLS+restrictive guard，空scope/PLATFORM拒绝。created_at是DB插入记录时间，不是精确事务提交时刻。FK/Hash不代替当前主体/会话/客户/TTL/参数正文/AI写许可。

新增`scripts/test-ticket-receipts.py`、`scripts/tests/test_ticket_receipts_postgres.py`与CI独立检查；更新新建/旧库迁移和完整服务的版本/权限断言、客服独立PG夹具、迁移说明及ADR-0022。没有Java/AI业务实现或API/模型WRITE工具变化；数据库建表不等于端到端Agent建单。后续必须复用既有TicketService，把业务行/事件与回执同事务提交，再接受控API/SDK/未知结果恢复。

本地Python3.11.5/隔离PG17/JDK25，无本机业务数据库/.env/真实供应商：

- `python scripts/test-ticket-receipts.py`最终38项全部通过，`b2-ticket-receipts-final.log`、`.local/verification/ticket-receipts.xml/json`。使用实际supportflow_customer（非owner/非superuser/非BYPASSRLS）；跨租户/缺省或空scope/PLATFORM读写拒绝，KB/平台不可读、AI不可连接customer库；同租户来源/Run/快照/工单复合FK，10种坏契约，重复Run/工单不覆盖SHA，UPDATE/DELETE/TRUNCATE拒绝；8路重复元数据插入只有1条回执；原始SQL业务行+回执同事务回滚/提交；即使加宽permissive策略仍被restrictive guard拒绝；6类管理员属性、角色继承/对象所有权共8种不安全能力组被迁移拒绝。再次完整provision后13条已提交回执精确不变，权限仍SI、无UD/TRUNCATE。该并发样本不是吞吐压测，SQL事务测试不代替TicketService整链路。
- 首轮`b2-ticket-receipts-first.log`27失败/11通过，原因是大ID夹具基数再加序号超出bigint；修正为仍超过2^53且处于int64范围的合成ID后38项通过。保留失败，不将夹具错误描述为产品漏洞。
- `python scripts/test-versioned-migrations.py`17项通过，`b2-ticket-receipts-upgrade.log`；原006→007/007→008、AI002→003→004保护保留，新增008→009前后已有工单全部字段及原来源/消息/知识精确不变，回执表初始为空；重复/失败回滚/checksum/历史/锁/只读与CLI继续通过。
- 从隔离初始化/迁移生成`.local/implementation/customer009-contract.json`候选后逐项审查：仅新表、ticket复合唯一约束/索引和009版本/checksum，其他全部数据库、旧表列/策略/约束/索引、视图/函数及旧SQL Hash不变。`python scripts/test-schema-drift.py`95表/1视图/5函数、14项实际SQL+3项metadata负面全部通过，`b2-ticket-receipts-drift.log`。
- `python scripts/test-database-init.py`空库六库95表、重复拒绝、旧数据/种子保留、权限/锁及失败库回滚通过，`b2-ticket-receipts-init.log`。
- `python scripts/test-security-flow.py`最终退出0，`b2-ticket-receipts-services.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套实际Java/Python服务全部通过，含原知识/访客WS→brain→BOT回复/权限/工单/计费、六库历史Customer009与新回执SI权限、readiness故障恢复。完整服务单独运行，未并行Maven/其他服务验收。
- `python scripts/test-customer-rls.py --with-ai`112项客服Java PG和19项Python KB全部通过，`b2-ticket-receipts-customer-pg.log`。独立夹具也应用009；新FK/索引不破坏原工单读、来源、消息幂等/认领/回复与知识查询测试。

回滚停用后续新建单调用，保留已提交工单/回执与009历史，不删除表/改旧checksum或恢复无回执盲重试。当前只有存储底座，未验证实际Agent建单、HITL、工单未知写核销或自动调度；新/旧库应停业务备份后按migrate→provision→verify升级，生产恢复实测仍M12。无生产规模、模型质量、性能或业务收益数据。本批未改AI/Runtime，本地不重复刚全绿1dc48d6的771项AI/241项Runtime；精确提交CI全部执行并新增38项存储检查。

45151c6对应的[CI 44876389932](https://github.com/jkwang1129/SupportFlow/actions/runs/44876389932)两个job全部成功，含新增38项回执存储、771项AI/241项Runtime、迁移/95表结构及三套完整服务；回执存储子批次本地/远端退出条件完成。

## B2 建单前置：操作专属的短期写许可（2026-11-21）

基线45151c6。复用AI RuntimeStore的现有精确RESERVED TOOL WRITE查询、Run/Step共享锁、锁后DB时间及5秒内短期许可；内部共用核验过程，公开窄方法authorize_reply/authorize_ticket_create。只接受session.reply/reply-v1、ticket.create/ticket-create-v1，禁止交叉配对/未知操作/版本/额外Scope。action-v1字段/签名顺序不变。新增SUPPORTFLOW_AI_RUNTIME_TICKET_ACTIONS_ENABLED默认false，独立于Runtime入口；仅显式隔离联调服务启用，不注册业务建单API/模型工具。

Java AgentActionPermit.Request增加封闭Operation，原七参数构造器仍默认SESSION_REPLY；两个窄客户端方法在HTTP前校验类型，序列化固定操作/版本，响应MAC也匹配该操作。AgentResultReplies写入口额外检查SESSION_REPLY，防止来源/参数Hash相同且MAC有效的TICKET_CREATE被用于消息。业务事务内仍禁授权网络调用，DB时钟有效期检查不放宽。许可只是执行资格，不代替当前业务资源/来源/正文/幂等回执核验，取消有短暂在途窗口，不能回滚已发生的副作用。

本地Python3.11.5/JDK25/隔离PG17，全部引用/凭据/数据合成，不读.env、不调用供应商：

- `cd ai-service; python -m pytest -q tests/test_runtime_ticket_authority.py tests/test_runtime_authority.py`首轮36项通过＝新13+原23，`b2-ticket-authority-unit-first.log`。开关关闭不查store、显式开启仅调窄方法；操作/版本/Scope伪造先于store拒绝，其他caller拒绝、稳定错误、独立操作签名公式保持。
- Java定向`AgentActionOperationTest,AgentActionPermitTest,AgentRuntimeClientTest`18项通过＝新3+原8+7，`b2-ticket-authority-java-unit.log`。两种有效MAC许可只各自通过自己的请求验证；互换且其他引用/Hash相同仍拒绝；窄客户端类型错在HTTP前阻断，实际Stub HTTP请求包含固定8个授权引用字段。旧Python签名字面向量93e4...仍原断言通过，没有放宽签名/时效/响应边界。
- AI普通全量784 passed、262 skipped、1 xfailed，`b2-ticket-authority-ai-final.log/xml`；新16项PG由专项实跑，原引用正文xfail保留；对应RAG报告24项全部执行通过、gate_passed=true。
- `python scripts/test-customer-rls.py --with-ai`113项Java PG+19项KB Python全部通过，`b2-ticket-authority-customer-pg.log`。新增1项实际受限PG：有效MAC的TICKET_CREATE许可与reply所有引用/参数SHA相同也被40903拒绝，消息/seq/回执/计费/afterCommit事件均不增加。原112项持续通过。
- `mvn -B -f backend/pom.xml verify`全模块BUILD SUCCESS，`b2-ticket-authority-backend-final.log`。条件PG skip不计通过，实际PG结果如上。

回滚关闭建单授权开关，保留原reply、009存储、Run/Step/预算/历史，不恢复操作不区分的判断；不宣称已完成Agent建单业务/HITL/通用未知写核销。无新的模型质量/性能/费用或生产成果。

`python scripts/test-agent-runtime.py --with-java-delivery`最终退出0，`b2-ticket-authority-runtime-first.log`：257项专项通过＝原241+新16（精确Ticket WRITE/操作版本/主体/参数、取消/过期/非RESERVED拒绝、签名API与预算不变）；旧工单/7例知识工具/真实进程接管/5例发布/核销/回复全部组合保持。Java实际通过受保护AI/受限PG分别领取reply与ticket许可，ticket的同一来源/Hash不能领取reply许可，取消后拒绝新许可；两案终态保留1Step/1Tool/0Model/0Retry，UNKNOWN保持、无业务写。显式合成接收器开启ticket授权，仅为协议资格；默认配置仍关闭，业务API尚不存在。

`python scripts/test-security-flow.py`最终退出0，`b2-ticket-authority-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整实际服务均通过，原知识/访客WS→brain→BOT回复/工单/计费/附件权限、六库Customer009和readiness故障恢复保持。完整服务单独运行，没有并行Maven/服务验收。无SQL改动，不重复刚通过45151c6精确CI的38项存储/迁移/漂移专项；当前精确提交CI继续全跑。

610ad79对应的[CI 44878260746](https://github.com/jkwang1129/SupportFlow/actions/runs/44878260746)两个job全部成功；操作专属许可子批次本地/远端退出条件完成。

## B2 原子Agent建单业务子批次（2026-11-21）

基线610ad79。新增AgentTicketArguments/AgentTicketCreations、参数单元/真实业务PG测试及ADR-0024；TicketService旧渠道创建体抽为共同内部方法，旧creator/editor仍channel，Agent窄方法使用agent-run:{runId}。实际业务继续负责编号、SLA、事件、客户统计/动态及原可选标签。未另写SQL创建工单，未伪造LoginUser，未注册API/SDK/模型WRITE工具。

可信tenant/event/run/snapshot与原文SHA绑定固定ticket-create-v1策略；客户/会话来自当前来源，模型不可覆盖。当前受限事务先锁tenant/run，再核现有回执、来源、客户UPDATE锁和TICKET_CREATE许可DB时间；实际TicketService创建后核工单全文/Run标记/客户会话/固定类别优先级/无指派，再插不可变回执；最后重验来源TTL/Hash与许可，失败整笔回滚。只读回执只确认过去的创建事实，接管/终态后可核对，不返回正文/当前状态也不授新执行资格。授权HTTP不在本业务边界内；下一批API须在事务外调用。

本地Python3.11.5/JDK25/隔离PG17，仅合成数据/许可、实际受限customer角色、真实TicketService/MyBatis/CustomerDynamics；可选网络依赖Stub，无.env/付费模型：

- 定向Java单元AgentTicketArgumentsTest,AgentActionOperationTest,AgentReplyArgumentsTest共11项通过，其中新4项，`b2-ticket-business-unit-first.log`。验证精确摘要、Unicode/UTF-8边界、空/PLATFORM/缺引用及缺租户/事务先于DB/业务调用拒绝。
- `python scripts/test-customer-rls.py --with-ai`首轮旧113项通过、新13项setup错误，`b2-ticket-business-pg-first.log`；夹具误用不存在的SLA status列，按真实is_enabled/is_deleted修正，未放宽业务代码。第二轮126项Java PG+19项KB通过，`b2-ticket-business-pg-second.log`。再补3项实际结果篡改/错误快照/最大Unicode原文用例，最终129项Java PG（新16）+19项KB全部通过，`b2-ticket-business-pg-final.log`。
- 新16项含实际SLA7/21分钟、1工单/1事件/1回执/客户工单数3→4及1客户动态；8路同Run只执行一次；匿名来源无虚构客户；旧渠道creator=channel；改参数/错误快照/有效MAC但SESSION_REPLY操作拒绝；当前来源接管/关闭/内容修改、删除/跨租户客户拒绝。回执INSERT权限失败、业务后结果篡改、业务后快照过期、业务后真实许可过期均无残留工单/事件/回执/客户统计增量；晚过期用例故意等约4秒，不是性能数据。回执在接管后仍仅确认过去事实，跨租户/错event不可见。
- `mvn -B -f backend/pom.xml verify`全模块BUILD SUCCESS，`b2-ticket-business-backend-final.log`。默认跳过的PG不计通过，实际结果如上。
- `python scripts/test-security-flow.py`退出0，`b2-ticket-business-services-final.log`：FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT三套完整实际服务全部通过；原知识检索/访客WS→brain→BOT回复/工单/计费/附件权限及六库历史、readiness故障恢复保持。验收单独运行，未并行Maven或其他服务验收。

边界/回滚：尚无Agent建单API/SDK/模型工具、HITL、自动触发或工单未知写核销；可选标签沿用旧best-effort，不承诺所有标签刷新必成。客户UPDATE锁避免本路径先SHARE再升级，但不声称与所有旧业务混用无死锁；锁等待2秒有界。8路样本仅验证幂等，不代表吞吐、P95或生产规模。回滚停用新窄方法入口，保留原渠道共用业务、Customer009及已提交工单/回执/预算/历史；不删表或恢复盲重试。无语义准确率/付费Token成本/生产客户或收益成果。本批无AI/SQL改动，本地不重复刚通过610ad79精确CI的784项AI/257项Runtime/38项存储迁移专项，远端精确提交CI继续全跑。

2e5cd53对应的[CI 44880321165](https://github.com/jkwang1129/SupportFlow/actions/runs/44880321165)两个job全部成功；原子建单业务子批次本地/远端退出条件完成。

## B2 受控建单API子批次（2026-11-21）

基线2e5cd53。新增RuntimeTicketCreateController/RuntimeTicketCreateCommand及权限/实际PG测试，扩展现有RuntimeSourceAccessFilter与application.yml，ADR-0025记录编码前决策。独立ticket-create路由避免占用tickets/{ticketNo}中的合法编号。POST独立建单开关默认false，GET复用read-tools且不需要建单开关/AI授权客户端；签名先于开关/正文/readiness，字段闭合且摘要绑定，固定操作/策略，不允许客户/租户/优先级/指派等输入。

先5秒短事务查匹配回执，未命中才在无业务连接/行锁阶段请求TICKET_CREATE许可，再进独立5秒原子建单事务；重复前检竞争由业务内advisory锁/回执复查处理。授权HTTP仅8个引用/摘要字段，不发送标题/正文/JWT。GET只返回9个固定引用字段，ticket_id显式十进制字符串；查询事实不授新执行权，404不证明在途POST未提交。数据库/上游错误不回传SQL或原文。尚无Python建单SDK、模型WRITE工具/审批/自动调度或建单UNKNOWN核销。

本地JDK25/Python3.11.5/隔离PG17，仅合成数据/凭据/授权服务，不读.env/不调用付费模型：

- 定向RuntimeTicketCreateAccessFilterTest及原source/reply/ticket/knowledge权限测试34项全部通过，新7项，`b2-ticket-api-unit-first.log`。最大标题/正文、固定操作/摘要、禁写先于正文、读写独立、越权/坏字段/Hash/版本/类型/重复/超限/跨路径签名全部拒绝；create/result/ticket-create合法工单编号GET保持，POST不能借旧路由建单。
- `python scripts/test-customer-rls.py --with-ai`首轮139项中130通过/9失败，`b2-ticket-api-pg-first.log`。授权HTTP测试夹具把真实version字段误写为schema_version导致返回前NPE；按实际协议修正，未放松客户端/业务校验。最终139项Java PG（新10）+19项Python KB全部通过，`b2-ticket-api-pg-final.log`。
- 新10项实际TicketService/受限PG/真实授权HTTP含RPC时连接数0与会话FOR UPDATE NOWAIT可取得；只建一次/终态确认无需再授权/改参数冲突、禁写且无AI客户端时GET仍可读、跨租户/错event/匿名不可见、授权401/404/409/503固定映射不泄露原文、有效MAC但reply操作拒绝、RPC期间接管拒绝、回执INSERT失败整笔回滚及4路已签名并发仅一业务结果。并发是幂等样本，不是性能基准。
- `mvn -B -f backend/pom.xml verify`全模块BUILD SUCCESS，`b2-ticket-api-backend-final.log`；条件PG跳过不计通过，实际PG如上。

实际新建单联调使用显式宿主手工签名/独立摘要公式，不依赖下一批SDK。`ticket_creation_api_fixture.py`复用已启用来源/只读工具的真实客服JAR，只在该隔离进程显式开启建单；来源专用JAR保持默认禁写/禁读回执，并追加实际签名POST/GET均503、匿名401检查。新3例全部通过，机器报告`.local/verification/runtime-ticket-creation-api.json`：正常创建及终态再次POST只确认1张工单/1事件/1回执、客户数3→4；丢弃已接收的POST确认、取消后GET仍确认同一工单，但Run/Step保留FAILED/SIDE_EFFECT_UNKNOWN，不伪造核销；来源接管时0工单/0事件/0回执、客户计数仍3。每例1Step/1Tool/0Model/0Retry，原访客消息仍1，无新增会话回复。票据ID实际超过2^53且返回字符串，中文/emoji与换行/尾空格原文保持。HTTP确认丢弃是显式宿主故障注入，不冒称已实现网络重投策略或SDK恢复。

`python scripts/test-agent-runtime.py --with-java-delivery`最终退出0，`b2-ticket-api-runtime-first.log`：257项Runtime专项全部通过，旧来源/工单/知识SDK、9例Loop/真实进程恢复、7例知识工具、5例发布/核销、操作许可和2例实际回复联调全部保持，新3例建单如上。本批未改SQL/旧Workflow，且2e5cd53精确CI刚完成三套完整默认服务；本地不重复那三套，实际新/默认客服JAR和已有全部Runtime组合已重新验证，当前精确提交CI仍全跑三套完整服务。无并行Maven或完整服务验收。

回滚关闭SUPPORTFLOW_BOT_RUNTIME_TICKET_CREATE_ENABLED，保留只读回执和已提交工单/Customer009/Run历史与预算，不删除回执或放宽来源权限。AI端建单许可仍有独立默认false开关。没有大规模功能重构、语义质量/Token费用/性能/生产收益成果。无SQL改动，本地不重复刚全绿2e5cd53的迁移/38项存储专项，精确提交CI继续全跑。

6799b6c对应的[CI 44881610275](https://github.com/jkwang1129/SupportFlow/actions/runs/44881610275)两个job全部成功；受控建单API子批次本地/远端退出条件完成。

## B2 建单签名SDK与严格回执验证（2026-11-21）

基线6799b6c。新增`ai/runtime/ticket_creation.py`、`tests/test_runtime_ticket_creation.py`和ADR-0026；共用CustomerTransport只加固定ticket-create POST/空正文GET及固定TICKET_CREATE前缀，只有该操作的请求上限增加至65536字节，返回8192字节。旧source/ticket/reply/knowledge方法/限制/凭据不变。Java仅参数测试补共享摘要字面向量与Java空白语义；无Java生产代码/SQL/默认路由/依赖改动。

TicketCreationClient.create在I/O前核可信RunInput/Lease/tenant/owner/fence/UUID和标题正文，按ticket-create-v1原文及固定策略生成SHA，闭合7字段签名POST；不接受客户/租户/优先级/指派等自由参数。receipt仅GET，允许成对提供预期快照/摘要；冻结TicketCreationReceipt只含tenant/Run/来源/快照/SHA/工单引用，持久化前receipt_reference再次核类型/绑定/int64/编号，输出引用而非正文。外层ApiResponse字段/元数据、UTF-8/重复/NaN/尾随JSON、内层精确9字段/操作/版本/来源/参数/规范UUID及十进制int64都校验；timestamp兼容Java规范long字符串。只有40406+null是查询缺失，泛404/来源拒绝不能伪装未建单。

请求总期限/有限正文/不使用环境代理/重定向/隐式重试沿用原transport。任何POST错误不证明无业务副作用，SDK不重发POST、不自动清UNKNOWN/退还预算、不读当前工单状态。SDK本身不是Scope/Runtime预算工具，尚未注册模型WRITE/审批或自动业务路由。

本地Python3.11.5/JDK25/隔离PG17，均为合成文本/凭据/引用，无.env/供应商调用：

- `cd ai-service; python -m pytest -q tests/test_runtime_ticket_creation.py tests/test_runtime_source.py tests/test_runtime_tickets.py tests/test_runtime_result.py tests/test_runtime_knowledge.py`首轮298过/11失败，`b2-ticket-sdk-unit-first.log`；新MockTransport夹具用已消费content而非原始ByteStream，导致StreamConsumed。修正夹具，不放松SDK/transport，309项全部通过，`b2-ticket-sdk-unit-final.log/xml`（新90、原219）。
- 追加最大原文跨8192字节传输、allowlist四类负例及int64两端7项，最终316项全部通过＝新97+原219，`b2-ticket-sdk-unit-final-v2.log/xml`。新97包含3例真实TCP正常/302拒绝/慢正文1秒总期限（服务故意等2秒），禁环境代理；每例只有1POST，异常不复制URL/私有原因。另覆盖字段/来源/快照/参数替换、类型/Hash/超限/坏编码/非法Unicode、固定业务错误、只读查询与伪造类型回执拒绝。该慢响应是故障注入，不是性能数据。
- Java `mvn -B -f backend/pom.xml -pl supportflow-customer-service -am test -Dtest=AgentTicketArgumentsTest -Dsurefire.failIfNoSpecifiedTests=false`4项通过，`b2-ticket-sdk-java-golden.log`；共享字面摘要97638edb...047dc86、CRLF/尾空格保持及Java/Python空白语义一致，未改变业务参数规则。
- AI全量首轮874 passed/262 skipped/1 xfailed（新90），`b2-ticket-sdk-ai-final.log/xml`；追加7项后881 passed/262 skipped/1 xfailed，`b2-ticket-sdk-ai-final-v2.log/xml`。PG条件skip不计通过，原引用正文xfail保留。`python scripts/report-rag-contract.py --junit .local/implementation/b2-ticket-sdk-ai-final-v2.xml --output .local/verification/rag-contract.json`：24项全部执行通过、gate_passed=true。

回滚停用新SDK/后续工具注册，保留Java来源/写许可/原子业务、Customer009、已提交工单/回执与Run预算/历史，不删除成功Step重发写。无模型WRITE Loop/HITL、工单UNKNOWN终态核销或生产/语义质量/Token费用/性能成果；候选下一批ADR-0027仅为设计，不算已完成能力。

`python scripts/test-agent-runtime.py --with-java-delivery`最终退出0，`b2-ticket-sdk-runtime-final.log`：257项Runtime全部通过，原全部实际Java/来源/工单/知识/9例Loop与真实进程恢复/7例知识工具/5例发布/核销/操作许可/回复组合保持。更新后的3例建单组合通过，机器报告`runtime-ticket-creation-api.json` v2：首个未提交请求由产品TicketCreationClient真实POST→Java→受保护AI许可/受限PG，独立手工摘要/回执GET核对；共享摘要、全局long字符串元数据、冻结回执及receipt_reference写入实际AI Step/Run结果。取消后SDK只GET确认过去工单，Run保持FAILED/SIDE_EFFECT_UNKNOWN；来源接管时SDK拒绝且0业务写。原工单/事件/回执各1、客户3→4与原消息1保持，所有case仅预留1Tool/1Step/0Model/0Retry；独立协议负例是显式宿主请求，SDK本身尚非预算执行器。报告sdk=VERIFIED_EXPLICIT_WRITE_AND_RECEIPT_CLIENT，model_write_tool=NOT_IMPLEMENTED；没有把丢弃确认描述为完整恢复/核销。

本批无Java生产/SQL/默认业务变更，不在本地重跑刚全绿6799b6c的139客服PG/19KB、六库迁移及三套完整服务；新Java参数向量、AI全量/共享transport单元、当前JAR全部组合已实跑。当前精确提交CI仍全跑这些专项与三套服务。无并行Maven或完整服务验收。

8f65bf2对应的[CI 44882505302](https://github.com/jkwang1129/SupportFlow/actions/runs/44882505302)两个job全部成功；建单SDK子批次本地/远端退出条件完成。

## B2 固定建单WRITE工具与共享执行预算（2026-11-21）

基线8f65bf2。新增`ai/runtime/ticket_write_tool.py`、工具单元/PG测试、真实TCP故障组合及ADR-0027；在既有ToolRegistry/ToolExecutor增加WriteTool分支，ReadTool原执行路径保持。闭合参数只title/content，原8KiB ToolCall整体上限、业务文本边界、Scope/版本/当前身份及有界回调复用。默认不注册模型WRITE Loop/新路由，不加服务/库/依赖/表或通用网络代理。

每Run每操作先固定工具/原文参数Hash/服务地址与策略Hash（不含可轮换密钥），换模型call key不能改变正文/目标；再计当前来源READ核权，构造ticket.create精确业务摘要，只有预留TOOL WRITE后才能调用SDK。Java再次核当前来源/主体/许可/原文并同事务创建业务/回执。Step只保存核验后的业务引用，不以payload缓存冒充写成功；任意同Run RESERVED/UNKNOWN写拒绝新的副作用。成功Step恢复只独立计READ查匹配回执，不重查当前来源或重发POST；更换正文/服务策略拒绝，旧成功Step保留。瞬时POST错误只允许一次独立预算GET；无回执/查询错/预算不足仍UNKNOWN并阻断下一来源，不回退预算、不自动核销终态。

本地Python3.11.5/JDK25/隔离PG17，全为显式宿主合成ToolCall/数据/凭据；无.env/供应商调用或模型WRITE决策：

- 工具单元首轮103过/2失败，`b2-ticket-write-unit-first.log`：NUL/超限ToolCall在构造时已拒绝，测试错误期待进入Executor；改成构造后篡改提案，检验Executor再次拒绝，未放松限制。最终`python -m pytest -q tests/test_runtime_write_tools.py tests/test_runtime_tools.py`105项全部通过＝新增42+原63，`b2-ticket-write-unit-final-v2.log/xml`。覆盖Scope/版本/字段/参数、伪授权/意图/结果、私有正文排除、回调参数副本、精确预留顺序、单次POST/查询、已有成功只确认、其他call key不可改原文及未知写不重放。
- AI普通全量923 passed、281 skipped、1 xfailed，`b2-ticket-write-ai-final.log/xml`；新增19项PG由独立脚本实跑，原引用正文xfail保留。对应RAG契约24项全部执行通过、gate_passed=true。
- `python scripts/test-agent-runtime.py --with-java-delivery`首轮276项中275过/1失败，`b2-ticket-write-runtime-first.log/xml`：新来源撤权Stub误用HTTP404，原source协议为HTTP200业务code40402，实际SDK正确报SOURCE_PROTOCOL_ERROR。只修夹具后，`PYTEST_ADDOPTS='-k ticket_write_tool' python scripts/test-agent-runtime.py --with-java-delivery`的新增19项全部通过、原257项deselected（其首轮全通过），`b2-ticket-write-runtime-targeted-final.log`。两轮累计覆盖276个不同用例，不声称本地曾单轮276全绿；精确提交CI不带筛选，重新全跑。
- 新19项PG使用实际受限AI角色/Worker/SDK/Step许可，customer HTTP为确定性Stub。正常3Steps/2Tools/0Model；同Run另一个call key只GET确认总4Steps/3Tools；丢确认只1POST+1GET仍成功。无回执/查询故障/授权拒绝/坏回执均保留UNKNOWN、无自动重试且后续同会话来源PENDING；TOOL预算0/1先于来源/POST停止，预算2丢确认不能越限查询；来源撤权/无Scope无WRITE，取消后迟到结果不能成功。成功Step已提交但ACK丢失后新Worker只GET确认，总4Steps/3Tools/1Retry，来源撤权不抹去过去事实；更换服务地址/原文或篡改业务引用失败，轮换宿主Key不改变策略绑定。来源503在原预算自然ready_at重试，只有1POST，原正文不入Step或payload。Key轮换用例是配置绑定测试，不冒充真实服务凭据轮换演练。

边界/回滚：显式宿主Scope允许的仅是本项目固定未指派内部工单，不代表退款/其他高风险动作免审批。模型WRITE选择Loop、可信触发/Profile Resolver、HITL、工单UNKNOWN终态核销和生产限额/运维仍未完成。回滚不注册新工具或回退执行器写分支，保留来源/Java许可/原子业务、已提交工单/回执、Run成功/未知Step与原预算。无语义质量/生产规模/性能/付费Token或收益成果。

定向Runtime命令最终退出0，旧所有实际组合/3例建单SDK/5例报告发布/核销/操作许可/回复保持。新增`ticket_write_tool_fixture.py`复用实际JAR并记录TCP代理观察，`.local/verification/runtime-ticket-write-tool.json`10案全部通过：正常3Steps/2Tools，1来源HTTP/1POST/0GET；真实上游事务提交但代理扣留正文直到SDK总期限，Runtime单独计READ查询确认，总4Steps/3Tools，1POST/1GET；代理未转发POST时404回执保持UNKNOWN；预算2在GET前阻断且业务已提交不冒称失败无副作用；预算0/无Scope/坏参数在HTTP前拒绝；来源接管只有1次来源核权、0POST；成功WRITE Step提交丢ACK，新Worker在人工接管/关闭后仅GET确认，总4Steps/3Tools/1Retry，无重新来源/POST；取消发生在真实业务提交后不能写迟到成功，UNKNOWN保持。所有已提交案恰1工单/1事件/1回执、客户计数3→4、原消息仍1；未提交案0工单/事件/回执、客户计数3，均0模型调用/无正文payload。三种UNKNOWN案再次入队同会话来源仍PENDING。代理故意等5秒/SDK1秒是故障注入，不是P95。模型ToolCall为显式合成宿主选择，非模型自主建单质量评测。

本批无Java生产/SQL/默认Workflow变更；刚全绿8f65bf2已包含139项客服PG/19KB/六库迁移/三套完整服务，本地不重复，保留当前原全部实际Runtime组合与新10例；精确提交CI继续全跑上述专项及276项Runtime（不带PYTEST_ADDOPTS筛选）。无并行Maven或完整服务验收。

b703d4f 的 [CI 44883937143](https://github.com/jkwang1129/SupportFlow/actions/runs/44883937143) 前后端任务全部通过，包含 276 项 Runtime 与 10 例真实建单工具联调。

## B3 工单核销审计账本（2026-11-21）

基于 b703d4f 新增 AI005、初始化后的结构契约、账号权限及只读 Readiness。`agent_ticket_reconciliation` 将确认事实绑定同租户 Run/Step，保存业务回执引用、原参数摘要、观察令牌、确认者和原步骤结果。同租户每个 Run 与工单仅一条记录，运行账号只可查和追加。原 AI004 的回复表和记录保留。此次交付的范围是存储和部署检查，核销入口随下一批实现。

- 隔离 PostgreSQL 17、Python 3.11.5。设置 `PYTEST_ADDOPTS='-k ticket_reconciliation_storage'` 后运行 `python scripts/test-agent-runtime.py`，25 项全部通过，原 276 项未在此次定向命令运行。覆盖外租户、空租户、PLATFORM、宽策略叠加、错误操作与版本、同租户外键、重复与八路并发插入、权限和事务回滚。重复执行角色配置后保留 1 条审计行，AI 账号只具备 SELECT/INSERT，Customer 与 KB 账号无 SELECT。原始日志 `b3-ticket-ledger-storage-first.log`。
- `python scripts/test-versioned-migrations.py` 通过 17 项迁移测试。AI 004 → 005 前后已有租约、Run/Step、预算、正文、原回复核销记录逐字段一致。`b3-ticket-ledger-upgrade.log`。
- `python scripts/test-schema-drift.py` 通过 96 表、1 视图和 5 个函数的核对。14 项 SQL 漂移及 3 项元数据漂移被拒绝。审查候选快照后确认唯一新增项为 AI005 迁移和新账本。`b3-ticket-ledger-drift.log`。
- `python scripts/test-database-init.py` 通过六库 CLI 初始化、重复初始化拒绝、数据保留和故障回滚。`b3-ticket-ledger-init.log`。
- `python scripts/test-security-flow.py` 退出 0。FRESH DEFAULT、LEGACY UPGRADE、FRESH CLI INIT 三套完整业务流程通过，均核实 AI005 历史和账本权限。`b3-ticket-ledger-services.log`。

业务提交证明仍来自客服服务签名回执。数据库约束只能保证本地审计结构，不能证明跨库工单已提交。回滚时停用核销调用并保留账本、业务回执及版本历史。测试使用隔离合成数据，未测量性能或生产数据。设计见 [ADR-0028](decisions/0028-ticket-reconciliation-ledger.md)。

## B3 工单未知写终态核销（2026-11-21）

基于 8ffc3cd。`TicketReconciler` 复用回复核销的观察和短事务确认流程。入口接收可信宿主提供的租户和任务 ID。候选必须是只有一个 UNKNOWN 写步骤的失败任务，操作、版本、租户、来源、快照、参数摘要和步骤令牌须匹配。回执 GET 在事务外调用，确认时锁定 Run/Step 并重验。确认后追加 AI005 审计，更新原步骤与任务错误码，保留 FAILED、取消标记、完成时间及原预算。同一事实重复确认直接返回已保存审计。查无回执或核对失败时，同会话后续来源保持等待。

- 设置 `PYTEST_ADDOPTS='-k reconciliation'` 后运行 `python scripts/test-agent-runtime.py --with-java-delivery`。隔离 PostgreSQL 17 的数据库部分 65 项通过，包含 19 项新的工单核销、21 项回复核销和 25 项账本用例，255 项未纳入本次定向运行。验证原预算和时间逐字段保持、六路并发只留一条记录、回执读取期间令牌变化、错误回执和跨租户拒绝、事务最后一步故障回滚、回复与工单候选类型隔离及确认应答丢失后的重复查询。原始日志 `b3-ticket-confirm-runtime-first.log`。
- AI 普通测试 923 项通过、325 项条件跳过、1 项原有预期失败。24 项 RAG 契约通过。日志 `b3-ticket-confirm-ai.log` 和对应 XML。
- `python scripts/test-versioned-migrations.py` 17 项通过。已确认的原回复及其审计记录可跨 AI004 → AI005 保持逐字段相等。`b3-ticket-confirm-upgrade.log`。
- `runtime-ticket-write-tool.json` v2 的 10 例实际 Worker → Java → AI → 受限 PostgreSQL 组合通过。写后取消与工具预算耗尽各确认一张已有工单，每案只增加一次 GET，原账本和客户侧工单、事件与业务回执各 1 条，后续同会话任务恢复领取。缺失回执一案继续阻断，未追加审计。三例均保留原预算和任务失败历史，所有场景使用合成数据。

回滚时停用可信宿主工单核销调用，保留 AI005、工单回执、原步骤和审计历史。确认能力目前限单写任务，后续接限时审批、恢复后执行业务及操作界面。设计见 [ADR-0029](decisions/0029-terminal-ticket-reconciliation.md)。

8ffc3cd 的 [CI 44889426859](https://github.com/jkwang1129/SupportFlow/actions/runs/44889426859) 前后端任务均通过。

## B3 限时审批存储（2026-11-21）

基于 3316818 新增 AI006 和 `agent_approval`。WAITING_APPROVAL 任务必须释放持有者和租约。审批记录绑定任务、主体、来源、工具版本、用途和参数摘要，受限角色仅可修改决定、关闭和消费字段。测试涵盖等待态的会话顺序、独立会话继续领取、跨租户和错误外键、缺失决定、批准时钟、原条款不可改、事务回滚以及只读 Readiness 对权限漂移的拒绝。

- 设置 `PYTEST_ADDOPTS='-k approval_storage'` 后执行 `python scripts/test-agent-runtime.py`。真实 PostgreSQL 17 中 18 项审批存储通过，320 项未纳入本次定向运行。重复配置账号后 1 条完整审批记录逐字段一致。原始日志 `b3-approval-storage-first.log`。
- `python scripts/test-versioned-migrations.py` 通过 17 项迁移测试。第一次执行时版本已加入清单，审查后的结构文件尚未替换，CLI verify 按预期拒绝不一致文件。替换后重跑通过，AI005 → AI006 前后旧任务、步骤、正文、回复核销和工单核销记录一致。原始日志 `b3-approval-upgrade.log` 和 `b3-approval-upgrade-final.log`。
- `python scripts/test-schema-drift.py` 通过 97 表、1 视图、5 受控函数和 17 项漂移检查。对比候选快照仅增加审批表、等待态的两个任务约束变化和 AI006 版本。`b3-approval-drift.log`。
- `python scripts/test-database-init.py` 验证六库 CLI 初始化、重复拒绝和故障回滚。`python scripts/test-security-flow.py` 通过默认新库、历史库升级和 CLI 初始化三套完整服务，核实 AI006 历史和审批列权限。`b3-approval-init.log`、`b3-approval-services.log`。

本批交付数据库契约和部署检查。暂停、决定、恢复与一次性消费分开验证。回滚时保留 AI006 和审计数据，待应用启用后需先处理仍在审批中的任务。设计与适用边界见 [ADR-0030](decisions/0030-durable-approval-boundary.md)。

3316818 的 [CI 44889946045](https://github.com/jkwang1129/SupportFlow/actions/runs/44889946045) 前后端任务均通过，覆盖工单终态核销、旧回复核销和全部服务验收。

## B3 审批暂停、决定与检查点恢复（2026-11-21）

基于 cdda89c。`TicketApprovals.pause` 复用原子步骤正文，持久化审批请求、检查点引用及等待态，并在同一事务释放租约和递增令牌。可信控制面可重复读取原决定、通过、拒绝、撤销和按任务显式处理过期。取消与审批状态更新同事务。新 Worker 从已有快照和参数摘要恢复精确标题与正文，保留预算、截止时间和检查点。对带审批记录的工单，写许可要求当前步骤已经消费该审批。

- 设置 `PYTEST_ADDOPTS='-k "approval_lifecycle or ticket_authority"'` 运行 `python scripts/test-agent-runtime.py --with-java-delivery`。真实 PostgreSQL 17 的定向部分 32 项通过，包含 16 项新审批生命周期和 16 项原建单许可用例，322 项未纳入定向执行。覆盖等待后重启、暂停事务崩溃后复用检查点、并发批准与取消竞争、拒绝、过期、撤销、跨租户、原文和参照替换、未消费审批拒绝。首轮筛选表达式缺内部引号，pytest 在收集前退出。修正命令后通过，保留 `b3-approval-lifecycle-first.log` 与 `b3-approval-lifecycle-targeted.log`。
- AI 普通测试 923 项通过、359 项条件跳过、1 项既有预期失败。RAG 契约 24 项通过。`b3-approval-lifecycle-ai.log` 及对应 XML。
- 真实 Java／AI／受限 PostgreSQL 验证通过。源服务实际创建签名快照，Worker 持久化等待和通过决定，新 Worker 取得原快照及原文。真实写许可拒绝已有写步骤但尚未消费的审批。工单、回执均为 0。`runtime-ticket-approval.json` 记录等待状态、新令牌和预算保留情况，续跑原建单与回复组合见同一 Runtime 日志。

审批 ID 由可信宿主处理，当前过期入口按已知任务显式调用。后续加入一次性消费、真实工单执行、自动过期发现和 reviewer 入口。回滚时先处理仍在等待的请求，再停用宿主暂停入口，保留检查点和审批记录。设计见 [ADR-0030](decisions/0030-durable-approval-boundary.md)。

cdda89c 的 [CI 44890433299](https://github.com/jkwang1129/SupportFlow/actions/runs/44890433299) 前后端任务均通过。

## B3 一次性消费审批并执行工单（2026-11-21）

基于 2d64038。批准后的宿主用相同工具从原检查点执行，先重验当前来源。Run 行锁内匹配操作、版本、主体、快照、参数摘要及数据库期限，将预算、写步骤与审批消费同事务提交。许可签发独立核对消费步骤和有效期。已成功写步骤的恢复仍走原有回执核对逻辑。

- 定向 `PYTEST_ADDOPTS='-k "approval or ticket_authority"' python scripts/test-agent-runtime.py --with-java-delivery` 的隔离真实 PostgreSQL 部分 58 项通过。新增 8 项覆盖发放许可、绑定篡改、撤销或过期、权限失败回滚、六路预留只消费一次。追加两例后 `PYTEST_ADDOPTS='-k approval_consumption' python scripts/test-agent-runtime.py` 10 项通过，涵盖已消费成功步骤恢复、超长提案在生成审批前拒绝。原始日志 `b3-approval-consumption-first.log`、`b3-approval-consumption-targeted-final.log`。
- 真实 Java／AI／PostgreSQL 三案通过。正常审批创建 1 张工单、1 条工单事件和 1 条回执，4 Steps、2 Tools、0 Model、0 Retry，原中文、emoji、换行和尾空格均保持。批准后会话接管只进行来源核验，工单、事件和回执均为 0。审批过期后同样为 0。机器报告 `runtime-ticket-approval.json` v2。
- AI 普通测试 923 项通过、367 项条件跳过、1 项既有预期失败，为追加两例前的全量结果。24 项 RAG 契约全部通过。首次从仓库根直接调用 pytest 导致 AI 模块导入失败，切换到 `ai-service` 工作目录通过。保留 `b3-approval-consumption-ai.log`、`b3-approval-consumption-ai-final.log` 和 XML。

批准后的会话接管会使任务失败，已记录的审批决定仍可审计。审批消费失败不会增加写次数。此阶段的决定入口面向可信宿主。自动过期调度与坐席身份接入另行验收。回滚停用审批执行入口并保留原检查点、审计、预算和业务回执。见 [ADR-0031](decisions/0031-approval-consumption-and-expiry.md)。

2d64038 的 [CI 44890932330](https://github.com/jkwang1129/SupportFlow/actions/runs/44890932330) 前后端任务均通过。

## B3 自动发现和关闭到期审批（2026-11-21）

基于 83bb132。新增 AI007 的到期索引和现有租户发现函数分支。Worker 处理当前租户的有限批次，每次默认 16 条。按 Run 再审批记录加锁，跳过已锁任务，复查数据库时钟、审批状态和未知副作用。闭合过期审批与终止等待任务同事务。保留审核决定、检查点和预算，同会话下一条任务可继续。跨租户记录及已消费写入保持原有处理规则。

- 在隔离 PostgreSQL 17 设置 `PYTEST_ADDOPTS='-k "approval or runtime_worker"'`，执行 `python scripts/test-agent-runtime.py`。77 项通过，其中新增 4 项验证租户到期发现、后续会话工作、获批过期不进处理器、锁跳过、跨租户和未知写保持。首轮角色负面夹具误用最新版本 SQL，导致重复创建到期索引。将夹具明确绑定含能力守卫的 AI006 后通过。日志 `b3-approval-expiry-pg.log`、`b3-approval-expiry-pg-final.log`。
- `python scripts/test-versioned-migrations.py` 17 项通过。005 的历史写步骤按当时契约植入，避免用需 AI006 的现版本写入方法模拟旧行为。006 → 007 保留已有审批、任务、检查点和预算，既有回复和工单核销记录逐字段一致。`b3-approval-expiry-upgrade.log`。
- `python scripts/test-schema-drift.py` 通过 97 表、1 视图、5 受控函数和 17 项漂移检查。候选对比差异仅为 AI007、到期索引和租户发现函数。`b3-approval-expiry-drift.log`。
- `python scripts/test-security-flow.py` 退出 0。默认新库、旧库升级和 CLI 初始化三套完整服务通过，核实 AI007 及审批权限。`b3-approval-expiry-services.log`。

83bb132 的 [CI 44891384398](https://github.com/jkwang1129/SupportFlow/actions/runs/44891384398) 通过 Runtime 联调，迁移测试因旧库夹具调用当前建单预留方法而失败，错误为 AI005 中找不到审批表。当前批修正了夹具，17 项本地迁移测试和旧库完整服务通过。该失败运行记录继续保留。

回滚可停用 Worker 到期调度，保留 AI007、历史审批和回执，由可信宿主显式处理仍到期的任务。真实业务评测使用隔离合成样本。坐席审查入口按 ADR-0032 另行交付。

## B3 签名内部审批审查协议（2026-11-21）

基于 a0e8043。AI 新增独立默认关闭的 `review-ticket-approval`。仅接受已按原方法、路径、正文与租户签名验证的客服服务。租户来自认证上下文，审查人引用限制为 staff 加正 int64 ID，审批 ID、来源事件、已审摘要和操作均用闭合协议。持有 Run/审批锁后再次核对签名请求与原提案。返回审批 ID 和决定状态。撤销或正向决定进入已过期窗口时，以审计后的过期状态返回冲突。

- 定向 `python -m pytest -q tests/test_runtime_approval_review.py tests/test_runtime_api.py tests/test_runtime_ticket_authority.py` 66 项通过，含新 15 项。`b3-approval-review-unit-first.log`。
- `PYTEST_ADDOPTS='-k runtime_approval_review' python scripts/test-agent-runtime.py --with-java-delivery` 的真实 PostgreSQL 部分 5 项全部通过。跨租户、错事件、错审批、错摘要均拒绝，原记录逐字段一致。重复批准保留原坐席，撤销与到期记录决定和关闭者。`b3-approval-review-runtime.log`。
- AI 普通测试 938 项通过、378 项条件跳过、1 项既有预期失败。24 项 RAG 契约通过。`b3-approval-review-ai.log` 和 XML。
- 实际 AI 服务、Java 客服 JAR 与受限数据库通过 3 条工单审批路径。签名携带错误摘要均返回 409。真实接受后原提案建单一次，来源接管及调度到期均未创建工单。模型调用为 0，均为隔离合成数据。

当前协议的坐席引用由签名客服服务提供，客服服务的已认证用户入口、当前角色及会话重验另行接入。回滚可关闭 `SUPPORTFLOW_AI_RUNTIME_APPROVAL_REVIEW_ENABLED` 并保留审计和任务。见 [ADR-0032](decisions/0032-ticket-approval-review-boundary.md)。

## B3 客服坐席审批决定入口（2026-11-21）

客服服务复用登录过滤器和当前企业角色查询，拒绝缺失、不可读取和普通坐席角色。批准前核对当前来源及快照，拒绝和撤销可在人工接管后收回同租户已交付任务的权限。AI 调用在短事务关闭后执行，只转发固定审批引用和可信坐席 ID。投递端接收已进入 `WAITING_APPROVAL` 的重复确认。详情和回滚见 [ADR-0032](decisions/0032-ticket-approval-review-boundary.md)。

- `python scripts/test-customer-rls.py --with-ai` 在隔离 PostgreSQL 17 下通过 145 项客服和 19 项知识库测试。新增 6 项覆盖跨租户、接管、角色变更、伪造字段、上游失败及 AI 调用期间未持有 JDBC 连接，日志 `b3-java-review-pg.log`。
- JDK 25 运行 `mvn -B -f backend/pom.xml verify` 通过。新增 4 项 TCP 单元测试，包括精确签名、大整数坐席 ID、拒绝变更的决定响应以及重复投递确认，日志 `b3-java-review-backend.log`。
- 初次完整 Runtime 联调的既有 `lost_ack` 工单用例已提交且尝试一次查询，但未能确认回执，Run 保留 `SIDE_EFFECT_UNKNOWN`。夹具原先用一秒期限共享模拟丢确认和回执查询。改为确认上游提交后断开测试代理连接，保留正常三秒查询期限。10 项写工具路径复测通过，包含两例只读核销和一例无回执保持阻断。日志 `b3-java-review-runtime.log` 和 `b3-java-review-and-preview-runtime.log`。
- 以 `PYTEST_ADDOPTS='-k runtime_approval_review'` 执行 `python scripts/test-agent-runtime.py --with-java-delivery`。实际客服 JAR、AI 服务与 PostgreSQL 的 3 条坐席审批用例通过。匿名、普通坐席、错摘要分别返回 401、403、409，消费前重放仍为原审批。批准后建单 1 张、工单事件和回执各 1 条。批准后来源接管或过期各 0 张工单。身份令牌和用户角色响应由隔离夹具生成，调用进入真实登录过滤器和角色客户端；角色夹具首轮缺少标准时间戳而被拒绝，补齐响应后通过。日志 `b3-java-review-api-final.log`、机器报告 `runtime-ticket-approval.json` v3。

## B3 签名提案预览（2026-11-21）

AI 内部预览承接客服签名的 Run 和来源，沿用审批开关。预览在受限租户事务下用共享锁读取任务，复用审批恢复的原检查点和参数摘要核验。只返回等待或尚未消费的有效提案，响应禁止缓存。读操作不改变任务、审批、步骤或预算。回滚关闭审批开关或回退预览路由，原检查点和决定继续保留。客服侧提案显示及发现另行接入。

- `python -m pytest -q tests/test_runtime_approval_review.py tests/test_runtime_api.py tests/test_runtime_ticket_authority.py` 73 项通过，新增 7 项签名服务、默认关闭和参数范围用例。全 AI 测试 945 项通过、384 项条件跳过、1 项原有预期失败。RAG 契约 24 项通过。日志和 XML 位于 `.local/implementation/b3-approval-preview-*`。
- `PYTEST_ADDOPTS='-k approval' python scripts/test-agent-runtime.py` 所选 59 项隔离 PostgreSQL 测试通过，其中 6 项新增覆盖原文和有效期、跨租户与来源、撤销、拒绝、过期、正文摘要异常。首轮预览调用缺少 Run 来源转换方法，两项用例报错，补齐共享方法后 59 项通过。原始日志为 `b3-java-review-runtime-final.log` 和 `b3-java-review-and-preview-runtime.log`。后者的 Runtime 联调部分另含客服角色夹具修正前的失败，最终真实接口结果记录在上一批。

## B3 客服坐席读取提案原文（2026-11-21）

客服公开 API 在同一企业登录态和当前管理员或主管角色下提供 `GET /api/customer/agent-approvals/events/{eventId}/runs/{runId}/proposal`。签名前确认当前来源和快照。AI 响应限定六个字段和有界长度，Java 用原工单参数规则独立核对当前快照与全文摘要。RPC 前后均重验当前会话，返回内容禁止缓存。审批决定按返回的 ID 和摘要提交，仍重验当前状态。

- `python scripts/test-customer-rls.py --with-ai` 通过 148 项真实客服 PostgreSQL 17 用例及 19 项知识库用例。新增 3 项验证已授权原文、普通坐席或跨租户不可读、RPC 期间接管后不返回内容。日志 `b3-customer-preview-pg.log`。
- JDK 25 的 `mvn -B -f backend/pom.xml verify` 通过。新增 TCP 用例校验精确签名、中文与原换行、正文替换、错摘要和过期响应的拒绝，日志 `b3-customer-preview-build.log`。
- `PYTEST_ADDOPTS='-k runtime_approval_review' python scripts/test-agent-runtime.py --with-java-delivery` 的实际客服 JAR、AI 与 PostgreSQL 三案均通过。已授权坐席读取原文后以真实返回的审批 ID 和摘要提交决定。普通坐席与匿名均拒绝，接管或过期后预览不可用。一次准许建单、一条工单事件、一条回执，另两案均无业务写入。角色和数据为隔离合成夹具，日志 `b3-customer-preview-runtime.log`、原始报告 `runtime-ticket-approval.json` v4。

预览仍需已有的来源事件和任务引用。待审批列表、坐席界面和可信业务触发待接。回滚关闭客服审批审核开关，保留原提案、决定及回执。该批不含数据库迁移，也无需付费模型。

## B1 可单独启动的只读工单 Worker（2026-11-21）

基于 1a94acf。新入口按运营侧显式提供的租户和供应商创建 Profile，沿用真实签名客户工具、租户和预算校验及现有只读 Loop。启动读取 Runtime 结构和角色元数据。租户限定的 Worker 跳过全局发现，开关关闭时退出。配置、退出边界和回滚见 [ADR-0033](decisions/0033-opt-in-ticket-worker.md)。

- `python -m pytest -q tests/test_ticket_worker.py tests/test_ticket_loop.py tests/test_runtime_models.py` 82 项通过，其中新增 8 项覆盖租户、无凭据、错误供应商、默认关闭、缺少运行角色和其他租户不领取。AI 全量 953 项通过、384 项条件跳过、1 项既有预期失败。RAG 契约 24 项通过。日志、XML 与契约结果在 `.local/implementation/b1-ticket-worker-*`。
- `PYTEST_ADDOPTS='-k "runtime_worker or ticket_loop"' python scripts/test-agent-runtime.py --with-java-delivery` 的真实 PostgreSQL 定向测试 44 项通过。原九项实际只读路径与新增真实进程路径共十项通过。真实独立 Worker 使用合成模型 TCP 和受限数据库、客服 JAR，消费指定租户且另一租户的待处理任务逐字段一致。开关关闭先退出 1 并保留任务，开启后成功产生 1 份报告，2 次模型请求、5 次工具预留、10 个步骤、0 次重试、0 条新增回复或回执。原始日志 `b1-ticket-worker-runtime.log`、`ticket-worker-process.log` 和报告 `runtime-ticket-loop.json`。

前序坐席决定及内部预览分别通过 [CI 44893909323](https://github.com/jkwang1129/SupportFlow/actions/runs/44893909323) 和 [CI 44893967231](https://github.com/jkwang1129/SupportFlow/actions/runs/44893967231)。本阶段运营入口消费已有可信任务。客服侧自动调度、访客消息登记和生产流量仍待验证。

## B1 已登记事件的单租户投递调度（2026-11-21）

客服服务新增默认关闭的 `SUPPORTFLOW_BOT_RUNTIME_DISPATCH_ENABLED`，开启时要求 RLS、明确的 `SUPPORTFLOW_BOT_RUNTIME_DISPATCH_TENANT` 和现有客服签名凭据。独立单线程每轮最多领取一项任务，每轮结束后等待一秒。复用原 Dispatcher 的领取、签名、回执、退避及租约恢复。关闭时停止新轮次并取消本地 HTTP，未确认的事件保留租约供后续恢复。回滚关闭调度开关，保留业务消息及 Outbox。

- JDK 25 的 `mvn -B -f backend/pom.xml verify` 通过。新增 2 项 Spring 配置测试，覆盖默认无调度实例、显式关闭、平台租户、关闭 RLS、缺失凭据。首版测试引入仓库未使用的测试框架导致编译失败，改为项目已有的 Spring 基础容器及 JUnit 后通过。初次错误记录在 `b1-ticket-worker-runtime.log` 末尾，修正验证见 `b1-dispatch-schedule-build.log`。
- `PYTEST_ADDOPTS='-k runtime_api' python scripts/test-agent-runtime.py --with-java-delivery` 中的两项真实 Java/AI/受限 PostgreSQL 联调通过。正式调度组件投递指定租户事件并保持另一租户事件不变。对已持久化但丢失响应的一项任务，重发相同事件只获得原 Run。日志 `b1-dispatch-schedule-runtime.log`。

当前调度消费既有事件，访客入口自动登记另行验收。逐租户显式配置，尚无跨租户或跨进程公平配额。

## B1 真实访客入口到只读报告（2026-11-21）

客服使用现有消息事务、服务认证与会话权限登记有界访客文本。选定租户中符合条件的消息通过保存点写入同事务 Outbox。原客服回复逻辑继续运行，影子任务只生成内部报告。访客登记默认关闭，开启时要求同一租户的受控投递。设计、降级与回滚见 [ADR-0034](decisions/0034-shadow-visitor-admission.md)。

- `python scripts/test-customer-rls.py --with-ai` 通过 153 项客服 PostgreSQL 17 测试及 19 项知识库测试。新增五项覆盖相同客户端消息重放、事务整体回滚、内容与接管边界、锁超时后消息提交和跨租户／无事务拒绝。首轮一个负例的夹具以无返回值的 Lambda 调用有返回值接口，修复后通过。日志 `b1-shadow-admission-pg.log`、`b1-shadow-admission-pg-final.log`。
- JDK 25 的 `mvn -B -f backend/pom.xml verify` 通过，包括未配置投递时拒绝启用登记的 Spring 用例。日志 `b1-shadow-admission-backend.log`。
- `PYTEST_ADDOPTS='-k runtime_api' python scripts/test-agent-runtime.py --with-java-delivery` 的 20 项真实 Runtime API 数据库测试通过。专用联调租户修复了按 pytest 筛选时遗留任务被后续来源 Worker 误领的问题。原始失败见 `b1-dispatch-schedule-runtime-retry.log`，修复和验收见 `b1-shadow-admission-runtime.log`。
- 实际消息 API 拒绝匿名，重发相同消息号保留 1 条消息和 1 条 Outbox，通过真实后台投递创建 1 个对应 Run。独立 Worker 对其生成 1 份工单报告，使用 2 次合成模型 HTTP 调用、5 次工具预留、10 个步骤、0 次重试。10 条实际工单读取用例均通过。原始机器报告 `runtime-ticket-loop.json` v2。

单租户 Worker 的 [CI 44894872366](https://github.com/jkwang1129/SupportFlow/actions/runs/44894872366) 和客服提案预览的 [CI 44894414086](https://github.com/jkwang1129/SupportFlow/actions/runs/44894414086) 均通过。测试全程使用隔离合成数据，验证范围为显式启用的只读任务。生产流量、费用和语义质量未测。

## B3 按会话发现有效审批引用（2026-11-21）

AI 新增签名客服查询。一条请求只接受一个会话编号。受限数据库按当前租户、会话和存活状态返回最多一条审批引用，仅含来源事件、Run、状态和有效期。并发状态变化由后续提案读取及决定接口再校验，过期、不可用或不一致的引用拒绝返回。回滚可关闭审批开关，保留待审任务和历史记录。

- 定向 API 单元与相邻鉴权测试 79 项通过，其中 6 项新增覆盖错误服务身份、默认停用、错误会话格式、额外批量字段。全 AI 测试 959 项通过、386 项条件跳过、1 项原有预期失败。RAG 契约 24 项通过。日志与 XML 在 `.local/implementation/b3-approval-active-*`。
- `PYTEST_ADDOPTS='-k runtime_approval_review' python scripts/test-agent-runtime.py` 在隔离 PostgreSQL 17 下 13 项通过。新增两项覆盖同租户和同会话的待审引用、批准后状态、撤销后消失、其他租户／会话不可见，以及 Worker 尚未执行前已经过期的记录。原日志 `b3-approval-active-pg.log`。

客服受权会话入口和坐席 UI 分批接入。动作继续按原提案摘要及当前状态核验。

## B3 客服会话的审批发现入口（2026-11-22）

客服通过 `GET /api/customer/agent-approvals/sessions/{sessionNo}/active` 查找当前会话的待审或已批准待执行任务。用既有审查权限检查当前坐席角色、租户及会话资格，再请求签名 AI 接口。返回前核对响应的来源事件属于同一会话并重验原快照。响应只保留四个引用字段，未找到有效提案时返回空数据。回滚关闭客服审查开关，保留审批和任务记录。

- `python scripts/test-customer-rls.py --with-ai` 在隔离 PostgreSQL 17 通过 156 项客服测试和 19 项知识库测试。新三项覆盖引用投影、普通坐席和外租户拒绝、接管前置拦截及拒绝与当前会话不符的上游引用。原日志 `b3-customer-active-pg.log`。
- JDK 25 的 `mvn -B -f backend/pom.xml verify` 通过。审批客户端 6 项测试全部通过，新增 TCP 用例验证精确请求签名与附带正文的上游响应拒绝。原日志 `b3-customer-active-build.log`。
- `PYTEST_ADDOPTS='-k runtime_approval_review' python scripts/test-agent-runtime.py --with-java-delivery` 真实数据库定向 13 项和完整服务组合通过。使用合成角色验证三条由会话查找、读取原提案和提交决定的路径。批准执行产生一张工单及一条回执。批准后接管及过期两案均无工单写入。原日志 `b3-customer-active-runtime.log` 和报告 `runtime-ticket-approval.json` v5。

AI 发现与访客只读登记分别通过 [CI 44896041748](https://github.com/jkwang1129/SupportFlow/actions/runs/44896041748) 和 [CI 44895871470](https://github.com/jkwang1129/SupportFlow/actions/runs/44895871470)。坐席工作台分批接入。

## B3 坐席工作台审批（2026-11-22）

工作台仅在 `VITE_RUNTIME_APPROVAL_REVIEW_ENABLED=true` 且当前登录人为企业管理员或主管、会话尚未接管时显示工单审批。点击时查询当前会话和原提案。标题和正文按纯文本展示，决定携带原审批 ID 与摘要。提交期间禁用重复操作。切换会话清空原内容，遇到冲突清除旧按钮并提供刷新。后端审批权限继续独立生效。显示已批准表示收到审查决定，工单创建由执行结果单独确认。

- `cd frontend; npm run build` 通过 TypeScript 与生产构建。构建仍提示应用入口包含超过 500 kB 的分块。原日志 `b3-approval-workspace-build.log`。
- 从仓库根启动 `node frontend/scripts/approval-workspace-fixture.mjs`，另一个终端执行 `./scripts/test-approval-workspace.ps1`。实际 Chromium 加载工作台，局部替身供应会话及审批 HTTP 流程。5 组浏览器断言通过，验证读取保留换行与标签文本、批准和撤销、空提案、过期冲突移除旧动作、拒绝，以及普通坐席不显示入口。替身记录提交携带的审批 ID、参数摘要和固定决定字段均匹配。后端真实数据库与真实服务证据见上一批。

前端和客服、AI 审批开关均需启用。关闭前端开关即可撤下交互入口，数据库和既有决定继续保留。

## B3 隔离只读 Worker 与审批任务（2026-11-22）

只读工单 Worker 领取时排除已有审批记录的 Run，并在锁住任务后再次核对。这保护批准待执行及等待接管的任务，防止只读处理器覆盖原提案检查点。同会话后续任务仍按原顺序等待，其他会话可继续处理。审批过期和可信执行端的既有行为保留。回滚前先停只读 Worker，保留任务、审批和预算。

- `PYTEST_ADDOPTS='-k "approval_expiry or runtime_worker or runtime_store"' python scripts/test-agent-runtime.py` 的 53 项真实 PostgreSQL 测试通过。新增两项覆盖批准后的同会话排序与异会话领取，以及已过期执行租约的接管资格。日志 `b3-approval-worker-exclusion-pg.log`。
- `python scripts/test-versioned-migrations.py` 17 项通过。通用领取模式可继续在历史结构上运行。日志 `b3-approval-worker-exclusion-migrations.log`。
- 普通 AI 测试 959 项通过、388 项条件跳过、1 项既有预期失败。日志、XML 与 RAG 契约证据在 `.local/implementation/b3-approval-worker-exclusion-*`。
