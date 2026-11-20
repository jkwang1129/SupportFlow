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
