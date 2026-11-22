# ADR-0036 受控工单 MCP 调用

首批 MCP 接入只替换现有工单查询工具的取值传输。模型继续使用 `get_ticket_status` 的固定注册定义，只提供工单编号。租户、事件、Run、服务器地址和凭据由宿主提供。调用前通过客服服务取得当前工单权限及结果版本。MCP 返回工单状态后，现有工具校验器再次核对资源、快照及版本，返回变化或越权结果时退出。

客户端使用 MCP 2026-07-28 Streamable HTTP 的 `tools/call`、请求元数据和路由头，接受有界 JSON 或 SSE 最终结果。保留原工具与任务预算。首批只对接这个固定对象结构，暂不协商旧版本、发现工具或进行多次交互。MCP 返回的描述、进度和文本内容不会进入模型。相关协议见 [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) 和 [Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。

用运营配置 `SUPPORTFLOW_AI_RUNTIME_TICKET_MCP_URL` 和独立的 `SUPPORTFLOW_AI_RUNTIME_TICKET_MCP_SECRET` 显式开启。默认仍直接调用客服服务，配置的 MCP 地址参与 Loop 绑定，凭据可轮换。无代理、自动重试或重定向。测试端在隔离回环网络上运行，复用签名客服 SDK 获取真实数据库中的工单状态。部署所需信任服务器需独立配置。

回滚前停止 MCP Worker，待已绑定任务完成或关闭，再用原配置启动。保留原检查点、工具预算及审计。
