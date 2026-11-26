# SupportFlow 生产运维手册与服务等级目标（Runbook & SLI/SLO）

本文定义 **SupportFlow — 多租户 SaaS 智能客服 Agent 平台** 的核心服务等级指标（SLI/SLO）、就绪门禁排查顺序、常见故障应急响应、未知副作用核销及灾备恢复规程。

---

## 1. 服务等级指标与目标（SLI / SLO）

> **边界说明**：以下指标区分**受控基准验收目标**与**生产运行观测口径**；用户主动要求转接人工或业务规则要求人工核验（`handoff` / `WAITING_APPROVAL`）属于预期业务分流，不计入系统错误。

| 维度 | SLI 定义（分子 / 分母） | 目标值（SLO / 门禁阈值） | 观测与验证入口 |
| --- | --- | --- | --- |
| **核心接口可用性** | 非 `5xx` 有效响应数 / 已认证合法业务请求总数（排除计划维护） | `>= 99.9%`（滚动窗口） | 网关/各服务 Actuator 与 `http.request` Span |
| **普通知识问答延迟** | 从问答入口接收请求至返回已复核回复的耗时分位数 | `P95 <= 5,000 ms` | `rag_hard_benchmark.py` / `rag.ask` Span |
| **Runtime 队列等待** | 任务入队（或到期可领）至首次 Worker 领取的等待时长 | 待领积压 `< 20` 条/租户，最老等待 `< 60 s` | `python -m ai.runtime.metrics`（超限退出码 `3`） |
| **故障注入恢复率** | 注入崩溃/超时/丢确认/重投故障后收敛至预期终态且账目一致的样本占比 | 基准门禁 `>= 96%`（目标 `>= 49/50`） | `fault_recovery_suite.py` / `test-agent-runtime.py` |
| **重复业务副作用** | 重试、崩溃接管、网络重发或取消竞争下产生的多余工单/回复/扣费数 | **严格为 `0`**（硬安全门禁） | `agent_result_receipt` / `ticket_create_receipt` |
| **越权与注入拦截率** | 跨租户访问、参数伪造、直接/间接 Prompt Injection 被确定性阻断的比例 | **`100%`**（`50/50`，硬安全门禁） | `security_redteam_suite.py` / `agent_boundary_report.py` |
| **关键节点 Trace 覆盖率** | 包含完整关键执行阶段 Span 的任务数 / 实际执行任务数 | **`100%`** | `ai.core.telemetry.verify_key_node_coverage` |

---

## 2. 探针矩阵与故障排查第一顺位

收到 `HTTP 503` 或响应超时告警时，**严禁重启或删除数据库卷**，按以下探针顺序在 2 分钟内定位故障层：

| 检查项 | 探针地址 | 正常状态 | 异常（`503`）含义与根因范围 |
| --- | --- | --- | --- |
| 1. Java 进程存活 | `http://127.0.0.1:9091..9095/actuator/health/liveness` | `200` | 进程假死或端口未监听，检查 `.local/logs/*.log` 与 JVM 堆栈 |
| 2. Java 数据库结构就绪 | `http://127.0.0.1:9091..9095/actuator/health/readiness` | `200` | 对应业务库连接不可达、迁移未完成缺表/缺列、或受限账号缺 `SELECT` 权限 |
| 3. 客服对象存储就绪 | `http://127.0.0.1:9093/actuator/attachmentreadiness` | `200` | RustFS/S3 桶不可达或凭据失效（此时纯文本客服仍保持可用，仅图片返回 `50308`） |
| 4. Python AI 存活 | `http://127.0.0.1:9100/api/ai/health/live` | `200` | FastAPI Worker 异常退出，检查 `ai-service` 进程日志 |
| 5. Python AI 结构就绪 | `http://127.0.0.1:9100/api/ai/health/ready` | `200` | `ai_db` 或 `customer_db` 知识表结构缺失、或 `supportflow_ai`/`supportflow_kb` 权限异常 |

---

## 3. 标准应急处置规程（SOP）

