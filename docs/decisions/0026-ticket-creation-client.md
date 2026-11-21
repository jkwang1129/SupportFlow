# ADR-0026：建单签名SDK与严格回执验证

日期：2026-11-21。接续ADR-0025，仅增加Python端受控建单/回执协议客户端，不同时注册模型WRITE工具、HITL、自动调度或通用未知写核销。

复用_CustomerTransport，allowlist只增加ticket-create的POST/GET和固定前缀TICKET_CREATE，不开放任意URL/方法/服务操作。POST标题/正文保持原文，按Java ticket-create-v1同一独立公式计算摘要；标题≤128码点/512字节，正文≤8192字节，拒绝NUL/非法Unicode/空白。可信RunInput/Lease提供租户/Run/当前owner/fence，外部I/O前拒绝错租户、坏lease、快照和文本，不允许输入客户/类别/优先级/指派。

单次请求有总期限、正文上限、无环境代理/重定向/隐式重试。无论网络错误、超时、服务异常还是坏回执，SDK不发第二次POST，也不把404或错误当成无副作用证明。receipt仅GET；宿主若用于确认结果必须提供预期snapshot/SHA，严格验证固定9字段、绑定tenant/event/run/operation/schema、规范UUID/SHA/ASCII编号及十进制int64 ID。结果冻结且不含标题/正文/客户信息，持久化引用需再次核验类型/绑定。

响应只接受有限ApiResponse外层字段和合法元数据，timestamp兼容Java long的规范十进制字符串或整数；拒绝重复/尾随/NaN JSON、坏编码/超限/替换来源/额外业务字段。缺失回执仅接受40406+data=null，泛404或来源撤权不伪装为正常未找到。稳定错误码不包含上游正文/URL/异常原因。

验收计划：跨语言固定摘要向量、Unicode边界、闭合请求/签名、只读查询/类型与绑定、业务/网络错误、慢正文总时限与无隐藏POST、真实TCP及实际Java/AI/PG联调。回滚停用SDK，不删Run/Step/预算或业务回执，不添加盲重试；SDK本身不预留Runtime预算，工具整合后续独立验收。

Java/Python固定摘要`97638edb...047dc86`对应TENANT_A与固定run/event/snapshot、标题“标题”、CRLF及尾空格原文。两端不规范化文本；Python明确沿用Java Character.isWhitespace空白语义，NBSP/figure space/narrow NBSP/NEL行为一致。最大128个emoji标题/8192字节正文可以跨越原8192字节HTTP默认上限，固定ticket-create请求使用65536字节协议上限，结果仍8192字节有界；不提高其他操作限制。

已通过97项新SDK单元（连旧客户端共316），含3例真实TCP正常/302拒绝/慢正文超时及禁止环境代理、最大原文传输、固定allowlist负例和int64两端精确性。Java定向4项参数测试包含共享摘要及空白语义。全量AI/真实服务联调最终结果、首次夹具StreamConsumed失败和原始日志见reliability-verification.md；不把SDK协议实现称为已接模型工具或通用未知写核销。
