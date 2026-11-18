import { request } from '../request'

/**
 * 原生渠道接入配置（App / 400 热线 / 邮件）。
 *
 * 字段定义由服务端给（`fields`），前端按定义渲染表单——
 * 这样以后加渠道只加一段后端 schema，页面不用动。
 */

export interface NativeField {
  name: string
  label: string
  /** text / textarea / json / secret / select */
  type: string
  required: boolean
  /** 密钥类字段：不回显明文，只回「是否已配置」 */
  secret: boolean
  placeholder?: string
  help?: string
  /** 下拉字段（type=select）的可选值 */
  options?: string[]
}

export interface NativeConfigDetail {
  channelType: number
  channelName: string
  fields: NativeField[]
  values: Record<string, unknown>
  configured: boolean
  /** 端上 / 网关要填的回调地址：{ message | event | inbound: url } */
  webhookUrls: Record<string, string>
  simulator: { hint: string; contentPlaceholder?: string }
}

export function getNativeConfig(channelId: string | number): Promise<NativeConfigDetail> {
  return request<NativeConfigDetail>({ url: `/customer/channels/${channelId}/native`, method: 'get' })
}

export function saveNativeConfig(channelId: string | number, data: Record<string, unknown>) {
  return request<{ configured: boolean }>({
    url: `/customer/channels/${channelId}/native`,
    method: 'put',
    data,
  })
}

export interface SimulateStep {
  name: string
  result: Record<string, unknown>
}

export function simulateNative(
  channelId: string | number,
  body: { content?: string; subject?: string } = {},
): Promise<{ channelType: number; steps: SimulateStep[]; note: string }> {
  return request({
    url: `/customer/channels/${channelId}/native/simulate`,
    method: 'post',
    data: body,
  })
}

// ------------------------------------------------------------------ 400 通话记录

/** 今日概况（页面顶部四格 + 平均通话时长） */
export interface PhoneCallSummary {
  total: number
  finished: number
  transferred: number
  missed: number
  avgDuration: number
}

/** 一条通话记录 */
export interface PhoneCallRow {
  id: string
  callId: string
  caller?: string | null
  callerName?: string | null
  customerNo?: string | null
  sessionNo?: string | null
  ticketNo?: string | null
  /** 1-呼入中、2-已转人工、3-已完成、4-未接通 */
  status: number
  ivrPath?: string | null
  ivrLabel?: string | null
  durationSeconds?: number | null
  recordingUrl?: string | null
  agentName?: string | null
  transcript?: string | null
  /** 通话录音的语音识别文本（阿里云 NLS，异步回填） */
  asrText?: string | null
  /** 转写状态：1-成功、2-失败/跳过；null=还没转写 */
  asrStatus?: number | null
  /** 转写失败或跳过的原因 */
  asrHint?: string | null
  asrTime?: string | null
  eventCount: number
  firstEventTime?: string | null
  answerTime?: string | null
  hangupTime?: string | null
  createTime?: string | null
}

export interface PhoneCallPage {
  total: number
  page: number
  pageSize: number
  summary: PhoneCallSummary
  list: PhoneCallRow[]
}

/** 400 通话记录（只有电话渠道有；别的渠道返回空列表） */
export function listPhoneCalls(
  channelId: string | number,
  params: { status?: number; keyword?: string; page?: number; pageSize?: number } = {},
): Promise<PhoneCallPage> {
  return request<PhoneCallPage>({
    url: `/customer/channels/${channelId}/native/calls`,
    method: 'get',
    params: {
      status: params.status || undefined,
      keyword: params.keyword || undefined,
      page: params.page,
      pageSize: params.pageSize,
    },
  })
}
