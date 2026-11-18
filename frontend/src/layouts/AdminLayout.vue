<template>
  <el-container class="admin-layout">
    <el-aside ref="asideRef" width="220px" class="admin-aside pro-scroll pro-scroll--dark">
      <div class="admin-logo">
        <img class="admin-logo-img" src="/supportflow-mark.svg" alt="SupportFlow" />
        <span>SupportFlow 智能客服</span>
      </div>
      <el-menu
        router
        :default-active="$route.path"
        class="admin-menu"
        background-color="#0b1220"
        text-color="#8a97ad"
        active-text-color="#ffffff"
      >
        <el-menu-item v-if="userStore.userType === 2" index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>数据概览</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/admin/reviews')" index="/admin/reviews">
          <el-icon><Stamp /></el-icon>
          <span>企业审核</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/support')" index="/platform/support">
          <el-icon><Tickets /></el-icon>
          <span>支持工单</span>
          <span v-if="platformTodo" class="menu-tip" :class="platformOverdue ? 'danger' : 'warning'">
            {{ platformOverdue ? `超时 ${platformOverdue}` : `待处理 ${platformTodo}` }}
          </span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/tenants')" index="/platform/tenants">
          <el-icon><OfficeBuilding /></el-icon>
          <span>租户管理</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/billing')" index="/platform/billing">
          <el-icon><Wallet /></el-icon>
          <span>计费与账单</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/payments')" index="/platform/payments">
          <el-icon><Tickets /></el-icon>
          <span>收款流水</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/users')" index="/platform/users">
          <el-icon><UserFilled /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/roles')" index="/platform/roles">
          <el-icon><Key /></el-icon>
          <span>角色权限</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/audit')" index="/platform/audit">
          <el-icon><Document /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/settings')" index="/platform/settings">
          <el-icon><Setting /></el-icon>
          <span>平台设置</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/sitecfg')" index="/platform/sitecfg">
          <el-icon><Monitor /></el-icon>
          <span>官网配置</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/analytics')" index="/platform/analytics">
          <el-icon><TrendCharts /></el-icon>
          <span>平台数据</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/aimon')" index="/platform/aimon">
          <el-icon><Odometer /></el-icon>
          <span>AI 监控</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/cs')" index="/platform/cs">
          <el-icon><Medal /></el-icon>
          <span>客户成功</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/sec')" index="/platform/sec">
          <el-icon><Lock /></el-icon>
          <span>安全中心</span>
        </el-menu-item>
        <el-menu-item v-if="showPlatform('/platform/observe')" index="/platform/observe">
          <el-icon><DataLine /></el-icon>
          <span>可观测</span>
          <span v-if="observabilityPending" class="menu-tip danger">
            待处理 {{ observabilityPending }}
          </span>
        </el-menu-item>
        <template v-if="userStore.userType === 2">
          <el-menu-item v-if="canManageMembers" index="/enterprise">
            <el-icon><OfficeBuilding /></el-icon>
            <span>企业信息</span>
            <span v-if="tipFor('enterprise')" class="menu-tip" :class="tipFor('enterprise').type">{{ tipFor('enterprise').text }}</span>
          </el-menu-item>
          <el-sub-menu index="workbench">
            <template #title>
              <span class="group-label">工作台</span>
            </template>
            <el-menu-item v-if="canMenu('workbench.online')" index="/modules/workspace"><el-icon><ChatDotRound /></el-icon><span>在线客服</span></el-menu-item>
            <el-menu-item v-if="canMenu('workbench.bot')" index="/modules/bot"><el-icon><Cpu /></el-icon><span>智能机器人</span><span v-if="tipFor('bot')" class="menu-tip" :class="tipFor('bot').type">{{ tipFor('bot').text }}</span></el-menu-item>
            <el-menu-item v-if="canMenu('workbench.training')" index="/training"><el-icon><MagicStick /></el-icon><span>模型训练</span></el-menu-item>
          </el-sub-menu>
          <el-sub-menu v-if="canManageMembers" index="team">
            <template #title>
              <span class="group-label">团队管理</span>
            </template>
            <el-menu-item index="/members"><el-icon><UserFilled /></el-icon><span>成员管理</span><span v-if="tipFor('member')" class="menu-tip" :class="tipFor('member').type">{{ tipFor('member').text }}</span></el-menu-item>
            <el-menu-item index="/skill-groups"><el-icon><Connection /></el-icon><span>技能组</span></el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="ops">
            <template #title><span class="group-label">运营管理</span></template>
            <el-menu-item v-if="canMenu('ops.kb')" index="/modules/kb"><el-icon><Notebook /></el-icon><span>知识库</span></el-menu-item>
            <el-menu-item v-if="canMenu('ops.tickets')" index="/modules/tickets">
              <el-icon><Tickets /></el-icon>
              <span>工单中心</span>
              <span v-if="ticketTodo" class="menu-tip" :class="ticketOverdue ? 'danger' : 'warning'">
                {{ ticketOverdue ? `超时 ${ticketOverdue}` : `待办 ${ticketTodo}` }}
              </span>
            </el-menu-item>
            <el-menu-item v-if="canMenu('ops.customers')" index="/modules/customers"><el-icon><UserFilled /></el-icon><span>客户 360</span></el-menu-item>
            <el-menu-item v-if="canMenu('ops.qa')" index="/modules/qa"><el-icon><DataAnalysis /></el-icon><span>质检中心</span><span v-if="tipFor('qa')" class="menu-tip" :class="tipFor('qa').type">{{ tipFor('qa').text }}</span></el-menu-item>
            <el-menu-item v-if="canMenu('ops.qaAlerts')" index="/modules/qa/alerts"><el-icon><BellFilled /></el-icon><span>实时预警</span><span v-if="alertPending" class="menu-tip warning">{{ alertPending }}</span></el-menu-item>
          </el-sub-menu>
          <el-sub-menu v-if="canAnyMenu('config.metrics', 'config.channels', 'config.orders')" index="config">
            <template #title><span class="group-label">数据与配置</span></template>
            <!-- 「实时大屏」不再放进菜单：它和「数据概览」重复，投屏场景仍可直接访问 /dashboard/realtime -->
            <el-menu-item v-if="canViewMetrics" index="/modules/analytics"><el-icon><Histogram /></el-icon><span>坐席绩效</span></el-menu-item>
            <el-menu-item v-if="canMenu('config.channels')" index="/channels"><el-icon><Connection /></el-icon><span>渠道接入</span><span v-if="tipFor('channel')" class="menu-tip" :class="tipFor('channel').type">{{ tipFor('channel').text }}</span></el-menu-item>
            <!--
              「版本与升级」与「支持工单」都不放企业侧菜单：
              · 版本与升级的内容（套餐对比 / 下单 / 续费）就在「我的订单」页里，两个入口是一回事；
              · 支持工单的入口在「工单中心 → 提交给平台支持」页签，单独再给一个菜单是重复的。
              路由都保留（侧边栏升级卡的按钮、老书签仍可用）。
            -->
            <el-menu-item v-if="canMenu('config.orders')" index="/modules/orders"><el-icon><Wallet /></el-icon><span>我的订单</span></el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>

      <!--
        AI 能力升级引导（原型侧边栏底部那张卡）：
        · 平台账号不显示（平台不需要看企业套餐引导）；
        · 已开通企业版的换文案，按钮改成"进入版本中心"；
        · 计费接口不可用时整张卡不显示——侧边栏是顺带提示，不该因为它报错。
      -->
      <!-- 升级卡是"账单/套餐"这一类的入口：一线角色（课程顾问这类）看不到，和「我的订单」同一口径 -->
      <div v-if="showUpgradeCard && canMenu('config.orders')" class="side-upgrade">
        <div class="u-title">{{ enterprisePlan ? '企业版已开通' : 'AI 能力升级' }}</div>
        <div class="u-text">
          {{ enterprisePlan
            ? '已解锁大模型多轮对话与全量质检，尽情使用'
            : '开通企业版，解锁大模型多轮对话与全量质检。' }}
        </div>
        <button class="u-btn" @click="router.push('/modules/upgrade')">
          {{ enterprisePlan ? '进入版本中心' : '查看企业版方案' }}
        </button>
      </div>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item>{{ $route.meta.title || '工作台' }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="tb-actions">
          <!-- 消息中心 -->
          <el-tooltip content="消息中心" placement="bottom">
            <button class="icon-btn" @click="notifyOpen = true">
              <el-icon :size="18"><Bell /></el-icon>
              <span v-if="unreadCount > 0" class="notif-count">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
            </button>
          </el-tooltip>

          <span class="tb-divider"></span>

          <!-- 用户菜单 -->
          <el-dropdown trigger="click" @command="handleUserCommand">
            <div class="tb-user">
              <div class="avatar-sm" :class="avatarClass">
                {{ avatarText }}
                <span class="st online"></span>
              </div>
              <div class="user-text">
                <div class="user-name">
                  {{ userStore.name || '未登录' }}
                  <!-- 身份标识：企业账号显示当前套餐（免费版也显示），平台账号显示"平台" -->
                  <span v-if="identityBadge" class="plan-badge" :class="identityBadge.tone">
                    {{ identityBadge.text }}
                  </span>
                </div>
                <div class="user-role">{{ roleText }}</div>
              </div>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu class="user-menu">
                <div class="drop-head">
                  <div class="drop-name">{{ userStore.name }}</div>
                  <div class="drop-no mono">{{ userStore.userNo }}</div>
                  <div v-if="identityBadge" class="drop-plan">
                    <span class="plan-badge" :class="identityBadge.tone">{{ identityBadge.text }}</span>
                    <span v-if="planExpireText" class="drop-expire">到期 {{ planExpireText }}</span>
                  </div>
                </div>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon>
                  个人中心
                </el-dropdown-item>
                <el-dropdown-item v-if="userStore.userType === 2" command="messages">
                  <el-icon><Bell /></el-icon>
                  消息中心
                  <span v-if="unreadCount > 0" class="drop-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
                </el-dropdown-item>
                <el-dropdown-item command="password">
                  <el-icon><Lock /></el-icon>
                  修改密码
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided class="drop-logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 消息中心抽屉 -->
    <el-drawer v-model="notifyOpen" title="消息中心" size="440px" class="notify-drawer"
               @open="loadNotifications">
      <div class="notify-tabs">
        <button
          v-for="tab in notifyTabs"
          :key="tab.key"
          class="notify-tab"
          :class="{ active: notifyTab === tab.key }"
          @click="notifyTab = tab.key"
        >
          {{ tab.label }}
          <span v-if="tabCount(tab.key)" class="tab-count">{{ tabCount(tab.key) }}</span>
        </button>
      </div>

      <div class="notify-list">
        <div
          v-for="item in filteredNotices"
          :key="item.id"
          class="notify-item"
          :class="{ unread: !item.read }"
          @click="markRead(item)"
        >
          <div class="notify-icon" :class="`type-${item.type}`">
            <el-icon :size="16">
              <component :is="iconOf(item.type)" />
            </el-icon>
          </div>
          <div class="notify-body">
            <div class="notify-title">
              {{ item.title }}
              <span v-if="!item.read" class="unread-dot"></span>
            </div>
            <div class="notify-text">{{ item.text }}</div>
            <div class="notify-time">{{ item.time }}</div>
          </div>
        </div>
        <el-empty v-if="!filteredNotices.length" description="暂无消息" :image-size="90" />
      </div>

      <template #footer>
        <div class="notify-footer">
          <span class="muted-text">共 {{ totalCount }} 条 · {{ unreadCount }} 条未读</span>
          <div class="notify-footer-actions">
            <el-button size="small" :disabled="unreadCount === 0" @click="markAllRead">
              全部已读
            </el-button>
            <el-button size="small" type="primary" @click="openMessageCenter">
              查看全部消息
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 安全水印：账号 + 时间，按「安全中心」策略开关；pointer-events:none 不挡操作 -->
    <div v-if="watermarkEnabled" class="sec-watermark" aria-hidden="true">
      <span v-for="row in watermarkRows" :key="row" class="wm-row">{{ row }}</span>
    </div>

    <!-- 修改密码 -->
    <ChangePasswordDialog v-model="pwdVisible" />

    <!-- 退出登录确认 -->
    <LogoutConfirmDialog
      v-model="logoutVisible"
      :name="userStore.name || ''"
      :account="userStore.userNo || ''"
      @confirm="doLogout"
    />
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getEnterpriseGuide } from '../api/enterprise'
import { listChannels } from '../api/customer/channel'
import { fetchPlans, fetchUsage } from '../api/billing'
import { getBotSetting } from '../api/ai/bot'
import { fetchQaAlerts } from '../api/customer/qa'
import { fetchTicketOverview } from '../api/customer/ticket'
import { fetchPlatformTicketOverview } from '../api/platform/ticket'
import { fetchPendingAlertCount } from '../api/platform/observe'
import {
  fetchNotifications,
  fetchNotificationSummary,
  markAllNotificationsRead,
  markNotificationRead,
  notificationRoute,
  type NotificationSummary,
} from '../api/customer/notification'
import { fetchMemberAccess } from '../api/member'
import { fetchMetricsAccess } from '../api/customer/metrics'
import { useUserStore } from '../stores/user'
import { useScrollFade } from '../composables/useScrollFade'
import { canMenu as canMenuOfRole } from '../utils/menu-permission'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'
import LogoutConfirmDialog from '../components/LogoutConfirmDialog.vue'

