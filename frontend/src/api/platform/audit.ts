import { default as service, request } from '../request'

/**
 * 审计日志（平台侧）：全平台操作留痕。
 *
 * <p>只读 + 导出：日志的写入在各服务的内部接口，平台页连改都改不了。</p>
 */

export interface AuditLogRow {
  id: string
  tenantCode: string
  operatorId?: string | null
  /** 写日志时的操作人姓名快照（改名后历史日志仍保留当时的名字） */
  operatorName?: string | null
  module: string
  action: string
  targetType?: string | null
  targetId?: string | null
  /** 1-成功、2-失败 */
  result: number
  detail?: string | null
  ip?: string | null
  requestId?: string | null
  auditTime?: string | null
}

export interface AuditLogQuery {
  module?: string
  result?: number
  operator?: string
  keyword?: string
  /** 审计时间范围（YYYY-MM-DD） */
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

export interface AuditLogPage {
  total: number
  page: number
  pageSize: number
  list: AuditLogRow[]
}

export interface ModuleOption {
  module: string
  cnt: number
}

export function fetchAuditLogs(query: AuditLogQuery = {}): Promise<AuditLogPage> {
  return request<AuditLogPage>({ url: '/ops/audit-logs', method: 'get', params: query })
}

/** 有日志的模块清单（筛选下拉用，不用手写一份会过期的常量表） */
export function fetchAuditModules(): Promise<ModuleOption[]> {
  return request<ModuleOption[]>({ url: '/ops/audit-logs/modules', method: 'get' })
}

/**
 * 导出审计日志 CSV（按当前筛选）。
 *
 * <p>走原始 axios 实例而不是 `request()`：CSV 不是 `{code,data}` 结构，
 * 统一解包会把它当成业务失败。BOM 由后端写入，Excel 打开中文不乱码。</p>
 */
export async function downloadAuditLogsCsv(query: AuditLogQuery = {}, fileName = '审计日志.csv') {
  const response = await service.get('/ops/audit-logs/export', {
    params: query,
    responseType: 'blob',
  })
  const blob = response.data as Blob
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
