# 可靠性改造验收记录

基线 `dca4b8e614adbce7ade8ba72323b2a4e6da4470e`；2026-11-19，Windows、Temurin JDK 25.0.5、Maven 3.9.16、隔离 PostgreSQL 17 + pgvector。测试使用合成数据/模型 Stub，无线上客户或收费模型调用。后续能力盘点与路线另行记录。未提交变更的报告以本地工作区为准。

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
