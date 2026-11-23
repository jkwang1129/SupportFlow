import { defineConfig } from '@playwright/test'

const origin = 'http://127.0.0.1:4173'

export default defineConfig({
  testDir: './tests',
  outputDir: './output/playwright',
  workers: 1,
  use: { baseURL: origin, trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: origin,
    reuseExistingServer: false,
    env: {
      VITE_RUNTIME_APPROVAL_REVIEW_ENABLED: 'true',
      VITE_API_BASE_URL: '/api',
      VITE_PROXY_TARGET: 'http://127.0.0.1:1',
      VITE_WS_BASE_URL: 'ws://127.0.0.1:4173',
      VITE_WS_PROXY_TARGET: 'http://127.0.0.1:1',
    },
  },
})
