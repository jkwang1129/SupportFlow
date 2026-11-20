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
