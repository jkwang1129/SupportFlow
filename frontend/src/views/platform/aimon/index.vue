<template>
  <div class="aimon-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">AI 监控</h2>
        <p class="page-sub">
          大模型调用量、Token 成本、质检质量与租户配额 · 仅聚合指标（不含对话内容）
        </p>
      </div>
      <div class="head-actions">
        <el-radio-group v-model="days" size="small" @change="load">
          <el-radio-button :value="1">今日</el-radio-button>
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <!-- 计量状态：一眼看出"这些数字是不是真实模型调用" -->
    <div class="status-strip" :class="statusTone">
      <div class="ss-icon">
        <el-icon :size="18"><component :is="statusIcon" /></el-icon>
      </div>
      <div class="ss-body">
        <div class="ss-title">{{ statusTitle }}</div>
        <div class="ss-sub">{{ statusSub }}</div>
      </div>
      <div class="ss-figures">
        <span>今日真实调用 <b>{{ status?.todayRealCalls ?? 0 }}</b></span>
      </div>
    </div>

    <!-- KPI -->
    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon blue"><el-icon :size="18"><ChatDotRound /></el-icon></div>
        <div>
          <div class="kpi-value">{{ compact(metrics?.overview?.calls) }}</div>
          <div class="kpi-label">{{ daysLabel }}调用量</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon purple"><el-icon :size="18"><DataLine /></el-icon></div>
        <div>
          <div class="kpi-value">{{ compact(metrics?.overview?.tokens) }}</div>
          <div class="kpi-label">Token 总量</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><Money /></el-icon></div>
        <div>
          <div class="kpi-value">{{ money(metrics?.overview?.cost) }}</div>
          <div class="kpi-label">估算成本</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon green"><el-icon :size="18"><Timer /></el-icon></div>
        <div>
          <div class="kpi-value">{{ metrics?.overview?.avgLatencyMs ?? 0 }} ms</div>
          <div class="kpi-label">平均耗时</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon green"><el-icon :size="18"><CircleCheck /></el-icon></div>
        <div>
          <div class="kpi-value">{{ successText }}</div>
          <div class="kpi-label">调用成功率（失败 {{ metrics?.overview?.failedCalls ?? 0 }} 次）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div>
          <div class="kpi-value">{{ quality?.overview.highRiskTasks ?? 0 }}</div>
          <div class="kpi-label">高风险会话（质检）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon red"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div>
          <div class="kpi-value">{{ quality?.overview.alerts ?? 0 }}</div>
          <div class="kpi-label">实时预警命中（严重 {{ quality?.overview.severeAlerts ?? 0 }}）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><Odometer /></el-icon></div>
        <div>
          <div class="kpi-value">{{ quotaAlertText }}</div>
          <div class="kpi-label">配额告警租户（今日）</div>
        </div>
      </div>
    </div>

    <div class="grid-row">
      <!-- 调用量趋势 -->
      <el-card shadow="never" class="col-8">
        <div class="card-head">
          <div>
            <div class="card-title">调用量与成本趋势</div>
            <div class="card-sub">
              {{ metrics?.from || '—' }} ~ {{ metrics?.to || '—' }} · 柱=调用量，折线=成本
            </div>
          </div>
          <div class="head-figures">
            <span>涉及租户 <b>{{ metrics?.overview?.tenants ?? 0 }}</b></span>
            <span>输入 <b>{{ compact(metrics?.overview?.inputTokens) }}</b></span>
            <span>输出 <b>{{ compact(metrics?.overview?.outputTokens) }}</b></span>
          </div>
        </div>
        <div class="chart-wrap">
          <svg class="usage-chart" :viewBox="`0 0 ${chart.width} ${chart.height}`" preserveAspectRatio="none">
            <line
              v-for="line in chart.gridLines"
              :key="`g-${line.y}`"
              :x1="chart.padLeft"
              :y1="line.y"
              :x2="chart.width - chart.padRight"
              :y2="line.y"
              class="grid-line"
            />
            <rect
              v-for="(bar, index) in chart.bars"
              :key="`b-${index}`"
              :x="bar.x"
              :y="bar.y"
              :width="bar.width"
              :height="bar.height"
              rx="4"
              class="bar-rect"
            />
            <polyline :points="chart.linePoints" class="trend-line" />
            <circle
              v-for="(dot, index) in chart.dots"
              :key="`d-${index}`"
              :cx="dot.x"
              :cy="dot.y"
              r="3"
              class="trend-dot"
            />
            <text
              v-for="(tick, index) in chart.ticks"
              :key="`t-${index}`"
              :x="tick.x"
              :y="chart.height - 6"
              text-anchor="middle"
              class="axis-text"
            >
              {{ tick.label }}
            </text>
          </svg>
          <el-empty v-if="!(metrics?.daily || []).length" description="这段时间没有 AI 调用记录" :image-size="90" />
        </div>
      </el-card>

      <!-- 模型 / 场景分布 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div class="card-title">按模型</div>
          <span class="card-tip">成本占比</span>
        </div>
        <div class="dist-list">
          <div v-for="item in modelRows" :key="item.label" class="dist-item">
            <div class="dist-top">
              <span class="dist-name">{{ item.label }}</span>
              <span class="dist-value">{{ money(item.cost) }} · {{ compact(item.calls) }} 次</span>
            </div>
            <div class="dist-bar"><span :style="{ width: item.percent + '%', background: item.color }"></span></div>
            <div class="dist-sub">
              {{ compact(item.tokens) }} token · 平均 {{ item.avgLatencyMs }} ms
              <span v-if="item.failedCalls > 0" class="danger"> · 失败 {{ item.failedCalls }}</span>
            </div>
          </div>
          <el-empty v-if="!modelRows.length" description="暂无模型调用" :image-size="70" />
        </div>

        <div class="divider"></div>
        <div class="card-head">
          <div class="card-title">按场景</div>
        </div>
        <div class="scene-list">
          <div v-for="item in sceneRows" :key="item.key" class="scene-item">
            <span class="scene-name">{{ item.label }}</span>
            <span class="scene-value">{{ compact(item.calls) }} 次</span>
            <span class="scene-value">{{ money(item.cost) }}</span>
          </div>
          <el-empty v-if="!sceneRows.length" description="暂无数据" :image-size="60" />
        </div>
      </el-card>
    </div>

    <div class="grid-row">
      <!-- 成本 Top 租户 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div>
            <div class="card-title">租户成本 Top</div>
            <div class="card-sub">{{ daysLabel }}按估算成本排序</div>
          </div>
        </div>
        <div class="tenant-list">
          <div v-for="(item, index) in metrics?.topTenants || []" :key="item.tenantCode" class="tenant-item">
            <span class="rank">{{ index + 1 }}</span>
            <div class="tenant-info">
              <div class="tenant-name">{{ companyName(item.tenantCode) }}</div>
              <div class="tenant-code mono">{{ item.tenantCode }}</div>
            </div>
            <div class="tenant-figures">
              <div class="tenant-cost">{{ money(item.cost) }}</div>
              <div class="tenant-sub">{{ compact(item.calls) }} 次 · {{ compact(item.tokens) }} token</div>
            </div>
          </div>
          <el-empty v-if="!(metrics?.topTenants || []).length" description="暂无租户用量" :image-size="70" />
        </div>
      </el-card>

      <!-- 质量与安全 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div>
            <div class="card-title">质量与安全</div>
            <div class="card-sub">AI 质检结果 + 会话实时预警</div>
          </div>
        </div>
        <div class="quality-row">
          <div class="q-box">
            <div class="q-value">{{ quality?.overview.avgAiScore ?? 0 }}</div>
            <div class="q-label">平均 AI 分</div>
          </div>
          <div class="q-box">
            <div class="q-value">{{ quality?.overview.reviewRate ?? 0 }}%</div>
            <div class="q-label">人工复核率</div>
          </div>
          <div class="q-box">
            <div class="q-value">{{ quality?.overview.qaTasks ?? 0 }}</div>
            <div class="q-label">质检任务</div>
          </div>
        </div>
        <div class="risk-bars">
          <div v-for="risk in riskRows" :key="risk.label" class="risk-item">
            <span class="risk-name">{{ risk.label }}</span>
            <div class="risk-bar"><span :style="{ width: risk.percent + '%', background: risk.color }"></span></div>
            <span class="risk-value">{{ risk.count }}</span>
          </div>
        </div>
        <div class="divider"></div>
        <div class="card-head">
          <div class="card-title small">命中规则 Top</div>
          <span class="card-tip">预警 · 严重</span>
        </div>
        <div class="rule-list">
          <div v-for="rule in quality?.topRules || []" :key="rule.ruleName" class="rule-item">
            <span class="rule-name">{{ rule.ruleName }}</span>
            <span class="rule-type">{{ rule.ruleTypeText }}</span>
            <span class="rule-count">{{ rule.alerts }} 次</span>
          </div>
          <el-empty v-if="!(quality?.topRules || []).length" description="这段时间没有预警命中" :image-size="60" />
        </div>
      </el-card>

      <!-- 预警趋势 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div>
            <div class="card-title">实时预警趋势</div>
            <div class="card-sub">按天命中数（深色=严重级别）</div>
          </div>
        </div>
        <div class="alert-chart">
          <div v-for="point in quality?.alertDaily || []" :key="point.statDate" class="alert-col">
            <div class="alert-total">{{ point.alerts }}</div>
            <div class="alert-bar-wrap">
              <div
                class="alert-bar"
                :style="{ height: alertBarHeight(point.alerts) + '%' }"
                :title="`${point.statDate}：${point.alerts} 条（严重 ${point.severe}）`"
              >
                <div class="alert-bar-severe" :style="{ height: severeShare(point) + '%' }"></div>
              </div>
            </div>
            <div class="alert-day">{{ point.statDate.slice(5) }}</div>
          </div>
          <el-empty v-if="!(quality?.alertDaily || []).length" description="这段时间没有预警" :image-size="80" />
        </div>
        <div class="foot-note">
          发布后每条预警都会回流到质检任务；待处理 {{ quality?.overview.pendingAlerts ?? 0 }} 条，
          涉及 {{ quality?.overview.alertTenants ?? 0 }} 个租户。
        </div>
      </el-card>
    </div>

    <!-- 租户配额 -->
    <el-card shadow="never" class="full-card">
      <div class="card-head">
        <div>
          <div class="card-title">租户 AI 配额</div>
          <div class="card-sub">
            {{ quota?.summary.statDate || '—' }} 今日用量 ÷ 日配额；到达 {{ quota?.warnPercent ?? 80 }}% 标黄，超过标红
          </div>
        </div>
        <div class="head-figures">
          <span>租户 <b>{{ quota?.summary.tenants ?? 0 }}</b></span>
          <span>今日调用 <b>{{ compact(quota?.summary.todayCalls) }}</b></span>
          <span class="danger">接近上限 <b>{{ quota?.summary.warningTenants ?? 0 }}</b></span>
          <span class="danger">已超限 <b>{{ quota?.summary.exceededTenants ?? 0 }}</b></span>
          <span>不限 <b>{{ quota?.summary.unlimitedTenants ?? 0 }}</b></span>
        </div>
      </div>
      <el-alert
        v-if="quota && quota.summary.usageAvailable === false"
        type="info"
        show-icon
        :closable="false"
        title="今日还没取到 AI 用量"
        description="配额限额来自 tenant_db，用量来自 AI 服务；AI 服务不可用时只显示限额。"
        class="mt-12"
      />
      <el-table :data="quota?.items || []" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column label="企业" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || row.tenantCode }}</template>
        </el-table-column>
        <el-table-column label="租户编号" width="180">
          <template #default="{ row }"><span class="mono">{{ row.tenantCode }}</span></template>
        </el-table-column>
        <el-table-column label="AI 日配额" width="120" align="right">
          <template #default="{ row }">{{ row.aiDailyLimit > 0 ? row.aiDailyLimit.toLocaleString() : '不限' }}</template>
        </el-table-column>
        <el-table-column label="今日调用" width="120" align="right">
          <template #default="{ row }">{{ row.todayCalls.toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="使用率" min-width="180">
          <template #default="{ row }">
            <div class="usage-cell">
              <div class="usage-bar">
                <span
                  :style="{
                    width: Math.min(100, row.aiDailyLimit > 0 ? row.usagePercent : 0) + '%',
                    background: quotaColor(row.status),
                  }"
                ></span>
              </div>
              <span class="usage-text" :class="`s-${row.status}`">
                {{ row.aiDailyLimit > 0 ? row.usagePercent + '%' : '不限' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="今日 Token" width="130" align="right">
          <template #default="{ row }">{{ compact(row.todayTokens) }}</template>
        </el-table-column>
        <el-table-column label="今日成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.todayCost) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'danger'" effect="light">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage } from '../../../utils/message'
import {
  BellFilled,
  ChatDotRound,
  CircleCheck,
  DataLine,
  Money,
  Odometer,
  Refresh,
  Timer,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  fetchAiMetrics,
  fetchAiQuality,
  fetchAiQuota,
  type AiMetrics,
  type AiQualityView,
  type AiQuotaView,
} from '../../../api/platform/aimon'

const loading = ref(false)
const days = ref(7)
const metrics = ref<AiMetrics | null>(null)
const quality = ref<AiQualityView | null>(null)
const quota = ref<AiQuotaView | null>(null)

const daysLabel = computed(() => (days.value === 1 ? '今日' : `近 ${days.value} 天`))

/** 空对象当作"没有状态"：ops 代理取不到时会塞一个 {}，不能拿它当"已配置" */
const status = computed(() => {
  const value = metrics.value?.status
  return value && Object.keys(value).length > 0 ? value : null
})


/** 状态条：说清"这些数字是不是真实模型调用"，别让人对着 0 猜 */
const statusTone = computed(() => {
  if (!metrics.value || metrics.value.available === false) {
    return 'tone-warn'
  }
  if (status.value && status.value.llmEnabled === false) {
    return 'tone-warn'
  }
  return 'tone-ok'
})

const statusIcon = computed(() => (statusTone.value === 'tone-ok' ? 'CircleCheck' : 'WarningFilled'))

const statusTitle = computed(() => {
  if (!metrics.value || metrics.value.available === false) {
    return 'AI 服务不可达：暂时取不到用量'
  }
  const current = status.value
  if (!current) {
    return '计量状态未返回：重启 ops-service 后可见'
  }
  if (!current.llmEnabled) {
    return 'AI 服务未配置模型密钥：请求会走规则 / 检索，不产生真实调用与成本'
  }
  return `真实模型已启用：${current.enabledProviders.join(' / ')}`
})

const statusSub = computed(() => {
  if (!metrics.value || metrics.value.available === false) {
    return '确认 AI 服务（http://127.0.0.1:9100）在运行；用量接口是 /api/internal/usage/metrics。'
  }
  const current = status.value
  if (!current) {
    // AI 服务已经能取到用量，只是 ops 还是旧进程（新版才会带 status 字段）
    return '调用量与成本已经从 AI 服务取到了；状态条（用的是哪家模型、最近一次真实调用）由 ops-service 新版提供。'
  }
  if (!current.llmEnabled) {
    return '配好密钥（SUPPORTFLOW_AI_QWEN_API_KEY 或 SUPPORTFLOW_AI_DEEPSEEK_API_KEY）并重启 AI 服务后，这里会开始出现真实调用量与成本。'
  }
  const model = current.enabledProviders.includes('deepseek')
    ? current.deepseekModel
    : current.qwenModel
  const last = current.lastRealCallAt || metrics.value?.lastRealCallAt || '—'
  return `默认模型 ${model || '—'} · 最近一次真实调用 ${last}`
})

const successText = computed(() => {
  const overview = metrics.value?.overview
  if (!overview) {
    return '—'
  }
  return `${overview.successRate}%`
})

const quotaAlertText = computed(() => {
  const summary = quota.value?.summary
  if (!summary) {
    return '—'
  }
  return String(Number(summary.warningTenants || 0) + Number(summary.exceededTenants || 0))
})

/** 租户编码 → 企业名：配额接口自带企业名，这里建索引复用 */
const companyIndex = computed(() => {
  const map: Record<string, string> = {}
  for (const item of quota.value?.items || []) {
    map[item.tenantCode] = item.companyName
  }
  return map
})

function companyName(tenantCode: string): string {
  return companyIndex.value[tenantCode] || tenantCode
}

const COLORS = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#0ea5e9', '#dc2626']

/** 模型分布：按成本占比画条（成本是平台最关心的口径） */
const modelRows = computed(() => {
  const rows = metrics.value?.byModel || []
  const totalCost = rows.reduce((sum, item) => sum + Number(item.cost || 0), 0)
  return rows.map((item, index) => ({
    label: item.modelKey,
    calls: Number(item.calls || 0),
    tokens: Number(item.tokens || 0),
    cost: Number(item.cost || 0),
    failedCalls: Number(item.failedCalls || 0),
    avgLatencyMs: Number(item.avgLatencyMs || 0),
    percent: totalCost === 0 ? 0 : Math.round((Number(item.cost || 0) / totalCost) * 100),
    color: COLORS[index % COLORS.length],
  }))
})

const sceneRows = computed(() =>
  (metrics.value?.byScene || []).map((item) => ({
    key: item.scene,
    label: item.sceneText,
    calls: Number(item.calls || 0),
    cost: Number(item.cost || 0),
  })),
)

/** 风险等级分布（高风险红、中风险黄、低风险蓝） */
const riskRows = computed(() => {
  const overview = quality.value?.overview
  const rows = [
    { label: '高风险', count: Number(overview?.highRiskTasks || 0), color: '#dc2626' },
    { label: '中风险', count: Number(overview?.midRiskTasks || 0), color: '#f59e0b' },
    { label: '低风险', count: Number(overview?.lowRiskTasks || 0), color: '#2563eb' },
  ]
  const max = Math.max(1, ...rows.map((item) => item.count))
  return rows.map((item) => ({ ...item, percent: Math.round((item.count / max) * 100) }))
})

/**
 * 调用量趋势图：不引图表库（项目前端零图表依赖），坐标自己算。
 * 柱=调用量，折线=成本——两条量纲不同，各自按自己的最大值归一化。
 */
const chart = computed(() => {
  const points = metrics.value?.daily || []
  const width = 720
  const height = 240
  const padLeft = 34
  const padRight = 16
  const padTop = 18
  const padBottom = 34
  const innerWidth = width - padLeft - padRight
  const innerHeight = height - padTop - padBottom
  if (!points.length) {
    return { width, height, padLeft, padRight, gridLines: [], bars: [], linePoints: '', dots: [], ticks: [] }
  }
  const maxCalls = Math.max(1, ...points.map((item) => Number(item.calls || 0)))
  const maxCost = Math.max(0.0001, ...points.map((item) => Number(item.cost || 0)))
  const step = innerWidth / points.length
  const barWidth = Math.min(38, step * 0.5)
  const bars = points.map((item, index) => {
    const barHeight = (Number(item.calls || 0) / maxCalls) * (innerHeight * 0.78)
    return {
      x: padLeft + step * index + (step - barWidth) / 2,
      y: height - padBottom - barHeight,
      width: barWidth,
      height: Math.max(Number(item.calls || 0) > 0 ? 3 : 0, barHeight),
    }
  })
  const dots = points.map((item, index) => ({
    x: padLeft + step * index + step / 2,
    y: height - padBottom - (Number(item.cost || 0) / maxCost) * innerHeight * 0.9,
  }))
  return {
    width,
    height,
    padLeft,
    padRight,
    gridLines: [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({ y: padTop + innerHeight * ratio })),
    bars,
    linePoints: dots.map((dot) => `${dot.x},${dot.y}`).join(' '),
    dots,
    ticks: points.map((item, index) => ({
      x: padLeft + step * index + step / 2,
      label: item.statDate.slice(5),
    })),
  }
})

const maxAlerts = computed(() =>
  Math.max(1, ...(quality.value?.alertDaily || []).map((item) => Number(item.alerts || 0))),
)

function alertBarHeight(alerts: number): number {
  return alerts <= 0 ? 2 : Math.max(4, Math.round((Number(alerts || 0) / maxAlerts.value) * 100))
}

function severeShare(point: { alerts: number; severe: number }): number {
  const alerts = Number(point.alerts || 0)
  return alerts === 0 ? 0 : Math.round((Number(point.severe || 0) / alerts) * 100)
}

function quotaColor(status: number): string {
  if (status === 3) {
    return 'linear-gradient(90deg, #f87171, #dc2626)'
  }
  if (status === 2) {
    return 'linear-gradient(90deg, #fbbf24, #f59e0b)'
  }
  return 'linear-gradient(90deg, #93c5fd, #2563eb)'
}

function money(value?: number | null): string {
  const amount = Number(value || 0)
  return '¥' + amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function compact(value?: number | null): string {
  const amount = Number(value || 0)
  if (amount >= 100_000_000) {
    return (amount / 100_000_000).toFixed(1) + '亿'
  }
  if (amount >= 10_000) {
    return (amount / 10_000).toFixed(1) + '万'
  }
  return String(amount)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    // 三块数据分属三个域，任何一个挂了都不该让整页空着
    const [metricData, qualityData, quotaData] = await Promise.all([
      fetchAiMetrics(days.value).catch(() => null),
      fetchAiQuality(days.value).catch(() => null),
      fetchAiQuota().catch(() => null),
    ])
    metrics.value = metricData
    quality.value = qualityData
    quota.value = quotaData
    if (!metricData && !qualityData && !quotaData) {
      errorMessage(undefined, 'AI 监控数据加载失败，请检查 AI 服务与后端服务是否已重启')
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.aimon-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.head-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }

.status-strip {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px; margin-bottom: 16px;
  border-radius: 12px; border: 1px solid #e6ebf2; background: #fff;
}
.status-strip.tone-ok { border-color: #bbf7d0; background: #f0fdf4; }
.status-strip.tone-warn { border-color: #fed7aa; background: #fffbeb; }
.ss-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: #ecfdf5; color: #059669; }
.tone-warn .ss-icon { background: #fff7ed; color: #d97706; }
.ss-body { flex: 1; min-width: 0; }
.ss-title { font-size: 13.5px; font-weight: 700; color: #0f172a; }
.ss-sub { margin-top: 3px; font-size: 12.5px; color: #64748b; }
.ss-figures { display: flex; align-items: center; gap: 14px; font-size: 12.5px; color: #64748b; flex-shrink: 0; }
.ss-figures b { color: #0f172a; }

.kpi-card {
  display: flex; align-items: center; gap: 10px; padding: 14px;
  background: #fff; border: 1px solid #e6ebf2; border-radius: 12px;
}
.kpi-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kpi-icon.blue { background: #eef4ff; color: #2563eb; }
.kpi-icon.green { background: #ecfdf5; color: #059669; }
.kpi-icon.purple { background: #f5f3ff; color: #7c3aed; }
.kpi-icon.amber { background: #fff7ed; color: #d97706; }
.kpi-icon.red { background: #fef2f2; color: #dc2626; }
.kpi-value { font-size: 19px; font-weight: 800; color: #0f172a; line-height: 1.25; }
.kpi-label { margin-top: 2px; font-size: 12px; color: #94a3b8; }

.grid-row { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.col-8 { grid-column: span 8; }
.col-4 { grid-column: span 4; }
.full-card { margin-bottom: 16px; }
.mt-12 { margin-top: 12px; }
.mb-12 { margin-bottom: 12px; }

.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.card-title.small { font-size: 13px; }
.card-sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.card-tip { font-size: 12px; color: #94a3b8; }
.head-figures { display: flex; align-items: center; gap: 14px; font-size: 12.5px; color: #64748b; flex-wrap: wrap; }
.head-figures b { color: #0f172a; }
.danger { color: #dc2626; }

.chart-wrap { margin-top: 10px; }
.usage-chart { width: 100%; height: 240px; }
.grid-line { stroke: #eef2f7; stroke-width: 1; }
.bar-rect { fill: #dbeafe; }
.trend-line { fill: none; stroke: #d97706; stroke-width: 2.4; stroke-linejoin: round; stroke-linecap: round; }
.trend-dot { fill: #fff; stroke: #d97706; stroke-width: 2; }
.axis-text { font-size: 11px; fill: #94a3b8; }

.dist-list { margin-top: 12px; display: flex; flex-direction: column; gap: 12px; }
.dist-top { display: flex; align-items: center; justify-content: space-between; font-size: 12.5px; }
.dist-name { color: #334155; font-weight: 600; }
.dist-value { color: #94a3b8; }
.dist-bar { margin-top: 6px; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.dist-bar span { display: block; height: 100%; border-radius: 999px; }
.dist-sub { margin-top: 4px; font-size: 11.5px; color: #94a3b8; }
.divider { height: 1px; background: #eef2f7; margin: 14px 0; }

.scene-list { margin-top: 10px; }
.scene-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 0; font-size: 12.5px; color: #334155; border-bottom: 1px dashed #eef2f7;
}
.scene-item:last-child { border-bottom: none; }
.scene-name { flex: 1; }
.scene-value { width: 90px; text-align: right; color: #64748b; }

.tenant-list { margin-top: 10px; }
.tenant-item { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px dashed #eef2f7; }
.tenant-item:last-child { border-bottom: none; }
.rank {
  width: 20px; height: 20px; border-radius: 6px; background: #eef4ff; color: #2563eb;
  font-size: 11.5px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.tenant-info { flex: 1; min-width: 0; }
.tenant-name { font-size: 13px; color: #0f172a; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tenant-code { font-size: 11px; }
.tenant-figures { text-align: right; flex-shrink: 0; }
.tenant-cost { font-size: 13px; font-weight: 700; color: #0f172a; }
.tenant-sub { font-size: 11px; color: #94a3b8; }

.quality-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.q-box { background: #f8fafc; border-radius: 10px; padding: 10px; text-align: center; }
.q-value { font-size: 18px; font-weight: 800; color: #0f172a; }
.q-label { margin-top: 2px; font-size: 11.5px; color: #94a3b8; }
.risk-bars { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.risk-item { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #64748b; }
.risk-name { width: 48px; }
.risk-bar { flex: 1; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.risk-bar span { display: block; height: 100%; border-radius: 999px; }
.risk-value { width: 32px; text-align: right; color: #0f172a; font-weight: 600; }
.rule-list { margin-top: 8px; }
.rule-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 12.5px; border-bottom: 1px dashed #eef2f7; }
.rule-item:last-child { border-bottom: none; }
.rule-name { flex: 1; color: #334155; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rule-type { font-size: 11.5px; color: #94a3b8; background: #f1f5f9; padding: 1px 6px; border-radius: 6px; }
.rule-count { width: 56px; text-align: right; color: #0f172a; font-weight: 600; }

.alert-chart { margin-top: 14px; display: flex; align-items: flex-end; gap: 8px; height: 200px; }
.alert-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.alert-total { font-size: 11px; color: #64748b; height: 16px; }
.alert-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.alert-bar {
  width: 100%; border-radius: 6px 6px 0 0; background: #bfdbfe;
  display: flex; align-items: flex-end; overflow: hidden; min-height: 3px;
}
.alert-bar-severe { width: 100%; background: #dc2626; }
.alert-day { margin-top: 6px; font-size: 11px; color: #94a3b8; }
.foot-note { margin-top: 10px; font-size: 11.5px; color: #94a3b8; line-height: 1.7; }

.usage-cell { display: flex; align-items: center; gap: 8px; }
.usage-bar { flex: 1; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.usage-bar span { display: block; height: 100%; border-radius: 999px; }
.usage-text { width: 48px; text-align: right; font-size: 12px; color: #64748b; }
.usage-text.s-2 { color: #d97706; font-weight: 600; }
.usage-text.s-3 { color: #dc2626; font-weight: 600; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: #64748b; }
.foot-tip { margin: 12px 0 0; font-size: 12px; color: #94a3b8; line-height: 1.7; }

@media (max-width: 1400px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .col-8, .col-4 { grid-column: span 12; }
}
</style>
