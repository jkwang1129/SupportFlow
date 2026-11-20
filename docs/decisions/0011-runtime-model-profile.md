# ADR-0011：显式模型Profile与持久化MODEL步骤

日期：2026-11-20。沿用core.llm_chat单次受限传输和现有ExecutionContext.payload_step，不增加第二模型网关、计数表或Workflow包装。RuntimeChatClient只接可信宿主构造的ChatProfile；租户、provider/model、地址、凭据、prompt版本、输出参数和超时不从用户/模型参数决定，Profile租户与当前Run/Lease必须相同。

Profile引用/模型/版本有界，复用共用配置校验，密钥repr隐藏；定义Hash含tenant/profile/provider/model/地址摘要/prompt/max_output_tokens/timeout而不含密钥。单纯凭据轮换可复用已收到的同策略结果，换端点/模型/Prompt/上下文则不能冒用原逻辑Step。Profile timeout≤30秒，是单次HTTP上限；max_output_tokens是请求参数，不是金额或供应商实际费用硬上限。

输入严格system首条且仅一条，后续user/assistant、只含role/content；最多32条、每条16KiB、总编码32KiB，超限明确拒绝而不静默截断。宿主负责将不可信内容放user角色，此接口不是Prompt Injection万能防护，也不是完整上下文压缩/信息恢复策略。Run/Step只存Profile/prompt/请求Hash引用，地址/凭据/完整输入不塞进引用。

每次真实尝试先预留MODEL/NONE并消耗同一Run预算，再调用已有call_chat，固定非stream/n=1/temperature=0，无provider选择、兼容格式重试或降级。模型HTTP稳定错误映射既有Transient/PermanentFailure，由同一Run重试预算决定下一次是否可再次预留；不是模块内自动发第二个请求。

响应只接一个stop结束、assistant文本选择，不接受截断/空正文/原生tool_calls/拒绝字段冒充规划结果。正文≤8KiB，非法Unicode/NUL或已配置当前凭据原样出现在正文时拒绝。成功结果chat-result-v1含有界正文、Profile/prompt/request/策略Hash和供应商报告的input/output usage，与Step SUCCEEDED同事务；缺usage保留available=false/DTO None，不填成零。只验证供应商报告格式，不把Stub数字或供应商自报数当账单校验。

恢复从同tenant/run/step正文解码，重新验证Profile/request/版本/正文/usage；同策略结果不再请求或扣模型次数。提交后丢DB回执按已有Worker恢复，提交前丢正文需新调用且继续原预算。取消/租约丢失后不保存迟到结果、不退还预留；HTTP在途不能保证供应商停止计算或不收费。

Runtime显式关闭旧best-effort usage写入，成功usage随原子MODEL正文保留；失败/取消/提交前丢输出的未知用量与统一金额结算仍由M11补齐，不能宣布完整成本治理。旧Workflow默认仍走原计量。当前Profile仅宿主注入，尚无租户配置Resolver/自动Worker/模型—工具循环，也没有语义任务质量数据。这里不宣称能恢复失败业务写工具。

回滚停止注册本模型适配器，保留所有MODEL Step/正文/usage/预算；不把新Profile计划指向旧策略代码，不重置调用次数。无SQL迁移或默认业务入口改变；旧Workflow不切换。
