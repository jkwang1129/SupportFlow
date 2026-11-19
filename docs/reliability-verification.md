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
