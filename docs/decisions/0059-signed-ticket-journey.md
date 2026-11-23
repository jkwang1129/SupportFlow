# ADR-0059 从签名访客入口验收工单闭环

组合验收沿用已部署的 Java 客服服务、签名投递、AI Runtime 和受限 PostgreSQL 账号。使用同一个测试会话先查询历史工单，再发送显式工单申请。两条消息各自匹配一个已送达的 Outbox 事件和一个 Run，保持会话顺序和同一访客主体。每条消息用同一个客户端消息号重复提交，最终从数据库独立核对消息数和事件数。

## 执行与结果核对

- 建单申请都经过签名消息、Outbox、独立提案 Worker、坐席 HTTP 审批以及独立的批准执行 Worker。坐席审批绑定服务返回的审批号及参数摘要。匿名、普通坐席、外租户管理员和错摘要均由真实服务拒绝。用户角色来自本地替身，令牌只在隔离验收使用。
- 批准执行对照审批消费的步骤号、Runtime 结果、签名回执查询和实际工单、事件、回执行。丢失确认用例在真实服务提交后由本地代理断开 POST 响应。核对 Worker 只发一次 POST，随后一次 GET 恢复结果，重复运行仍只有一张工单。
- 来源接管、审批过期和撤回审批在写入前发生。各案核对实际终态及错误、审批状态和零业务写入。撤回使用真实坐席 HTTP 入口，过期和接管由测试准备连接控制。

## 复现与回退

安装本地开发依赖并准备 Docker PostgreSQL 17、JDK 25 和 Python 3.11 或 3.12。从仓库根目录运行以下命令，使用临时数据库和本地协议替身。验收脚本生成 `.local/verification/runtime-ticket-approval.json`。GitHub Actions 的 `ai-test-reports` 留存同名报告。

```powershell
$env:PYTEST_ADDOPTS = '-k "ticket_loop or runtime_approval_review or cross_tenant_status_cancel"'
python scripts/test-agent-runtime.py --with-java-delivery
```

这一批只扩展验收夹具。回退该提交即可恢复原验收范围。覆盖的访客入口为 Java 客服的签名消息 API。工作台和访客 Widget 的浏览器交互、真实用户角色目录、工单号向访客发布和运营指标仍需独立验收。[本地验收](../reliability-verification.md#d16-访客查询与审批建单闭环2026-11-23)
