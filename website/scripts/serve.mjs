/**
 * 官网本地预览服务器（零依赖，只用 Node 内置模块；可反复执行）。
 *
 * 用法：
 *   node scripts/serve.mjs              # http://localhost:5180，端口被上一次占用会自动重启
 *   PORT=5181 node scripts/serve.mjs
 *   SUPPORTFLOW_API_BASE=http://127.0.0.1:9090/api node scripts/serve.mjs
 *   NO_RESTART=1 node scripts/serve.mjs # 端口被占用就报错，不自动重启
 *   FORCE=1 node scripts/serve.mjs      # 端口被别的程序占用也强行清掉
 *
 * 与 scripts/serve.py 行为一致：静态托管 + /api 反代 + 端口被上一次的预览服务占用时自动重启。
 * Python 版还支持 --force / --no-restart 等参数，优先用 serve.sh（它会先挑 Python）。
 *
 * 为什么自己写一个而不是用 vite/webpack：
 *   官网是纯静态站点（HTML + CSS + 原生 JS，SEO 友好、可整站丢 CDN），
 *   引构建工具只会带来依赖与版本负担。这里只用 Node 内置模块，
 *   把 /api 反代到网关，本地也能真取到"套餐价格"和"官网配置"。
 */
import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PORT = Number(process.env.PORT || process.env.SUPPORTFLOW_OFFICIAL_PORT || 5180)
const HOST = process.env.HOST || '127.0.0.1'
const API_TARGET = process.env.SUPPORTFLOW_API_BASE || 'http://127.0.0.1:9090/api'

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
}

/* ── 端口占用处理：只清理"上一次的官网预览服务"，别人的进程不动 ───────── */

function sh(command, args) {
  try {
    return execFileSync(command, args, { encoding: 'utf8', timeout: 5000 }).trim()
  } catch {
    return ''
  }
}

function processTable() {
  return sh('ps', ['-ax', '-o', 'pid=,command='])
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [pid, ...rest] = line.split(' ')
      return { pid: Number(pid), command: rest.join(' ').trim() }
    })
    .filter((row) => Number.isInteger(row.pid))
}

function looksLikePreview(command) {
  if (!command) return false
  if (!command.includes('supportflow-official') && !command.includes(ROOT)) return false
  return ['serve.mjs', 'serve.sh', 'scripts/serve.py'].some((token) => command.includes(token))
}

function listeningPids(port) {
  const pids = new Set()
  const lsof = sh('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'])
  lsof.split('\n').forEach((token) => {
    if (/^\d+$/.test(token.trim())) pids.add(Number(token.trim()))
  })
  if (!pids.size) {
    processTable()
      .filter((row) => row.pid !== process.pid && looksLikePreview(row.command))
      .forEach((row) => pids.add(row.pid))
  }
  return [...pids]
}

function portFree(port) {
  return new Promise((resolve) => {
    const probe = net.createServer()
    probe.once('error', () => resolve(false))
    probe.once('listening', () => probe.close(() => resolve(true)))
    probe.listen(port, HOST)
  })
}

async function waitPortFree(port, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await portFree(port)) return true
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  return portFree(port)
}

