import { request } from '../request'

/**
 * 客户成功（平台侧）：健康度/续费风险/扩展商机在 tenant-service（算出来的），
 * 随访安排与回访任务在 ops-service（人维护的）。
 */

export interface CsScoreParts {
  usage: number
  active: number
  expire: number
  trend: number
}

export interface CsTenantRow {
  tenantCode: string
  companyName: string
  industry?: string | null
  planId?: string | null
  isFreePlan: boolean
  tenantStatus: number
  tenantStatusText: string
  expireTime?: string | null
  remainDays?: number | null
  requests30d: number
  sessions30d: number
  aiCalls30d: number
  activeDays30d: number
  requestsLast7: number
  requestsPrev7: number
  lastActiveDate?: string | null
  healthScore: number
  healthLevel: 'HEALTHY' | 'WATCH' | 'RISK'
  healthLevelText: string
  scoreParts: CsScoreParts
  renewRisk: number
  renewRiskText: string
  renewRiskReason: string
  expandOpportunity: number
  expandOpportunityText: string
  expandSuggestion?: string | null
}

export interface CsOverview {
  summary: {
    tenants: number
    healthy: number
    watch: number
    risky: number
    highRisk: number
    midRisk: number
    opportunities: number
    expiringIn30Days: number
    expired: number
    avgHealthScore: number
  }
  items: CsTenantRow[]
  model: {
    usage: number
    active: number
    expire: number
    trend: number
    expandUsagePercent: number
    renewWatchDays: number
    note: string
  }
}

export function fetchCsOverview(): Promise<CsOverview> {
  return request<CsOverview>({ url: '/tenant/admin/customer-success', method: 'get' })
}

export interface CsFollowUp {
  tenantCode: string
  csOwnerId?: string | null
  csOwnerName?: string | null
  renewRisk?: number | null
  renewRiskText?: string | null
  expandOpportunity?: number | null
  expandOpportunityText?: string | null
  healthScore?: number | null
  lastVisitTime?: string | null
  nextVisitTime?: string | null
  nextVisitDate?: string | null
  visitRemainDays?: number | null
  remark?: string | null
  updateTime?: string | null
}

export interface CsFollowUps {
  summary: {
    followed: number
    visitDueSoon: number
    visitOverdue: number
    highRiskFollowed: number
    expandFollowed: number
  }
  items: CsFollowUp[]
}

export function fetchCsFollowUps(): Promise<CsFollowUps> {
  return request<CsFollowUps>({ url: '/ops/platform/customer-success/follow-ups', method: 'get' })
}

export function saveCsFollowUp(body: {
  tenantCode: string
  healthScore?: number
  renewRisk?: number
  expandOpportunity?: number
  csOwnerId?: string
  csOwnerName?: string
  nextVisitTime?: string
  remark?: string
}): Promise<CsFollowUps> {
  return request<CsFollowUps>({
    url: '/ops/platform/customer-success/follow-ups',
    method: 'put',
    data: body,
  })
}

export interface CsTask {
  id: string
  tenantCode: string
  companyName?: string | null
  taskType: number
  taskTypeText: string
  title: string
  ownerName?: string | null
  dueTime?: string | null
  dueRemainDays?: number | null
  overdue: boolean
  status: number
  statusText: string
  result?: string | null
  finishTime?: string | null
  createTime?: string | null
}

export interface CsTaskPage {
  total: number
  page: number
  pageSize: number
  list: CsTask[]
  summary: { pending: number; overdue: number; dueToday: number; finished: number }
}

export function fetchCsTasks(params: {
  status?: number
  tenantCode?: string
  page?: number
  pageSize?: number
} = {}): Promise<CsTaskPage> {
  return request<CsTaskPage>({
    url: '/ops/platform/customer-success/tasks',
    method: 'get',
    params: {
      status: params.status || undefined,
      tenantCode: params.tenantCode || undefined,
      page: params.page,
      pageSize: params.pageSize,
    },
  })
}

export function createCsTask(body: {
  tenantCode: string
  companyName?: string
  taskType: number
  title: string
  ownerName?: string
  dueTime?: string
}): Promise<{ id: string }> {
  return request<{ id: string }>({
    url: '/ops/platform/customer-success/tasks',
    method: 'post',
    data: body,
  })
}

export function updateCsTask(id: string, body: {
  taskType?: number
  title?: string
  ownerName?: string
  dueTime?: string
}): Promise<{ id: string }> {
  return request<{ id: string }>({
    url: `/ops/platform/customer-success/tasks/${id}`,
    method: 'put',
    data: body,
  })
}

export function finishCsTask(id: string, body: { status: number; result?: string }): Promise<{ id: string; status: number }> {
  return request<{ id: string; status: number }>({
    url: `/ops/platform/customer-success/tasks/${id}/finish`,
    method: 'post',
    data: body,
  })
}
