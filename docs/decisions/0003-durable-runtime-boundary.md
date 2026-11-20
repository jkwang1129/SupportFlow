# ADR-0003：持久化 Runtime 的状态、租约与业务边界

日期：2026-11-19。阶段 B1 分批交付；本决策不是完整 Runtime 已实现声明。

## 边界

Java 继续拥有消息、会话、工单、资源权限和最终业务事务。Python 在现有 ai-service/ai/runtime 内持有 Run/Step；沿用 ai_db 和唯一版本化迁移入口，不另加服务/MQ。原 FAQ Workflow/RAG 保留，只有可靠投递和结果幂等完成后才切换业务触发入口。

首批仅实现存储原语：幂等创建、领取/心跳/租约 fencing、步骤意图与结果、计数预算、取消/超时/有限重试。没有 Worker/业务 API/Router/动态 Tool Loop，不把表或状态枚举称为 Runtime 完整交付。下一批再接 Worker 与 Java 同事务 Outbox；B2 补工具权限，B3 补审批/checkpoint。

## 状态与不变量

- PENDING → RUNNING → SUCCEEDED/FAILED/CANCELLED，暂时失败可进入 RETRY_WAIT 后重新领取。终态不可逆；恢复终态任务未来必须产生显式新任务或经审核的恢复操作，不能清零原预算。WAITING_APPROVAL 留 B3 实现，不以业务对话记录替代 checkpoint。
- 输入事件 ID 在租户内唯一，同 ID/同不可变输入返回既有 Run，同 ID/异输入冲突。持久化输入只存业务事件/消息/主体引用、版本与 Trace，不重复存原始 JWT/API 密钥/模型隐藏推理。该层只接收服务已验证的主体引用；公开请求认证将在接入时另外核验。
- 数据库时间决定 deadline/租约。领取递增 fencing token；心跳、结果、重试、步骤预留/完成均验证 tenant/run/owner/fence/未过期状态，失效 Worker 不能写回。租约接管不证明旧进程或旧外部请求已物理停止；最终 Java 写操作仍须核验幂等键和有效执行权。
- 同会话仅一个 RUNNING，按已入队事件序列处理。源端可靠投递必须保持访客事件顺序；仅靠接收端排队不能修正尚未送达的更早事件，完整保证在 Java Outbox 接入时验收。
- 步骤先持久化意图并扣预算，再执行外部调用。步骤幂等键只对完全相同意图返回既有记录，内容变更拒绝。跨重试/接管不清零步骤、模型/工具调用预算；失联步骤标记 UNKNOWN，已预留的调用仍计入预算。
- 未知 WRITE 工具结果必须进入 SIDE_EFFECT_UNKNOWN 失败/人工核验状态，不自动重试。取消不撤销已有副作用：存在未确认写意图时公开状态必须保留未知结果，不能谎称“没有执行”。后续 B3 用业务结果查询和审批恢复解决。
- 首批硬限制为步骤/模型调用/工具调用/重试次数和墙钟 deadline；Token/费用的供应商预留与结算沿用 M08/M11 后续工程，不以计数器冒充费用硬额度。

## 隔离与接入

新表强制 RLS，运行身份非 owner/NOSUPERUSER/NOBYPASSRLS；每个事务只用可信租户设置 LOCAL app.tenant_code，并保留显式 tenant 条件。复用现有 AI 登录角色，新增 NOLOGIN 能力组授予最小 S/I/U，不另发运行密码，不允许删除/DDL/TRUNCATE。迁移与角色 provisioning 仍独立。

后台恢复需要发现待执行租户；受控 SECURITY DEFINER 函数只返回有待执行/过期租约的租户编码，限量，不返回输入、消息、工具或结果。只有 AI 能力组可执行。函数必须由可信迁移管理员持有，运行身份不继承该 owner；当前本地迁移身份具有 RLS 管理权限，不能借此给运行账号 BYPASSRLS。完整角色/grants 与跨租户负面测试是发布条件。

可靠投递随后在 Java 消息事务中登记唯一事件，不再仅依赖 afterCommit 内存任务；AI Run 幂等接收后由 Worker 驱动。结果回写同样需要稳定事件键和 Java 最终权限/状态核验。这两端未接好前，现有客服路径保持原样，故障测试不能被描述成端到端恢复已通过。

## 验收与回滚

先用隔离真 PG 验证重复事件、内容冲突、多 Worker、同会话排队、过期 fencing、取消/完成竞争、预算不重置、未知写阻断、无/错租户 RLS 与运行权限。模型/业务工具采用可控 Stub；不填写生产吞吐、真实任务质量或 Token/费用数据。

新增持久化状态不能删除历史回滚。首批未接业务，停用新组件即可保留原 Workflow；一旦后续接入 Outbox/Worker，回退必须停止派发、保留待处理事件/Run/Step，使用兼容版本接管，不能清表或直接复用旧 Worker 执行未知写操作。每个子批次另附实际结果和未完成项。

## B1 第二子批次：Worker 执行原语

`RuntimeWorker` 以一个执行槽和一个心跳线程运行，版本到处理器为可信代码显式注册，不根据输入动态 import/执行代码；FastAPI 不自动启动，业务接入仍待后续子批次。多个独立进程通过既有 PG 租约协调。轮询按本地租户轮转，不声称跨进程硬配额或公平性已完成。

每个外部边界再次校验执行权；心跳数据库异常立即撤销本地可信执行权，恢复交给后续过期接管。默认 DB 连接 timeout=2秒、SQL timeout=5秒、锁 timeout=2秒；注入的 connector 也必须有界。一个执行结束前等待其心跳退出，不堆积无界后台线程。

