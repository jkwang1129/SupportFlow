# SupportFlow 脚本工具链说明

本目录按职责分层管理本地开发、数据库治理、集成验证与 AI 评测基准脚本：

```text
scripts/
├── setup-local-env.py          # 生成根目录 .env 中的本地随机数据库密码与服务间 HMAC 密钥
├── run-backend.py              # 本地一键启动 7 个 Java 微服务并按最小权限分发凭据
├── run-ai.py                   # 本地启动 Python AI 服务（自动拼装受限 AI / KB 数据库连接串）
├── create-platform-admin.py    # 初始化首个平台超级管理员账号
├── db-migrate.py               # 六库权威版本化迁移 CLI（支持 init / migrate / verify）
├── upgrade-runtime-security.py # 存量 Compose 数据库原地升级（应用增量迁移、严格 RLS 与 8 个运行角色）
├── lib/                        # 公共基础设施模块（迁移引擎、结构漂移核验、附件备份与一次性 PG 容器管理）
├── verify/                     # 隔离环境专项集成验证入口（RLS、Runtime 恢复、迁移、六库恢复、全栈安全流）
│   └── fixtures/               # 跨服务联调测试夹具（Java-Python 投递、知识/工单工具、审批与回执验证）
├── benchmarks/                 # AI 评测与契约门禁脚本（RAG 质量、原文支撑、混合检索、意图预算、权限边界）
├── security/                   # 全栈安全验收客户端与凭据分发辅助模块
└── tests/                      # 迁移引擎、备份模块与评测门禁自身的单元测试
```

常用命令示例见仓库根目录 [`README.md`](../README.md) 与 [`docs/local-development.md`](../docs/local-development.md)。
