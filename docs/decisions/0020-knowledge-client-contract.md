# ADR-0020：知识查询签名SDK与结果核验

日期：2026-11-21。接续ADR-0019的Java窄API，先单独实现Python KnowledgeClient与协议测试，再接Registry/Runtime预算。复用现有CustomerTransport的固定服务根、HMAC、总截止时间、无重定向/环境代理、稳定错误与正文上限；只新增固定POST knowledge/search，不能变成任意URL代理。

可信宿主提供来源Run/快照ID与Hash、查询SHA、兼容索引标签及1024维向量。SDK在I/O前校验类型/有限非零向量、top5/200文档ID；null与空范围保持区别，非空仅缩小。签名覆盖实际序列化字节，响应必须匹配来源、Run、快照、来源Hash、请求SHA、查询SHA和模型标签。结果投影SHA按ADR-0019的跨语言规则重新计算，严格核字段/十进制字符串int64/片段大小/整数评分/范围/重复项；不把Hash当授权或完整知识版本。

返回不可变对象，repr隐藏标题/片段；模型用投影无租户、向量、原文件/客户信息或凭据。不记录原始错误响应，无重试/关键词或本地hash兜底。调用本SDK本身不代替预算预留；当前批不注册模型工具、不自动切换业务或宣称完整知识Agent闭环。后续Registry必须先做当前来源授权、单独预留Embedding MODEL，并每次重新查询发布范围，不能缓存授权或复用过期知识正文。

验收：确定性HTTP Stub覆盖精确签名字节、错误分类与类型/篡改/范围/大小/超时负例；实际customer JAR/受限PG核跨语言签名与投影。保留旧source/ticket/reply测试和实际联调。回滚停用新SDK；保留Java过滤与现有预算/历史，不改旧协议或放宽授权。无真实模型质量/性能/成本结论。

实际结果：新增91项SDK测试含3例真实TCP，随AI普通736项全部通过（另233条件skip/1原xfail）；24项拒答门禁通过。228项Runtime及原全部实际组合联调通过，KnowledgeClient与真实customer JAR/受限PG的签名/投影/范围/越权/接管核验通过。真实服务揭示Java全局long序列化让外层timestamp为字符串，已补规范十进制字符串兼容与回归，不放宽知识业务字段。首轮失败、最终命令/日志、原始机器报告和范围见reliability-verification。
