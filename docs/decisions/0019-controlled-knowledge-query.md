# ADR-0019：业务端受控知识查询协议

日期：2026-11-21。在现有customer服务增加窄POST知识查询API，复用RuntimeSourceAccessFilter、RuntimeCustomerEndpoint、可信租户绑定与AgentSourceSnapshots。Python在事务外完成受预算限制的Embedding，Java事务内只做当前来源授权及知识SQL，不把模型HTTP放进数据库事务。

请求固定snapshot_id/query_sha256/embedding_model/vector/top_k/doc_ids，HMAC覆盖原始请求体；最多1024维、top5/200文档ID，严格类型/大小/有限非零向量，禁止传tenant/权限/URL/SQL/发布开关。embedding_model必须明确匹配索引标签，不把local-hash索引当真实语义向量。doc_ids=null为当前租户公开知识范围，[]为空，非空只缩小范围。

业务端重新核对DELIVERED来源事件、Run/访客主体/当前会话/原消息及不可变快照；按同租户、已发布且索引完成、未删除、匹配模型标签和有限距离检索。共享锁保护来源与选中知识行；查询后再次核来源/DB时钟TTL。只返最多5个ID/标题/前1024码点片段/整数百万分评分，省略原文件/客户信息/向量。响应绑定原始request_sha256、来源Hash及投影resource_version。该Hash是结果投影完整性，不是全库版本/MVCC证明。

首批交付Java API/自动化测试，下一批再做Python签名SDK与Registry的当前授权/每次重新读策略；不自动接Loop/Worker/业务路由。角色/表/模型服务不新增，read-tools开关默认关闭，网络与事务有界。当前范围为租户公开FAQ知识，不是完整用户级文档ACL，也不能撤回已发模型上下文；Embedding费用仍由Runtime适配预算，API不调用模型。

验证需覆盖签名/禁用/方法/未知参数/字节与数字边界、真实受限角色PG的来源接管/关闭/变化/过期、跨租户/草稿/下线/索引异常/模型不匹配/空范围、并发与行锁/SQL故障，并核结果投影和无业务副作用。回滚关闭read-tools/停用新调用，保留历史/数据/预算，不退回直连SQL或去掉过滤；旧source/ticket/reply协议继续回归。

验收结果：新6项Java单元/9项PG通过，合计112项客服PG+19项KB Python通过；全Java构建、228项Runtime与原全部实际联调、新知识POST真实JAR/受限PG和三套完整服务路径全部通过。请求/投影SHA由Python独立计算与Java一致，跨租户/错密钥/接管拒绝；无新增消息/回执/模型请求。复现命令、原始日志与限制见reliability-verification的本批条目，不把合成向量当语义效果或性能测量。
