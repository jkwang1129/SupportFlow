import { request } from '../request'

/**
 * 平台侧「租户管理」接口：多租户深度隔离的治理面。
 *
 * <p>三块能力都在这一个页面：行级安全（RLS）状态、独立库升级、租户级限流。
 * 数字口径：`todayRequests` / `todayRejected` 来自网关批量上报的日用量，
 * `dbVersion`/`currentVersion` 来自数据面（customer-service）的升级记录。</p>
 */

/** 隔离模式：1-共享库、2-独立 Schema、3-独立实例 */
export type IsolationMode = 1 | 2 | 3

export interface TenantIsolationRow {
  tenantCode: string
  companyName?: string | null
  industry?: string | null
  status: number
  statusText: string
  isolationMode: IsolationMode
  isolationModeText: string
  schemaName?: string | null
  dbVersion: number
  upgradedAt?: string | null
  expireTime?: string | null
  createTime?: string | null
  /** 限流配置 */
  qpsLimit: number
  dailyRequestLimit: number
  quotaEnabled: boolean
  /** 今日用量（网关上报） */
  todayRequests: number
  todayRejected: number
  /** 升级与安全事件计数 */
  upgradeCount: number
  lastUpgradeAt?: string | null
  todaySecurityEvents: number
}

export interface TenantIsolationOverview {
  total: number
  active: number
  dedicated: number
  frozen: number
  versionBehind: number
  todayRejected: number
  latestVersion: number
}

export interface TenantUpgradeStep {
  version: number
  script: string
  title: string
}

export interface TenantIsolationDetail extends TenantIsolationRow {
  licenseNo?: string | null
  legalPerson?: string | null
  contactName?: string | null
  dailySessionLimit: number
  aiDailyLimit: number
  quotaRemark?: string | null
  todaySessions: number
  todayAiCalls: number
  /** 当前库版本 / 最新版本 / 待执行脚本（数据面 reachable=false 时是控制面的兜底值） */
  currentVersion: number
  latestVersion: number | null
  pending: TenantUpgradeStep[]
  dataPlaneReachable: boolean
  upgrades: Array<{
    id: string
    targetSchema: string
    fromVersion: number
    toVersion: number
    status: number
    scriptCount: number
    appliedScripts?: string | null
    message?: string | null
    durationMs: number
    operatorName?: string | null
    startedAt?: string | null
    finishedAt?: string | null
  }>
  securityEvents: Array<{
    id: string
    tenantCode: string
    eventType: number
    detail?: string | null
    requestUri?: string | null
    clientIp?: string | null
    tokenTenant?: string | null
    operatorName?: string | null
    eventTime?: string | null
  }>
}

export interface TenantIsolationPage {
  total: number
  page: number
  pageSize: number
  list: TenantIsolationRow[]
}

export interface TenantIsolationQuery {
  status?: number
  isolationMode?: IsolationMode
  keyword?: string
  page?: number
  pageSize?: number
}

export interface QuotaBody {
  qpsLimit?: number
  dailyRequestLimit?: number
  dailySessionLimit?: number
  aiDailyLimit?: number
  enabled?: boolean
  remark?: string
}

export function fetchTenantIsolationOverview(): Promise<TenantIsolationOverview> {
  return request<TenantIsolationOverview>({ url: '/tenant/isolation/overview', method: 'get' })
}

/** 租户目录一行：编码 → 企业名称 */
export interface TenantNameItem {
  tenantCode: string
  companyName?: string | null
}

/**
 * 租户目录（编码 → 企业名称）。
 *
 * 跨库的页面（收款流水只存 tenant_code，企业名在 tenant_db）拉一次做成 map 用；
 * 别用分页列表接口凑，那边每页最多 100 条。
 */
export function fetchTenantNames(): Promise<TenantNameItem[]> {
  return request<TenantNameItem[]>({ url: '/tenant/isolation/tenant-names', method: 'get' })
}

export function fetchTenantIsolationPage(
  query: TenantIsolationQuery = {},
): Promise<TenantIsolationPage> {
  return request<TenantIsolationPage>({ url: '/tenant/isolation/tenants', method: 'get', params: query })
}

export function fetchTenantIsolationDetail(tenantCode: string): Promise<TenantIsolationDetail> {
  return request<TenantIsolationDetail>({
    url: `/tenant/isolation/tenants/${tenantCode}`,
    method: 'get',
  })
}

/** 调整限流配额（0 = 不限） */
export function updateTenantQuota(
  tenantCode: string,
  body: QuotaBody,
): Promise<TenantIsolationDetail> {
  return request<TenantIsolationDetail>({
    url: `/tenant/isolation/tenants/${tenantCode}/quota`,
    method: 'post',
    data: body,
  })
}

/** 切换隔离模式（独立实例需要运维介入，接口会拒绝） */
export function switchTenantIsolationMode(
  tenantCode: string,
  mode: IsolationMode,
  schemaName?: string,
): Promise<TenantIsolationDetail> {
  return request<TenantIsolationDetail>({
    url: `/tenant/isolation/tenants/${tenantCode}/isolation-mode`,
    method: 'post',
    params: { mode, schemaName },
  })
}

/** 发起租户库升级（幂等：已是最新版本直接返回） */
export function upgradeTenantSchema(
  tenantCode: string,
  toVersion?: number,
): Promise<TenantIsolationDetail> {
  return request<TenantIsolationDetail>({
    url: `/tenant/isolation/tenants/${tenantCode}/upgrade`,
    method: 'post',
    params: toVersion ? { toVersion } : {},
  })
}

/** 冻结 / 解冻租户 */
export function freezeTenant(tenantCode: string, freeze: boolean): Promise<TenantIsolationDetail> {
  return request<TenantIsolationDetail>({
    url: `/tenant/isolation/tenants/${tenantCode}/freeze`,
    method: 'post',
    params: { freeze },
  })
}