function alive(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function stopProcess(pid) {
  if (pid === process.pid) return false
  try {
    process.kill(pid, 'SIGTERM')
  } catch {
    return true
  }
  for (let i = 0; i < 25 && alive(pid); i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  if (alive(pid)) {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {
      return !alive(pid)
    }
  }
  return !alive(pid)
}

/** 停掉上一次占着端口的预览服务；返回是否已腾出端口 */
async function stopPrevious(port, { force = false } = {}) {
  const pids = listeningPids(port)
  if (!pids.length) {
    console.log('   没查到占用端口的进程，直接尝试重新绑定…')
    return portFree(port)
  }
  const table = new Map(processTable().map((row) => [row.pid, row.command]))
  const ours = []
  const others = []
  pids.forEach((pid) => {
    if (pid === process.pid) return
    const command = table.get(pid) || ''
    ;(looksLikePreview(command) ? ours : others).push({ pid, command })
  })
  if (others.length && !force) {
    others.forEach((row) => console.log(`   端口 ${port} 被别的程序占用：PID ${row.pid}（${row.command.slice(0, 60)}）`))
    console.log('   这不是官网预览服务，我没有动它。换个端口 PORT=5181，或改用 python3 scripts/serve.py --force。')
    return false
  }
  for (const row of [...ours, ...(force ? others : [])]) {
    const label = looksLikePreview(row.command) ? '上一次的官网预览服务' : '占用端口的其他程序'
    console.log(`   停止${label}：PID ${row.pid}`)
    await stopProcess(row.pid)
  }
  const freed = await waitPortFree(port)
  console.log(`   端口 ${port} ${freed ? '已释放' : '仍被占用'}`)
  return freed
}

/* ── 静态托管 + /api 反代 ─────────────────────────────────────────────── */

function proxy(req, res) {
  const target = new URL(API_TARGET)
  const options = {
    hostname: target.hostname,
    port: target.port || 80,
    path: target.pathname.replace(/\/$/, '') + req.url.slice('/api'.length),
    method: req.method,
    headers: { ...req.headers, host: target.host },
  }
  const upstream = http.request(options, (upstreamRes) => {
    res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers)
    upstreamRes.pipe(res)
    console.log(`  代理  ${req.url}  →  ${upstreamRes.statusCode}`)
  })
  upstream.on('error', (err) => {
    console.log(`  代理  ${req.url}  →  502（网关不可用：${err.message}）`)
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({
      code: 50200,
      message: `后端网关不可用（${API_TARGET}），官网会用静态兜底价：${err.message}`,
      data: null,
    }))
  })
  req.pipe(upstream)
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    proxy(req, res)
    return
  }
  const urlPath = decodeURIComponent(req.url.split('?')[0])
  let filePath = path.join(ROOT, urlPath === '/' ? 'index.html' : urlPath)
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403).end('forbidden')
    return
  }
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    filePath = path.join(ROOT, 'index.html')
  }
  const ext = path.extname(filePath)
  const body = fs.readFileSync(filePath)
  console.log(`  静态  ${req.url}  →  200 ${path.basename(filePath)}（${(body.length / 1024).toFixed(1)} KB）`)
  res.writeHead(200, {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  })
  res.end(body)
})

async function start() {
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(PORT, HOST, resolve)
    })
  } catch (error) {
    if (error.code !== 'EADDRINUSE' || process.env.NO_RESTART === '1') {
      console.error(`❌ 端口 ${PORT} 起不来：${error.message}`)
      console.error(`   换个端口试试：PORT=${PORT + 1} node scripts/serve.mjs`)
      process.exit(1)
    }
    console.log(`⟳ 端口 ${PORT} 已被占用，先停掉上一次的预览服务再重启…`)
    const freed = await stopPrevious(PORT, { force: process.env.FORCE === '1' })
    if (!freed) {
      console.error(`❌ 端口 ${PORT} 还是被占用，换个端口试试：PORT=${PORT + 1} node scripts/serve.mjs`)
      process.exit(1)
    }
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(PORT, HOST, resolve)
    })
  }

  const url = `http://localhost:${PORT}/`
  console.log('')
  console.log('SupportFlow 智能客服 · 官网本地预览（Node 版）')
  console.log(`  访问地址：${url}`)
  console.log(`  站点目录：${ROOT}`)
  console.log(`  API 反代：/api  →  ${API_TARGET}`)
  console.log('  提示：「登录 / 免费试用」默认跳管理端 http://localhost:5173，')
  console.log(`       想换地址就打开 ${url}?app=http://localhost:5173`)
  console.log('  停止：Ctrl + C（再跑一次会自动顶替当前进程，不用手动 kill）')
  console.log('')
}

start()
