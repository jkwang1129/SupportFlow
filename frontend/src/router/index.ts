import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'
import { canMenuForRoles, menuKeyOfPath } from '../utils/menu-permission'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/login/index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('../views/register/index.vue'),
      meta: { title: '注册企业账号' },
    },
    {
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('../views/forgot-password/index.vue'),
      meta: { title: '找回密码', public: true },
    },
    {
      path: '/',
      component: () => import('../layouts/AdminLayout.vue'),
      redirect: '/entry',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('../views/dashboard/index.vue'),
          meta: { title: '数据概览' },
        },
        {
          path: 'enterprise',
          name: 'EnterpriseReview',
          component: () => import('../views/enterprise/index.vue'),
          meta: { title: '企业信息' },
        },
        {
          path: 'admin/reviews',
          name: 'PlatformReview',
          component: () => import('../views/admin/review/index.vue'),
          meta: { title: '企业审核', requiresPlatform: true },
        },
        {
          // 平台侧：企业提给平台的支持工单（跨租户）
          path: 'platform/support',
          name: 'PlatformSupportTickets',
          component: () => import('../views/platform/support/index.vue'),
          meta: { title: '支持工单', requiresPlatform: true },
        },
        {
          // 平台侧：租户管理（多租户隔离与限流：RLS 状态 / 独立库升级 / 租户级限流）
          path: 'platform/tenants',
          name: 'PlatformTenants',
          component: () => import('../views/platform/tenants/index.vue'),
          meta: { title: '租户管理', requiresPlatform: true },
        },
        {
          // 平台侧：计费与账单（套餐 / 用量计量 / 账期结算）
          path: 'platform/billing',
          name: 'PlatformBilling',
          component: () => import('../views/platform/billing/index.vue'),
          meta: { title: '计费与账单', requiresPlatform: true },
        },
        {
          // 平台侧：收款流水（真实收到的钱，来自已支付订单；与账期账单是两个口径）
          path: 'platform/payments',
          name: 'PlatformPayments',
          component: () => import('../views/platform/payments/index.vue'),
          meta: { title: '收款流水', requiresPlatform: true },
        },
        {
          // 平台侧：用户管理（平台账号中心：平台管理员 + 企业用户统一管理）
          path: 'platform/users',
          name: 'PlatformUsers',
          component: () => import('../views/platform/users/index.vue'),
          meta: { title: '用户管理', requiresPlatform: true },
        },
        {
          // 平台侧：角色权限（平台角色 + 菜单权限，与租户侧相互独立）
          path: 'platform/roles',
          name: 'PlatformRoles',
          component: () => import('../views/platform/roles/index.vue'),
          meta: { title: '角色权限', requiresPlatform: true },
        },
        {
          // 平台侧：审计日志（全平台操作留痕，只追加；与「安全中心」是两件事）
          path: 'platform/audit',
          name: 'PlatformAudit',
          component: () => import('../views/platform/audit/index.vue'),
          meta: { title: '审计日志', requiresPlatform: true },
        },
        {
          // 平台侧：平台设置（注册审核策略 / 套餐价格 / 支付配置 / 平台公告）
          path: 'platform/settings',
          name: 'PlatformSettings',
          component: () => import('../views/platform/settings/index.vue'),
          meta: { title: '平台设置', requiresPlatform: true },
        },
        {
          // 平台侧：官网配置（可视化编辑官网品牌与内容 → 预览 → 发布）
          path: 'platform/sitecfg',
          name: 'PlatformSiteConfig',
          component: () => import('../views/platform/sitecfg/index.vue'),
          meta: { title: '官网配置', requiresPlatform: true },
        },
        {
          // 平台侧：平台数据（平台经营分析：租户增长 / 套餐分布 / 平台收入）
          path: 'platform/analytics',
          name: 'PlatformAnalytics',
          component: () => import('../views/platform/analytics/index.vue'),
          meta: { title: '平台数据', requiresPlatform: true },
        },
        {
          // 平台侧：AI 监控（调用量 / Token 成本 / 质量与安全 / 租户配额）
          path: 'platform/aimon',
          name: 'PlatformAiMonitor',
          component: () => import('../views/platform/aimon/index.vue'),
          meta: { title: 'AI 监控', requiresPlatform: true },
        },
        {
          // 平台侧：客户成功（租户健康度 / 续费风险 / 扩展商机 / 回访任务）
          path: 'platform/cs',
          name: 'PlatformCustomerSuccess',
          component: () => import('../views/platform/cs/index.vue'),
          meta: { title: '客户成功', requiresPlatform: true },
        },
        {
          // 平台侧：安全中心（安全策略 / API 密钥 / 安全事件 / 导出审批）
          path: 'platform/sec',
          name: 'PlatformSecurity',
          component: () => import('../views/platform/sec/index.vue'),
          meta: { title: '安全中心', requiresPlatform: true },
        },
        {
          // 平台侧：可观测（告警规则 / 告警事件 / 成本预算 / 调用链）
          path: 'platform/observe',
          name: 'PlatformObservability',
          component: () => import('../views/platform/observe/index.vue'),
          meta: { title: '可观测', requiresPlatform: true },
        },
        {
          // 企业侧：版本与升级（照原型：hero + 套餐对比 + 能力对比表 + FAQ + 下单支付）
          path: 'modules/upgrade',
          name: 'ModuleUpgrade',
          component: () => import('../views/billing/upgrade.vue'),
          meta: { title: '版本与升级' },
        },
        {
          // 企业侧：我的订单（照原型：概览 + 订单列表；账单是本系统真实的账期结算数据）
          path: 'modules/orders',
          name: 'ModuleOrders',
          component: () => import('../views/billing/orders.vue'),
          meta: { title: '我的订单' },
        },
        {
          // 收银台：订单确认 → 支付确认 → 支付 → 结果（从升级页 / 我的订单进入）
          path: 'billing/checkout',
          name: 'BillingCheckout',
          component: () => import('../views/billing/checkout.vue'),
          meta: { title: '购买套餐' },
        },
        {
          path: 'profile',
          name: 'Profile',
          component: () => import('../views/profile/index.vue'),
          meta: { title: '个人中心' },
        },
        {
          // 企业侧：消息中心独立页面（公告 / 工单提醒 / 系统通知；右上角铃铛抽屉点"查看全部消息"进来）
          path: 'messages',
          name: 'Messages',
          component: () => import('../views/messages/index.vue'),
          meta: { title: '消息中心' },
        },
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('../views/channels/index.vue'),
          meta: { title: '渠道接入' },
        },
        {
          path: 'channels/setup/:stage/:channelId?',
          name: 'ChannelSetup',
          component: () => import('../views/channels/setup.vue'),
          meta: { title: '渠道接入流程' },
        },
        {
          // 渠道接入文档：六种渠道的接入方式 + 开放接口 + 安全规范 + 联调排错
          path: 'channels/docs',
          name: 'ChannelDocs',
          component: () => import('../views/channels/docs.vue'),
          meta: { title: '渠道接入文档' },
        },
        {
          // 原生渠道接入配置（App / 400 热线 / 邮件）：字段由后端 schema 给，页面动态渲染
          path: 'channels/native/:channelId',
          name: 'ChannelNative',
          component: () => import('../views/channels/native.vue'),
          meta: { title: '原生渠道接入' },
        },
        {
          // 微信公众号 / 小程序原生接入配置（服务器配置 + 网页授权 + 模板消息）
          path: 'channels/wechat/:channelId',
          name: 'ChannelWeChat',
          component: () => import('../views/channels/wechat.vue'),
          meta: { title: '微信原生接入' },
        },
        {
          path: 'modules/bot',
          name: 'BotConfig',
          component: () => import('../views/bot/index.vue'),
          meta: { title: '智能机器人' },
        },
        {
          // 企业侧：模型训练中心（与智能机器人页里的「模型训练」页签同一份能力，
          // 单独给一个路由是为了在左侧菜单里能直接点进来）
          path: 'training',
          name: 'BotTraining',
          component: () => import('../views/training/index.vue'),
          meta: { title: '模型训练' },
        },
        {
          path: 'members',
          name: 'TeamMembers',
          component: () => import('../views/members/index.vue'),
          meta: { title: '成员管理' },
        },
        {
          path: 'skill-groups',
          name: 'SkillGroups',
          component: () => import('../views/skill-groups/index.vue'),
          meta: { title: '技能组' },
        },
        {
          path: 'modules/settings',
          redirect: '/members',
        },
        {
          path: 'modules/kb',
          name: 'KnowledgeBase',
          component: () => import('../views/kb/index.vue'),
          meta: { title: '企业知识库' },
        },
        {
          path: 'modules/qa',
          name: 'QualityInspection',
          component: () => import('../views/qa/index.vue'),
          meta: { title: '质检中心' },
        },
        {
          path: 'modules/qa/alerts',
          name: 'QaAlerts',
          component: () => import('../views/qa/alerts.vue'),
          meta: { title: '实时预警' },
        },
        {
          path: 'modules/qa/rules',
          name: 'QaRules',
          component: () => import('../views/qa/rules.vue'),
          meta: { title: '质检规则配置' },
        },
        {
          path: 'modules/tickets',
          name: 'Tickets',
          component: () => import('../views/tickets/index.vue'),
          meta: { title: '工单中心' },
        },
        {
          // 客户 360：客户列表；点进去是画像 + 标签体系 + 会话轨迹
          path: 'modules/customers',
          name: 'Customers',
          component: () => import('../views/customers/index.vue'),
          meta: { title: '客户 360' },
        },
        {
          path: 'modules/customers/tags',
          name: 'CustomerTags',
          component: () => import('../views/customers/tags.vue'),
          meta: { title: '标签体系' },
        },
        {
          // 坐席绩效报表（数据报表）：只有管理员 / 主管能看，后端会再判一次
          path: 'modules/analytics',
          name: 'Analytics',
          component: () => import('../views/analytics/agents.vue'),
          meta: { title: '坐席绩效' },
        },
        {
          // 实时数据大屏：深色大屏，独立路由（也可投到会议室大屏）
          path: 'dashboard/realtime',
          name: 'RealtimeScreen',
          component: () => import('../views/analytics/realtime.vue'),
          meta: { title: '实时数据大屏' },
        },
        {
          path: 'modules/workspace',
          name: 'Workspace',
          component: () => import('../views/workspace/index.vue'),
          meta: { title: '在线客服' },
        },
      ],
    },
    {
      path: '/entry',
      name: 'Entry',
      component: () => import('../views/entry/index.vue'),
      meta: { title: '进入后台' },
    },
    {
      path: '/guide',
      name: 'Guide',
      component: () => import('../views/guide/index.vue'),
      meta: { title: '企业开通引导' },
    },
    {
      // 渠道效果演示中心：六个渠道接入后客户看到的样子（照原型 channel-demos.html）
      path: '/channel-demos',
      name: 'ChannelDemos',
      component: () => import('../views/channels/demos/index.vue'),
      meta: { title: '渠道效果演示' },
    },
    {
      // 单个渠道的效果演示（照原型 channel-web / channel-wechat / … 六张页面）
      path: '/channel-demos/:key',
      name: 'ChannelDemoDetail',
      component: () => import('../views/channels/demos/detail.vue'),
      meta: { title: '渠道效果演示' },
    },
    {
      // 访客端：企业把 /visitor?key=渠道密钥 嵌到自己官网/小程序里
      path: '/visitor',
      name: 'Visitor',
      component: () => import('../views/visitor/index.vue'),
      meta: { title: '在线客服', public: true },
    },
    {
      path: '/invite/accept/:code',
      name: 'InviteAccept',
      component: () => import('../views/invite/accept.vue'),
      meta: { title: '接受客服成员邀请', public: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('../views/error/404.vue'),
    },
  ],
})

