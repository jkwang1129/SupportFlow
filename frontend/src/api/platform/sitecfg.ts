import { request } from '../request'

/**
 * 官网配置（平台侧）：可视化编辑 → 预览 → 发布。
 *
 * <p>后端把配置按区块（hero / features / faq…）存在 ops_db.site_config 里，
 * 草稿与已发布分开；官网项目通过 /api/ops/public/site-config 读"已发布"那份。</p>
 */

export interface SiteConfigState {
  /** 编辑中的草稿（没改过就是已发布那份） */
  draft: Record<string, any>
  /** 当前线上生效的版本 */
  published: Record<string, any>
  /** 草稿与已发布是否不一致 */
  dirty: boolean
  /** 已发布版本号（0 = 还没发布过，官网用出厂文案） */
  version: number
  /** 草稿版本号（发布后会 +1） */
  draftVersion: number
  publishTime?: string | null
  updateTime?: string | null
  editor?: string | null
  /** 出厂文案：恢复默认用得到 */
  defaults?: Record<string, any>
}

export function fetchSiteConfig(): Promise<SiteConfigState> {
  return request<SiteConfigState>({ url: '/ops/platform/site-config', method: 'get' })
}

/** 保存草稿：不影响线上，点「保存并发布」才生效 */
export function saveSiteConfigDraft(body: Record<string, unknown>): Promise<SiteConfigState> {
  return request<SiteConfigState>({ url: '/ops/platform/site-config', method: 'put', data: body })
}

export function publishSiteConfig(): Promise<SiteConfigState> {
  return request<SiteConfigState>({ url: '/ops/platform/site-config/publish', method: 'post' })
}

export function resetSiteConfigDraft(): Promise<SiteConfigState> {
  return request<SiteConfigState>({ url: '/ops/platform/site-config/reset', method: 'post' })
}

/** 预览参数：草稿编码成官网认识的 ?site= 串（只影响你自己的浏览器） */
export function fetchSitePreviewParam(): Promise<{ param: string }> {
  return request<{ param: string }>({ url: '/ops/platform/site-config/preview', method: 'get' })
}
