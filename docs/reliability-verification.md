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