// 简单路由守卫：未登录跳登录页
router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (!['Login', 'Register', 'ForgotPassword'].includes(String(to.name)) && !getToken()) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  // 平台侧页面要过一层「菜单权限」：权限没拉回来之前先放行（避免刷新时被踢），
  // 拿到权限之后再用 perm_code（= 路由 path）比对——直接改地址栏也进不去没权限的页。
  if (to.meta.requiresPlatform && isPermDenied(to.path)) {
    return { path: '/entry' }
  }
  // 企业侧同理：管理类页面（渠道接入 / 我的订单 / 坐席绩效 / 质检 / 团队…）按角色收口，
  // 一线角色（课程顾问这类）改地址栏也进不去；没登记的路由不受影响。
  // 落点用「数据概览」而不是 /entry：企业成员本来就能进后台，没必要再绕一层分流。
  if (isEnterpriseMenuDenied(to.path)) {
    return { path: '/dashboard' }
  }
  return true
})

/**
 * 平台菜单权限判断：只有平台账号 + 权限已加载 + 明确不在权限里，才算「没权限」。
 *
 * <p>用 store 时要延迟拿到实例（路由模块在 pinia 之前被 import），
 * 所以这里放在函数体里而不是模块顶层。</p>
 */
function isPermDenied(path: string): boolean {
  const store = useUserStore()
  if (store.userType !== 1) return false
  if (!store.permsLoaded) return false
  return !store.hasPerm(path)
}

/**
 * 企业侧菜单权限判断：只在能确定角色、且菜单明确不给时才拦。
 *
 * <p>角色还没拉回来（刚刷新）或没有角色数据时一律放行——和平台侧一样，
 * 宁可让人多看一眼页面，也别把人莫名弹回入口页。</p>
 */
function isEnterpriseMenuDenied(path: string): boolean {
  const store = useUserStore()
  if (store.userType !== 2) return false
  const key = menuKeyOfPath(path)
  if (!key) return false
  if (!store.permsLoaded || store.roles.length === 0) return false
  return !canMenuForRoles(store.roles, key)
}

export default router
