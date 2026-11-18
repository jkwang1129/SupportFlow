/**
 * 渠道效果演示中心的落地数据。
 *
 * 一个渠道一条记录，页面只负责渲染（演示中心卡片 + 单个渠道的效果演示页都读它）：
 *   · 六个渠道的**原生通道都已实现**（网站挂件 / 公众号与小程序回调 / App webhook + 推送 /
 *     400 呼叫事件 / 邮件进线与发信），所以数据里不再保留"能不能接入"这种开关——
 *     它只会随着实现推进变成过期文案（徽标统一是「已接入」）；
 *   · `channelType`：对应后端真实的渠道类型（1 网站 / 2 公众号 / 3 小程序），
 *     演示中心用它把"真实渠道"（渠道名、AppID、今日会话）贴到对应卡片上；
 *   · `points` / `integrate`：演示要点与接入方式，跟原型页面一一对应。
 */

export interface DemoPoint {
  title: string
  desc: string
}

/** 会话里可插入的卡片：公众号图文 / 订单 / 商品（照原型 channel-wechat 等页面的卡片形态） */
export interface ChatCard {
  kind: 'wx' | 'order' | 'product'
  title: string
  lines?: string[]
  action?: string
  /** 卡片封面底色 */
  cover?: string
  /** 封面图标（Element Plus 图标名） */
  icon?: string
}

export interface ChannelDemoSpec {
  /** 路由/组件里用的键：web / wechat / miniapp / app / phone / email */
  key: string
  /** 卡片标题 */
  title: string
  /** 卡片角标，例如 WEB · 网站 */
  tag: string
  /** 一句话卖点 */
  tagline: string
  /** 正文描述 */
  desc: string
  /** 顶部横幅渐变 */
  banner: string
  /** 横幅上的纹理（照原型每张卡片内联的那段 SVG） */
  patternSvg: string
  /** Element Plus 图标名 */
  icon: string
  /** 对应的后端渠道类型（仅 1/2/3 有） */
  channelType?: number
  /** 卡片上的三个示例指标：照原型 channel-demos.html 的示例数据（演示页不做真实统计） */
  demoStats: { sessions: number; csat: string; bot: string }
  /** 详情页左上角徽标文案，例如「网站渠道 · 已接入」（照原型各渠道页） */
  badge: string
  /** 详情页主按钮文案与动作（照原型各渠道页的按钮） */
  actionLabel: string
  action: 'fullscreen' | 'reset' | 'ask' | 'card' | 'ivr'
  actionArg?: string
  /** 演示要点（4 条） */
  points: DemoPoint[]
  /** 访客端会话的拟真内容：机器人名、开场白、快捷提问 */
  chat: {
    agentName: string
    greeting: string
    quick: string[]
  }
}