/**
 * 平台侧菜单是否显示：既要是平台账号，也要有这条菜单的权限。
 *
 * <p>perm_code 就是路由 path（见 schema/user_db_platform.sql），所以这里直接拿 path 比对；
 * 超管（permissions 里带 "*"）全都能看；权限还没拉回来时先显示（hasPerm 里兜底），
 * 避免刷新页面时菜单闪一下空白。</p>
 */
function showPlatform(path: string) {
  return userStore.userType === 1 && userStore.hasPerm(path)
}

/** 抽屉页签：全部，或某一种消息类型 */
type NoticeTabKey = 'all' | 'ticket' | 'announce' | 'system' | 'review' | 'qa' | 'bill'

interface NoticeItem {
  id: string
  type: Exclude<NoticeTabKey, 'all'>
  title: string
  text: string
  time: string
  read: boolean
  /** 全部来自后端消息中心 */
  source?: 'server'
  /** 后端消息的跳转标识：tickets / platform-support */
  linkView?: string | null
}

const router = useRouter()
/** 安全水印与空闲登出：执行点在前端，策略值来自「安全中心 → 安全策略」 */
const watermarkEnabled = ref(true)
const watermarkText = ref('')
let idleTimer = 0
let idleTimeoutMinutes = 120

/** 水印排几行：够铺满屏幕又不花屏 */
const watermarkRows = computed(() => {
  if (!watermarkText.value) {
    return []
  }
  return Array.from({ length: 6 }, () => watermarkText.value)
})

