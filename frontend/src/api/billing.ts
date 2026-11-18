import { request } from './request'

/**
 * 平台运营与计费接口：套餐 / 用量计量 / 账期账单。
 *
 * <p>企业侧与平台侧共用同一组路径，靠登录态里的 user_type 区分：
 * 企业账号只能看自己的；平台账号必须显式带 `tenantCode`（后端会拒绝"平台不指定租户"的调用，
 * 避免有人拿平台令牌盲查）。</p>
 */

export interface PlanItem {
  id: string
  planCode: string
  planName: string
  priceYearly: number
  priceMonthly: number
  quotaRobotSessions: number
  quotaChannels: number
  quotaAiQa: boolean
  quotaVision: boolean
  quotaSeats: number
  quotaStorageGb: number
  quotaAiCalls: number
  overagePricePerK: number
}

export interface UsageView {
  cycleKey: string
  sessions: number
  messages: number
  aiCalls: number
  seats: number
  storageGb: number
  planId?: string | null
  planName?: string | null
  /** 套餐到期时间（来自 tenant-service 的租户当前套餐） */
  planExpireTime?: string | null
  quotaSessions: number
  quotaAiCalls: number
  quotaSeats: number
  quotaStorageGb: number
  /** 最紧的一项占用率（四项里最大），页面据此提示升级 */
  usagePercent: number
  daily: Array<{ statDate: string; sessions: number; messages: number; aiCalls: number }>
}

export interface BillingStatementItem {
  id: string
  tenantCode: string
  statementNo: string
  cycleKey: string
  planId?: string | null
  planName?: string | null
  planAmount: number
  overageAmount: number
  totalAmount: number
  /** 1-待结算、2-已结算、3-已逾期、4-已作废 */
  status: number
  dueDate?: string | null
  settleTime?: string | null
  usageSnapshot?: string | null
  /** 账单说明：例如「套餐费已预付（有效期至 2027-09-23），本期只结超量费」 */
  remark?: string | null
  createTime?: string | null
}

export interface BillingOverview {
  cycleKey: string
  /** 账期账单：预付费周期内只承载超量费，所以这些数字可能都很小甚至为 0 */
  statements: number
  totalAmount: number
  settledAmount: number
  pendingAmount: number
  overdueCount: number
  /** 本账期**真实收到的钱**：按支付时间落在账期内的已支付订单（预付费走订单） */
  paidOrders: number
  paidAmount: number
  planDistribution: Array<{ name: string; cnt: number; amount: number }>
}

export interface StatementPage {
  total: number
  page: number
  pageSize: number
  list: BillingStatementItem[]
}

export interface BillingOrderItem {
  id: string
  tenantCode: string
  orderNo: string
  planId: string
  cycle: number
  amount: number
  /** 1-待支付、2-已支付、3-已取消、4-已退款 */
  status: number
  /** 支付方式：1-支付宝、2-微信、3-对公转账 */
  payMethod?: number | null
  invoiceStatus: number
  payTime?: string | null
  createTime?: string | null
}

export interface OrderResult {
  orderNo: string
  tenantCode?: string
  planId?: string
  planName?: string
  cycle?: number
  amount?: number
  status: number
  statusText?: string
  payTime?: string
  tenantPlanSynced?: boolean
}

export function fetchPlans(): Promise<PlanItem[]> {
  return request<PlanItem[]>({ url: '/billing/plans', method: 'get' })
}

export function fetchUsage(params: { tenantCode?: string; planId?: string } = {}): Promise<UsageView> {
  return request<UsageView>({ url: '/billing/usage', method: 'get', params })
}

export function fetchBillingOverview(cycleKey?: string): Promise<BillingOverview> {
  return request<BillingOverview>({ url: '/billing/overview', method: 'get', params: { cycleKey } })
}

/**
 * 订单 / 收款流水。
 *
 * 平台侧不传 tenantCode 就是**跨租户**；`status=2` 只看已支付，
 * 配合 `cycleKey` 按支付时间筛账期 —— 这就是"真实付费账单"。
 */
export function fetchOrders(params: {
  tenantCode?: string
  cycleKey?: string
  status?: number
  planId?: string
  /** 计费周期：1-年付、2-月付 */
  payCycle?: number
  /** 支付方式：1-支付宝、2-微信、3-对公转账 */
  payMethod?: number
  /** 支付时间范围（YYYY-MM-DD），比账期更细 */
  payFrom?: string
  payTo?: string
  /** 订单号 / 租户编码 模糊匹配 */
  keyword?: string
  page?: number
  pageSize?: number
} = {}) {
  return request<OrderPage>({
    url: '/billing/orders',
    method: 'get',
    params,
  })
}

export interface OrderPage {
  total: number
  /** 筛选结果的收款合计（列表、计数、合计共用同一套过滤条件） */
  amount: number
  page: number
  pageSize: number
  list: BillingOrderItem[]
}

