<template>
  <div class="upgrade-page">
    <div class="page-head">
      <div>
        <h2>版本与升级</h2>
        <div class="sub">套餐对比 · 用量概览 · 一键开通企业版</div>
      </div>
      <div class="page-head-actions">
        <span class="badge b-blue">
          <span class="dot blue" />当前套餐：{{ usage?.planName || '未开通' }}
        </span>
        <span v-if="usage?.planExpireTime" class="expire-hint">
          到期 {{ dateText(usage?.planExpireTime) }}
        </span>
        <el-button @click="bookDemo">
          <el-icon class="btn-icon"><Calendar /></el-icon>
          预约演示
        </el-button>
      </div>
    </div>

    <!-- Hero：左侧主张 + 右侧真实指标（取不到就显示 —，不编数字） -->
    <div class="upg-hero">
      <div>
        <span class="badge b-purple">AI 能力升级</span>
        <h3>让 AI 接管重复咨询，把人力留给真正需要人的对话</h3>
        <p>
          大模型多轮对话、全量 AI 质检与知识库检索增强，随业务规模弹性升级，
          一次接入全渠道通用。
        </p>
      </div>
      <div class="upg-hero-stats">
        <div><b>{{ heroStats.botRatio }}</b><span>机器人接待率</span></div>
        <div><b>{{ heroStats.firstResponse }}</b><span>平均首响</span></div>
        <div><b>{{ heroStats.csat }}</b><span>客户满意度</span></div>
      </div>
    </div>

    <!-- 用量概览：本账期用量 vs 套餐配额（原型 sub 里的"用量概览"） -->
    <div class="upg-usage">
      <div class="uu-title">本账期用量（{{ usage?.cycleKey || '—' }}）</div>
      <div class="uu-items">
        <div class="uu-item">
          <span class="uu-label">会话</span>
          <b>{{ fmt(usage?.sessions) }}</b>
          <el-progress :percentage="pct(usage?.sessions, usage?.quotaSessions)" :stroke-width="6" :show-text="false" />
          <span class="uu-quota">配额 {{ usage?.quotaSessions ? fmt(usage?.quotaSessions) : '不限' }}</span>
        </div>
        <div class="uu-item">
          <span class="uu-label">AI 调用</span>
          <b>{{ fmt(usage?.aiCalls) }}</b>
          <el-progress :percentage="pct(usage?.aiCalls, usage?.quotaAiCalls)" :stroke-width="6" :show-text="false" />
          <span class="uu-quota">配额 {{ usage?.quotaAiCalls ? fmt(usage?.quotaAiCalls) : '不限' }}</span>
        </div>
        <div class="uu-item">
          <span class="uu-label">坐席</span>
          <b>{{ fmt(usage?.seats) }}</b>
          <el-progress :percentage="pct(usage?.seats, usage?.quotaSeats)" :stroke-width="6" :show-text="false" />
          <span class="uu-quota">配额 {{ usage?.quotaSeats || '不限' }}</span>
        </div>
        <div class="uu-item">
          <span class="uu-label">存储</span>
          <b>{{ usage?.storageGb || 0 }} GB</b>
          <el-progress :percentage="pct(usage?.storageGb, usage?.quotaStorageGb)" :stroke-width="6" :show-text="false" />
          <span class="uu-quota">配额 {{ usage?.quotaStorageGb || '不限' }} GB</span>
        </div>
      </div>
    </div>

    <!-- 计费周期切换 -->
    <div class="upg-billing">
      <div class="cycle-toggle">
        <!-- 滑动指示块：跟着选中的周期走，比"换背景色"更容易看出当前选的是哪个 -->
        <span
          class="cycle-slider"
          :style="{ transform: cycle === 1 ? 'translateX(0)' : 'translateX(100%)' }"
        />
        <button
          type="button"
          :class="{ active: cycle === 1 }"
          :aria-pressed="cycle === 1"
          @click="cycle = 1"
        >
          年付
          <span class="save-hint">省 2 个月</span>
        </button>
        <button
          type="button"
          :class="{ active: cycle === 2 }"
          :aria-pressed="cycle === 2"
          @click="cycle = 2"
        >
          月付
        </button>
      </div>
      <span class="badge b-red">年付 8 折</span>
      <span class="hint">价格含税 · 升级随时生效 · 无隐藏费用</span>
    </div>

    <!-- 套餐卡（原型 upg-plans） -->
    <div class="upg-plans">
      <div
        v-for="plan in plans"
        :key="plan.id"
        class="upg-plan"
        :class="{ hot: isHot(plan), selected: isSelected(plan) }"
        @click="selectPlan(plan)"
      >
        <div v-if="isHot(plan)" class="upg-ribbon">最受欢迎</div>
        <span v-if="isCurrent(plan)" class="upg-cur">当前套餐</span>
        <span v-if="isSelected(plan) && !isCurrent(plan)" class="upg-picked">已选</span>
        <div class="upg-plan-name">{{ plan.planName }}</div>
        <div class="upg-plan-tag">{{ planTag(plan) }}</div>
        <div class="upg-plan-price">
          <!-- 免费版：显示"免费/永久"，不显示 ¥0 这种别扭的价格 -->
          <template v-if="priceOf(plan) === 0">
            <span class="num free">免费</span>
            <span class="per">永久</span>
          </template>
          <template v-else>
            <span class="cny">¥</span>
            <span class="num">{{ priceOf(plan).toLocaleString('zh-CN') }}</span>
            <span class="per">/{{ cycle === 1 ? '年' : '月' }}</span>
            <span v-if="cycle === 1 && monthlySaving(plan) > 0" class="save">
              相当于省 ¥{{ monthlySaving(plan).toLocaleString('zh-CN') }}
            </span>
          </template>
        </div>
        <el-button
          :type="isCurrent(plan) ? 'default' : isSelected(plan) ? 'primary' : 'default'"
          :disabled="isCurrent(plan)"
          :loading="submitting"
          @click.stop="choose(plan)"
        >
          {{ isCurrent(plan) ? '当前套餐'
            : priceOf(plan) === 0 ? '免费开通'
              : isHot(plan) ? '立即开通企业版' : `购买${plan.planName}` }}
        </el-button>
        <div class="upg-plan-feats">
          <div class="f"><el-icon><Check /></el-icon>机器人会话 {{ fmt(plan.quotaRobotSessions) }} 次/月</div>
          <div class="f"><el-icon><Check /></el-icon>坐席 {{ plan.quotaSeats }} 个 · 渠道 {{ plan.quotaChannels }} 个</div>
          <div class="f"><el-icon><Check /></el-icon>AI 调用 {{ fmt(plan.quotaAiCalls) }} 次/月</div>
          <div class="f"><el-icon><Check /></el-icon>存储 {{ plan.quotaStorageGb }} GB</div>
          <div class="f">
            <el-icon :class="{ off: !plan.quotaAiQa }">
              <component :is="plan.quotaAiQa ? Check : Close" />
            </el-icon>
            全量 AI 质检
          </div>
          <div class="f">
            <el-icon :class="{ off: !plan.quotaVision }">
              <component :is="plan.quotaVision ? Check : Close" />
            </el-icon>
            视觉识别（图片判读）
          </div>
        </div>
        <div class="upg-plan-foot">
          {{ priceOf(plan) === 0
            ? '免费使用 · 随时升级'
            : plan.overagePricePerK > 0
              ? `超量 ¥${plan.overagePricePerK}/千次 · 随时升级`
              : '超量不可用 · 建议按量升级' }}
        </div>
      </div>
    </div>

    <!-- 信任条 -->
    <div class="upg-trust">
      <div class="t"><el-icon><Lock /></el-icon><div><b>等保三级</b> · 数据行级隔离（RLS 兜底）</div></div>
      <div class="t"><el-icon><DocumentChecked /></el-icon><div><b>安全合规</b> · 审计留痕 / 可导出</div></div>
      <div class="t"><el-icon><Headset /></el-icon><div><b>7×24 支持</b> · 企业版专属客户成功经理</div></div>
      <div class="t"><el-icon><CreditCard /></el-icon><div><b>随用随付</b> · 无隐藏费用</div></div>
    </div>

    <!-- 套餐能力对比（用套餐的真实字段，不是营销文案） -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head">
          <span class="card-title">套餐能力对比</span>
          <span class="muted">核心配额随套餐开放，数据与配置随时可迁移</span>
        </div>
      </template>
      <el-table :data="compareRows" class="compare-table">
        <el-table-column prop="item" label="能力项" min-width="180" />
        <!-- 选中哪档套餐，哪一列就整列高亮 + 文字变蓝（跟着上面套餐卡的选择走） -->
        <el-table-column
          v-for="plan in plans"
          :key="plan.id"
          :label="plan.planName"
          min-width="160"
          :class-name="isSelected(plan) ? 'col-selected' : ''"
          :label-class-name="isSelected(plan) ? 'col-selected-head' : ''"
        >
          <template #header>
            <span :class="{ 'sel-text': isSelected(plan) }">{{ plan.planName }}</span>
            <span v-if="isSelected(plan)" class="col-flag">已选</span>
          </template>
          <template #default="{ row }">
            <span
              :class="{
                'sel-text': isSelected(plan) && row.value(plan) !== '—',
                muted: row.value(plan) === '—',
              }"
            >
              {{ row.value(plan) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 常见问题（文案照原型） -->
    <el-card shadow="never" class="mt-16">
      <template #header><span class="card-title">常见问题</span></template>
      <el-collapse v-model="openFaq">
        <el-collapse-item v-for="(faq, i) in faqs" :key="faq.q" :name="i" :title="faq.q">
          <div class="faq-a">{{ faq.a }}</div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- 预约演示：系统里没有独立的"售前线索"表，所以引导到真实的通道（提交给平台支持） -->
    <el-dialog v-model="demoOpen" title="预约企业版演示" width="460px">
      <p class="demo-text">
        企业版包含大模型多轮对话、全量 AI 质检与视觉识别。想要 1 对 1 演示，
        可以在「工单中心 → 提交给平台支持」留一下你的联系方式与方便的时间，
        平台会安排专属顾问对接。
      </p>
      <template #footer>
        <el-button @click="demoOpen = false">稍后再说</el-button>
        <el-button type="primary" @click="goSupport">去提交演示需求</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage } from '../../utils/message'
import { useRouter } from 'vue-router'
import {
  Calendar,
  Check,
  Close,
  CreditCard,
  DocumentChecked,
  Headset,
  Lock,
} from '@element-plus/icons-vue'
import {
  fetchPlans,
  fetchUsage,
  money,
  type PlanItem,
  type UsageView,
} from '../../api/billing'
import { fetchRealtime, type RealtimeMetrics } from '../../api/customer/metrics'

const submitting = ref(false)
const router = useRouter()
const cycle = ref(1)
const plans = ref<PlanItem[]>([])
const usage = ref<UsageView | null>(null)
const metrics = ref<RealtimeMetrics | null>(null)
const openFaq = ref<number[]>([])
const demoOpen = ref(false)
/**
 * 被选中的套餐（默认选中企业版）。
 *
 * <p>点卡片任意位置都能选中，选中的那张用"高亮外框"标出来（原型里只有企业版有这圈框，
 * 这里把它变成"跟随选择走"的状态：点谁谁高亮，默认落在企业版——也就是引导最想卖的那档）。</p>
 */
const selectedId = ref<string>('')

/** 企业版 = 会话配额最大且有超量单价的那一档（不写死 code，避免改名就失效） */
const enterprisePlan = computed(() =>
  [...plans.value].sort((a, b) => b.priceMonthly - a.priceMonthly)[0],
)

const heroStats = computed(() => {
  const data = metrics.value
  const rate =
    data && data.csatCountToday > 0
      ? `${((data.csatGoodToday / data.csatCountToday) * 100).toFixed(1)}%`
      : '—'
  return {
    botRatio: data ? `${data.botRatio}%` : '—',
    firstResponse: data ? `${data.firstResponseSeconds}s` : '—',
    csat: rate,
  }
})

/**
 * 对比表：行是"能力项"，取的是套餐真实字段。
 *
 * <p>列由 `plans` 渲染，所以加一档套餐（比如免费版）表里就自动多一列；
 * 哪一列是"选中的"由页面上的 `selectedId` 决定，样式上整列高亮 + 文字变蓝。</p>
 */
const compareRows = computed(() => [
  { item: '机器人会话量', value: (plan: PlanItem) => `${fmt(plan.quotaRobotSessions)} 次/月` },
  { item: '坐席数', value: (plan: PlanItem) => `${plan.quotaSeats} 个` },
  { item: '接入渠道', value: (plan: PlanItem) => `${plan.quotaChannels} 个` },
  { item: 'AI 调用', value: (plan: PlanItem) => `${fmt(plan.quotaAiCalls)} 次/月` },
  { item: '知识库 / 附件存储', value: (plan: PlanItem) => `${plan.quotaStorageGb} GB` },
  { item: '全量 AI 质检', value: (plan: PlanItem) => (plan.quotaAiQa ? '含' : '—') },
  { item: '视觉识别（图片判读）', value: (plan: PlanItem) => (plan.quotaVision ? '含' : '—') },
  {
    item: '超量计费',
    value: (plan: PlanItem) =>
      plan.overagePricePerK > 0 ? `${money(plan.overagePricePerK)}/千次` : '不允许超量',
  },
])

const faqs = [
  {
    q: '开通后什么时候生效？',
    a: '在线支付即时生效；对公转账到账后 1 个工作日内开通，专属顾问将协助完成对账。',
  },
  {
    q: '可以随时升级或降级吗？',
    a: '支持随时升级，付费后立即生效并同步限流配额；降级在当前周期结束后生效，配置数据完整保留。',
  },
  {
    q: '发票如何开具？',
    a: '支付完成后可在「我的订单」查看订单与账单；发票信息（税号、开户行）请提交给平台，按实际到账金额开具。',
  },
  {
    q: '升级后原有配置会丢失吗？',
    a: '不会。渠道、知识库、会话历史与客户档案完整保留，升级后立即解锁大模型多轮对话与全量质检能力。',
  },
]

function fmt(value?: number | null) {
  return value == null ? '0' : Number(value).toLocaleString('zh-CN')
}

function dateText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 10) : '—'
}

