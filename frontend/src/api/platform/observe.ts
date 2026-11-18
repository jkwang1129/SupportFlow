import { request } from '../request'

/**
 * 可观测（平台侧）：告警规则 / 告警事件 / 成本预算 / 调用链。
 *
 * 与「AI 监控」的分工：监控页是只读大盘（花了多少、效果如何），
 * 这一页是能动手的地方（什么算异常、超了怎么办、这个请求号发生了什么）。
 */

export interface AlertRuleRow {
  id: string
  ruleName: string
  metric: number
  metricText: string
  windowDays: number
  compareOp: number
  compareText: string
  threshold: number
  level: number
  levelText: string
  scope: number
  scopeText: string
  notifyTenant: boolean
  isEnabled: boolean
  remark?: string | null
  updateTime?: string | null
}

export interface AlertRulePage {
  items: AlertRuleRow[]
  metrics: Array<{ value: number; label: string }>
}

export interface AlertRuleBody {
  ruleName: string
  metric: number
  windowDays: number
  compareOp: number
  threshold: number
  level: number
  scope: number
  notifyTenant: boolean
  isEnabled: boolean
  remark?: string
}

export function fetchAlertRules(): Promise<AlertRulePage> {
  return request<AlertRulePage>({ url: '/ops/platform/observability/rules', method: 'get' })
}

export function createAlertRule(body: AlertRuleBody): Promise<AlertRulePage> {
  return request<AlertRulePage>({ url: '/ops/platform/observability/rules', method: 'post', data: body })
}

export function updateAlertRule(id: string, body: AlertRuleBody): Promise<AlertRulePage> {
  return request<AlertRulePage>({
    url: `/ops/platform/observability/rules/${id}`,
    method: 'put',
    data: body,
  })
}

export function deleteAlertRule(id: string): Promise<AlertRulePage> {
  return request<AlertRulePage>({ url: `/ops/platform/observability/rules/${id}`, method: 'delete' })
}

export interface AlertEventRow {
  id: string
  ruleId: string
  ruleName: string
  metric: number
  metricText: string
  level: number
  levelText: string
  tenantCode?: string | null
  companyName?: string | null
  metricValue: number
  threshold: number
  windowDays: number
  statDate: string
  detail?: string | null
  handleStatus: number
  handleStatusText: string
  handleRemark?: string | null
  handlerName?: string | null
  handleTime?: string | null
  notified: boolean
  eventTime?: string | null
}

export interface AlertEventPage {
  total: number
  page: number
  pageSize: number
  list: AlertEventRow[]
  summary: { total: number; pending: number; critical: number; warning: number; today: number }
}

export function fetchAlertEvents(params: {
  handleStatus?: number
  level?: number
  tenantCode?: string
  page?: number
  pageSize?: number
} = {}): Promise<AlertEventPage> {
  return request<AlertEventPage>({
    url: '/ops/platform/observability/events',
    method: 'get',
    params: {
      handleStatus: params.handleStatus || undefined,
      level: params.level || undefined,
      tenantCode: params.tenantCode || undefined,
      page: params.page,
      pageSize: params.pageSize,
    },
  })
}

export function handleAlertEvent(
  id: string,
  status: number,
  remark?: string,
): Promise<{ id: string; handleStatus: number }> {
  return request<{ id: string; handleStatus: number }>({
    url: `/ops/platform/observability/events/${id}/handle`,
    method: 'post',
    data: { status, remark },
  })
}

/** 待处理告警数：菜单角标用 */
export function fetchPendingAlertCount(): Promise<{ count: number }> {
  return request<{ count: number }>({
    url: '/ops/platform/observability/events/pending-count',
    method: 'get',
  })
}

/** 立即跑一轮评估（改完规则不用等定时任务） */
export function evaluateAlertsNow(): Promise<{ created: number }> {
  return request<{ created: number }>({
    url: '/ops/platform/observability/evaluate',
    method: 'post',
  })
}

export interface BudgetRow {
  id: string
  tenantCode: string
  monthlyBudget: number
  alertRatio: number
  overAction: number
  overActionText: string
  isEnabled: boolean
  remark?: string | null
  updateTime?: string | null
  usedCost: number
  usedRatio: number
  usageAvailable: boolean
}

export interface BudgetPage {
  items: BudgetRow[]
  actions: Array<{ value: number; label: string }>
}

export function fetchBudgets(): Promise<BudgetPage> {
  return request<BudgetPage>({ url: '/ops/platform/observability/budgets', method: 'get' })
}

export function saveBudget(body: {
  tenantCode: string
  monthlyBudget: number
  alertRatio: number
  overAction: number
  isEnabled: boolean
  remark?: string
}): Promise<BudgetPage> {
  return request<BudgetPage>({ url: '/ops/platform/observability/budgets', method: 'post', data: body })
}

export interface TraceRow {
  id: string
  tenantCode: string
  modelKey: string
  scene: number
  sceneText: string
  inputTokens: number
  outputTokens: number
  cost: number
  latencyMs?: number | null
  success: boolean
  requestId?: string | null
  remark?: string | null
  time?: string | null
}

export interface TraceResult {
  found: boolean
  requestId: string
  items: TraceRow[]
}

/** 按调用链号查这次请求在 AI 侧发生了什么 */
export function fetchTrace(requestId: string): Promise<TraceResult> {
  return request<TraceResult>({
    url: '/ops/platform/observability/trace',
    method: 'get',
    params: { requestId },
  })
}