/** 空闲计时：有操作就重置，超时按策略自动登出 */
function resetIdleTimer() {
  if (idleTimer) {
    window.clearTimeout(idleTimer)
  }
  if (!idleTimeoutMinutes || idleTimeoutMinutes <= 0) {
    return
  }
  idleTimer = window.setTimeout(() => {
    userStore.reset()
    localStorage.removeItem('supportflow_admin_token')
    ElMessage.warning(`已超过 ${idleTimeoutMinutes} 分钟未操作，请重新登录`)
    void router.push('/login')
  }, idleTimeoutMinutes * 60 * 1000)
}

/** 拉安全策略（拿不到就沿用默认：水印开、两个小时空闲登出） */
async function loadSecurityPolicy() {
  // 走"登录即可读"的只读接口：平台账号与企业账号都能拿到水印开关与空闲时长。
  // （平台专用的策略接口含失败阈值/IP 白名单，企业账号读它会被 40302 拦，所以不在这里用。）
  try {
    const { request } = await import('../api/request')
    const policy = await request<{ watermarkEnabled: boolean; sessionTimeoutMinutes: number }>({
      url: '/customer/security/console-policy',
      method: 'get',
    })
    watermarkEnabled.value = policy.watermarkEnabled !== false
    idleTimeoutMinutes = Number(policy.sessionTimeoutMinutes ?? 120)
  } catch {
    // 读不到就沿用默认值（水印开、120 分钟）：策略问题不该影响后台能不能用
  }
  const name = userStore.name || '未登录'
  watermarkText.value = `${name} · ${userStore.userNo || ''} · ${new Date().toLocaleString('zh-CN')}`
  resetIdleTimer()
}
/** 侧边菜单滚动时会用到（滚动条用时出现） */
const asideRef = ref<HTMLElement>()
const userStore = useUserStore()

const ONBOARD_DONE_KEY = 'supportflow_onboard_done'

