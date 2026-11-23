# ADR-0056 Agent 输入与权限边界回归

访客的可选结构化知识问答使用两条消息。系统角色持有固定规则，用户角色持有 `question` 和 `sources` 的 JSON 数据。每段资料只投影引用序号、标题、切片序号和正文。问题与资料中的引号和换行由 JSON 转义，来源序号由服务端分配。既有访客 SQL 权限范围与返回前发布复核继续决定哪些资料可用。该改动沿用 ADR-0055 的配置开关，覆盖已启用结构化答复的访客路径。[ADR-0055](0055-grounded-visitor-rag-replies.md)

## 授权由运行时和业务状态决定

- 工单读取 Worker 将访客正文送入现有用户数据字段。只有已观察到并再次核权的工单读结果能支持报告。只读工具目录中的能力独立于模型选项。测试记录实际 PostgreSQL Run、Step 和已验证报告，核对拒绝路径没有业务写请求或 `WRITE` 步骤。
- 工单工具只把已校验的工单号、状态和更新时间投影给下一轮。回归用业务 HTTP 替身的说明字段验证额外传输信息被隔离，正常结果仍可持久化。另复用已有写权限、审批主体与消费凭据、撤销后的资源复核用例。
- 知识问答使用两个合成租户和受限知识库账号。混合范围中的外租户、草稿和索引失败资料在模型调用前排除。只有受限资料时得到拒答且模型调用数为 0。检索内容的角色与字段归属通过模型 HTTP 替身观察。回归针对服务端的数据边界及授权，模型语义表现需要独立的质量验收。

## CI 门禁与复现

[固定矩阵](../../fixtures/evaluation/agent-boundaries-v1.json) 绑定 10 个用例和两个 PostgreSQL JUnit 文件。报告按文件、测试模块和参数化名称精确匹配。缺文件、无法解析、用例漏跑、重复、跳过或失败均返回非零退出码。JSON 记录矩阵与两个输入报告的 SHA-256、逐项状态和耗时，完整 JUnit 仍可用于定位。[汇总脚本](../../scripts/agent_boundary_report.py)

在可用的 Docker、JDK 25、Maven 及 AI 测试依赖环境中，从仓库根目录运行。

```powershell
python scripts/test-customer-rls.py --with-ai
python scripts/test-agent-runtime.py
python scripts/agent_boundary_report.py --reports .local/verification --output .local/verification/agent-boundary-contract.json
python -m unittest discover -s scripts/tests -p 'test_agent_boundary_report.py' -v
```

CI 同时跑原有的 Java 和 Python 服务联调，报告保存在 `ai-test-reports`。回退本批可还原结构化模式的输入序列化并撤下对应测试门禁，数据库无需迁移。部署时仍可通过原配置关闭结构化问答。
