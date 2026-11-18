<template>
  <div class="pdata-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">平台数据</h2>
        <p class="page-sub">
          平台经营分析 · 租户聚合视图（客户与业务明细保持租户隔离）
        </p>
      </div>
      <div class="head-actions">
        <span class="live-badge"><span class="dot"></span>聚合数据 · 实时</span>
        <el-button :loading="loading" @click="load()">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <!-- 顶部：经营大盘 -->
    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon blue"><el-icon :size="18"><OfficeBuilding /></el-icon></div>
        <div>
          <div class="kpi-value">{{ tenants?.overview.tenantTotal ?? '—' }}</div>
          <div class="kpi-label">租户总数</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon green"><el-icon :size="18"><TrendCharts /></el-icon></div>
        <div>
          <div class="kpi-value">{{ tenants?.overview.newThisMonth ?? '—' }}</div>
          <div class="kpi-label">本月新增</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon purple"><el-icon :size="18"><Medal /></el-icon></div>
        <div>
          <div class="kpi-value">{{ tenants?.overview.paidTenants ?? '—' }}</div>
          <div class="kpi-label">付费租户（不含免费版）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><Money /></el-icon></div>
        <div>
          <div class="kpi-value">{{ money(revenue?.paidTotal) }}</div>
          <div class="kpi-label">累计实收（订单）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><Wallet /></el-icon></div>
        <div>
          <div class="kpi-value">{{ money(revenue?.paidThisMonth) }}</div>
          <div class="kpi-label">本月实收 · {{ revenue?.cycleKey || '—' }}</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon blue"><el-icon :size="18"><DataLine /></el-icon></div>
        <div>
          <div class="kpi-value">{{ compact(tenants?.overview.monthRequests) }}</div>
          <div class="kpi-label">本月请求量（跨租户）</div>
        </div>
      </div>
    </div>

    <div class="grid-row">
      <!-- 租户增长趋势 -->
      <el-card shadow="never" class="col-8">
        <div class="card-head">
          <div>
            <div class="card-title">租户增长趋势</div>
            <div class="card-sub">近 {{ growth.length }} 个月新增与累计（按租户开通时间）</div>
          </div>
          <div class="legend">
            <span class="li"><span class="sw bar"></span>当月新增</span>
            <span class="li"><span class="sw line"></span>累计租户</span>
          </div>
        </div>
        <div class="chart-body">
          <svg class="growth-chart" :viewBox="`0 0 ${growthChart.width} ${growthChart.height}`" preserveAspectRatio="none">
            <line
              v-for="line in growthChart.gridLines"
              :key="`g-${line.y}`"
              :x1="growthChart.padLeft"
              :y1="line.y"
              :x2="growthChart.width - growthChart.padRight"
              :y2="line.y"
              class="grid-line"
            />
            <rect
              v-for="(bar, index) in growthChart.bars"
              :key="`b-${index}`"
              :x="bar.x"
              :y="bar.y"
              :width="bar.width"
              :height="bar.height"
              rx="4"
              class="bar-rect"
            />
            <polyline :points="growthChart.linePoints" class="trend-line" />
            <circle
              v-for="(dot, index) in growthChart.dots"
              :key="`d-${index}`"
              :cx="dot.x"
              :cy="dot.y"
              r="3"
              class="trend-dot"
            />
            <text
              v-for="(tick, index) in growthChart.ticks"
              :key="`t-${index}`"
              :x="tick.x"
              :y="growthChart.height - 6"
              text-anchor="middle"
              class="axis-text"
            >
              {{ tick.label }}
            </text>
          </svg>
          <el-empty v-if="!growth.length" description="暂无租户增长数据" :image-size="80" />
        </div>
      </el-card>

      <!-- 套餐分布 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div class="card-title">套餐分布</div>
        </div>
        <div class="dist-list">
          <div v-for="item in planRows" :key="item.label" class="dist-item">
            <div class="dist-top">
              <span class="dist-name">{{ item.label }}</span>
              <span class="dist-value">{{ item.count }} 家 · {{ item.percent }}%</span>
            </div>
            <div class="dist-bar"><span :style="{ width: item.percent + '%', background: item.color }"></span></div>
          </div>
          <el-empty v-if="!planRows.length" description="暂无租户" :image-size="70" />
        </div>
        <div class="foot-note">
          付费租户 {{ tenants?.overview.paidTenants ?? 0 }} 家 ·
          免费版 {{ freeTenants }} 家 ·
          未开套餐 {{ tenants?.planDistribution.find((p) => !p.planId)?.tenantCount ?? 0 }} 家
        </div>
      </el-card>
    </div>

    <div class="grid-row">
      <!-- 收入趋势 -->
      <el-card shadow="never" class="col-8">
        <div class="card-head">
          <div>
            <div class="card-title">平台收入趋势</div>
            <div class="card-sub">近 {{ revenueTrend.length }} 个月已支付订单实收（预付费口径）</div>
          </div>
          <div class="head-figures">
            <span>累计 <b>{{ money(revenue?.paidTotal) }}</b></span>
            <span>客单价 <b>{{ money(revenue?.avgOrderAmount) }}</b></span>
            <span>付费租户 <b>{{ revenue?.payingTenants ?? 0 }}</b> 家</span>
          </div>
        </div>
        <div class="revenue-chart">
          <div v-for="point in revenueTrend" :key="point.month" class="rev-col">
            <div class="rev-amount">{{ point.paidAmount > 0 ? money(point.paidAmount) : '' }}</div>
            <div class="rev-bar-wrap">
              <div
                class="rev-bar"
                :style="{ height: revenueBarHeight(point.paidAmount) + '%' }"
                :title="`${point.month}：${money(point.paidAmount)}（${point.paidOrders} 笔）`"
              ></div>
            </div>
            <div class="rev-month">{{ point.month.slice(5) }}月</div>
          </div>
          <el-empty v-if="!revenueTrend.length" description="暂无收入数据" :image-size="80" />
        </div>
        <div class="rev-legend">
          <span v-for="item in revenue?.planRevenue || []" :key="item.planId" class="li">
            {{ item.planName }}：{{ money(item.paidAmount) }}（{{ item.paidOrders }} 笔）
          </span>
        </div>
      </el-card>

      <!-- 数据边界 + 账期账单 -->
      <el-card shadow="never" class="col-4">
        <div class="card-head">
          <div class="card-title">数据边界</div>
        </div>
        <div class="boundary-item">
          <div class="bi-top"><span class="dot green"></span><span class="t">聚合可见</span></div>
          <div class="bi-desc">用量、套餐、活跃度、经营统计</div>
        </div>
        <div class="boundary-item">
          <div class="bi-top"><span class="dot amber"></span><span class="t">明细隔离</span></div>
          <div class="bi-desc">客户档案、会话内容、工单明细仅租户可见</div>
        </div>
        <div class="boundary-item">
          <div class="bi-top"><span class="dot blue"></span><span class="t">审计留痕</span></div>
          <div class="bi-desc">平台查看与变更操作全量审计</div>
        </div>

        <div class="divider"></div>
        <div class="card-title small">后付费账期（与预付费分开摆）</div>
        <div class="stmt-row">
          <span>账单 <b>{{ revenue?.statements.statements ?? 0 }}</b> 张</span>
          <span>已结算 <b>{{ money(revenue?.statements.settledAmount) }}</b></span>
        </div>
        <div class="stmt-row">
          <span>待结算 <b>{{ money(revenue?.statements.pendingAmount) }}</b></span>
          <span :class="{ danger: (revenue?.statements.overdueCount ?? 0) > 0 }">
            逾期 <b>{{ money(revenue?.statements.overdueAmount) }}</b>
          </span>
        </div>
        <div class="foot-note">
          实收只看已支付订单；账期账单承载后付费超量费，两套口径不混算。
        </div>
      </el-card>
    </div>

    <!-- 用量 Top -->
    <el-card shadow="never" class="full-card">
      <div class="card-head">
        <div>
          <div class="card-title">TOP 租户用量</div>
          <div class="card-sub">本月请求量前 6（网关按天上报，只出聚合量）</div>
        </div>
      </div>
      <div class="top-list">
        <div v-for="(item, index) in tenants?.topTenants || []" :key="item.tenantCode" class="top-item">
          <span class="top-rank">{{ index + 1 }}</span>
          <span class="top-name">{{ item.companyName || '（未填企业名）' }}</span>
          <span class="top-code mono">{{ item.tenantCode }}</span>
          <span class="top-bar">
            <span :style="{ width: topPercent(item.monthRequests) + '%' }"></span>
          </span>
          <span class="top-value">{{ compact(item.monthRequests) }} 请求</span>
          <span class="top-value muted">{{ compact(item.monthSessions) }} 会话</span>
        </div>
        <el-empty v-if="!(tenants?.topTenants || []).length" description="本月还没有用量上报" :image-size="80" />
      </div>
    </el-card>

    <!-- 租户列表 -->
    <el-card shadow="never" class="full-card">
      <div class="card-head">
        <div>
          <div class="card-title">租户列表</div>
          <div class="card-sub">企业 + 套餐 + 本月用量；点企业名可跳到租户管理查看隔离与限流</div>
        </div>
        <div class="list-filters">
          <el-input
            v-model="query.keyword"
            class="w-220"
            clearable
            placeholder="企业名称 / 租户编码"
            @keydown.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-select v-model="query.status" class="w-140" placeholder="全部状态" clearable @change="applyFilter">
            <el-option :value="1" label="正常" />
            <el-option :value="2" label="冻结" />
            <el-option :value="3" label="到期" />
            <el-option :value="4" label="注销" />
          </el-select>
          <el-button @click="applyFilter">查询</el-button>
        </div>
      </div>

      <el-table :data="tenants?.tenants.list || []" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="indexOfRow" />
        <el-table-column label="企业" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="link" @click="goTenants(row)">{{ row.companyName || '（未填企业名）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="租户编号" width="190">
          <template #default="{ row }"><span class="mono">{{ row.tenantCode }}</span></template>
        </el-table-column>
        <el-table-column label="套餐" width="110">
          <template #default="{ row }">{{ planName(row.planId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'warning'" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="本月请求" width="120" align="right">
          <template #default="{ row }">{{ compact(row.monthRequests) }}</template>
        </el-table-column>
        <el-table-column label="本月会话" width="120" align="right">
          <template #default="{ row }">{{ compact(row.monthSessions) }}</template>
        </el-table-column>
        <el-table-column label="到期时间" width="170">
          <template #default="{ row }">{{ row.expireTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="最近活跃" width="130">
          <template #default="{ row }">{{ row.lastActiveDate || '—' }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的租户" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        class="pager"
        layout="total, prev, pager, next"
        :total="Number(tenants?.tenants.total) || 0"
        :page-size="query.pageSize"
        @current-change="loadTenants"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../../../utils/message'
import { DataLine, Medal, Money, OfficeBuilding, Refresh, TrendCharts, Wallet } from '@element-plus/icons-vue'
import {
  fetchRevenueAnalytics,
  fetchTenantAnalytics,
  type PlatformRevenueAnalytics,
  type PlatformTenantAnalytics,
} from '../../../api/platform/analytics'
import { fetchPlatformPlans, type PlatformPlanRow } from '../../../api/platform/settings'

const router = useRouter()

const loading = ref(false)
const tenants = ref<PlatformTenantAnalytics | null>(null)
const revenue = ref<PlatformRevenueAnalytics | null>(null)
const plans = ref<PlatformPlanRow[]>([])

/**
 * 状态下拉的初值是 undefined，不是 0：0 没有对应选项，Element Plus 会把
 * "没有匹配项的值"直接渲染成 0，看着像多了一个叫 0 的状态。
 */
const query = reactive<{ keyword: string; status?: number; page: number; pageSize: number }>({
  keyword: '',
  status: undefined,
  page: 1,
  pageSize: 10,
})

const growth = computed(() => tenants.value?.growth || [])
const revenueTrend = computed(() => revenue.value?.revenueTrend || [])

/** 图表配色：和租户管理页、计费页保持同一套（蓝/绿/琥珀/紫） */
const PLAN_COLORS = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#0ea5e9']

const planRows = computed(() => {
  const rows = tenants.value?.planDistribution || []
  // Number 兜底：大整数在部分接口里是字符串（Long→String 序列化），不转会让百分比算错
  const total = rows.reduce((sum, item) => sum + Number(item.tenantCount || 0), 0)
  return rows.map((item, index) => ({
    label: item.planId ? planName(item.planId) : '未开套餐',
    count: Number(item.tenantCount || 0),
    percent: total === 0 ? 0 : Math.round((Number(item.tenantCount || 0) / total) * 100),
    color: item.planId ? PLAN_COLORS[index % PLAN_COLORS.length] : '#cbd5e1',
  }))
})

const freeTenants = computed(() => {
  const freePlan = plans.value.find((item) => item.planCode === 'FREE')
  if (!freePlan) {
    return 0
  }
  return (tenants.value?.planDistribution || [])
    .filter((item) => item.planId === freePlan.id)
    .reduce((sum, item) => sum + Number(item.tenantCount || 0), 0)
})

/**
 * 增长图：柱（当月新增）+ 折线（累计）。
 *
 * <p>不引图表库（这个项目前端零图表依赖）：自己算好坐标画 SVG——
 * 数据规模就这么大，够用且不会有额外包体积。</p>
 */
const growthChart = computed(() => {
  const points = growth.value
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
  const maxNew = Math.max(1, ...points.map((item) => item.newTenants))
  const maxCumulative = Math.max(1, ...points.map((item) => item.cumulative))
  const step = innerWidth / points.length
  const barWidth = Math.min(38, step * 0.44)
  const bars = points.map((item, index) => {
    const barHeight = (item.newTenants / maxNew) * (innerHeight * 0.72)
    return {
      x: padLeft + step * index + (step - barWidth) / 2,
      y: height - padBottom - barHeight,
      width: barWidth,
      height: Math.max(item.newTenants > 0 ? 3 : 0, barHeight),
    }
  })
  const dots = points.map((item, index) => ({
    x: padLeft + step * index + step / 2,
    y: height - padBottom - (item.cumulative / maxCumulative) * innerHeight,
  }))
  const gridLines = [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    y: padTop + innerHeight * ratio,
  }))
  const ticks = points.map((item, index) => ({
    x: padLeft + step * index + step / 2,
    label: item.month.slice(5) + '月',
  }))
  return {
    width,
    height,
    padLeft,
    padRight,
    gridLines,
    bars,
    linePoints: dots.map((dot) => `${dot.x},${dot.y}`).join(' '),
    dots,
    ticks,
  }
})