const notifyOpen = ref(false)
const pwdVisible = ref(false)
const logoutVisible = ref(false)
const guideStage = ref('')
const channelRunning = ref(false)
/** 待处理的实时质检预警条数：挂成菜单角标，有没有没处理的预警一眼就看到 */
const alertPending = ref(0)
/** 工单待办：待处理 + 处理中 + 待确认；其中超时单单独标红（客户催之前系统先催） */
const ticketTodo = ref(0)
const ticketOverdue = ref(0)
/** 平台侧支持工单待办：平台账号才拉 */
const platformTodo = ref(0)
const platformOverdue = ref(0)
/** 可观测的待处理告警数：菜单角标（与「可观测」页同一口径，与质检预警是两回事） */
const observabilityPending = ref(0)
const canManageMembers = ref(false)
/** 能不能看数据报表（坐席绩效，以及直接访问的实时大屏）：管理员 + 客服主管，口径与"成员管理"不同 */
const canViewMetrics = ref(false)
/**
 * 当前企业成员的角色码（ADMIN / SUPERVISOR / SENIOR_AGENT / AGENT / QUALITY / AI_OPERATOR）。
 *
 * <p>企业侧菜单原来只分"管理员 / 非管理员"两档：课程顾问这类一线角色照样能看到
 * 「数据与配置 / 渠道接入 / 我的订单」，与实际职责不符。现在按角色分配菜单。</p>
 */
const currentRoleCode = ref('')

/**
 * 企业侧菜单是否显示给我。
 *
 * <p>分配表与判定逻辑在 utils/menu-permission（路由守卫用同一份），这里只把
 * 当前角色码喂进去。</p>
 */
function canMenu(key: string): boolean {
  return canMenuOfRole(currentRoleCode.value, key)
}

/** 菜单分组：组内有一条能用就显示整组，避免出现只有标题的空分组 */
function canAnyMenu(...keys: string[]): boolean {
  return keys.some(canMenu)
}
/** 侧边栏「AI 能力升级」卡片：企业账号才显示；已开通企业版时换文案 */
const showUpgradeCard = ref(false)
const enterprisePlan = ref(false)
/**
 * 机器人是否真的配置过（菜单上的「待配置」角标据此显示）。
 *
 * <p>以前只看浏览器里的本地开关 `supportflow_ob_done_bot`，而机器人配置页保存时没写那个开关，
 * 结果"设置了也保存了，菜单还是待配置"。现在以**后端配置**为准（AI 服务按
 * update_time > create_time 给出 configured），本地开关只作为接口不可用时的兜底。</p>
 */
const botConfigured = ref(false)
/**
 * 右上角身份标识：企业账号显示当前套餐（免费版也显示），平台账号显示"平台"。
 *
 * <p>付费与免费都要有标识——用户一眼能看出"我现在是什么身份/什么档"，
 * 也让"该不该升级"这件事有上下文。</p>
 */
const identityBadge = ref<{ text: string; tone: string } | null>(null)
const planExpireText = ref('')
const currentRoleName = ref('')
const notifyTab = ref<NoticeTabKey>('all')

/** 页签与后端类型码一一对应（顺序就是抽屉里的展示顺序） */
const TABS: Array<{ key: Exclude<NoticeTabKey, 'all'>; code: number }> = [
  { key: 'ticket', code: 2 },
  { key: 'announce', code: 5 },
  { key: 'system', code: 1 },
  { key: 'review', code: 3 },
  { key: 'qa', code: 4 },
  { key: 'bill', code: 6 },
]

/**
 * 抽屉页签 = 全部 + **真有消息的类型**。
 *
 * <p>以前是写死 5 个页签再塞演示数据凑内容；现在类型从后端统计来，
 * 没有的消息类型就不出现——抽屉里的条数跟消息中心页面天然一致。</p>
 */
const notifyTabs = computed(() => {
  const tabs: Array<{ key: NoticeTabKey; label: string }> = [{ key: 'all', label: '全部' }]
  for (const item of TABS) {
    const stat = notifySummary.value?.byType[String(item.code)]
    if (stat && Number(stat.total) > 0) {
      tabs.push({ key: item.key, label: stat.text || notifyTabLabel(item.key) })
    }
  }
  return tabs
})

function notifyTabLabel(key: Exclude<NoticeTabKey, 'all'>) {
  return ({ ticket: '工单提醒', announce: '公告', system: '系统通知',
    review: '审核通知', qa: '质检通知', bill: '账单通知' } as Record<string, string>)[key] ?? key
}

const roleText = computed(() => {
  if (userStore.userType === 1) return '平台管理员'
  if (userStore.userType === 2) return currentRoleName.value || '企业成员'
  return '企业成员'
})

const avatarText = computed(() => (userStore.name || '用').slice(0, 1))

const avatarClass = computed(() => {
  const name = userStore.name || ''
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  }
  return `avatar-hue-${hash % 5}`
})

/**
 * 抽屉里的消息**全部来自后端**（notification 表）。
 *
 * <p>这里以前放了几条写死的演示消息，导致"铃铛里的条数/未读数"跟消息中心页面（真数据）
 * 对不上——两条口径只能有一条是真的，那就只留真的：没有的消息类型，页签自然不出现。</p>
 */
const serverNotices = ref<NoticeItem[]>([])
const notices = computed<NoticeItem[]>(() => serverNotices.value)
/** 后端分类统计：抽屉的条数/未读数以它为准，跟消息中心页面同源 */
const notifySummary = ref<NotificationSummary | null>(null)

const filteredNotices = computed(() => {
  if (notifyTab.value === 'all') return notices.value
  return notices.value.filter((n) => n.type === notifyTab.value)
})

/** 菜单角标的定时器：离开页面时清掉，避免一直挂着 */
let alertTimer = 0
let ticketTimer = 0
let platformTimer = 0
let observabilityTimer = 0

