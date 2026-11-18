import { request } from '../request'

/**
 * 平台设置：注册审核策略 / 套餐价格 / 支付配置 / 平台公告。
 *
 * <p>四个区块分属两个服务：注册策略与公告在 ops-service，套餐价格与支付配置在 billing-service。
 * 页面上是一个页面，接口按域分开——钱的库只有一个写入口。</p>
 */

// ------------------------------------------------------------------ 注册与审核策略

export interface RegisterConfigView {
  /** 是否开放企业注册 */
  allowRegister: boolean
  /** 新注册企业是否需要运营审核 */
  needReview: boolean
  /** 新租户试用时长（天） */
  trialDays: number
  /** 邮箱域名白名单：这些域名的注册自动放行 */
  autoApproveDomains: string[]
  /** 注册是否要求手机 + 邮箱双重验证 */
  requireDoubleVerify: boolean
  /** 是否已经保存过（没保存过返回的是默认值） */
  configured: boolean
}

export function fetchRegisterConfig(): Promise<RegisterConfigView> {
  return request<RegisterConfigView>({ url: '/ops/platform/settings/register-config', method: 'get' })
}

export function saveRegisterConfig(body: {
  allowRegister: boolean
  needReview: boolean
  trialDays: number
  autoApproveDomains: string[]
  requireDoubleVerify: boolean
}): Promise<RegisterConfigView> {
  return request<RegisterConfigView>({
    url: '/ops/platform/settings/register-config',
    method: 'put',
    data: body,
  })
}

// ------------------------------------------------------------------ 套餐价格

export interface PlatformPlanRow {
  id: string
  planCode: string
  planName: string
  priceYearly: number
  priceMonthly: number
  quotaRobotSessions: number
  quotaSeats: number
  quotaAiCalls: number
  overagePricePerK: number
  isEnabled: boolean
}

export function fetchPlatformPlans(): Promise<PlatformPlanRow[]> {
  return request<PlatformPlanRow[]>({ url: '/billing/platform/settings/plans', method: 'get' })
}

/** 保存套餐价格（保存后「版本与升级」页同步生效） */
export function savePlatformPlans(body: Array<Partial<PlatformPlanRow>>): Promise<{ saved: number }> {
  return request({ url: '/billing/platform/settings/plans', method: 'put', data: body })
}

// ------------------------------------------------------------------ 支付配置

export interface PaymentConfigView {
  onlinePayEnabled: boolean
  alipayMerchantId?: string | null
  wechatMerchantId?: string | null
  corpAccountName?: string | null
  corpAccountNo?: string | null
  corpBankName?: string | null
  configured: boolean
}

export function fetchPaymentConfig(): Promise<PaymentConfigView> {
  return request<PaymentConfigView>({ url: '/billing/platform/settings/payment-config', method: 'get' })
}

export function savePaymentConfig(body: {
  onlinePayEnabled: boolean
  alipayMerchantId?: string
  wechatMerchantId?: string
  corpAccountName?: string
  corpAccountNo?: string
  corpBankName?: string
}): Promise<PaymentConfigView> {
  return request<PaymentConfigView>({
    url: '/billing/platform/settings/payment-config',
    method: 'put',
    data: body,
  })
}

// ------------------------------------------------------------------ 平台公告

export interface AnnouncementRow {
  id: string
  title: string
  content?: string | null
  /** 1-系统公告、2-产品更新、3-维护通知 */
  announceType: number
  /** 1-草稿、2-已发布、3-已下线 */
  status: number
  publishTime?: string | null
  createTime?: string | null
  creator?: string | null
}

export interface AnnouncementPage {
  total: number
  page: number
  pageSize: number
  list: AnnouncementRow[]
}

export function fetchAnnouncements(params: {
  status?: number
  announceType?: number
  keyword?: string
  page?: number
  pageSize?: number
} = {}): Promise<AnnouncementPage> {
  return request<AnnouncementPage>({
    url: '/ops/platform/settings/announcements',
    method: 'get',
    params,
  })
}

export function saveAnnouncement(
  id: string | null,
  body: { title: string; content?: string; announceType: number },
): Promise<{ id: string }> {
  return id
    ? request({ url: `/ops/platform/settings/announcements/${id}`, method: 'put', data: body })
    : request({ url: '/ops/platform/settings/announcements', method: 'post', data: body })
}

/** 发布（2）/ 下线（3）：发布会广播到全部租户的通知中心，返回 delivered */
export function changeAnnouncementStatus(
  id: string,
  status: number,
): Promise<{ id: string; status: number; delivered: number }> {
  return request({
    url: `/ops/platform/settings/announcements/${id}/status`,
    method: 'post',
    params: { status },
  })
}

export function removeAnnouncement(id: string): Promise<void> {
  return request({ url: `/ops/platform/settings/announcements/${id}`, method: 'delete' })
}