function pct(used?: number | null, quota?: number | null) {
  if (!quota || quota <= 0) return 0
  return Math.min(100, Math.round((Number(used ?? 0) / Number(quota)) * 100))
}

function priceOf(plan: PlanItem) {
  return Number(cycle.value === 1 ? plan.priceYearly : plan.priceMonthly)
}

/** 年付相当于比按月省多少（真实计算，不是写死的"立省"文案） */
function monthlySaving(plan: PlanItem) {
  return Math.max(0, Math.round(Number(plan.priceMonthly) * 12 - Number(plan.priceYearly)))
}

function isCurrent(plan: PlanItem) {
  return Boolean(usage.value?.planId && String(usage.value.planId) === String(plan.id))
}

function isHot(plan: PlanItem) {
  return enterprisePlan.value?.id === plan.id
}

function isSelected(plan: PlanItem) {
  return selectedId.value === plan.id
}

/** 点卡片即选中；当前已购套餐也能选中（只是高亮，不代表不能续费） */
function selectPlan(plan: PlanItem) {
  selectedId.value = plan.id
}

function planTag(plan: PlanItem) {
  const seats = plan.quotaSeats
  if (seats <= 5) return '个人与小微团队入门体验'
  if (seats <= 20) return '成长型团队的日常客服'
  return '规模化服务与全量质检'
}