### 3.1 模型供应商超时 / 429 限流 / 熔断器打开（`CircuitBreakerOpenError`）
- **现象**：模型调用延迟飙升、供应商返回 `429`/`5xx`，`ModelGateway` 触发 `OPEN` 状态熔断。
- **自动保护**：
  1. 熔断器在冷却窗口内快速失败，防止雪崩放大请求与账单；
  2. 常规客服大脑自动退化至本地规则识别与关键词检索兜底，无法确定答复时平稳引导转人工；
  3. Runtime 任务对瞬态模型错误进入 `RETRY_WAIT`（受 `max_retries` 与总 `deadline_at` 限制），绝不重置已消耗预算。
- **人工处置**：
  1. 核查供应商控制台配额与连通性；切勿为“绕过报错”将甲租户绑定的供应商静默切换至未经数据处理授权的第三方供应商。
  2. 若租户自身月度/日度预算耗尽（`ModelTokenQuotaExceeded` / `ModelCostRejected` / Ops 预算闸门），与租户管理员确认后再调整配额快照，严禁手工篡改 `agent_model_usage` 历史回执。

### 3.2 Agent Runtime 队列积压、租约过期与 `SIDE_EFFECT_UNKNOWN` 核销
- **诊断命令**（使用只读轨迹角色）：
  ```powershell
  Push-Location ai-service
  python -m ai.runtime.metrics --tenant <TENANT_CODE> --window-minutes 60 --alert-due-count 20 --alert-due-seconds 60
  python -m ai.runtime.trace_timeline --tenant <TENANT_CODE> --request-id <REQUEST_ID>
  Pop-Location
  ```
- **处置分支**：
  - **`expired_leases > 0`**：确认对应租户 Worker 进程是否存活；启动或扩容 Worker 后，系统会自动通过递增 `fence` 安全接管过期租约（已完成只读/模型检查点自动复用）。
  - **`waiting_approval` 积压**：通知企业主管/管理员在工作台「工单审批」面板处理；超期未批任务将由 Worker 自动收敛为 `APPROVAL_EXPIRED`（零写入）。
  - **任务处于 `FAILED` 且错误码为 `SIDE_EFFECT_UNKNOWN`**：
    - **严禁**手工向业务库补插工单或删除阻塞状态强行重跑！
    - 执行受控终态核销流程：通过只读回执接口核对 `customer_db` 中的 `agent_result_receipt` 或 `ticket_create_receipt`。若回执存在，登记不可变核销审计记录以解除同会话后续任务阻塞；若确认未落库，交由人工坐席在工作台核实后处理。

### 3.3 知识库重索引异常、暂存清理与一键回滚
- **重索引响应丢失**：系统已在 `kb_chunk_stage` 隔离暂存切片，未完成 Java 文档事务切换的切片不会污染线上检索，直接重新触发索引即可（自动分配新 `attempt_id`）。
- **版本误发布回滚**：若新发布的知识正文或切片存在业务错误，企业知识管理员可查询 `GET /api/customer/kb/documents/{id}/index/previous` 获取上一版 `versionId`，调用 `POST .../index/rollback` 在文档写锁内免重算向量一键恢复上一版生效切片。
- **闲置租户暂存切片例行清理**：
  ```powershell
  Push-Location ai-service
  python -m ai.rag.stage_retention --tenant <TENANT_CODE> --limit 500
  python -m ai.rag.stage_retention --tenant <TENANT_CODE> --limit 500 --apply
  Pop-Location
  ```

### 3.4 数据库结构漂移、备份与灾难恢复
- **结构核验（只读无副作用）**：
  ```powershell
  python scripts/db-migrate.py verify
  ```
  若报告表、索引或 RLS 策略漂移，严禁覆盖 `current-schema.json` 掩盖问题，须停止受影响写入后通过权威迁移修复。
- **备份与全量恢复步骤**：
  1. 停止应用写入流量，使用管理员账号对六个数据库分别执行 `pg_dump -Fc`，并归档 RustFS 对象桶及 SHA-256 清单；
  2. 在隔离目标集群初始化六库与角色权限，按序恢复数据库与对象存储，运行 `python scripts/db-migrate.py verify`；
  3. 启动客服与 AI 服务，确认 `/actuator/health/readiness`、`/actuator/attachmentreadiness` 与 `/api/ai/health/ready` 全部返回 `200` 后再切换网关流量（本地演练脚本：`python scripts/verify/test-database-restore.py --with-attachments --with-services`）。
