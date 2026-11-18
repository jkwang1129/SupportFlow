import { request } from '../request'

export interface BotIntent {
  id: number
  intentCode: string
  name: string
  status: number
  confidence?: number | null
  hitCount: number
  samples?: string
  /** 命中该意图是否直接转人工（人工客服、投诉这类） */
  escalate?: boolean
  updateTime?: string
}

export interface BotSetting {
  botName: string
  /** 租户品牌名：自我介绍里用它（留空则不带品牌，不会出现平台品牌） */
  brandName?: string
  /** 机器人头像地址：访客端聊天里显示；留空用内置默认头像 */
  avatarUrl?: string
  welcomeMessage?: string
  fallbackMessage?: string
  /** 转人工提示：机器人答不上来时告诉客户"怎么找人工"（不是转接中的确认语） */
  transferPrompt?: string
  /** 转接中话术：客户要求人工后回的那一句（如"好的，正在为您转接人工客服，请稍候。"） */
  transferMessage?: string
  isEnabled: boolean
  /** 是否由机器人首轮接待：关闭后客户进线直接进人工队列 */
  receptionEnabled?: boolean
  /** 客户情绪激动时是否自动转人工 */
  transferOnAnger?: boolean
  /** 连续多少轮没解决就转人工（0=从不） */
  transferAfterUnresolved?: number
  /** 命中即转人工的关键词，逗号分隔 */
  transferKeywords?: string
  modelKey?: string
  temperature: number
  /** 管理员是否**保存过**设置（后端按 update_time > create_time 判断；GET 自动建行时为 false） */
  configured?: boolean
  createTime?: string
  updateTime?: string
}

/** 机器人接待记录：一个会话一条 */
export interface BotDialogue {
  sessionNo: string
  turnCount: number
  unresolvedRounds: number
  lastIntent?: string | null
  lastConfidence?: number | null
  lastEmotion?: string | null
  emotionScore?: number | null
  slots?: Record<string, string>
  transferred: boolean
  transferReason?: string | null
  lastMessage?: string | null
  updateTime?: string
}

/** 意图识别统计 */
export interface BotIntentStats {
  totalTurns: number
  totalSessions: number
  transferredSessions: number
  byIntent: { intent: string; sessions: number; transferred: number }[]
  byEmotion: { emotion: string; sessions: number }[]
}

export interface BotModel {
  id: number
  modelKey: string
  modelName: string
  provider: string
  modelType: number
  temperature: number
  isEnabled: boolean
}

export function listBotIntents(): Promise<BotIntent[]> {
  return request({ url: '/ai/v1/bot/intents', method: 'get' })
}

export function createBotIntent(data: { name: string; samples?: string; escalate?: boolean }): Promise<BotIntent> {
  return request({ url: '/ai/v1/bot/intents', method: 'post', data })
}

export function updateBotIntent(
  id: number,
  data: { name?: string; samples?: string; status?: number; escalate?: boolean },
): Promise<void> {
  return request({ url: `/ai/v1/bot/intents/${id}`, method: 'put', data })
}

export function listBotDialogues(limit = 50): Promise<BotDialogue[]> {
  return request({ url: '/ai/v1/bot/dialogues', method: 'get', params: { limit } })
}

export function getBotIntentStats(): Promise<BotIntentStats> {
  return request({ url: '/ai/v1/bot/intent-stats', method: 'get' })
}

export function getBotSetting(): Promise<BotSetting> {
  return request({ url: '/ai/v1/bot/settings', method: 'get' })
}

export function saveBotSetting(data: Partial<BotSetting>): Promise<BotSetting> {
  return request({ url: '/ai/v1/bot/settings', method: 'put', data })
}

export function listBotModels(): Promise<BotModel[]> {
  return request({ url: '/ai/v1/bot/models', method: 'get' })
}

export function selectBotModel(data: { modelKey: string; temperature: number }): Promise<void> {
  return request({ url: '/ai/v1/bot/model', method: 'put', data })
}

// ------------------------------------------------------------------ 模型训练中心