`ExecutionContext.step` 用逻辑 key +意图摘要关联持久化步骤，同意图已成功则复用引用，不重复调用；内容变更拒绝。MODEL/READ/INTERNAL 的未知结果可在新 fence 下预留新步骤后重试，每个尝试继续消耗原 Run 预算。WRITE 未知结果不能自动重试。所有结果仍仅存引用；大对象/上下文快照与审批不是本批能力。

取消和超时只能终止有效写回权限，不能强杀 Python 线程或撤回外部请求。可信 handler 必须使用有界外部 I/O 并在步骤之间协作检查；未接入供应商的本批不会谎称可取消任意阻塞模型。原始异常只分类成稳定错误码，不持久化 DSN/供应商正文；未知编程错误永久失败，明确瞬态失败才使用共享重试预算。

## B1 第三子批次：Java 源端事件存储

新增 customer_db 005 / AgentEventOutbox，只提供事务存储API，本子批次不接SessionService自动触发、不启动网络派发，所以不会同旧机器人链路重复执行。自动化测试在生产SessionService的appendMessage与outbox.enqueue外层共用真实Spring事务，验证消息和事件同时提交/回滚；后续接入只替换触发位置，不重新实现消息链路。

事件只从同租户的已存在、可见访客消息派生，主体为可信visitor-session引用，消息正文/JWT不进入Outbox。按tenant/message_id唯一，持久化event UUID/消息seq/Trace/Runtime版本后重发保持不变；同事件修改版本拒绝。要求先绑定可信tenant再开启短事务，数据库强制RLS加restrictive guard；新表运行角色只有S/I/U，保留历史禁止删除/清空。

投递领取独立租约/fence，按同会话先前未DELIVERED事件阻塞，另一会话可进展。网络调用必须在领取事务外进行；只有匹配eventId且含Run UUID的接收回执才能落DELIVERED，这表示目标已持久化接收而非Agent已成功。丢响应在租约过期后用同事件重发，旧Worker不能ack/retry。最多8次投递，耗尽保留HELD并阻断后续同会话事件，等待后续运维核查，不自动丢弃或跳过。积压满时事务失败使源消息一同回滚；未来入口必须给出明确可重试响应，而非确认发送后漏任务。

仍缺带body绑定的服务鉴权接收协议、Dispatcher、Java业务快照/结果幂等入口和Python业务handler。未接好前保持原业务触发不变；不把事务测试描述成已上线的消息→Agent→回复闭环。回滚保留事件/外键/迁移历史，停派发再由兼容版本处理，不删除HELD或重用新eventId绕过去重。

## B1 第四子批次：带请求体签名的持久化接收协议

新增内部`POST /api/ai/v1/runtime/runs`、`GET /api/ai/v1/runtime/runs/{uuid}`和`POST .../{uuid}/cancel`。只允许customer服务身份和业务租户，复用独立CUSTOMER_SECRET；不使用终端用户JWT。新audience为`supportflow-ai-runtime:v1`，凭据格式`runtime:customer:expiry:hex`。规范串依次为audience、caller、expiry、HTTP method、raw path、raw query、tenant、原始body的SHA-256，换行连接，无尾换行。有效期最多35秒；Java签发30秒并用同一byte[]克隆构建请求，不先签JSON再重新序列化。旧签名不接受新入口，新凭据也不能用于旧业务入口。重放同输入只能返回原Run；取消为终态幂等操作，短有效期不被描述成一次性nonce机制。

外层ASGI先校验单一凭据/租户头并有界读取8192字节，验证签名后才解析JSON；拒绝重复字段、重复Content-Type、非JSON、GET带body、query、越权角色/租户。通过受信scope标记交给原认证中间件，不修改私有Request属性。此协议只接收严格Schema的事件/消息/会话/主体引用，禁止额外字段、调用者自定预算和未知版本；不保存body、密钥或JWT。请求头tenant与正文必须一致。错误及验证响应不回显入参、SQL或DSN；404不泄露跨租户Run存在性。

接收使用既有RuntimeStore短事务，202表示持久化接收，绝不表示任务已完成。重复事件用不可变输入摘要核对；冲突409、积压429并带Retry-After、持久化不可用503。同一runtime-v1策略固定12步骤/4模型/8工具/2重试/120秒墙钟，排队也消耗deadline。未来改策略需新版本并保留旧版本处理器，不能重放旧事件时暗中改变限额。状态只返回稳定标识/状态/计数/错误码，不返回owner、租约、步骤/业务内容。取消复用存储层的fencing；未知WRITE保持FAILED/SIDE_EFFECT_UNKNOWN，不声称已经撤销副作用。

`SUPPORTFLOW_AI_RUNTIME_API_ENABLED=false`为默认。启用后只读Readiness增查Run/Step所有使用列、有效S/I/U、无DELETE/TRUNCATE、强制RLS、非owner/非管理角色、能力组成员和队列发现函数EXECUTE，失败阻断业务而live仍可用；不执行DDL，不代替发布前的权威schema verify。回退先停Dispatcher/Worker再关闭开关，保留事件、Run和Step；本批尚无Dispatcher自动运行，也不接入现有消息触发。

服务签名证明可信调用方，不能代替Java业务资源授权。此批尚未实现Java来源快照/结果写入、Dispatcher、用户取消权限与传播、实际业务handler及恢复后的业务权限检查。接收端的引用不能被视作任意资源操作许可；后续Java最终操作仍须核验租户/资源/主体/当前状态/幂等键/执行权。没有端到端恢复或生产吞吐声明。
