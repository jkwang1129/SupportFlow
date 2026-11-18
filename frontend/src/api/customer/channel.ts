import { request } from '../request'

/** 渠道返回 */
export interface ChannelResult {
  id: string
  channelId: string
  channelType: number
  name: string
  desc?: string
  status: number
  /** 1-未配置 2-待上线 3-生效中 */
  stage: number
  appKey?: string
  skillGroupId?: number | null
  creatorName?: string
  createTime?: string
}

export const channelTypeText: Record<number, string> = {
  1: '网站在线客服',
  2: '微信公众号',
  3: '微信小程序',
  4: '官方 App',
  5: '400 电话热线',
  6: '邮件工单',
}

/**
 * 渠道类型的展示文案。
 *
 * <p>别再直接拿 `channelTypeText[type]` 去渲染：漏登记一类就会出现"只有图标、没有文字"的空列
 * （App / 400 / 邮件就是这么漏掉的）。这里兜一层，没登记的新类型显示成「渠道 N」，
 * 至少让人看得出是哪一类，而不是一片空白。</p>
 */
export function channelTypeLabel(channelType?: number | null): string {
  if (channelType == null) {
    return '—'
  }
  return channelTypeText[channelType] || `渠道 ${channelType}`
}

export const stageText: Record<number, string> = {
  1: '待配置',
  2: '待上线',
  3: '生效中',
}

export interface SkillGroupResult {
  id: string
  name: string
  description?: string
  isDefault: boolean
}

export interface TestCheck {
  name: string
  result: string
  detail: string
}

export interface ChannelTestResult {
  result: string
  summary: string
  latencyMs: number
  checks: TestCheck[]
}

export function listChannels(): Promise<ChannelResult[]> {
  return request<ChannelResult[]>({ url: '/customer/channels', method: 'get' })
}

export function createChannel(data: {
  channelType: number
  name: string
  desc?: string
}): Promise<ChannelResult> {
  return request<ChannelResult>({
    url: '/customer/channels',
    method: 'post',
    data,
  })
}

export function testChannel(channelId: string): Promise<ChannelTestResult> {
  return request({
    url: `/customer/channels/${channelId}/test`,
    method: 'post',
  })
}

export function listSkillGroups(): Promise<SkillGroupResult[]> {
  return request<SkillGroupResult[]>({ url: '/customer/channels/skill-groups', method: 'get' })
}

export function rotateChannelKey(channelId: string): Promise<ChannelResult> {
  return request<ChannelResult>({
    url: `/customer/channels/${channelId}/key/rotate`,
    method: 'post',
  })
}

export function updateChannelStatus(channelId: string, enabled: boolean): Promise<ChannelResult> {
  return request<ChannelResult>({
    url: `/customer/channels/${channelId}/status`,
    method: 'post',
    data: { enabled },
  })
}

export function bindAndLaunchChannel(channelId: string, skillGroupId?: string): Promise<ChannelResult> {
  return request<ChannelResult>({
    url: `/customer/channels/${channelId}/bind-and-launch`,
    method: 'post',
    data: { skillGroupId },
  })
}
