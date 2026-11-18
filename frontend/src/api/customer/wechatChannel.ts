import { request } from '../request'

/**
 * 微信公众号 / 小程序的「原生接入」配置（服务器配置 + 网页授权 + 模板消息）。
 * 密钥类字段只回「是否已配置」，不回显明文。
 */

export interface WeChatConfigDetail {
  configured: boolean
  appId?: string | null
  appSecretConfigured?: boolean
  tokenConfigured?: boolean
  aesKeyConfigured?: boolean
  welcomeText?: string | null
  templateLogistics?: string | null
  templateTicket?: string | null
  templateCoupon?: string | null
  /** 小程序订阅消息模板 ID（与公众号模板消息不是一套） */
  subscribeTicket?: string | null
  subscribeService?: string | null
  /** 1-公众号 2-小程序 */
  platform?: number | null
  callbackUrl?: string
  oauthUrl?: string
}

export function getWeChatConfig(channelId: string | number): Promise<WeChatConfigDetail> {
  return request<WeChatConfigDetail>({ url: `/customer/channels/${channelId}/wechat`, method: 'get' })
}

export function saveWeChatConfig(
  channelId: string | number,
  data: {
    appId: string
    appSecret?: string
    token?: string
    aesKey?: string
    welcomeText?: string
    templateLogistics?: string
    templateTicket?: string
    templateCoupon?: string
    /** 小程序订阅消息模板 ID（只有 platform=2 才用得上） */
    subscribeTicket?: string
    subscribeService?: string
    platform?: number
  },
): Promise<{ configured: boolean; appId: string; callbackUrl: string; oauthUrl: string }> {
  return request({
    url: `/customer/channels/${channelId}/wechat`,
    method: 'put',
    data,
  })
}