async function choose(plan: PlanItem) {
  if (isCurrent(plan)) return
  // 下单不再是两个 confirm 弹窗：进收银台走「订单确认 → 支付确认 → 支付 → 结果」四步
  void router.push({ path: '/billing/checkout', query: { planId: plan.id, cycle: String(cycle.value) } })
}

function bookDemo() {
  demoOpen.value = true
}

function goSupport() {
  demoOpen.value = false
  // 工单中心会读 ticketType=2 直接切到「提交给平台支持」页签
  void router.push({ path: '/modules/tickets', query: { ticketType: '2' } })
}

async function load() {
  try {
    const planList = await fetchPlans()
    plans.value = planList
    // 默认选中企业版（没有企业版就选最贵的那档）：这样首屏就把引导焦点落在目标套餐上
    if (!selectedId.value) {
      selectedId.value = enterprisePlan.value?.id ?? planList[planList.length - 1]?.id ?? ''
    }
    // 不传 planId：后端会去 tenant-service 查这个租户**当前**的套餐，
    // 这样买完套餐刷新立刻就能看到（之前前端自报 planId，买完还是"未开通"）
    const usageView = await fetchUsage()
    usage.value = usageView
  } catch (e) {
    errorMessage(e, '套餐加载失败')
  }
  // Hero 指标只有管理员 / 主管能取：取不到就显示 —（不编数字）
  try {
    metrics.value = await fetchRealtime()
  } catch {
    metrics.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.upgrade-page { width: 100%; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.page-head .sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.page-head-actions { display: flex; align-items: center; gap: 10px; }
.btn-icon { margin-right: 4px; }
.expire-hint { font-size: 12px; color: #94a3b8; }
.mt-16 { margin-top: 16px; }
.muted { color: #94a3b8; font-size: 12px; }
.text-primary { color: #2563eb; font-weight: 600; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.card-title { font-size: 14.5px; font-weight: 600; }

.badge { display: inline-flex; align-items: center; gap: 6px; height: 21px; padding: 0 8px; border-radius: 999px; font-size: 11.5px; font-weight: 600; }
.b-blue { background: #eaf2fe; color: #2563eb; }
.b-purple { background: #e0f2fe; color: #0284c7; }
.b-red { background: #feecec; color: #dc2626; }
.dot { width: 7px; height: 7px; border-radius: 50%; }
.dot.blue { background: #3b82f6; }

/* Hero（原型 .upg-hero） */
.upg-hero {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 20px;
  align-items: center;
  padding: 22px 24px;
  border-radius: 14px;
  border: 1px solid #dbeafe;
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
}
.upg-hero h3 { margin: 10px 0 6px; font-size: 20px; font-weight: 700; color: #0f172a; }
.upg-hero p { margin: 0; font-size: 13px; color: #475569; max-width: 640px; line-height: 1.7; }
.upg-hero-stats { display: flex; gap: 26px; }
.upg-hero-stats div { text-align: center; }
.upg-hero-stats b { display: block; font-size: 22px; font-weight: 800; color: #2563eb; }
.upg-hero-stats span { font-size: 11.5px; color: #64748b; }

/* 用量概览 */
.upg-usage { margin-top: 16px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 18px; }
.uu-title { font-size: 13px; font-weight: 600; color: #1e293b; margin-bottom: 10px; }
.uu-items { display: grid; grid-template-columns: repeat(4, 1fr); gap: 18px; }
.uu-item { display: grid; gap: 4px; }
.uu-label { font-size: 12px; color: #64748b; }
.uu-item b { font-size: 16px; color: #0f172a; }
.uu-quota { font-size: 11.5px; color: #94a3b8; }

/* 计费周期 */
.upg-billing { display: flex; align-items: center; gap: 12px; margin: 16px 0; flex-wrap: wrap; }
/* 分段控件：底槽 + 会滑动的"选中块"。
   为什么不用"把选中项背景刷白"那种做法：两个按钮宽度不一样时，
   白块和文字会各走各的；这里用一个指示块整体滑动，选的是哪个一眼就能看出来。 */
.cycle-toggle {
  position: relative;
  display: inline-flex;
  padding: 4px;
  border-radius: 12px;
  background: #eef2f7;
  border: 1px solid #e2e8f0;
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.05);
}
.cycle-slider {
  position: absolute;
  top: 4px;
  left: 4px;
  width: calc(50% - 4px);
  height: calc(100% - 8px);
  border-radius: 9px;
  background: linear-gradient(135deg, #2563eb 0%, #0ea5e9 100%);
  box-shadow: 0 6px 14px rgba(37, 99, 235, 0.3);
  transition: transform 0.28s cubic-bezier(0.4, 0, 0.2, 1);
}
.cycle-toggle button {
  position: relative;
  z-index: 1;
  flex: 1;
  min-width: 138px;
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: none;
  background: transparent;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: #475569;
  cursor: pointer;
  transition: color 0.2s ease;
}
.cycle-toggle button:hover { color: #1e293b; }
.cycle-toggle button.active,
.cycle-toggle button.active:hover { color: #fff; }
/* 年付按钮里的"省 2 个月"：未选中时是红底小胶囊，选中后切成半透明白（保证在蓝底上依然清楚） */
.save-hint {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  background: rgba(220, 38, 38, 0.1);
  color: #dc2626;
  transition: background 0.2s ease, color 0.2s ease;
}
.cycle-toggle button.active .save-hint { background: rgba(255, 255, 255, 0.24); color: #fff; }
.hint { font-size: 12px; color: #94a3b8; }

/* 套餐卡 */
/* 四档套餐（免费版 / 标准版 / 专业版 / 企业版）：用 auto-fit，宽屏一行四张，窄屏自动换行 */
.upg-plans { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 16px; }
.upg-plan {
  position: relative;
  background: #fff;
  border: 1px solid #e6e8ef;
  border-radius: 14px;
  padding: 20px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}
.upg-plan:hover { border-color: #bfdbfe; transform: translateY(-2px); }
/* 选中态 = 原型里企业版那圈高亮外框（点谁谁高亮，默认落在企业版） */
.upg-plan.selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1), 0 14px 30px -20px rgba(37, 99, 235, 0.65);
  transform: translateY(-2px);
}
.upg-plan.hot { border-color: #dbeafe; }
.upg-plan.hot.selected { border-color: #2563eb; }
.upg-picked {
  position: absolute; top: 12px; right: 12px;
  font-size: 11px; color: #2563eb; background: #eaf2fe; border-radius: 999px;
  padding: 2px 8px; font-weight: 600;
}
.upg-ribbon {
  position: absolute; top: -12px; left: 50%; transform: translateX(-50%);
  background: linear-gradient(135deg, #2563eb, #0ea5e9); color: #fff;
  font-size: 11.5px; font-weight: 600; padding: 3px 12px; border-radius: 999px;
}
.upg-cur {
  position: absolute; top: 12px; right: 12px;
  font-size: 11px; color: #059669; background: #e7f8f0; border-radius: 999px; padding: 2px 8px; font-weight: 600;
}
.upg-plan-name { font-size: 16px; font-weight: 700; color: #0f172a; }
.upg-plan-tag { font-size: 12px; color: #94a3b8; min-height: 18px; }
.upg-plan-price { display: flex; align-items: baseline; gap: 2px; flex-wrap: wrap; }
.upg-plan-price .cny { font-size: 14px; color: #2563eb; font-weight: 700; }
.upg-plan-price .num { font-size: 28px; font-weight: 800; color: #2563eb; letter-spacing: -0.5px; }
.upg-plan-price .num.free { font-size: 26px; color: #10b981; }
.upg-plan-price .per { font-size: 12px; color: #94a3b8; }
.upg-plan-price .save { width: 100%; font-size: 11.5px; color: #dc2626; }
.upg-plan-feats { display: grid; gap: 6px; margin-top: 4px; }
.upg-plan-feats .f { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: #475569; }
.upg-plan-feats .f .el-icon { color: #10b981; }
.upg-plan-feats .f .el-icon.off { color: #cbd5e1; }
.upg-plan-foot { margin-top: auto; padding-top: 8px; border-top: 1px dashed #eef2f7; font-size: 11.5px; color: #94a3b8; }

.upg-trust { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-top: 16px; }
.upg-trust .t { display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #e6e8ef; border-radius: 12px; padding: 12px 14px; font-size: 12.5px; color: #475569; }
.upg-trust .t .el-icon { color: #2563eb; font-size: 18px; }
.upg-trust .t b { color: #0f172a; }

/* 对比表：选中套餐那一列浅蓝底 + 一圈蓝边框，文字变蓝（列头再加个「已选」小标） */
.compare-table :deep(.el-table__cell) { font-size: 12.5px; }
.compare-table :deep(td.col-selected) {
  border-left: 1px solid #2563eb;
  border-right: 1px solid #2563eb;
  background: #eff6ff;
}
/* 整列的上下边：首行补 top、末行补 bottom，合成一个完整的蓝框 */
.compare-table :deep(tr.el-table__row:first-child td.col-selected) { border-top: 1px solid #2563eb; }
.compare-table :deep(tr.el-table__row:last-child td.col-selected) { border-bottom: 1px solid #2563eb; }
.compare-table :deep(th.col-selected-head) {
  border: 1px solid #2563eb;
  border-bottom: none;
  background: #dbeafe;
}
.compare-table :deep(.col-selected-head .cell) { color: #2563eb; font-weight: 700; }
.sel-text { color: #2563eb; font-weight: 600; }
.col-flag {
  margin-left: 6px;
  font-size: 10.5px;
  font-weight: 600;
  color: #2563eb;
  background: #fff;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  padding: 1px 6px;
}
.faq-a { font-size: 12.5px; color: #475569; line-height: 1.8; }
.demo-text { margin: 0; font-size: 13px; color: #475569; line-height: 1.8; }

@media (max-width: 1280px) {
  .upg-plans { grid-template-columns: 1fr; }
  .uu-items { grid-template-columns: repeat(2, 1fr); }
  .upg-trust { grid-template-columns: repeat(2, 1fr); }
}
</style>
