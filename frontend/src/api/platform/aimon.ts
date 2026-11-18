import { request } from '../request'

/**
 * AI 监控（平台）：调用量与成本在 AI 服务（经 ops 代理），质量与安全在 customer-service，
 * 租户配额在 tenant-service。
 *
 * <p>三块数据分属三个域，按"谁拥有数据谁出聚合"分开取，页面拼装。</p>
 */

export interface AiUsageOverview {
  calls: number
  failedCalls: number
  successRate: number
  inputTokens: number
  outputTokens: number
  tokens: number
  cost: number
  avgLatencyMs: number
  tenants: number
}

export interface AiUsageDaily {
  statDate: string
  calls: number
  failedCalls: number
  tokens: number
  cost: number
  avgLatencyMs: number
}

/** 计量状态：AI 服务是否在用真实模型（未配置密钥时不会有任何调用量与成本） */
export interface AiUsageStatus {
  llmEnabled: boolean
  providers: Record<string, boolean>
  enabledProviders: string[]
  defaultProvider?: string
  qwenModel?: string
  deepseekModel?: string
  lastRealCallAt?: string | null
  todayRealCalls: number
}

export interface AiUsageByModel {
  modelKey: string
  calls: number
  failedCalls: number
  tokens: number
  cost: number
  avgLatencyMs: number
}

export interface AiUsageByScene {
  scene: number
  sceneText: string
  calls: number
  tokens: number
  cost: number
}

export interface AiUsageByTenant {
  tenantCode: string
  calls: number
  tokens: number
  cost: number
}

export interface AiMetrics {
  available: boolean
  days: number
  from?: string
  to?: string
  overview?: AiUsageOverview
  daily?: AiUsageDaily[]
  byModel?: AiUsageByModel[]
  byScene?: AiUsageByScene[]
  topTenants?: AiUsageByTenant[]
  lastRealCallAt?: string | null
  status?: AiUsageStatus
}

export function fetchAiMetrics(days = 7): Promise<AiMetrics> {
  return request<AiMetrics>({ url: '/ops/platform/ai-metrics', method: 'get', params: { days } })
}

export interface AiQualityOverview {
  qaTasks: number
  scoredTasks: number
  avgAiScore: number
  highRiskTasks: number
  midRiskTasks: number
  lowRiskTasks: number
  reviewedTasks: number
  reviewRate: number
  alerts: number
  severeAlerts: number
  warningAlerts: number
  pendingAlerts: number
  alertTenants: number
}

export interface AiQualityView {
  days: number
  from: string
  to: string
  overview: AiQualityOverview
  alertDaily: Array<{ statDate: string; alerts: number; severe: number }>
  topRules: Array<{
    ruleName: string
    ruleType: number
    ruleTypeText: string
    alerts: number
    severe: number
    lastHitTime?: string | null
  }>
}

export function fetchAiQuality(days = 7): Promise<AiQualityView> {
  return request<AiQualityView>({
    url: '/customer/platform/ai-quality',
    method: 'get',
    params: { days },
  })
}

export interface AiQuotaItem {
  tenantCode: string
  companyName: string
  aiDailyLimit: number
  todayCalls: number
  todayTokens: number
  todayCost: number
  failedCalls: number
  usagePercent: number
  status: number
  statusText: string
  tenantStatus: number
  dailyRequestLimit: number
  quotaEnabled: boolean
}

export interface AiQuotaView {
  summary: {
    statDate: string
    tenants: number
    todayCalls: number
    warningTenants: number
    exceededTenants: number
    unlimitedTenants: number
    usageAvailable: boolean
  }
  items: AiQuotaItem[]
  warnPercent: number
}

export function fetchAiQuota(): Promise<AiQuotaView> {
  return request<AiQuotaView>({ url: '/tenant/admin/ai-quota', method: 'get' })
}
