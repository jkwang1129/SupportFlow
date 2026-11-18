/**
 * 企业侧「角色 -> 菜单」分配：菜单渲染（AdminLayout）与路由守卫（router）共用一份规则。
 *
 * <p>为什么要单独一个文件：既要“看不到入口”，也要“直接改地址栏也进不去”。
 * 两处各写一份映射迟早会对不上，所以规则只写在这里一份。</p>
 *
 * <p>三条约定（与后端口径一致，也和平台侧菜单权限一致）：</p>
 * <ul>
 *   <li><b>ADMIN 不在表里</b>：管理员默认全开；</li>
 *   <li>角色码为空、或表里没有这个角色（老后端没回角色码 / 租户自定义角色）默认全开，
 *       藏菜单比多显示一条更容易被当成“功能没了”（真踩过）；</li>
 *   <li>安全靠后端各接口自己的角色校验，这里只是把入口收起来。</li>
 * </ul>
 */

/** 企业侧菜单键（与 AdminLayout 里每一项的 canMenu('...') 一一对应） */
export const ROLE_MENUS: Record<string, string[]> = {
  // 客服主管：一线作业 + 质检 + 坐席绩效（看得到数据报表，但不管渠道与订单）
  SUPERVISOR: ['workbench.online', 'workbench.bot',
    'ops.kb', 'ops.tickets', 'ops.customers', 'ops.qa', 'ops.qaAlerts', 'config.metrics'],
  // 高级客服：一线接待与跟进，参与机器人话术的日常调整
  SENIOR_AGENT: ['workbench.online', 'workbench.bot', 'ops.kb', 'ops.tickets', 'ops.customers'],
  // 客服专员（在线教育租户里叫「课程顾问」）：只做一线接待与跟进，不碰机器人配置
  AGENT: ['workbench.online', 'ops.kb', 'ops.tickets', 'ops.customers'],
  // 质检专员：围着质检与工单转，不接触配置与账单
  QUALITY: ['workbench.online', 'ops.tickets', 'ops.customers', 'ops.qa', 'ops.qaAlerts'],
  // AI 运营：维护机器人与知识库
  AI_OPERATOR: ['workbench.online', 'workbench.bot', 'workbench.training', 'ops.kb', 'ops.tickets'],
}

/**
 * 这个角色能不能看到某个菜单。
 *
 * <p>管理员（或角色未知）一律放行；其余角色按 ROLE_MENUS 白名单。</p>
 */
export function canMenu(role: string | null | undefined, key: string): boolean {
  if (!role || role === 'ADMIN') {
    return true
  }
  const allowed = ROLE_MENUS[role]
  return !allowed || allowed.includes(key)
}

/** 一个人可能挂多个角色：只要有一个角色放行就放行（与后端按角色“或”的判定一致） */
export function canMenuForRoles(roles: string[] | null | undefined, key: string): boolean {
  if (!roles || roles.length === 0) {
    return true
  }
  return roles.some((role) => canMenu(role, key))
}

/**
 * 路由 -> 菜单键：命中就说明“进这个页面得先有这个菜单权限”。
 *
 * <p>只登记管理类页面（机器人配置 / 渠道配置 / 账单 / 质检 / 团队）。日常作业页（在线客服、工单、客户 360）
 * 不在这里卡：它们本来就对所有一线角色开放，多一层判断只会带来“被莫名弹回”的风险。</p>
 */
const ROUTE_MENUS: Array<{ prefix: string; key: string }> = [
  { prefix: '/channels', key: 'config.channels' },
  { prefix: '/modules/orders', key: 'config.orders' },
  { prefix: '/modules/upgrade', key: 'config.orders' },
  { prefix: '/modules/analytics', key: 'config.metrics' },
  { prefix: '/dashboard/realtime', key: 'config.metrics' },
  { prefix: '/modules/bot', key: 'workbench.bot' },
  { prefix: '/training', key: 'workbench.training' },
  { prefix: '/modules/qa/alerts', key: 'ops.qaAlerts' },
  { prefix: '/modules/qa', key: 'ops.qa' },
  { prefix: '/members', key: 'team.members' },
  { prefix: '/skill-groups', key: 'team.skillGroups' },
]

/** 这个路由对应的菜单键；不受菜单管控的页面返回 null */
export function menuKeyOfPath(path: string): string | null {
  const hit = ROUTE_MENUS.find((rule) => path === rule.prefix || path.startsWith(rule.prefix + '/'))
  return hit ? hit.key : null
}
