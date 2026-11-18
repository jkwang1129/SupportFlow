import { request } from '../request'

/**
 * 快捷话术（坐席工作台 Alt+1~9）。
 *
 * <p>排序由服务端给：启用中的话术按「使用次数倒序 → sortNo」排，前 9 条带 `altIndex`
 * （1~9），工作台据此绑定 Alt+1~Alt+9；第 10 条起 `altIndex` 为 null，只能从面板里点。</p>
 */

export interface QuickReplyRow {
  id: string
  title: string
  content: string
  category?: string | null
  sortNo: number
  isEnabled: boolean
  useCount: number
  /** 1~9 = 绑定 Alt+N；null = 没有全局快捷键 */
  altIndex: number | null
  shortcut: string | null
  updateTime?: string | null
}

/** 话术列表：onlyEnabled=true 只回启用中的（工作台用），false 回全部（设置页用） */
export function listQuickReplies(onlyEnabled = false): Promise<QuickReplyRow[]> {
  return request<QuickReplyRow[]>({
    url: '/customer/quick-replies',
    method: 'get',
    params: { onlyEnabled },
  })
}

export function saveQuickReply(data: {
  title: string
  content: string
  category?: string
  sortNo?: number
  isEnabled?: boolean
}, id?: string) {
  return request<{ id: string; list: QuickReplyRow[] }>({
    url: id ? `/customer/quick-replies/${id}` : '/customer/quick-replies',
    method: id ? 'put' : 'post',
    data,
  })
}

export function removeQuickReply(id: string) {
  return request<void>({ url: `/customer/quick-replies/${id}`, method: 'delete' })
}

/** 用了一次：累加使用次数并返回最新排序 */
export function useQuickReply(id: string): Promise<QuickReplyRow[]> {
  return request<QuickReplyRow[]>({ url: `/customer/quick-replies/${id}/use`, method: 'post' })
}

/** 一键预置常用话术（幂等：已有话术就不动） */
export function initDefaultQuickReplies(): Promise<QuickReplyRow[]> {
  return request<QuickReplyRow[]>({ url: '/customer/quick-replies/init-defaults', method: 'post' })
}
