# ADR-0080 RAG 政策冲突与证据不足留出集

新增 `rag-quality-holdout-v1`，包含场馆服务领域的 16 份合成资料和 12 道题。可回答、须拒答、建议人工核验各 4 题。两道冲突题各含两份同等生效政策。其余样本覆盖明确人工条款、缺少价格的政策、社区讨论、跨租户同名资料、草稿、失效版本和索引失败。

留出集独立保存并在实验前冻结 SHA-256。加载时核对题目 ID、问题文本、资料 ID、完整资料正文和语料版本，阻止复用原 25 题的内容。两个 Profile 仍使用同一套 LangGraph 和 PostgreSQL 关键词检索，改写上限相同，逐题交替执行顺序。基线使用自由文本，候选使用可选的结构化答复。生产配置和提示词沿用现行版本。

CI 使用现有本地生成替身运行租户范围、检索对照、引用原文和调用预算契约。真实模型对照使用限额 Qwen 入口，单次逐题评分绑定原始报告 SHA-256。复核分别记录正确性、条件完整性、引用语义支撑及人工核验建议。实际坐席转接仍由业务入口单独验收。

```text
python scripts/rag_quality_dataset.py --suite holdout
python scripts/rag_quality_benchmark.py --suite holdout --comparison grounding --output .local/verification/rag-holdout-benchmark.json
python scripts/rag_quality_benchmark.py --suite holdout --comparison grounding --live-qwen --output .local/verification/rag-holdout-live-new.json
python scripts/rag_quality_review.py --suite holdout --raw .local/verification/rag-holdout-live-new.json --output .local/verification/rag-holdout-review-template.json
```

复核模板填入逐题判断后，用同一命令加 `--review` 并指定新的输出路径。默认取原 25 题，可用原有命令复跑。若要回退本批评测，移除留出集步骤与选项，既有产品配置沿用当前值。
