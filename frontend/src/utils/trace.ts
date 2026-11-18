/**
 * 调用链号（前端这一段）。
 *
 * <p>一次请求一个号：发请求前带上 `X-Request-Id`，后端（网关 → Java → AI 服务）
 * 一路透传并把同一个号写进日志、响应头与 AI 计量表。所以「页面报错」到
 * 「这次 AI 调用花了多少、慢在哪」之间只差一个号。</p>
 *
 * <p>页面把这个号显示在错误提示里（`withTraceId`），用户复制给运维就能查——
 * 不用再问"你刚才点的是哪个按钮、大概几点"。</p>
 */

/** 与后端约定一致的请求头名 */
export const TRACE_HEADER = 'X-Request-Id'

/** 最近一次请求的调用链号（错误提示与"复制给运维"用） */
let lastTraceId = ''

export function newTraceId(): string {
  // crypto.randomUUID 在 http://localhost 下也可用；降级用随机串，够唯一即可
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 16)
  }
  return Math.random().toString(16).slice(2, 10) + Math.random().toString(16).slice(2, 10)
}

export function setLastTraceId(traceId?: string | null): void {
  if (traceId && traceId.trim()) {
    lastTraceId = traceId.trim()
  }
}

export function getLastTraceId(): string {
  return lastTraceId
}

/** 把调用链号拼到提示语后面（没有号就原样返回，不显示一串"请求号 —"） */
export function withTraceId(message: string, traceId = lastTraceId): string {
  return traceId ? `${message}（请求号 ${traceId}）` : message
}
