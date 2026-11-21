# ADR-0018：Runtime 单次查询 Embedding 的预算与恢复

日期：2026-11-21。旧RAG Embedding是同步批量/本地降级路径，不能直接放进Runtime工具后声称已计入MODEL预算。首批增加显式宿主EmbeddingProfile/RuntimeEmbeddingClient，只处理一个查询，保持旧Workflow原入口，后续知识工具再组合来源授权/检索。

复用现有chat_transport有界JSON传输，提取同一私有模型HTTP函数，路径仅允许固定chat/completions或embeddings；原chat包装入口保持。不得接用户/模型URL、隐式重试/换供应商或本地hash降级。1024维匹配现有pgvector，单查询≤4096 UTF-8字节、严格Profile/租户/≤30秒超时；先预留MODEL/NONE，再一次HTTP。向量/usage随原model-output-v1步骤成功同事务保存，Run/Step只存引用；新Worker/同一逻辑账户策略的密钥轮换复用同策略/输入（账户/模型策略变更须换Profile/版本），不重置预算。

协议采用项目支持的OpenAI兼容子集：input单元素字符串数组、float编码、dimensions=1024；响应单index0 embedding与固定模型、有限数值且分量绝对值≤1e6、维度/非零/现有PG序列化后非零检查。缺usage保留未知，不记零；已提供prompt_tokens/total_tokens必须为合法等值整数，Embedding只有输入计量。策略/输入/向量/usage恢复时重新校验；不把模型向量视为业务事实或文档授权。

参数/响应约定依据[OpenAI Embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create)与[百炼OpenAI兼容Embedding](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)官方文档（2026-11-21核对）；固定1024和单查询为本项目限制，不代表所有模型均支持。无真实供应商资格/质量/收费验证，仅以确定性HTTP/隔离PG证明工程不变量。超时/失败仍可能有供应商费用，未知结算与硬Token/金额预留仍M11；输入字节上限不是实际Tokenizer测量。

无新服务/SQL/模型选择器/通用MCP。权限前提仍是可信Run/Profile，知识工具必须另做当前来源与文档授权；旧RAG同步Embedding暂不迁移，不将其调用混入新Run账本。回滚停用新适配，保留原MODEL步骤/正文/usage/预算，不重复请求已成功结果或切换策略套旧Checkpoint。原chat/Workflow行为须回归。
