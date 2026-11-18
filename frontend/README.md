# SupportFlow 控制台

Vue 3、TypeScript、Vite、Element Plus、Pinia 和 Vue Router。企业控制台、平台后台、访客页面与网站挂件共用这一工程。

```bash
npm ci
npm run dev
npm run build
```

访问 `http://localhost:5173`。[Vite 配置](vite.config.ts)把 HTTP API 转到 `127.0.0.1:9090`，把 WebSocket 转到 `127.0.0.1:9096`。可用 `VITE_PROXY_TARGET`、`VITE_WS_PROXY_TARGET` 覆盖地址。`VITE_OFFICIAL_BASE` 用于从平台端预览官网。[完整联调](../docs/local-development.md)
