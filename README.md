# SupportFlow - Multi-tenant Customer Service Agent Platform

SupportFlow 是一个面向企业智能客服场景构建的多租户 AI Agent 工程平台。系统在保留确定性多渠道客服接待、工单 SLA 与计费运营闭环的基础上，围绕生产级 Agent 工程能力构建了集**多轮对话、受控工具调用、人工审批（HITL）、知识检索、全链路追踪与成本治理**于一体的可追踪、可恢复服务闭环。

---

## 核心工程定位（六大支柱）

| 工程维度 | 核心能力与架构设计 | 关键源码位置 |
| --- | --- | --- |
| **Agent Runtime** | `Workflow + Agent Loop` 混合路由（`IntentRouter`）与多工具动态规划（`CustomerAgentLoop`）、持久化 `Run / Step / Payload` 状态机、事务 `Outbox`、数据库租约与递增 `fence`、跨进程崩溃接管、自然语言建单提案与限时 HITL 审批及基于不可变回执的未知副作用核销。 | [`ai-service/ai/runtime/`](ai-service/ai/runtime/)、`Customer` 侧 Outbox、受控工具端点与回执服务 |
| **Context Engineering** | 系统规则与不可信 JSON 帧隔离（内置提示注入检测）、长多轮 `SlidingWindowCompressor` 摘要压缩与关键业务槽位保留、`ToolResultPruner` 白名单裁剪、`TokenBudgetManager` 确定性裁剪与当前轮槽位覆盖。 | [`ai-service/ai/core/context_engine.py`](ai-service/ai/core/context_engine.py)、[`ai-service/ai/services/brain.py`](ai-service/ai/services/brain.py) |
| **AI Security** | 六库逻辑隔离 + **8 个非超级用户受限数据库角色** + PostgreSQL `FORCE RLS`（fail-closed）；服务间 HMAC 签名认证、客户资料脱敏与历史会话只读隔离、工具闭合 Schema + `Scope` + 实时资源归属复核、单次参数绑定审批消费（50 项红队越权/注入拦截率 **100%**）。 | [`backend/deploy/db-init/40-runtime-roles.sql`](backend/deploy/db-init/40-runtime-roles.sql)、[`ai-service/ai/core/internal_auth.py`](ai-service/ai/core/internal_auth.py) |
| **Agentic RAG & 知识治理** | `pgvector` HNSW + 线上可选 `Hybrid Search + RRF + Reranking`（Qwen / 本地混合重排）、查询改写、服务端可信来源权威分级（`official / reference / community`）、低置信度拒答、逐句原文支撑校验、数值冲突拦截与索引暂存原子切换/一键回滚。 | [`ai-service/ai/rag/`](ai-service/ai/rag/)、`customer_db` `013` 迁移、[`KbService.java`](backend/supportflow-customer-service/src/main/java/io/supportflow/customer/service/bot/KbService.java) |
| **Evaluation** | 六大类 **200 条 Golden Dataset** + 40 条人工校准 `LLM-as-a-Judge`、50 项越权与注入红队套件、50 项故障恢复矩阵、36 题高区分度 RAG 2.0 基准与 16 组多轮成本基准，离线门禁全量接入 GitHub Actions CI。 | [`scripts/benchmarks/`](scripts/benchmarks/)、[`fixtures/evaluation/`](fixtures/evaluation/)、[`docs/evaluation-summary.md`](docs/evaluation-summary.md) |
| **Observability & LLMOps** | `OpenTelemetry + W3C Trace Context + Langfuse` 全链路 `Run/Step/Span` 追踪（10 个关键执行节点覆盖率 **100%**）、无副作用规划回放；`Model Gateway` 多供应商路由、滑动窗口 `CircuitBreaker` 熔断与降级、租户并发及日/月 Token 与金额预留、运维 `Runbook`。 | [`ai-service/ai/core/telemetry.py`](ai-service/ai/core/telemetry.py)、[`ai-service/ai/core/circuit_breaker.py`](ai-service/ai/core/circuit_breaker.py)、[`docs/runbook.md`](docs/runbook.md) |

---

## 系统架构总览

```mermaid
flowchart TD
    Visitor[访客 Widget / 渠道回调] -->|HTTP / WebSocket| GW[API Gateway 9090 / Realtime 9096]
    Staff[企业坐席 / 平台管理端] -->|HTTP / WebSocket| GW
    GW --> Biz[Java 微服务集群<br/>Customer · User · Tenant · Billing · Ops]
    Biz -->|签名 HTTP / 事务 Outbox| AI[Python AI & Agent Runtime 9100]
    AI --> Router[确定性客服大脑 Workflow / 有界 Agent Loop]
    Router --> RAG[Agentic RAG 2.0<br/>HNSW · 改写 · 原文核查 · 拒答]
    Router --> Tools[受控 Tool Executor / MCP 接入层]
    Tools -->|二次鉴权 & 幂等回执| Biz
    Tools -->|高风险写操作暂停| HITL[限时人工审批与工作台续传]
    AI --> LLM[Model Gateway<br/>租户路由 · 有界传输 · Token/金额预留]
    Biz --> PG[(PostgreSQL 17 + pgvector<br/>6 数据库 · 8 受限角色 · 严格 RLS)]
    AI --> PG
    Biz --> S3[(RustFS 对象存储)]
```

---

## 仓库目录导览

