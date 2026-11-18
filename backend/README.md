# SupportFlow 后端

Java 25、Spring Boot 4.0.3、Spring Cloud Gateway、MyBatis-Plus 和 PostgreSQL。启动流程见[本地运行指南](../docs/local-development.md)。

| Maven 模块 | 端口 | 职责 |
| --- | --- | --- |
| supportflow-common | — | 响应格式、租户上下文和审计客户端 |
| supportflow-gateway | 9090 | HTTP 路由、登录态和租户限流 |
| supportflow-user-service | 9091 | 账号、认证、角色和成员邀请 |
| supportflow-tenant-service | 9092 | 企业审核、租户开通、配额和隔离治理 |
| supportflow-customer-service | 9093 | 渠道、会话、客户、知识库、工单和质检 |
| supportflow-billing-service | 9094 | 套餐、订单、用量和账单 |
| supportflow-ops-service | 9095 | 审计、安全与官网配置、平台运营 |
| supportflow-realtime-service | 9096 | WebSocket、连接管理和消息推送 |

从本目录执行 `mvn -B clean verify` 构建全部模块。[初始化脚本](deploy/db-init/README.md)提供空环境所需的六库结构和基础数据。各服务共用由环境变量设置的 `SUPPORTFLOW_JWT_SECRET`。

配置 `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME` 和 `MAIL_PASSWORD` 后，用户服务可发邀请邮件。邀请链接前缀 `SUPPORTFLOW_MEMBER_FRONTEND_URL` 默认是 `http://localhost:5173`。未配置 SMTP 时仍可在后台复制邀请链接。