/** 未读数与总条数优先用后端统计（口径与消息中心页面一致），拿不到时用已加载的列表兜底 */
const unreadCount = computed(
  () => notifySummary.value?.all.unread ?? notices.value.filter((n) => !n.read).length,
)
const totalCount = computed(() => notifySummary.value?.all.total ?? notices.value.length)

// 每次切页都重问一次"机器人配置过没有"：在机器人页保存后返回，菜单角标要立刻消失
watch(
  () => router.currentRoute.value.path,
  () => {
    void loadBotConfigured()
    // 消息中心页里标了已读，回来铃铛要立刻少几条，不能等手动刷新
    void loadNotifications()
  },
)

onUnmounted(() => {
  if (idleTimer) {
    window.clearTimeout(idleTimer)
  }
  ;['mousemove', 'keydown', 'click', 'scroll'].forEach((event) =>
    window.removeEventListener(event, resetIdleTimer))
  if (alertTimer) {
    window.clearInterval(alertTimer)
  }
  if (ticketTimer) {
    window.clearInterval(ticketTimer)
  }
  if (platformTimer) {
    window.clearInterval(platformTimer)
  }
  if (observabilityTimer) {
    window.clearInterval(observabilityTimer)
  }
})

// 侧边菜单：滚动条用时出现、闲时隐去
useScrollFade(asideRef)

onMounted(async () => {
  void loadSecurityPolicy()
  ;['mousemove', 'keydown', 'click', 'scroll'].forEach((event) =>
    window.addEventListener(event, resetIdleTimer, { passive: true }))
  try {
    if (!userStore.userId) {
      await userStore.fetchProfile()
    }
    // 侧边栏升级引导：只问一次（套餐不会频繁变），失败就整张卡不显示
    void loadUpgradeCard()
    // 机器人"待配置"角标：按后端真实配置判断
    void loadBotConfigured()
    if (userStore.userType === 1) {
      // 平台账号：支持工单 + 可观测待处理告警两个待办角标
      void loadPlatformTodo()
      platformTimer = window.setInterval(() => void loadPlatformTodo(), 60_000)
      void loadObservabilityPending()
      observabilityTimer = window.setInterval(() => void loadObservabilityPending(), 60_000)
      return
    }
    if (userStore.userType === 2) {
      try {
        const access = await fetchMemberAccess()
        canManageMembers.value = access.canManage
        currentRoleName.value = access.roleName || '企业成员'
        currentRoleCode.value = access.roleCode || ''
      } catch {
        // 老版本后端未提供权限接口时，兼容为企业管理员
        canManageMembers.value = true
        currentRoleName.value = '企业管理员'
      }
    }
    // 经营数据的口径与成员管理不同（成员管理仅管理员，数据报表管理员 + 主管都行），单独问一次。
    // 注意：**接口不可用不等于没权限**——后端没重启时这个接口会 404，如果这里把菜单藏掉，
    // 用户会以为"功能没了"（真踩过）。所以只有明确返回 canView=false 才隐藏，其余情况先放行，
    // 真没权限的话页面里会给出 40302 的说明。
    try {
      const metricsAccess = await fetchMetricsAccess()
      canViewMetrics.value = metricsAccess.canView !== false
    } catch {
      canViewMetrics.value = true
    }
    if (!canManageMembers.value) {
      // 普通客服成员不进入企业开通引导，也不展示企业信息/成员管理；
      // 但工单待办和他们直接相关，角标照样要拉
      void loadTicketTodo()
      ticketTimer = window.setInterval(() => void loadTicketTodo(), 60_000)
      void loadNotifications()
      return
    }
    void loadAlertPending()
    alertTimer = window.setInterval(() => void loadAlertPending(), 60_000)
    void loadTicketTodo()
    ticketTimer = window.setInterval(() => void loadTicketTodo(), 60_000)
    void loadNotifications()
    const guide = await getEnterpriseGuide()
    guideStage.value = guide.stage
    let done = localStorage.getItem(ONBOARD_DONE_KEY) === 'true'
    if (guide.stage === 'APPROVED') {
      try {
        const channels = await listChannels()
        channelRunning.value = channels.some((c) => c.stage === 3)
        if (channelRunning.value) {
          done = true
          localStorage.setItem(ONBOARD_DONE_KEY, 'true')
        }
      } catch {
        // 渠道服务不可用时按本地标记处理
      }
    }
    if (
      router.currentRoute.value.name !== 'EnterpriseReview'
      && (guide.stage !== 'APPROVED' || !done)
    ) {
      router.replace('/guide')
    }
  } catch {
    // 状态查询失败时保持可访问，避免后台不可用造成死循环
  }
})

function doneFlag(key: string) {
  return localStorage.getItem(`supportflow_ob_done_${key}`) === 'true'
}

/**
 * 问后端"机器人配置过没有"。
 *
 * <p>企业侧才问（平台账号没有机器人）；接口不可用时退回本地开关，
 * 这样至少不会因为 AI 服务没起就永远显示"待配置"。</p>
 */
async function loadBotConfigured() {
  if (userStore.userType !== 2) {
    botConfigured.value = false
    return
  }
  try {
    const setting = await getBotSetting()
    botConfigured.value = Boolean(setting.configured) || doneFlag('bot')
  } catch {
    botConfigured.value = doneFlag('bot')
  }
}

function tipFor(key: string): { text: string; type: string } {
  if (userStore.userType !== 2) return { text: '', type: '' }
  if (key === 'enterprise') {
    if (guideStage.value === 'PENDING_PROFILE' || guideStage.value === 'REJECTED') {
      return { text: '待完善', type: 'danger' }
    }
    if (guideStage.value === 'PENDING_REVIEW') {
      return { text: '审核中', type: 'warning' }
    }
    return { text: '', type: '' }
  }
  if (guideStage.value !== 'APPROVED') return { text: '', type: '' }
  if (key === 'channel' && !channelRunning.value) {
    return { text: '待接入', type: 'warning' }
  }
  if (key === 'bot' && channelRunning.value && !botConfigured.value) {
    return { text: '待配置', type: 'warning' }
  }
  if (key === 'member' && channelRunning.value && !doneFlag('member')) {
    return { text: '待配置', type: 'warning' }
  }
  if (key === 'qa' && channelRunning.value && !doneFlag('qa')) {
    return { text: '待配置', type: 'warning' }
  }
  return { text: '', type: '' }
}

