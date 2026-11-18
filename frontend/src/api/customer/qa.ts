import { request } from '../request'

export interface QaOverview {
  total: number
  pending: number
  passed: number
  rejected: number
  avgAiScore: number
  avgReviewScore: number
  passRate: number
  riskLow: number
  riskMid: number
  riskHigh: number
  /** 实时质检：告警总数 */
  alertTotal: number
  /** 实时质检：待处理告警 */
  alertPending: number
  /** 实时质检：严重告警 */
  alertSevere: number
}

/** 会话实时质检告警（边聊边检命中一次就有一条） */
export interface QaAlertItem {
  id: string
  sessionNo: string
  agentId?: string | null
  agentName?: string | null
  ruleName: string
  ruleType: number
  ruleTypeText: string
  severity: number
  severityText: string
  hitKeyword?: string | null
  snippet?: string | null
  advice?: string | null
  status: number
  statusText: string
  handlerName?: string | null
  handleRemark?: string | null
  messageSeq?: number | null
  createTime?: string | null
  handleTime?: string | null
}

export interface QaTaskItem {
  id: string
  taskNo: string
  sessionName: string
  agentName: string
  aiScore?: number
  reviewScore?: number
  riskLevel: number
  riskText: string
  status: number
  statusText: string
  createTime?: string
  reviewTime?: string
  aiComment?: string
  ruleNames: string[]
  /** 会话期间的实时预警条数（边聊边检留下的证据） */
  alertCount?: number
  alertSevere?: number
}

export interface RuleResult {
  name: string
  type: string
  pass: boolean
  reason: string
}

export interface QaTaskDetail extends QaTaskItem {
  ruleResults: RuleResult[]
  reviewerId?: string
  /** 会话期间的实时预警明细 */
  alerts?: QaAlertItem[]
}

export interface QaRuleItem {
  id: string
  ruleName: string
  ruleType: number
  ruleTypeText: string
  ruleContent?: string
  weight: number
  enabled: boolean
  /** 是否参与实时质检（边聊边检） */
  realtime: boolean
  /** 命中词表（逗号分隔）：敏感词类规则命中任意一个就告警 */
  hitKeywords?: string | null
  /** 告警级别：1-提示、2-警告、3-严重 */
  severity: number
  /** 响应超时秒数（仅"响应超时"类规则使用） */
  timeoutSeconds: number
  updateTime?: string
}

export interface QaScanResult {
  /** 本次扫描到的"已结束且还没质检过"的会话数 */
  scanned?: number
  /** 实际生成的质检任务数 */
  created: number
  taskNos: string[]
}

export function fetchQaOverview(): Promise<QaOverview> {
  return request<QaOverview>({ url: '/customer/qa/overview', method: 'get' })
}

export function fetchQaTasks(params: { status?: number; keyword?: string }): Promise<QaTaskItem[]> {
  return request<QaTaskItem[]>({ url: '/customer/qa/tasks', method: 'get', params })
}

export function fetchQaTaskDetail(taskNo: string): Promise<QaTaskDetail> {
  return request<QaTaskDetail>({ url: `/customer/qa/tasks/${taskNo}`, method: 'get' })
}

export function scanQaTasks(): Promise<QaScanResult> {
  return request<QaScanResult>({ url: '/customer/qa/scan', method: 'post' })
}

export function createManualQaTask(data: {
  sessionName: string
  agentName: string
  aiScore?: number
  riskLevel?: number
  comment?: string
  ruleNames?: string[]
}): Promise<QaTaskItem> {
  return request<QaTaskItem>({ url: '/customer/qa/tasks/manual', method: 'post', data })
}

export function reviewQaTask(
  taskNo: string,
  data: { action: number; score?: number; comment?: string },
): Promise<QaTaskItem> {
  return request<QaTaskItem>({ url: `/customer/qa/tasks/${taskNo}/review`, method: 'post', data })
}

export interface BatchReviewResult {
  processed: number
  taskNos: string[]
}

export function batchReviewQaTasks(
  taskNos: string[],
  data: { action: number; score?: number; comment?: string },
): Promise<BatchReviewResult> {
  return request<BatchReviewResult>({ url: '/customer/qa/tasks/batch-review', method: 'post', data: { taskNos, ...data } })
}

export function batchAiQaTasks(taskNos: string[]): Promise<BatchReviewResult> {
  return request<BatchReviewResult>({ url: '/customer/qa/tasks/batch-ai', method: 'post', data: { taskNos } })
}

export function fetchQaRules(): Promise<QaRuleItem[]> {
  return request<QaRuleItem[]>({ url: '/customer/qa/rules', method: 'get' })
}

export function saveQaRule(data: {
  id?: string
  ruleName: string
  ruleType: number
  ruleContent?: string
  weight: number
  enabled: boolean
  realtime?: boolean
  hitKeywords?: string
  severity?: number
  timeoutSeconds?: number
}): Promise<QaRuleItem> {
  return request<QaRuleItem>({ url: '/customer/qa/rules', method: 'post', data })
}

export function deleteQaRule(id: string): Promise<void> {
  return request<void>({ url: `/customer/qa/rules/${id}`, method: 'delete' })
}

/** 实时质检告警查询条件（都为空表示不过滤） */
export interface QaAlertQuery {
  /** 处理状态：1-待处理、2-已处理 */
  status?: number
  /** 告警级别：1-提示、2-警告、3-严重 */
  severity?: number
  /** 规则类型：1-敏感词、2-承诺规范、3-必答项、4-情绪识别 */
  ruleType?: number
  /** 只看某个会话 */
  sessionNo?: string
  /** 关键词：匹配会话号 / 规则名 / 命中词 / 命中内容 */
  keyword?: string
  /** 开始时间，yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss */
  startTime?: string
  /** 结束时间，yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss */
  endTime?: string
}

/** 实时质检告警列表（实时预警页面） */
export function fetchQaAlerts(params: QaAlertQuery = {}): Promise<QaAlertItem[]> {
  return request<QaAlertItem[]>({ url: '/customer/qa/alerts', method: 'get', params })
}

/** 某个会话的告警：坐席切到这条会话时拉一次，之前错过的预警也不会漏 */
export function fetchSessionQaAlerts(sessionNo: string): Promise<QaAlertItem[]> {
  return request<QaAlertItem[]>({ url: `/customer/qa/alerts/session/${sessionNo}`, method: 'get' })
}

/** 标记告警已处理 */
export function handleQaAlert(id: string, remark?: string): Promise<QaAlertItem> {
  return request<QaAlertItem>({
    url: `/customer/qa/alerts/${id}/handle`,
    method: 'post',
    data: { remark },
  })
}
