# ADR-0081 访客 RAG 的政策冲突与人工核验

结构化答复已经能逐字验证引用。E21 的真实模型对照发现，两份生效政策给出不同数值时，模型仍会同时报告两个结论。访客链路因此增加可选的生成人工核验建议，沿用现有检索、租户过滤、发布复核和转人工入口。

生成前比较当前召回的候选切片，评测和访客默认使用 Top 5。两份不同文档中，若切片前后文字与单位一致，只有一个与问题相关的金额或时长数值不同，就以 `policy_conflict` 停止生成。规则比较时保留切片中其他数值和条件，排除区间、等价小数值和同文档重复切片。候选缺失、改写表述、单位换算、政策权威排序和跨段语义冲突仍依赖进一步治理。

模型可以提交 `handoff` 及一至三条出处。服务端核对出处编号与逐字完整原句，并要求原文含明确的强制人工核验表述。业务条件是否适用仍由模型判断。结构错误、伪造原文、缺少明确表述或夹带正文时走既有证据拒答。最终发布复核失败时返回 `stale_evidence`。两个通过复核的人工核验分支使用固定客服话术和 `handoff_recommended=true`，沿用 `decision=abstain`，不返回争议引文。访客机器人据此显示现有转人工按钮。用户点击、坐席调度和租户原有未解决轮次策略照常运行。

访客需要同时启用 `SUPPORTFLOW_AI_RAG_REQUIRE_GROUNDED_REPLY` 和 `SUPPORTFLOW_AI_RAG_REQUIRE_POLICY_REVIEW`。两个配置默认关闭，后者关闭即可停用本批逻辑。重新加载 AI 配置后生效。没有数据库变更。

在仓库根目录用以下命令复现。评测首次需要能启动隔离 PostgreSQL 的 Docker 环境。真实模型命令使用现有 Qwen 配置，产生 API 调用。沿用 E21 冻结的合成语料作为本批回归集。

```text
python scripts/rag_quality_benchmark.py --suite holdout --comparison policy-review --output .local/verification/rag-policy-review-benchmark.json
python scripts/rag_quality_benchmark.py --suite holdout --comparison policy-review --live-qwen --output .local/verification/rag-policy-review-live-new.json
```

在 `ai-service` 目录执行 `python -m pytest -q tests/test_rag_grounded_reply.py` 可覆盖 LangGraph、线性路径与下发转人工动作。CI 复跑离线对照，并保存逐题检索、拒答原因、人工核验建议和调用次数。实际坐席接通和正负例分布更广的模型质量仍需生产环境前验证。