/**
 * 拉一次待处理预警数：只取待处理的告警，够轻量，60 秒刷新一次。
 */
async function loadAlertPending() {
  try {
    alertPending.value = (await fetchQaAlerts({ status: 1 })).length
  } catch {
    // 拿不到就不显示角标，不影响其它菜单
  }
}

/** 工单待办数：和质检预警一样 60 秒轮询一次（工单页自己也有一套 30 秒的看板刷新） */
async function loadTicketTodo() {
  try {
    const overview = await fetchTicketOverview()
    ticketTodo.value = overview.pending + overview.processing + overview.confirming
    ticketOverdue.value = overview.overdue
  } catch {
    // 拿不到就不显示角标
  }
}

/** 平台侧支持工单待办（平台账号专用，60 秒轮询） */
async function loadPlatformTodo() {
  try {
    const overview = await fetchPlatformTicketOverview()
    platformTodo.value = overview.pending + overview.processing + overview.confirming
    platformOverdue.value = overview.overdue
  } catch {
    // 拿不到就不显示角标
  }
}

/** 可观测待处理告警：拿不到就不显示角标（服务没重启时不该像"功能没了"） */
async function loadObservabilityPending() {
  try {
    const result = await fetchPendingAlertCount()
    observabilityPending.value = Number(result.count) || 0
  } catch {
    observabilityPending.value = 0
  }
}

/**
 * 侧边栏「AI 能力升级」卡片。
 *
 * <p>判断"是不是企业版"用的是**套餐 ID**（来自 billing 的套餐列表与当前用量里的 planId），
 * 不是套餐名字符串——名字改一次（"企业版"→"旗舰版"）就会让判断失效。</p>
 */
async function loadUpgradeCard() {
  if (userStore.userType !== 2) {
    showUpgradeCard.value = false
    // 平台账号也给一个身份标识（平台侧不需要套餐概念）
    identityBadge.value = userStore.userType === 1 ? { text: '平台', tone: 'platform' } : null
    return
  }
  try {
    const [usage, plans] = await Promise.all([fetchUsage(), fetchPlans()])
    const enterprise = plans.find((plan) => plan.planCode === 'ENTERPRISE')
    enterprisePlan.value = Boolean(
      enterprise && usage.planId && String(usage.planId) === String(enterprise.id),
    )
    showUpgradeCard.value = true
    // 右上角身份：按套餐编码给不同样式（免费版也要显示，只是样式朴素）
    const current = plans.find((plan) => String(plan.id) === String(usage.planId ?? ''))
    identityBadge.value = usage.planName
      ? { text: usage.planName, tone: badgeToneOf(current?.planCode) }
      : { text: '未开通套餐', tone: 'free' }
    planExpireText.value = usage.planExpireTime
      ? String(usage.planExpireTime).replace('T', ' ').slice(0, 10)
      : ''
  } catch {
    // 计费接口不可用（billing-service 没起 / 没跑迁移）时不显示这张卡：
    // 它是"顺带提示"，不该因为自己拿不到数据而打扰用户
    showUpgradeCard.value = false
  }
}

/** 套餐编码 → 身份标识配色：免费版朴素、企业版最显眼 */
function badgeToneOf(planCode?: string | null) {
  switch (planCode) {
    case 'ENTERPRISE':
      return 'ent'
    case 'PROFESSIONAL':
      return 'pro'
    case 'STANDARD':
      return 'std'
    default:
      return 'free'
  }
}

/**
 * 拉后端消息（工单提醒：SLA 预警 / 分派 / 升级 / 平台回复）。
 *
 * <p>打不开抽屉时也拉一次——右上角红点要有数字，不能等用户点开才知道有新消息。</p>
 */
async function loadNotifications() {
  if (userStore.userType !== 2) {
    return
  }
  try {
    // 列表与统计一起拉：抽屉的条数/未读数跟消息中心页面同源，不会各算一套
    const [rows, summary] = await Promise.all([
      fetchNotifications({ limit: 50 }),
      fetchNotificationSummary().catch(() => null),
    ])
    serverNotices.value = rows.map((row) => ({
      id: row.id,
      type: noticeTabOf(row.notifyType),
      title: row.title,
      text: row.content || '',
      time: row.publishTime || '',
      read: row.read,
      source: 'server' as const,
      linkView: row.linkView,
    }))
    notifySummary.value = summary
  } catch {
    // 消息中心拿不到不影响页面其它功能
  }
}

function handleUserCommand(command: string | number | object) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'messages') {
    router.push('/messages')
  } else if (command === 'password') {
    pwdVisible.value = true
  } else if (command === 'logout') {
    openLogoutConfirm()
  }
}

/** 抽屉里点「查看全部消息」：关掉抽屉，进独立页面 */
function openMessageCenter() {
  notifyOpen.value = false
  void router.push('/messages')
}

async function openLogoutConfirm() {
  // 页面刷新后先恢复会话，确保弹窗展示真实姓名与账号
  if (!userStore.userId) {
    try {
      await userStore.fetchProfile()
    } catch {
      // 拉取失败时按原信息弹窗，不阻断退出操作
    }
  }
  logoutVisible.value = true
}

function doLogout() {
  userStore.reset()
  localStorage.removeItem(ONBOARD_DONE_KEY)
  ElMessage.success('已安全退出登录')
  router.push('/login')
}

