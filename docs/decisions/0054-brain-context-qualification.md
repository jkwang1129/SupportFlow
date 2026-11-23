# ADR-0054 客服意图上下文预算对照

用现有的 `node_recognize()` 和 `node_persist()` 对照原版最近六条历史、2 KiB 与 4 KiB。后两组走现有 `brain_context_max_bytes` 策略，保留系统规则、当前问题、允许的已恢复槽位和预算内最近的完整消息。三组均使用原有规则、意图候选和 `bot_dialogue` 存取。跑分脚本独立于在线服务，策略发布仍由原配置控制。

## 试验设计

- [固定样本](../../fixtures/evaluation/brain-context-v1.json) 包含 9 道合成题，涵盖短指代、长历史、较早主题和切换主题、同会话号的双租户、同租户其他会话、已删除记忆、显式订单号更正和当前输入超预算。历史线索和意图、订单号标签独立于模型输入。三组从相同的会话初始值开始，各自使用独立会话编号，逐题轮换执行顺序。
- 启动临时 PostgreSQL 17 并应用 `ai_db` 迁移。评测账号仅能访问所用的设置、意图和会话表。用实际读取、写入和下一轮恢复验证服务层的租户及会话过滤。软删除用独立的初始化连接标记。正式部署账号和工单检查点恢复由现有 Runtime 验收脚本覆盖。意图候选未配置匹配语料与训练版本，以隔离上下文对模型分析的影响。
- CI 生成固定 JSON 替身，重点检验预算、当前问题完整性、必要历史线索、状态恢复、更正后的订单号和禁止读取的订单号。测试替身没有语义分数。手动运行 `--live-qwen` 时沿用 D10 的有界调用，意图请求使用生产链路的温度 0.1，关闭思考模式。每个配置每题运行一次。对必需输入超预算的题，两个有预算的配置均跳过模型调用，该题仍计入质量分母。[D10 有界模型调用](0053-live-rag-evaluation.md)

## 指标与决策

成功要求生产意图识别节点的意图和最终订单号同时符合标注。另报正确订单号数、四条手工标注历史线索的保留数、序列化 `messages` 的 UTF-8 字节、供应商实报输入和输出 Token、真实调用及用量回执数。延迟为意图识别节点，包括本地读库和远程 Chat，独立的初始化和写库操作不计时。

报告全样本结果，并在三个配置都实际发出模型请求的共同样本上比较 Token 和成功数。2 KiB 有已知的旧主题丢失。4 KiB 在共同样本上保留全部标注历史线索，超长当前问题仍走现有兜底。样本规模用于说明策略差异，发布前应在独立样本验证长输入的兜底质量。工单大工具结果、其他记忆字段以及长期记忆过期需各自验收。

## 复现与回退

本地启动 Docker 后，在仓库根目录运行。`--live-qwen` 使用本机现有凭证并要求新报告路径。CI 执行固定替身模式，原始结果在 `ai-test-reports`。

```powershell
python -m pip install -r ai-service/requirements-test.txt
python scripts/brain_context_benchmark.py --output .local/verification/brain-context-benchmark.json
python scripts/brain_context_benchmark.py --live-qwen --output .local/verification/brain-context-live-new.json
python -m unittest discover -s scripts/tests -p 'test_brain_context_benchmark.py' -v
```

回退本批可撤销试验脚本、固定样本、自动化门禁和评测模型适配参数。业务数据库及配置文件无需恢复。
