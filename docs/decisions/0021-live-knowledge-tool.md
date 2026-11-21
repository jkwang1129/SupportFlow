# ADR-0021：每次重新授权的知识工具与共享预算

日期：2026-11-21。接续ADR-0018～0020，复用Registry/Executor、SourceClient、KnowledgeClient与RuntimeEmbeddingClient；不调用旧RAG的隐藏Embedding/关键词降级、不新增服务/表/授权或模型调度入口。

实现`search_knowledge`只读工具，模型参数仅query/top_k/doc_ids；租户/来源/Run/索引模型标签/配置/Scope来自可信宿主。宿主每个ExecutionContext构造独立工具，禁止跨Run复用捕获的执行上下文。授权先读取当前来源快照，分别计入Executor的授权READ与执行READ；查询表示额外使用同一Run的MODEL预算，不能把Embedding藏在免费工具里。

当前没有完整知识库版本，所以不复用历史知识正文：每次授权产生新的引用nonce，沿用current-version执行策略，每次重新调用Java的当前发布就绪查询。成功的同策略/同问题Embedding可复用已有原子Checkpoint，复用向量不授予知识权限；新Worker仍重新授权和查询。nonce只是阻止缓存复用，不是知识版本或权限签名。每次查询可增加预算与延迟，待版本治理后用实测决定优化。

空doc_ids明确返回empty-scope，无Embedding/知识SQL，不伪造向量、供应商usage或Java查询Hash；仍核当前来源且计工具操作。其余结果只给受限片段与证据引用，明确是待核事实支持的不可信资料，不自动生成/发布答案。工具不接默认业务或现有仅工单Loop；后续闭环与最终输出核验另验。

有界I/O组合：每个HTTP都受当前工具Deadline限制；Embedding允许宿主额外缩小单次网络时间，不能扩大已绑定Profile上限或改变恢复策略Hash；后续Java请求在剩余时间内发起，已耗尽则停止。数据库语句仍依赖已有受限超时，不能声称Python能强杀任意同步回调或撤销已花模型费用。

验收覆盖参数/Scope/Run/Profile拒绝、来源撤权在MODEL前阻断、真实PG共享预算/重试/取消/恢复、模型故障分类与无隐藏重试、知识下线后重新读取而不返回旧片段、空范围零模型与知识请求，以及实际Worker→Embedding协议→Java/受限PG联调。回滚不注册工具并保留预算/Checkpoint/知识发布过滤，不删除成功模型步骤重跑。

结果：工具26项+Embedding新截止9项/原50项共85通过，AI普通771通过/246条件skip/1原xfail，24项拒答门禁通过；新13项受限PG随241项专项通过；实际7例Worker/Embedding TCP/Java知识查询通过，原全部Loop/进程恢复/发布/核销回归保持。恢复正常和下线案例均4Tools/1Model/1Retry，知识请求2/Embedding HTTP1，下线命中1→0；空范围无模型/知识请求。逐案计数、失败分类、命令/日志与回滚见reliability-verification。仍不宣称模型决策知识Loop或完整知识版本/事实支持。