```text
SupportFlow/
├── backend/                     # Java 25 + Spring Boot 4.0.3 + Spring Cloud 微服务集群
│   ├── supportflow-common/      # 公共鉴权签名、ScopedValue 租户上下文、只读 Schema Readiness
│   ├── supportflow-gateway/     # API 网关（路由分级、JWT 解析、内部 AI 路径保护）
│   ├── supportflow-realtime-service/  # WebSocket 实时网关（消息 seq/ACK、断线补拉、多节点通知）
│   ├── supportflow-customer-service/  # 核心客服业务（会话、工单 SLA、知识库生命周期、Outbox、审批与回执）
│   ├── supportflow-user-service/      # 账号认证与 RBAC 角色管理
│   ├── supportflow-tenant-service/    # 企业入驻审核、套餐权益与结构升级守卫
│   ├── supportflow-billing-service/   # 幂等用量批次账本与日统计聚合
│   ├── supportflow-ops-service/       # 平台监控、AI 月预算闸门与可观测聚合
│   └── deploy/                  # 六库初始化 SQL、运行角色权限及权威版本化迁移（manifest + baselines）
├── ai-service/                  # Python 3.11+ FastAPI 智能服务与 Agent Runtime
│   ├── ai/agents/               # 客服大脑 LangGraph 工作流
│   ├── ai/rag/                  # Agentic RAG 编排、pgvector/LlamaIndex 检索、原文引用校验与暂存维护
│   ├── ai/runtime/              # 持久化 Agent Runtime、Tool Registry/MCP、HITL 审批、配额预留与追踪回放
│   ├── ai/core/                 # 内部 HMAC 鉴权、有界模型传输、多供应商路由与只读结构门禁
│   ├── ai/services/             # 意图分析、知识问答、会话质检、视觉识别与训练服务
│   └── tests/                   # 分层自动化测试（unit/ 快速契约单测、postgres/ 隔离数据库集成测试）
├── frontend/                    # Vue 3 + TypeScript + Vite 企业工作台、平台端、访客组件及 Playwright 测试
├── website/                     # 独立产品官网与交互演示页
├── fixtures/                    # 知识库多格式测试文档与版本化评测冻结数据集（SHA-256 校验）
├── scripts/                     # 研发与验证工具链
│   ├── *.py                     # 6 个日常本地启动、初始化与数据库迁移主入口
│   ├── lib/                     # 迁移引擎、结构漂移校验、备份恢复与隔离 PG 基础设施库
│   ├── verify/                  # 13 个数据库/跨服务集成验证脚本及测试夹具（fixtures/）
│   ├── benchmarks/              # RAG 检索/回答质量、意图上下文预算与权限边界评测脚本
│   ├── security/                # 全栈受限角色安全流联调辅助模块
│   └── tests/                   # 工具链与评测门禁自身的单元测试
└── docs/                        # 架构设计、本地开发指南、工程进度矩阵、验收记录与 86 份 ADR
```

---

## 快速启动

环境要求：**JDK 25**、**Maven 3.9+**、**Python 3.11+**、**Node.js 22+**、**Docker（PostgreSQL 17 + pgvector / RustFS）**。完整步骤详见 [本地开发与升级指南](docs/local-development.md)。

```powershell
# 1. 生成本地环境密钥、启动 PostgreSQL 并配置受限运行角色与迁移
python scripts/setup-local-env.py
docker compose up -d --wait postgres
python scripts/upgrade-runtime-security.py

# 2. 打包并启动 7 个 Java 微服务（端口 9090–9096）
mvn -B -f backend/pom.xml clean package -DskipTests
python scripts/run-backend.py

# 3. 启动 Python AI 服务（端口 9100）与前端工作台（端口 5173）
python scripts/run-ai.py
npm --prefix frontend ci && npm --prefix frontend run dev
```

---

## 自动化验证与评测入口

所有自动化测试默认使用合成数据、受限数据库角色与确定性模型替身，不依赖外部付费 API 密钥即可复现（详见 [`.github/workflows/verification.yml`](.github/workflows/verification.yml)）：

```powershell
# Java 全模块构建与单元测试
mvn -B -f backend/pom.xml verify

# Python 单元与契约门禁测试
python -m pytest ai-service/tests/unit -q

# 隔离 PostgreSQL 多租户 RLS、Runtime 故障恢复、六库迁移漂移与备份恢复演练
python scripts/verify/test-customer-rls.py --with-ai
python scripts/verify/test-agent-runtime.py --with-java-delivery
python scripts/verify/test-versioned-migrations.py
python scripts/verify/test-database-restore.py --with-attachments --with-services

# 冻结数据集 RAG 质量、原文支撑、检索排序与意图预算基准
python scripts/benchmarks/rag_quality_benchmark.py --comparison grounding --output .local/verification/rag-grounding.json
python scripts/benchmarks/rag_retrieval_benchmark.py --output .local/verification/rag-retrieval.json
python scripts/benchmarks/brain_context_benchmark.py --output .local/verification/brain-context.json
```

---

## 文档导航

- [系统架构与核心链路](docs/architecture.md) — 服务边界、双层 Agent 编排、六库安全隔离与模块映射
- [本地运行与数据库升级指南](docs/local-development.md) — 本地部署、最小权限凭据分发、存量库升级与备份
- [Agent 工程能力矩阵与实施路线](docs/agent-engineering-plan.md) — PR-0～PR-12 与 M01～M12 当前真实能力边界与待收口项
- [架构决策记录索引（ADR 0001–0086）](docs/decisions/README.md) — 按六大工程主题归类的设计取舍与回滚规范
- [量化评测与可复现证据汇总](docs/evaluation-summary.md) — 200 条 Golden Dataset、50 安全红队、50 故障恢复、RAG 2.0 召回与上下文成本实测
- [生产运维手册与故障处置 Runbook](docs/runbook.md) — SLI/SLO、健康探针、熔断降级、未知副作用核销与六库灾备恢复 SOP
- [可靠性与评测实测验收记录](docs/reliability-verification.md) — 各阶段复现命令、分母口径与报告摘要
