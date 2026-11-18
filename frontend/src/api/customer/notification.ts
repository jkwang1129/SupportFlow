import { request } from '../request'

/**
 * 消息中心（后端）。
 *
 * <p>消息按"租户 + 类型"存，已读按人存：工单 SLA 预警、被分派的工单、
 * 平台回复企业这些都会写成 notification，右上角铃铛读的就是它。</p>
 */

export interface NotificationItem {
  id: string
  /** 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
  notifyType: number
  notifyTypeText: string
  title: string
  content?: string | null
  /** 点击后跳转的页面标识（tickets / platform-support …） */
  linkView?: string | null
  publishTime?: string | null
  read: boolean
}

export function fetchNotifications(params: { notifyType?: number; limit?: number } = {}) {
  return request<NotificationItem[]>({ url: '/customer/notifications', method: 'get', params })
}

export function fetchNotificationUnreadCount(): Promise<{ count: number }> {
  return request<{ count: number }>({ url: '/customer/notifications/unread-count', method: 'get' })
}

// ------------------------------------------------------------------ 消息中心页（分页 + 分类统计）

export interface NotificationPage {
  total: number
  page: number
  pageSize: number
  list: NotificationItem[]
  /** 只看未读时的扫描条数：已读按人存，SQL 里筛不掉 */
  scanLimit?: number
}

export function fetchNotificationPage(params: {
  notifyType?: number
  page?: number
  pageSize?: number
  keyword?: string
  unreadOnly?: boolean
} = {}): Promise<NotificationPage> {
  return request<NotificationPage>({
    url: '/customer/notifications/page',
    method: 'get',
    params: {
      notifyType: params.notifyType,
      page: params.page,
      pageSize: params.pageSize,
      keyword: params.keyword || undefined,
      unreadOnly: params.unreadOnly ? true : undefined,
    },
  })
}

export interface NotificationTypeStat {
  text: string
  total: number
  unread: number
}

export interface NotificationSummary {
  all: { total: number; unread: number }
  byType: Record<string, NotificationTypeStat>
  scanLimit?: number
}

export function fetchNotificationSummary(): Promise<NotificationSummary> {
  return request<NotificationSummary>({ url: '/customer/notifications/summary', method: 'get' })
}

/**
 * 页面标识 → 路由。
 *
 * <p>后端只存标识（tickets / platform-support），URL 由前端定；认不出来的标识返回 null，
 * 页面就退化成"看详情"——不猜、不乱跳。</p>
 */
export function notificationRoute(
  linkView?: string | null,
): { path: string; query?: Record<string, string> } | null {
  if (!linkView) {
    return null
  }
  if (linkView === 'tickets') {
    return { path: '/modules/tickets' }
  }
  if (linkView === 'platform-support') {
    return { path: '/modules/tickets', query: { ticketType: '2' } }
  }
  if (linkView === 'qa-alerts') {
    return { path: '/modules/qa/alerts' }
  }
  if (linkView === 'upgrade') {
    return { path: '/modules/upgrade' }
  }
  if (linkView === 'customers') {
    return { path: '/modules/customers' }
  }
  return null
}

/**
 * 这条消息点开是「直接下载」而不是跳页面。
 *
 * <p>导出审批通过的那条消息带的就是这个标识：平台批的是「哪些人可以导出」，
 * 企业点一下就该拿到文件，而不是被丢回客户 360 页面自己再找一次导出按钮。</p>
 */
export function isExportDownloadNotice(linkView?: string | null): boolean {
  return linkView === 'customer-export'
}

export function markNotificationRead(notifyId: string): Promise<void> {
  return request<void>({ url: `/customer/notifications/${notifyId}/read`, method: 'post' })
}

export function markAllNotificationsRead(notifyType?: number): Promise<{ count: number }> {
  return request<{ count: number }>({
    url: '/customer/notifications/read-all',
    method: 'post',
    params: { notifyType },
  })
}