/** 语料口径：人工维护的 + 历史真实会话里弱标注过来的 */
export interface BotTrainingCorpus {
  manual: number
  history: number
  total: number
  intents: number
  /** 弱标注门槛：机器人当时判出的意图置信度要达到这个值才进语料 */
  historyMinConfidence: number
}

/** 一个训练版本（v1.0 / v1.1 …） */
export interface BotVersionRow {
  versionNo: string
  /** 1-全量、2-增量 */
  mode: number
  modeText: string
  /** 1-训练中、2-灰度中、3-已上线、4-已下线 */
  status: number
  statusText: string
  grayPercent: number
  sampleCount: number
  intentCount: number
  accuracy: number | null
  jobNo: string | null
  remark: string | null
  /** 向量来源：qwen:text-embedding-v3 或 local-hash（本地兜底向量，语义能力弱） */
  baseModel: string | null
  publishTime: string | null
  createTime: string | null
}

/** 一次训练任务 */
export interface BotTrainingJob {
  jobNo: string
  mode: number
  modeText: string
  /** 1-等待中、2-执行中、3-成功、4-失败 */
  status: number
  statusText: string
  progress: number
  phase: string | null
  sampleCount: number
  intentCount: number
  grayPercent: number
  resultVersion: string | null
  errorMessage: string | null
  startTime: string | null
  finishTime: string | null
  createTime: string | null
}

export interface BotTrainingOverview {
  onlineVersion: BotVersionRow | null
  grayVersion: BotVersionRow | null
  lastJob: BotTrainingJob | null
  jobs: BotTrainingJob[]
  versions: BotVersionRow[]
  corpus: BotTrainingCorpus
}

/** 训练中心总览（首屏） */
export function getBotTraining(): Promise<BotTrainingOverview> {
  return request({ url: '/ai/v1/bot/training', method: 'get' })
}

/** 发起训练：mode 1-全量 / 2-增量，grayPercent 0 表示训练完直接全量上线 */
export function startBotTraining(data: { mode: number; grayPercent: number }): Promise<{
  job: BotTrainingJob
  overview: BotTrainingOverview
}> {
  return request({ url: '/ai/v1/bot/training/jobs', method: 'post', data })
}

/** 轮询某个任务的进度 */
export function getBotTrainingJob(jobNo: string): Promise<BotTrainingJob> {
  return request({ url: `/ai/v1/bot/training/jobs/${jobNo}`, method: 'get' })
}

/** 发布版本（grayPercent>0 就是灰度，旧版本继续兜底） */
export function publishBotVersion(versionNo: string, grayPercent: number): Promise<BotTrainingOverview> {
  return request({
    url: `/ai/v1/bot/training/versions/${versionNo}/publish`,
    method: 'post',
    data: { grayPercent },
  })
}

/** 回滚：把该版本重新全量上线 */
export function rollbackBotVersion(versionNo: string): Promise<BotTrainingOverview> {
  return request({ url: `/ai/v1/bot/training/versions/${versionNo}/rollback`, method: 'post' })
}

export interface BotAvatarUploadResult {
  fileId: string
  /** 可直接填进 avatarUrl 的地址（/api/customer/bot/avatar/{fileId}） */
  url: string
  fileName: string
  fileSize: number
  mimeType: string
}

/**
 * 上传机器人头像。
 *
 * <p>存对象存储 + file_meta(biz_type=1)，返回的 url 是**免登录直链**——
 * 访客端没有登录态，必须能直接当 img src 用。</p>
 */
export function uploadBotAvatar(file: File): Promise<BotAvatarUploadResult> {
  const form = new FormData()
  form.append('file', file)
  return request<BotAvatarUploadResult>({
    url: '/customer/bot/avatar',
    method: 'post',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/** 调试：这句话在当前生效版本里会判成哪个意图 */
export function matchBotIntent(question: string): Promise<{
  versionNo: string | null
  match: { intentCode: string; intentName: string; sample: string; score: number } | null
  minScore: number
}> {
  return request({ url: '/ai/v1/bot/training/match', method: 'get', params: { question } })
}
