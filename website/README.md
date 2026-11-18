# SupportFlow 官网

独立 HTML、CSS 与原生 JavaScript 站点，包含产品主页、交互演示和条款示例。套餐和官网配置从 API 网关读取，接口不可用时使用页面内的默认内容。

```bash
node scripts/serve.mjs
node scripts/build.mjs
```

默认预览地址为 `http://localhost:5180`，管理端默认是 `http://localhost:5173`。预览将 `/api` 转到 `http://127.0.0.1:9090/api`，也可运行 `python scripts/serve.py`。

构建输出在 `dist/`，可用 `SUPPORTFLOW_API_BASE` 和 `SUPPORTFLOW_APP_BASE` 覆盖构建配置。使用镜像内置的 nginx 反代时，在容器网络中提供可解析的 `supportflow-gateway:9090`，或按部署环境修改 [nginx.conf](nginx.conf)。

官网上的行业案例与条款是演示内容。对外运营时应配置实际站点信息和运营主体。
