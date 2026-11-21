// Local browser test server for the real workspace and a synthetic approval API.
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

const port = 17364
const root = fileURLToPath(new URL('../', import.meta.url))
const origin = `http://127.0.0.1:${port}`
const sessions = [
  { sessionNo: 'FIXTURE_APPROVAL', status: 2, customerName: '测试客户 A' },
  { sessionNo: 'FIXTURE_EMPTY', status: 2, customerName: '测试客户 B' },
  { sessionNo: 'FIXTURE_STALE', status: 2, customerName: '测试客户 C' },
]
const eventId = '00000000-0000-4000-8000-000000000001'
const runId = '00000000-0000-4000-8000-000000000002'
const approvalId = '00000000-0000-4000-8000-000000000003'
const digest = 'a'.repeat(64)
let status = 'PENDING'
let stale = false

function reply(response, data, statusCode = 200, message = 'fixture') {
  response.writeHead(statusCode, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify({ code: statusCode === 200 ? 0 : statusCode * 100, message, data }))
}

async function route(request, response, next) {
  const path = new URL(request.url, origin)
  if (path.pathname === '/__approval-fixture') {
    status = 'PENDING'
    stale = false
    const role = path.searchParams.get('role') === 'agent' ? 'fixture-agent' : 'fixture-admin'
    response.writeHead(200, { 'Content-Type': 'text/html' })
    response.end(`<script>localStorage.setItem('supportflow_admin_token','${role}');localStorage.setItem('supportflow_admin_tenant','FIXTURE');location.replace('/modules/workspace')</script>`)
    return
  }
  if (!path.pathname.startsWith('/api/')) return next()

  const admin = request.headers.authorization === 'Bearer fixture-admin'
  if (path.pathname === '/api/user/auth/me') {
    return reply(response, { userId: 1, userNo: 'F1', name: '本地验收', userType: 2,
      tenantCode: 'FIXTURE', roles: [admin ? 'ADMIN' : 'AGENT'], permissions: [] })
  }
  if (path.pathname === '/api/customer/sessions') return reply(response, sessions)
  if (path.pathname === '/api/customer/sessions/summary') return reply(response, { mine: 0, queue: 3 })
  const session = sessions.find((item) => path.pathname === `/api/customer/sessions/${item.sessionNo}`)
  if (session) return reply(response, session)
  if (!path.pathname.startsWith('/api/customer/agent-approvals/')) return reply(response, [])
  if (!admin) return reply(response, null, 403)

  const expires_at = new Date(Date.now() + 120_000).toISOString()
  if (path.pathname.endsWith('/active')) {
    stale = path.pathname.includes('/FIXTURE_STALE/')
    const empty = path.pathname.includes('/FIXTURE_EMPTY/') || status === 'CLOSED'
    return reply(response, empty ? null : { source_event_id: eventId, run_id: runId, status, expires_at })
  }
  const proposalPath = `/api/customer/agent-approvals/events/${eventId}/runs/${runId}`
  if (path.pathname === `${proposalPath}/proposal`) {
    return reply(response, { approval_id: approvalId, arguments_sha256: digest, status, expires_at,
      title: '合成工单提案', content: '第一行\n<fixture-text>原文以纯文本显示</fixture-text>' })
  }
  if (path.pathname !== `${proposalPath}/decisions` || request.method !== 'POST') return reply(response, null, 404)
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  const decision = JSON.parse(Buffer.concat(chunks))
  if (decision.approval_id !== approvalId || decision.arguments_sha256 !== digest || Object.keys(decision).length !== 3) {
    return reply(response, null, 422)
  }
  if (stale) return reply(response, null, 409)
  const nextStatus = new Map([['approve', 'APPROVED'], ['deny', 'DENIED'], ['revoke', 'REVOKED']]).get(decision.decision)
  if (!nextStatus) return reply(response, null, 422)
  status = nextStatus === 'APPROVED' ? 'APPROVED' : 'CLOSED'
  console.log(`Decision payload verified: ${decision.decision}`)
  return reply(response, { approval_id: approvalId, status: nextStatus })
}

process.env.VITE_RUNTIME_APPROVAL_REVIEW_ENABLED = 'true'
process.env.VITE_API_BASE_URL = '/api'
process.env.VITE_USE_MOCK = 'false'
process.env.VITE_WS_BASE_URL = origin.replace('http', 'ws')
const server = await createServer({
  root,
  configFile: false,
  plugins: [vue(), { name: 'approval-workspace-fixture', configureServer(vite) {
    vite.middlewares.use((request, response, next) => { void route(request, response, next).catch(next) })
  } }],
  server: { host: '127.0.0.1', port, strictPort: true },
})
await server.listen()
console.log(`${origin}/__approval-fixture`)