export const channelDemos: ChannelDemoSpec[] = [
  {
    key: 'web',
    title: '网站在线客服',
    tag: 'WEB · 网站',
    tagline: '官网悬浮组件 · 机器人接待 · 知识推荐 · 转人工 · 满意度评价',
    desc: '在官网任意页面嵌入一行 SDK 代码，右下角就会出现悬浮客服组件。客户不用离开页面就能咨询，会话自动进入统一工作台。',
    banner: 'linear-gradient(135deg,#3b82f6,#2563eb)',
    patternSvg:
      '<defs><pattern id="p-web" width="22" height="22" patternUnits="userSpaceOnUse"><circle cx="3" cy="3" r="1.5" fill="#fff"/></pattern></defs><rect width="100" height="100" fill="url(#p-web)"/>',
    icon: 'Monitor',
    channelType: 1,
    demoStats: { sessions: 486, csat: '96.2%', bot: '71%' },
    badge: '网站渠道 · 已接入',
    actionLabel: '全屏体验',
    action: 'fullscreen',
    points: [
      { title: '机器人秒回', desc: '智能助手 7×24 小时在线，先接住再判断要不要转人工' },
      { title: '知识卡片推荐', desc: '命中知识库后自动附上原文入口，回答有依据' },
      { title: '一键转人工', desc: '客户回复「转人工」即进入排队，坐席端带上下文接入' },
      { title: '满意度评价', desc: '会话结束后自动邀评，低分进入质检与回访' },
    ],
    chat: {
      agentName: '小云智能客服',
      greeting: '您好，我是智能客服小云，物流、退换、发票、会员积分都可以直接问我，需要人工时回复「转人工」。',
      quick: ['快递到哪了？', '怎么退货？', '发票抬头怎么改？', '转人工'],
    },
  },
  {
    key: 'wechat',
    title: '微信公众号',
    tag: 'WECHAT · 公众号',
    tagline: '菜单触发咨询 · 自动回复 · 模板消息推送 · 订单卡片',
    desc: '客户在公众号菜单发起咨询，会话自动接入统一客服平台；机器人先接待，并可推送模板消息（物流提醒、工单进展、优惠券到账）。',
    banner: 'linear-gradient(135deg,#07c160,#059669)',
    patternSvg:
      '<defs><pattern id="p-wx" width="30" height="30" patternUnits="userSpaceOnUse"><circle cx="15" cy="15" r="7" fill="none" stroke="#fff" stroke-width="1.2"/></pattern></defs><rect width="100" height="100" fill="url(#p-wx)"/>',
    icon: 'ChatDotRound',
    channelType: 2,
    demoStats: { sessions: 329, csat: '97.1%', bot: '74%' },
    badge: '微信公众号 · 已接入',
    actionLabel: '重新体验',
    action: 'reset',
    points: [
      { title: '菜单触发咨询', desc: '底部菜单一键发起会话，不用教客户怎么找客服' },
      { title: '模板消息推送', desc: '物流异常、工单进展自动发关怀提醒，客户不催也安心' },
      { title: '订单卡片', desc: '点「我的订单」直接查看物流卡片，减少无效问询' },
      { title: '转人工', desc: '公众号里同样能排队转人工，坐席看到完整上下文' },
    ],
    chat: {
      agentName: 'SupportFlow 商城服务号',
      greeting: '您好，这里是SupportFlow 商城服务号，回复问题即可咨询，也可以点下方菜单「客服咨询」进入人工。',
      quick: ['我的订单到哪了？', '申请退款', '优惠券怎么用？', '转人工'],
    },
  },
  {
    key: 'miniapp',
    title: '微信小程序',
    tag: 'MINIAPP · 小程序',
    tagline: '快捷问题 · 订单卡片 · 售后工单直达 · 转人工',
    desc: '小程序内嵌客服会话，支持快捷问题、订单卡片与售后工单直达，机器人识别意图后自动处理，复杂问题一键转人工。',
    banner: 'linear-gradient(135deg,#2563eb,#38bdf8)',
    patternSvg:
      '<defs><pattern id="p-mp" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M4 4h8M12 4v8" stroke="#fff" stroke-width="1.3" fill="none"/></pattern></defs><rect width="100" height="100" fill="url(#p-mp)"/>',
    icon: 'Iphone',
    channelType: 3,
    demoStats: { sessions: 268, csat: '95.8%', bot: '69%' },
    badge: '微信小程序 · 已接入',
    actionLabel: '体验转人工',
    action: 'ask',
    actionArg: '转人工',
    points: [
      { title: '快捷问题', desc: '查物流、退换货、开发票点一下就问，不用打字' },
      { title: '订单卡片', desc: '会话里直接发订单卡片，客户点开看物流、点售后' },
      { title: '售后直达', desc: '卡片内一键申请售后，自动创建工单并开始 SLA 计时' },
      { title: '转人工', desc: '复杂问题转人工，坐席接手时已看到机器人聊过的内容' },
    ],
    chat: {
      agentName: 'SupportFlow 商城小程序客服',
      greeting: '您好，我是小程序里的智能客服，可以直接查物流、申请售后、开发票。',
      quick: ['查物流', '申请退款', '开发票', '转人工'],
    },
  },
  {
    key: 'app',
    title: '官方 App 客服中心',
    tag: 'APP · 客户端',
    tagline: 'IM 会话 · 商品卡片 · 快捷工具 · 会话结束满意度评价',
    desc: 'App 内置完整客服中心：IM 会话、快捷入口、商品卡片、订单协同，会话结束自动弹出满意度评价，低分自动触发挽回流程。',
    banner: 'linear-gradient(135deg,#7c3aed,#2563eb)',
    patternSvg:
      '<defs><pattern id="p-app" width="26" height="26" patternUnits="userSpaceOnUse"><rect x="4" y="4" width="9" height="9" rx="2.5" fill="none" stroke="#fff" stroke-width="1.2"/></pattern></defs><rect width="100" height="100" fill="url(#p-app)"/>',
    icon: 'Cellphone',
    demoStats: { sessions: 412, csat: '96.9%', bot: '72%' },
    badge: '官方 App · 已接入',
    actionLabel: '体验商品卡片',
    action: 'card',
    points: [
      { title: '快捷工具', desc: '商品、订单、售后一步直达，减少输入' },
      { title: '商品卡片', desc: '客服推送商品，客户可以直接加购，服务带转化' },
      { title: '满意度评价', desc: '会话结束自动邀评，可一键联系主管' },
      { title: '低分挽回', desc: '1~2 星自动建工单并通知主管，问题不过夜' },
    ],
    chat: {
      agentName: 'SupportFlow App 客服中心',
      greeting: '您好，这里是 App 客服中心，可以查物流、看商品、申请售后。',
      quick: ['查物流', '看商品', '申请售后', '转人工'],
    },
  },
  {
    key: 'phone',
    title: '400 电话热线',
    tag: 'PHONE · 热线',
    tagline: 'AI 语音导航 · 按键分流 · 排队转人工 · 通话小结自动建单',
    desc: 'AI 语音导航自动分流：订单、售后、投诉、人工。高峰期自动排队并支持回呼，通话录音实时转写，通话结束后自动生成服务小结与工单。',
    banner: 'linear-gradient(135deg,#0ea5e9,#0891b2)',
    patternSvg:
      '<defs><pattern id="p-phone" width="26" height="26" patternUnits="userSpaceOnUse"><circle cx="13" cy="13" r="2.4" fill="#fff"/></pattern></defs><rect width="100" height="100" fill="url(#p-phone)"/>',
    icon: 'PhoneFilled',
    demoStats: { sessions: 187, csat: '94.5%', bot: '46%' },
    badge: '400 电话热线 · 已接入',
    actionLabel: '体验语音导航',
    action: 'ivr',
    points: [
      { title: 'AI 语音导航', desc: '按键即可分流，高峰期不用干等' },
      { title: '上下文随行', desc: '转人工后坐席已经知道客户选了什么诉求' },
      { title: '通话小结', desc: '挂机自动生成纪要并创建工单，不用手工记' },
      { title: '忙时回呼', desc: '排队超过阈值改回呼，客户不用贴在电话旁' },
    ],
    chat: {
      agentName: '400 语音导航',
      greeting: '',
      quick: [],
    },
  },
  {
    key: 'email',
    title: '邮件工单',
    tag: 'EMAIL · 邮件',
    tagline: '自动受理回执 · 规则识别诉求 · 工单与 SLA 跟踪 · 解决后回访',
    desc: '客户发邮件即自动生成工单，秒级回执确认；邮件内容按关键词规则识别出意图与优先级，进入 SLA 时效跟踪，解决后自动回访。',
    banner: 'linear-gradient(135deg,#f59e0b,#ea580c)',
    patternSvg:
      '<defs><pattern id="p-mail" width="28" height="28" patternUnits="userSpaceOnUse"><path d="M4 10l6 4 6-4" fill="none" stroke="#fff" stroke-width="1.2"/></pattern></defs><rect width="100" height="100" fill="url(#p-mail)"/>',
    icon: 'Message',
    demoStats: { sessions: 12, csat: '92.0%', bot: '100%' },
    badge: '邮件工单 · 已接入',
    actionLabel: '重新体验',
    action: 'reset',
    points: [
      { title: '自动回执', desc: '邮件到达立即确认受理，客户不会石沉大海' },
      { title: '工单联动', desc: '规则识别诉求并给出建议处理人与优先级' },
      { title: 'SLA 跟踪', desc: '处理进度邮件同步，超时自动升级' },
      { title: '解决后回访', desc: '结单自动回访，满意度回写客户 360' },
    ],
    chat: {
      agentName: '邮件工单机器人',
      greeting: '',
      quick: [],
    },
  },
]

export function demoByKey(key?: string | null) {
  return channelDemos.find((item) => item.key === key)
}

export function demoByChannelType(channelType?: number | null) {
  return channelDemos.find((item) => item.channelType === channelType)
}
