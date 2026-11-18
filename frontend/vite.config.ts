import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  // 默认走本地网关 9090；联调不同后端时可用 VITE_PROXY_TARGET 覆盖
  const apiTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:9090'
  // 实时网关（WebSocket 长连接）单独一个端口，默认 9096
  const wsTarget = env.VITE_WS_PROXY_TARGET || 'http://127.0.0.1:9096'
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        // 开发环境代理到后端服务；生产环境由 API 网关统一转发
        '/api/user': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/tenant': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/customer': {
          target: apiTarget,
          changeOrigin: true,
        },
        // 平台侧接口（支持工单）：同样走网关，网关再转给 customer-service
        '/api/platform': {
          target: apiTarget,
          changeOrigin: true,
        },
        // 计费（套餐 / 用量 / 账单）：网关 /api/billing/** → billing-service(9094)
        '/api/billing': {
          target: apiTarget,
          changeOrigin: true,
        },
        // 平台运营：网关 /api/ops/** → ops-service(9095)
        '/api/ops': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/ai': {
          target: apiTarget,
          changeOrigin: true,
        },
        // 开放 API（企业自有系统用 X-Api-Key 调，网关 /api/open/** → customer-service）：
        // 开发环境同样要代理过去，否则请求会落到 Vite 的 SPA 回退，接口返回 index.html
        '/api/open': {
          target: apiTarget,
          changeOrigin: true,
        },
        // 实时网关的 HTTP 接口（在线状态对账）
        '/api/realtime': {
          target: apiTarget,
          changeOrigin: true,
        },
        // WebSocket 长连接：开发环境由 Vite 转发到实时网关
        '/ws': {
          target: wsTarget,
          ws: true,
          changeOrigin: true,
        },
      },
    },
  }
})