/**
 * 后端消息类型码 → 抽屉页签。
 *
 * <p>1-系统 2-工单 3-审核 4-质检 5-公告 6-账单（账单归到系统通知）；
 * 认不出来的类型也归系统通知，总比塞进"工单提醒"里强。</p>
 */
function noticeTabOf(notifyType?: number): NoticeItem['type'] {
  if (notifyType === 2) return 'ticket'
  if (notifyType === 3) return 'review'
  if (notifyType === 4) return 'qa'
  if (notifyType === 5) return 'announce'
  if (notifyType === 6) return 'bill'
  return 'system'
}

function iconOf(type: NoticeItem['type']) {
  if (type === 'ticket') return 'Tickets'
  if (type === 'announce') return 'Promotion'
  if (type === 'review') return 'CircleCheck'
  if (type === 'qa') return 'DataAnalysis'
  if (type === 'bill') return 'Wallet'
  return 'Bell'
}

function tabCount(key: string) {
  if (key === 'all') return unreadCount.value
  const code = TABS.find((item) => item.key === key)?.code
  if (code != null) {
    const stat = notifySummary.value?.byType[String(code)]
    if (stat) {
      return Number(stat.unread) || 0
    }
  }
  return notices.value.filter((n) => n.type === key && !n.read).length
}

/** 点开一条消息：写已读（后端按人存），有跳转标识就顺手跳过去 */
async function markRead(item: NoticeItem) {
  if (item.read) {
    return
  }
  try {
    await markNotificationRead(item.id)
    item.read = true
    if (notifySummary.value) {
      notifySummary.value.all.unread = Math.max(0, notifySummary.value.all.unread - 1)
    }
  } catch {
    // 标记失败不改本地状态：下次打开还会显示未读，不会"假装看过了"
  }
  // 工单类提醒点开就跳到工单中心（消息里说的是哪张单，页面里能直接看）
  const route = notificationRoute(item.linkView)
  if (route) {
    notifyOpen.value = false
    void router.push(route)
  }
}

async function markAllRead() {
  if (unreadCount.value > 0) {
    try {
      await markAllNotificationsRead()
      await loadNotifications()
    } catch {
      // 接口失败就保持原样：界面不假装全读
    }
  }
}
</script>

<style scoped>
/* 安全水印：斜排浅字，截图留痕用；不挡点击 */
.sec-watermark {
  position: fixed;
  inset: 0;
  z-index: 2000;
  pointer-events: none;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-around;
  align-items: center;
}
.wm-row {
  font-size: 13px;
  color: rgba(15, 23, 42, 0.05);
  white-space: nowrap;
  transform: rotate(-20deg);
  letter-spacing: 2px;
}
.admin-layout {
  height: 100%;
}

.admin-aside {
  background: #0b1220;
  display: flex;
  flex-direction: column;
  /* 滚动条走全站统一规范（assets/styles/scrollbar.css 的 pro-scroll--dark），
     这里不再自写一套，避免侧边栏与聊天窗口两套标准各长各的 */
}

.admin-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

.admin-logo-img {
  width: 26px;
  height: 26px;
  border-radius: 7px;
}

.admin-menu {
  border-right: none;
  flex: 1;
}

/* 侧边栏底部的「AI 能力升级」卡（配色照原型 .side-upgrade） */
.side-upgrade {
  margin: 10px 12px 14px;
  padding: 14px;
  border-radius: 12px;
  background: linear-gradient(140deg, rgba(59, 130, 246, 0.22), rgba(124, 58, 237, 0.12));
  border: 1px solid rgba(14, 165, 233, 0.35);
}

.side-upgrade .u-title {
  color: #dbeafe;
  font-size: 12.5px;
  font-weight: 600;
}

.side-upgrade .u-text {
  color: #8b97ad;
  font-size: 11.5px;
  margin: 4px 0 10px;
  line-height: 1.5;
}

.side-upgrade .u-btn {
  width: 100%;
  height: 28px;
  border: none;
  border-radius: 7px;
  background: linear-gradient(135deg, #2563eb 0%, #0ea5e9 100%);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.18s ease;
}

.side-upgrade .u-btn:hover {
  filter: brightness(1.08);
}

.admin-menu :deep(.el-menu-item) {
  display: flex;
  align-items: center;
  height: 42px;
  margin: 3px 10px;
  border-radius: 8px;
  font-size: 13.5px;
  color: #8a97ad;
  transition: background 0.18s ease, color 0.18s ease;
}

/* 悬浮：整行变亮一点点 */
.admin-menu :deep(.el-menu-item:hover),
.admin-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(148, 163, 184, 0.12);
  color: #e2e8f0;
}

/* 选中：蓝色渐变底 + 左侧高亮条 + 白字加粗，一眼就能看出当前在哪 */
.admin-menu :deep(.el-menu-item.is-active) {
  position: relative;
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.34), rgba(37, 99, 235, 0.14));
  color: #fff;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(59, 130, 246, 0.36);
}

.admin-menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #3b82f6;
}

.admin-menu :deep(.el-menu-item.is-active .el-icon) {
  color: #60a5fa;
}

/* 分组标题：当前分组里有选中项时也稍微提亮 */
.admin-menu :deep(.el-sub-menu__title) {
  transition: color 0.18s ease;
}

.admin-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #cbd5e1;
}

.menu-tip {
  margin-left: auto;
  font-size: 11px;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 999px;
  flex-shrink: 0;
}

.menu-tip.warning {
  color: #fbbf24;
  background: rgba(245, 158, 11, 0.16);
  animation: menuTipPulse 1.2s ease-in-out infinite;
}

.menu-tip.danger {
  color: #f87171;
  background: rgba(239, 68, 68, 0.16);
  animation: menuTipPulse 1s ease-in-out infinite;
}

