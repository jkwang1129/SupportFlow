# ADR-0010：复用现有对话入口的单次受限传输

日期：2026-11-20。最小Agent循环需要“一次MODEL预留对应一次明确HTTP尝试”。现有core.llm_chat.call_chat是真实调用入口；llm_gateway.py仍是旧Mock占位，不另建第二个真实网关或把整个多模型Workflow包成一次Step。先补共用传输边界，再独立接Runtime模型适配器和循环。

call_chat保持既有调用方式，委托私有chat_transport执行一次POST，无内部重试或跨供应商降级；选择供应商的旧choose_provider/resolve_provider策略没有在这批改写。仅使用宿主传入配置，拒绝非HTTP(S)、URL凭据/query/fragment、非法路径/端口、空或非法Bearer密钥；请求model必须与chosen一致。HTTPX不使用环境代理、不跟重定向，单调时钟整体deadline覆盖客户端准备、连接、响应头和完整正文，范围0.05～300秒；不能把逐次socket timeout误当整体时限。

请求根对象、纯JSON类型、深度64/节点16384与256KiB编码上限，预检查并流式编码，拒绝非有限数/非法Unicode/任意对象。响应只接application/json/identity，实际原始字节≤256KiB，不信任Content-Length；拒绝重复键、NaN、非对象与非法JSON。这里是通用HTTP/JSON边界，不把它冒充具体规划Schema、语义正确率或事实校验。

ModelCallFailure是RuntimeError子类，只包含稳定code/retryable/status/attempted：429限流、408/425/5xx或网络/整体超时可重试；认证、请求、协议、响应格式/大小为永久错误。不读取或记录供应商错误正文，不把URL/凭据/prompt/响应正文写入本入口日志，即使旧llm_log_payload打开也只记稳定元数据。其他业务消费者自己的日志、旧Mock网关、视觉/Embedding与计量器日志尚未完成统一治理，不能宣布全项目脱敏完成。

attempted只说明已进入传输阶段，不证明供应商接受了请求或实际产生费用；Run调用预算与供应商Token/账单不混用。

旧消费者默认继续调用既有best-effort usage记录，每次HTTP尝试至多一次；配置/请求预校验失败不伪装为实际模型调用。计量器失败不改变模型结果。新增受信代码参数meter_usage=False供Runtime未来在原子MODEL正文中保留供应商usage、再按后续M11统一结算，避免隐式写第二份可变账本；它不是面向模型或外部HTTP的开关。这批尚未接入Runtime调用者，也没有硬Token/金额预留、供应商切换配额或真实费用测量。旧同步计量器不受HTTP deadline覆盖，不能宣称旧call_chat所有数据库/HTTP路径都具统一硬时限。

真实回环HTTP夹具验证成功、禁止重定向、收到请求后延迟正文的整体超时及无环境代理；MockTransport负例验证配置、大小/结构、重复JSON、HTTP分类、计量与无敏感正文日志。原RAG故障测试迁移到新入口注入点并断言明确错误码，仍要求故障不伪造答案；已知引用正文xfail限制为AssertionError，防止传输夹具错误冒充已知质量缺口。

本批不接真实付费模型，Stub usage不是实际Token计量数据；需要以后用固定模型/数据集另测。兼容边界：非JSON/压缩/重复字段/超大正文的非规范上游现在明确拒绝；不以自动重试掩盖。回滚回退本传输及call_chat委托，保留既有Run/Step/正文/计量，不更改SQL；将重新暴露未受限传输/错误正文日志，不能作为生产推荐。旧Workflow入口/默认业务路由不切换。
