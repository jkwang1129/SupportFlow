import { request } from '../request'

/**
 * 平台数据（平台经营分析）：租户规模在 tenant-service，收入口径在 billing-service。
 *
 * <p>一个页面拉两个域：谁也不跨库读别人的表，页面把两边拼起来看。</p>
 */

export interface PlatformOverview {
  tenantTotal: number
  tenantActive: number
  tenantFrozen: number
  tenantExpired: number
  tenantCancelled: number
  tenantIsolated: number
  paidTenants: number
  newThisMonth: number
  monthRequests: number
  monthRejected: number
  monthSessions: number
  monthAiCalls: number
  monthKey: string
}

export interface TenantGrowthPoint {
  month: string
  newTenants: number
  cumulative: number
}

export interface PlanDistributionItem {
  /** 还没开套餐的租户这里是 null */
  planId: string | null
  tenantCount: number
}

export interface StatusDistributionItem {
  status: number
  statusText: string
  tenantCount: number
}

export interface TenantAnalyticsRow {
  tenantCode: string
  companyName?: string | null
  industry?: string | null
  planId?: string | null
  status: number
  statusText: string
  expireTime?: string | null
  createTime?: string | null
  monthRequests: number
  monthSessions: number
  lastActiveDate?: string | null
}

export interface TenantAnalyticsPage {
  total: number
  page: number
  pageSize: number
  list: TenantAnalyticsRow[]
}

export interface PlatformTenantAnalytics {
  overview: PlatformOverview
  growth: TenantGrowthPoint[]
  planDistribution: PlanDistributionItem[]
  statusDistribution: StatusDistributionItem[]
  topTenants: TenantAnalyticsRow[]
  tenants: TenantAnalyticsPage
}

export function fetchTenantAnalytics(params: {
  months?: number
  page?: number
  pageSize?: number
  keyword?: string
  status?: number
} = {}): Promise<PlatformTenantAnalytics> {
  return request<PlatformTenantAnalytics>({
    url: '/tenant/admin/analytics',
    method: 'get',
    params: {
      months: params.months,
      page: params.page,
      pageSize: params.pageSize,
      keyword: params.keyword || undefined,
      status: params.status || undefined,
    },
  })
}

export interface RevenueTrendPoint {
  month: string
  paidAmount: number
  paidOrders: number
}

export interface PlanRevenueItem {
  planId: string
  planCode: string
  planName: string
  paidOrders: number
  paidAmount: number
}

export interface PayMethodRevenueItem {
  payMethod: number
  payMethodText: string
  paidOrders: number
  paidAmount: number
}

export interface StatementSummary {
  statements: number
  settledAmount: number
  pendingAmount: number
  overdueAmount: number
  overdueCount: number
}

export interface PlatformRevenueAnalytics {
  cycleKey: string
  paidTotal: number
  paidThisMonth: number
  paidOrders: number
  paidOrdersThisMonth: number
  pendingOrders: number
  pendingAmount: number
  payingTenants: number
  avgOrderAmount: number
  revenueTrend: RevenueTrendPoint[]
  planRevenue: PlanRevenueItem[]
  payMethodRevenue: PayMethodRevenueItem[]
  statements: StatementSummary
}

export function fetchRevenueAnalytics(months = 6): Promise<PlatformRevenueAnalytics> {
  return request<PlatformRevenueAnalytics>({
    url: '/billing/platform/analytics',
    method: 'get',
    params: { months },
  })
}