export function fetchStatements(params: {
  tenantCode?: string
  cycleKey?: string
  status?: number
  page?: number
  pageSize?: number
} = {}): Promise<StatementPage> {
  return request<StatementPage>({ url: '/billing/statements', method: 'get', params })
}

/** 下单：选套餐 + 周期（1 年付 / 2 月付） */
export function createOrder(body: { planId: string; cycle: number; tenantCode?: string }): Promise<OrderResult> {
  return request<OrderResult>({ url: '/billing/orders', method: 'post', data: body })
}

/**
 * 收银台读的支付配置（登录即可读）。
 *
 * 商户号是掩码（只用来核对收的是不是这家），对公账户给全（客户要照着它转账）。
 * 渠道开关在这里只是"页面该不该显示/可选"，真正生效在 payOrder 的服务端校验。
 */
export interface ConsolePaymentConfig {
  onlinePayEnabled: boolean
  alipayEnabled: boolean
  wechatEnabled: boolean
  alipayMerchantId?: string | null
  wechatMerchantId?: string | null
  corpAccountName?: string | null
  corpAccountNo?: string | null
  corpBankName?: string | null
  corpEnabled: boolean
  configured: boolean
}

export function fetchConsolePaymentConfig(): Promise<ConsolePaymentConfig> {
  return request<ConsolePaymentConfig>({ url: '/billing/payment-config', method: 'get' })
}

/** 确认收款 / 支付成功：套餐立即生效并同步限流配额 */
export function payOrder(
  orderNo: string,
  body: { planId: string; cycle: number; tenantCode?: string; payMethod?: number },
): Promise<OrderResult> {
  return request<OrderResult>({ url: `/billing/orders/${orderNo}/pay`, method: 'post', data: body })
}

/** 生成 / 重算账期账单（幂等） */
export function generateStatement(params: {
  tenantCode: string
  cycleKey?: string
  planId?: string
}): Promise<BillingStatementItem> {
  return request<BillingStatementItem>({
    url: '/billing/statements/generate',
    method: 'post',
    params,
  })
}

/** 账单结算 / 作废 */
export interface StatementBatchResult {
  cycleKey: string
  tenants: number
  generatedCount: number
  generated: string[]
  skippedCount: number
  skipped: Array<{ tenantCode: string; reason: string }>
  /** 拿不到活跃租户清单时的原因（区别于"平台里真的没有租户"） */
  error?: string | null
}

/**
 * 给所有活跃租户生成某账期账单（与每月 1 号的自动出账走同一段逻辑）。
 *
 * 逐户独立事务：一家失败（例如该账期已结算）只进 skipped，不影响其它家。
 */
export function generateAllStatements(cycleKey?: string): Promise<StatementBatchResult> {
  return request<StatementBatchResult>({
    url: '/billing/statements/generate-all',
    method: 'post',
    params: { cycleKey },
  })
}

export function settleStatement(
  statementNo: string,
  params: { tenantCode: string; settle: boolean },
): Promise<BillingStatementItem> {
  return request<BillingStatementItem>({
    url: `/billing/statements/${statementNo}/settle`,
    method: 'post',
    params,
  })
}

/**
 * 一笔收款流水（`payment_record`：**真的收到的钱**，一笔一条）。
 *
 * <p>与订单的区别：订单是"要收多少钱"的意向（含待支付、已取消），对账要看这个表。</p>
 */
export interface PaymentRow {
  id: string
  tenantCode: string
  orderId?: string | null
  /** 1-订单（预付费套餐）、2-账期账单（后付费超量） */
  bizType: number
  bizTypeText: string
  bizNo?: string | null
  /** 收款单号 / 三方流水号 */
  payNo: string
  payMethod?: string | null
  /** 支付方式码：1-支付宝、2-微信、3-对公 */
  payMethodCode: number
  payMethodText: string
  amount: number
  /** 1-处理中、2-成功、3-失败 */
  status: number
  statusText: string
  payTime?: string | null
  remark?: string | null
  creator?: string | null
}

export interface PaymentOverview {
  total: number
  totalCount: number
  month: number
  today: number
  orderCount: number
  statementCount: number
}

export interface PaymentPage {
  total: number
  page: number
  pageSize: number
  list: PaymentRow[]
  /** 筛选范围内**成功收款**的合计 */
  amount: number
  overview: PaymentOverview
  byMethod: Array<{ payMethodCode: number; payMethod: string; count: number; amount: number }>
}

export interface PaymentQuery {
  tenantCode?: string
  bizType?: number
  payMethodCode?: number
  status?: number
  keyword?: string
  /** 到账时间范围（YYYY-MM-DD） */
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

/** 收款流水（对账口径）：读 `payment_record`，不是订单表 */
export function fetchPayments(query: PaymentQuery = {}): Promise<PaymentPage> {
  return request<PaymentPage>({ url: '/billing/platform/payments', method: 'get', params: query })
}

/** 金额展示：¥1,980.00 */
export function money(value?: number | string | null): string {
  const amount = Number(value ?? 0)
  return `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
