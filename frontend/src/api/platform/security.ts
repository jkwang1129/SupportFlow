import { request } from '../request'

/** 安全中心：策略与密钥/导出审批在 ops-service，安全事件在 tenant-service（数据在 tenant_db）。 */

export interface SecurityPolicyView {
  loginFailThreshold: number
  loginLockMinutes: number
  ipWhitelistEnabled: boolean
  ipWhitelist: string
  exportNeedApproval: boolean
  watermarkEnabled: boolean
  sessionTimeoutMinutes: number
  twoFactorRequired: boolean
  /** 双因素覆盖范围：1-仅平台账号与企业管理员、2-全部账号（含坐席） */
  twoFactorScope: number
  configured: boolean
  updateTime?: string | null
  editor?: string | null
}

export function fetchSecurityPolicy(): Promise<SecurityPolicyView> {
  return request<SecurityPolicyView>({ url: '/ops/platform/security/policy', method: 'get' })
}

export function saveSecurityPolicy(body: Partial<SecurityPolicyView>): Promise<SecurityPolicyView> {
  return request<SecurityPolicyView>({ url: '/ops/platform/security/policy', method: 'put', data: body })
}

export interface ApiKeyRow {
  id: string
  tenantCode: string
  keyName: string
  keyPrefix: string
  permissions?: string | null
  status: number
  statusText: string
  callCount: number
  lastUsedAt?: string | null
  expireTime?: string | null
  createTime?: string | null
  creator?: string | null
}

export interface ApiKeyPage {
  summary: { total: number; enabled: number; calls: number }
  items: ApiKeyRow[]
}

export function fetchApiKeys(tenantCode?: string): Promise<ApiKeyPage> {
  return request<ApiKeyPage>({ url: '/ops/platform/security/api-keys', method: 'get', params: { tenantCode } })
}

/** 生成密钥：明文只在这次响应里返回一次 */
export function createApiKey(body: {
  tenantCode: string
  keyName: string
  permissions?: string
  expireTime?: string
}): Promise<{ id: string; apiKey: string }> {
  return request<{ id: string; apiKey: string }>({
    url: '/ops/platform/security/api-keys',
    method: 'post',
    data: body,
  })
}

export function updateApiKeyStatus(id: string, status: number): Promise<{ id: string; status: number }> {
  return request<{ id: string; status: number }>({
    url: `/ops/platform/security/api-keys/${id}/status`,
    method: 'post',
    params: { status },
  })
}

export interface SecurityEventRow {
  id: string
  tenantCode: string
  companyName: string
  eventType: number
  eventTypeText: string
  detail?: string | null
  requestUri?: string | null
  requestMethod?: string | null
  clientIp?: string | null
  tokenTenant?: string | null
  eventTime?: string | null
  handleStatus: number
  handleStatusText: string
  handleRemark?: string | null
  handlerName?: string | null
  handleTime?: string | null
}

export interface SecurityEventPage {
  total: number
  page: number
  pageSize: number
  list: SecurityEventRow[]
  summary: { total: number; pending: number; today: number; last7: number }
  typeDistribution: Array<{ eventType: number; eventTypeText: string; events: number }>
  daily: Array<{ statDate: string; events: number }>
}

export function fetchSecurityEvents(params: {
  eventType?: number
  handleStatus?: number
  tenantCode?: string
  page?: number
  pageSize?: number
} = {}): Promise<SecurityEventPage> {
  return request<SecurityEventPage>({
    url: '/tenant/admin/security-events',
    method: 'get',
    params: {
      eventType: params.eventType || undefined,
      handleStatus: params.handleStatus || undefined,
      tenantCode: params.tenantCode || undefined,
      page: params.page,
      pageSize: params.pageSize,
    },
  })
}

export function handleSecurityEvent(
  id: string,
  status: number,
  remark?: string,
): Promise<{ id: string; handleStatus: number }> {
  return request<{ id: string; handleStatus: number }>({
    url: `/tenant/admin/security-events/${id}/handle`,
    method: 'post',
    data: { status, remark },
  })
}

export interface ExportRow {
  id: string
  tenantCode: string
  exportType: number
  exportTypeText: string
  scopeJson?: string | null
  status: number
  statusText: string
  requesterName?: string | null
  approverName?: string | null
  rejectReason?: string | null
  requestTime?: string | null
  approveTime?: string | null
  exportRows?: number | null
  exportTime?: string | null
}

export interface ExportPage {
  total: number
  page: number
  pageSize: number
  list: ExportRow[]
  summary: { pending: number; approved: number; rejected: number }
}

export function fetchExports(params: { status?: number; page?: number; pageSize?: number } = {}): Promise<ExportPage> {
  return request<ExportPage>({
    url: '/ops/platform/security/exports',
    method: 'get',
    params: { status: params.status || undefined, page: params.page, pageSize: params.pageSize },
  })
}

export function approveExport(id: string, remark?: string): Promise<ExportRow> {
  return request<ExportRow>({
    url: `/ops/platform/security/exports/${id}/approve`,
    method: 'post',
    data: { remark },
  })
}

export function rejectExport(id: string, remark: string): Promise<ExportRow> {
  return request<ExportRow>({
    url: `/ops/platform/security/exports/${id}/reject`,
    method: 'post',
    data: { remark },
  })
}