@keyframes menuTipPulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.55;
    transform: scale(0.92);
  }
}

.admin-header {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid #e6e8ef;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.tb-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.icon-btn {
  position: relative;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: #475569;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.18s;
}

.icon-btn:hover {
  background: #f1f5f9;
  color: #2563eb;
}

.notif-count {
  position: absolute;
  top: 2px;
  right: 0;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  border: 2px solid #fff;
}

.tb-divider {
  width: 1px;
  height: 22px;
  background: #e6e8ef;
  margin: 0 10px;
}

.tb-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.18s;
  outline: none;
}

.tb-user:hover {
  background: #f1f5f9;
}

.avatar-sm {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-sm .st {
  position: absolute;
  right: 0;
  bottom: 1px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #fff;
}

.user-text {
  line-height: 1.25;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 1px;
}

/* 右上角身份标识（套餐）：付费档用彩色，免费版用灰底，企业版用金渐变 */
.plan-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 6px;
  padding: 0 7px;
  height: 17px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  vertical-align: middle;
  white-space: nowrap;
}
.plan-badge.free { background: #f1f5f9; color: #64748b; border: 1px solid #e2e8f0; }
.plan-badge.std { background: #eaf2fe; color: #2563eb; border: 1px solid #dbeafe; }
.plan-badge.pro { background: #ede9fe; color: #7c3aed; border: 1px solid #ddd6fe; }
.plan-badge.ent {
  background: linear-gradient(135deg, #f59e0b, #f97316);
  color: #fff;
  box-shadow: 0 2px 6px -2px rgba(245, 158, 11, 0.8);
}
.plan-badge.platform { background: #0f172a; color: #e2e8f0; }

.user-menu .drop-plan { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.user-menu .drop-plan .plan-badge { margin-left: 0; }
.user-menu .drop-expire { font-size: 11.5px; color: #94a3b8; }

.caret {
  color: #94a3b8;
  font-size: 12px;
}

.admin-main {
  background: #f5f6f8;
  padding: 20px;
}

.user-menu :deep(.drop-head) {
  padding: 10px 14px 12px;
  border-bottom: 1px solid #eef2f7;
}

.user-menu :deep(.drop-name) {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.user-menu :deep(.drop-no) {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.user-menu :deep(.el-dropdown-menu__item) {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
}

.user-menu :deep(.drop-logout) {
  color: #dc2626;
}

.user-menu :deep(.drop-logout:hover) {
  color: #b91c1c;
  background: #fef2f2;
}

.avatar-hue-0 { background: linear-gradient(135deg, #2563eb, #38bdf8); }
.avatar-hue-1 { background: linear-gradient(135deg, #1d4ed8, #60a5fa); }
.avatar-hue-2 { background: linear-gradient(135deg, #0284c7, #38bdf8); }
.avatar-hue-3 { background: linear-gradient(135deg, #3b82f6, #93c5fd); }
.avatar-hue-4 { background: linear-gradient(135deg, #1e40af, #3b82f6); }

.notify-drawer :deep(.el-drawer__header) {
  margin-bottom: 4px;
  font-weight: 700;
}

.notify-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 0 14px;
  border-bottom: 1px solid #eef2f7;
}

.notify-tab {
  position: relative;
  border: none;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12.5px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.18s;
}

.notify-tab.active {
  background: #eff6ff;
  color: #2563eb;
}

.notify-tab .tab-count {
  margin-left: 4px;
  color: #2563eb;
  font-size: 11px;
}

.notify-list {
  padding: 8px 0;
  min-height: 300px;
}

.notify-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 10px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.notify-item:hover {
  background: #f8fafc;
}

.notify-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.type-system { background: #eff6ff; color: #2563eb; }
.type-review { background: #e0f2fe; color: #0284c7; }
.type-qa { background: #dbeafe; color: #1d4ed8; }
/* 工单提醒：暖色，跟系统/审核/质检的蓝色系区分开 */
.type-ticket { background: #fff7ed; color: #ea580c; }
/* 平台公告：绿色，一眼跟工单/系统消息区分开 */
.type-announce { background: #ecfdf5; color: #059669; }
/* 账单通知：紫色 */
.type-bill { background: #f5f3ff; color: #7c3aed; }

.notify-body {
  flex: 1;
  min-width: 0;
}

.notify-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 6px;
}

.notify-item.read .notify-title {
  color: #64748b;
  font-weight: 500;
}

.unread-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ef4444;
  flex-shrink: 0;
}

.notify-text {
  font-size: 12.5px;
  color: #64748b;
  line-height: 1.7;
  margin-top: 4px;
}

.notify-time {
  font-size: 11px;
  color: #cbd5e1;
  margin-top: 6px;
}

.notify-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.notify-footer-actions { display: flex; align-items: center; gap: 8px; }

/* 用户菜单里的未读数：跟铃铛红点同一口径 */
.drop-badge {
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  line-height: 16px;
}

.muted-text {
  font-size: 12px;
  color: #94a3b8;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>

<style>
/* 下拉菜单 / 抽屉为 teleport 渲染，需使用非 scoped 样式 */
.user-menu .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
}

.user-menu .drop-head {
  padding: 10px 14px 12px;
  border-bottom: 1px solid #eef2f7;
  margin-bottom: 4px;
}

.user-menu .drop-name {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.user-menu .drop-no {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.user-menu .drop-logout {
  color: #dc2626;
}

.user-menu .drop-logout:hover {
  color: #b91c1c;
  background: #fef2f2;
}

.notify-drawer .el-drawer__header {
  margin-bottom: 4px;
  font-weight: 700;
}

.notify-drawer .el-drawer__body {
  padding-top: 0;
}

.logout-confirm {
  width: 400px;
}
</style>