const maxRevenue = computed(() => Math.max(1, ...revenueTrend.value.map((item) => item.paidAmount)))

function revenueBarHeight(amount: number): number {
  return amount <= 0 ? 2 : Math.max(4, Math.round((amount / maxRevenue.value) * 100))
}

const topMax = computed(() => Math.max(1, ...(tenants.value?.topTenants || []).map((item) => item.monthRequests)))

function topPercent(requests: number): number {
  return Math.max(2, Math.round((requests / topMax.value) * 100))
}

function planName(planId?: string | null): string {
  if (!planId) {
    return '未开套餐'
  }
  return plans.value.find((item) => item.id === planId)?.planName || `套餐 ${planId.slice(-4)}`
}

function money(value?: number | null): string {
  const amount = Number(value || 0)
  return '¥' + amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 大数字压缩成 1.2万 / 3.4亿，表格里不撑破列宽 */
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

function indexOfRow(index: number): number {
  return (query.page - 1) * query.pageSize + index + 1
}

function goTenants(row: { tenantCode: string }): void {
  void router.push({ path: '/platform/tenants', query: { keyword: row.tenantCode } })
}

function applyFilter(): void {
  query.page = 1
  void loadTenants()
}

async function loadTenants(): Promise<void> {
  loading.value = true
  try {
    tenants.value = await fetchTenantAnalytics({
      months: 8,
      page: query.page,
      pageSize: query.pageSize,
      keyword: query.keyword,
      status: query.status || undefined,
    })
  } catch (e) {
    errorMessage(e, '平台数据加载失败')
  } finally {
    loading.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [tenantData, revenueData, planList] = await Promise.all([
      fetchTenantAnalytics({
        months: 8,
        page: query.page,
        pageSize: query.pageSize,
        keyword: query.keyword,
        status: query.status || undefined,
      }),
      fetchRevenueAnalytics(6),
      fetchPlatformPlans(),
    ])
    tenants.value = tenantData
    revenue.value = revenueData
    plans.value = planList
  } catch (e) {
    errorMessage(e, '平台数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pdata-page { width: 100%; min-width: 0; }

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
.live-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 12.5px;
  font-weight: 600;
}
.live-badge .dot { width: 7px; height: 7px; border-radius: 50%; background: #10b981; }

.kpi-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
.kpi-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kpi-icon.blue { background: #eef4ff; color: #2563eb; }
.kpi-icon.green { background: #ecfdf5; color: #059669; }
.kpi-icon.purple { background: #f5f3ff; color: #7c3aed; }
.kpi-icon.amber { background: #fff7ed; color: #d97706; }
.kpi-value { font-size: 19px; font-weight: 800; color: #0f172a; line-height: 1.25; }
.kpi-label { margin-top: 2px; font-size: 12px; color: #94a3b8; }

.grid-row { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.col-8 { grid-column: span 8; }
.col-4 { grid-column: span 4; }
.full-card { margin-bottom: 16px; }

.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.card-title.small { font-size: 13px; }
.card-sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.legend { display: flex; align-items: center; gap: 14px; font-size: 12px; color: #64748b; }
.legend .li { display: inline-flex; align-items: center; gap: 6px; }
.legend .sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.legend .sw.bar { background: #2563eb; }
.legend .sw.line { background: #059669; border-radius: 50%; }

.chart-body { margin-top: 10px; }
.growth-chart { width: 100%; height: 240px; }
.grid-line { stroke: #eef2f7; stroke-width: 1; }
.bar-rect { fill: #dbeafe; }
.trend-line { fill: none; stroke: #059669; stroke-width: 2.4; stroke-linejoin: round; stroke-linecap: round; }
.trend-dot { fill: #fff; stroke: #059669; stroke-width: 2; }
.axis-text { font-size: 11px; fill: #94a3b8; }

.dist-list { margin-top: 12px; display: flex; flex-direction: column; gap: 12px; }
.dist-top { display: flex; align-items: center; justify-content: space-between; font-size: 12.5px; }
.dist-name { color: #334155; font-weight: 600; }
.dist-value { color: #94a3b8; }
.dist-bar { margin-top: 6px; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.dist-bar span { display: block; height: 100%; border-radius: 999px; }
.foot-note { margin-top: 12px; font-size: 11.5px; color: #94a3b8; line-height: 1.7; }

.head-figures { display: flex; align-items: center; gap: 16px; font-size: 12.5px; color: #64748b; }
.head-figures b { color: #0f172a; }

.revenue-chart { margin-top: 14px; display: flex; align-items: flex-end; gap: 10px; height: 220px; }
.rev-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.rev-amount { font-size: 11px; color: #64748b; height: 16px; }
.rev-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.rev-bar { width: 100%; border-radius: 6px 6px 0 0; background: linear-gradient(180deg, #60a5fa 0%, #2563eb 100%); min-height: 3px; }
.rev-month { margin-top: 6px; font-size: 11.5px; color: #94a3b8; }
.rev-legend { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; color: #64748b; }

.boundary-item { margin-top: 12px; }
.bi-top { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #334155; font-weight: 600; }
.bi-desc { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.green { background: #10b981; }
.dot.amber { background: #f59e0b; }
.dot.blue { background: #3b82f6; }
.divider { height: 1px; background: #eef2f7; margin: 14px 0; }
.stmt-row { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; font-size: 12.5px; color: #64748b; }
.stmt-row b { color: #0f172a; }
.stmt-row .danger b { color: #dc2626; }

.top-list { margin-top: 12px; }
.top-item { display: flex; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px dashed #eef2f7; }
.top-item:last-child { border-bottom: none; }
.top-rank {
  width: 22px; height: 22px; border-radius: 6px; background: #eef4ff; color: #2563eb;
  font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.top-name { width: 200px; font-size: 13px; color: #0f172a; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-code { width: 180px; }
.top-bar { flex: 1; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.top-bar span { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #93c5fd, #2563eb); }
.top-value { width: 110px; text-align: right; font-size: 12.5px; color: #334155; }
.top-value.muted { color: #94a3b8; }

.list-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.w-220 { width: 220px; }
.w-140 { width: 140px; }
.mt-12 { margin-top: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: #64748b; }
.link { color: #2563eb; cursor: pointer; }
.link:hover { text-decoration: underline; }

@media (max-width: 1400px) {
  .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .col-8, .col-4 { grid-column: span 12; }
  .top-code { display: none; }
}
</style>
