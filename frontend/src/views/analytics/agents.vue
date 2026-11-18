<template>
  <div class="agent-metrics">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">坐席绩效</h2>
        <p class="page-sub">
          接待量 · 消息量 · 首次响应 · 平均响应 · 会时长 · 转接 · 满意度，点任意一行可下钻到具体会话
        </p>
        <p class="role-line">
          数据来自「坐席 × 天」的预聚合（每 5 分钟自动重算一次），只有企业管理员 / 客服主管能看
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/dashboard/realtime')">
          <el-icon class="btn-icon"><Histogram /></el-icon>
          实时大屏
        </el-button>
        <el-button :loading="loading" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button :loading="rebuilding" @click="rebuild">
          <el-icon class="btn-icon"><Timer /></el-icon>
          重算今天
        </el-button>
        <el-button @click="exportCsv">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="load-error"
      type="error"
      show-icon
      :closable="false"
      :title="error"
    >
      <div class="load-error-tip">
        数据报表只有企业管理员 / 客服主管能看；如果是"没有数据"，先点右上角「重算今天」把当天的绩效算出来。
      </div>
    </el-alert>

    <el-card shadow="never" class="filter-card">
      <el-form class="filter-form">
        <el-form-item>
          <el-date-picker
            v-model="range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            :clearable="false"
            class="w-260"
            @change="reload"
          />
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.sort" class="w-180" @change="reload">
            <el-option value="sessionCount" label="按接待量排序" />
            <el-option value="messageCount" label="按消息量排序" />
            <el-option value="firstResponse" label="按首响时长排序" />
            <el-option value="csat" label="按满意度排序" />
            <el-option value="transfer" label="按转接次数排序" />
            <el-option value="closeCount" label="按结束会话数排序" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
          <el-button @click="resetRange">最近 7 天</el-button>
        </el-form-item>
        <el-form-item class="filter-right">
          <span class="muted">
            统计区间 {{ report?.from || '—' }} ~ {{ report?.to || '—' }}
            <template v-if="report"> · 覆盖 {{ report.list.length }} / {{ report.total }} 个坐席</template>
          </span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="main-card">
      <el-table v-loading="loading" :data="report?.list ?? []" stripe class="agent-table">
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column label="坐席" min-width="150">
          <template #default="{ row }">
            <div class="agent-cell">
              <div class="avatar">{{ row.agentName.slice(0, 1) }}</div>
              <div>
                <div class="a-name">{{ row.agentName }}</div>
                <div class="a-sub">活跃 {{ row.activeDays }} 天</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="接待会话" width="110" sortable>
          <template #default="{ row }">
            <span class="num strong">{{ row.sessionCount }}</span>
            <div class="cell-sub">人工 {{ row.humanSessionCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="日均接待" width="100">
          <template #default="{ row }">{{ row.sessionPerDay }}</template>
        </el-table-column>
        <el-table-column label="消息量" width="110">
          <template #default="{ row }">
            <span class="num">{{ row.messageCount }}</span>
            <div class="cell-sub">客户 {{ row.customerMessageCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="首次响应" width="110">
          <template #default="{ row }">
            <span :class="toneOf(row.firstResponseSeconds)">{{ durationText(row.firstResponseSeconds) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平均响应" width="110">
          <template #default="{ row }">{{ durationText(row.avgResponseSeconds) }}</template>
        </el-table-column>
        <el-table-column label="平均会话时长" width="130">
          <template #default="{ row }">{{ durationText(row.avgSessionSeconds) }}</template>
        </el-table-column>
        <el-table-column label="转接" width="80">
          <template #default="{ row }">{{ row.transferCount }}</template>
        </el-table-column>
        <el-table-column label="满意度" width="140">
          <template #default="{ row }">
            <template v-if="row.csatScore">
              <span class="csat" :class="csatClass(row.csatScore)">{{ Number(row.csatScore).toFixed(2) }}</span>
              <div class="cell-sub">{{ row.csatCount }} 条评价 · 好评 {{ row.goodCsatCount }}</div>
            </template>
            <span v-else class="muted">暂无评价</span>
          </template>
        </el-table-column>
        <el-table-column label="结束会话" width="100">
          <template #default="{ row }">{{ row.closeCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrill(row)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-foot">
        <span class="muted">
          统计口径：首次响应 = 客户最后一句 → 坐席第一句（内部备注不算）；平均响应 = 坐席每条消息距客户上一条的间隔；
          会话时长 = 开始 → 结束（未结束按当前时间算）
        </span>
        <el-pagination
          class="page-bar"
          background
          layout="total, prev, pager, next, sizes"
          :total="Number(report?.total) || 0"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>

    <!-- 下钻抽屉：按天趋势 + 会话明细（点会话号回工作台看上下文） -->
    <el-drawer v-model="drillVisible" size="60%" :destroy-on-close="true">
      <template #header>
        <div class="drawer-head">
          <div class="avatar-lg">{{ drill?.agentName?.slice(0, 1) }}</div>
          <div>
            <div class="head-name">{{ drill?.agentName }}</div>
            <div class="head-sub">{{ drill?.from }} ~ {{ drill?.to }} · 共 {{ drill?.sessions?.length ?? 0 }} 条会话</div>
          </div>
        </div>
      </template>

      <div v-loading="drillLoading" class="drill-body">
        <div class="section-title">按天趋势</div>
        <div v-if="drill?.trend?.length" class="trend">
          <div v-for="item in drillTrend" :key="item.day" class="trend-col">
            <div class="trend-bars">
              <div class="tb session" :style="{ height: item.sessionHeight + '%' }" :title="`会话 ${item.sessionCount}`" />
              <div class="tb message" :style="{ height: item.messageHeight + '%' }" :title="`消息 ${item.messageCount}`" />
            </div>
            <div class="trend-day">{{ item.day }}</div>
            <div class="trend-csat">{{ item.csatScore ? Number(item.csatScore).toFixed(1) : '—' }}</div>
          </div>
        </div>
        <el-empty v-else description="这段时间没有数据" :image-size="90" />

        <div class="section-title">会话明细<span class="section-sub">点「去工作台」看这次会话的上下文</span></div>
        <el-table :data="drill?.sessions ?? []" size="small" empty-text="这段时间没有会话">
          <el-table-column type="index" label="序号" width="64" align="center" />
          <el-table-column label="会话号" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.sessionNo }}</span></template>
          </el-table-column>
          <el-table-column label="客户" width="120">
            <template #default="{ row }">{{ row.customerName || '访客' }}</template>
          </el-table-column>
          <el-table-column label="渠道" width="110">
            <template #default="{ row }">{{ row.source || '—' }}</template>
          </el-table-column>
          <el-table-column label="意图 / 情绪" min-width="150">
            <template #default="{ row }">{{ row.intent || '未识别' }} / {{ row.emotion || '中性' }}</template>
          </el-table-column>
          <el-table-column label="消息" width="80">
            <template #default="{ row }">{{ row.msgCount }}</template>
          </el-table-column>
          <el-table-column label="时长" width="100">
            <template #default="{ row }">{{ row.durationMinutes == null ? '—' : row.durationMinutes + ' 分钟' }}</template>
          </el-table-column>
          <el-table-column label="满意度" width="90">
            <template #default="{ row }">
              <span v-if="row.csatScore" :class="csatClass(row.csatScore)">{{ row.csatScore }} 分</span>
              <span v-else class="muted">未评价</span>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="gotoWorkspace(row.sessionNo)">去工作台</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { ElMessage } from 'element-plus'
import {
  downloadMetricsCsv,
  durationText,
  fetchAgentDrill,
  fetchAgentReport,
  rebuildMetrics,
  type AgentDrill,
  type AgentMetricRow,
  type AgentReport,
} from '../../api/customer/metrics'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const rebuilding = ref(false)
const drillLoading = ref(false)
const error = ref('')
const report = ref<AgentReport | null>(null)
const page = ref(1)
const pageSize = ref(10)
const range = ref<[string, string]>([...lastNDays(7)] as [string, string])
const query = reactive({ sort: 'sessionCount' })

const drillVisible = ref(false)
const drill = ref<AgentDrill | null>(null)

/** 默认最近 7 天（含今天） */
function lastNDays(days: number): [string, string] {
  const to = new Date()
  const from = new Date(to.getTime() - (days - 1) * 24 * 3600 * 1000)
  return [format(from), format(to)]
}

function format(date: Date) {
  const m = `${date.getMonth() + 1}`.padStart(2, '0')
  const d = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}

function buildQuery() {
  return {
    from: range.value?.[0],
    to: range.value?.[1],
    sort: query.sort,
    page: page.value,
    pageSize: pageSize.value,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 坐席绩效同样是企业侧页面：平台账号直接敲 URL 进来时，换到平台首页而不是报"加载失败"
    if (userStore.userType === 1) {
      await router.replace('/admin/reviews')
      return
    }
    report.value = await fetchAgentReport(buildQuery())
  } catch (e) {
    report.value = null
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  void load()
}

function resetRange() {
  range.value = [...lastNDays(7)] as [string, string]
  reload()
}

function onPageChange(next: number) {
  page.value = next
  void load()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  void load()
}

async function rebuild() {
  rebuilding.value = true
  try {
    const result = await rebuildMetrics()
    ElMessage.success(`已重算 ${result.date} 的坐席绩效（${result.rows} 行）`)
    await load()
  } finally {
    rebuilding.value = false
  }
}

async function openDrill(row: AgentMetricRow) {
  if (!row.agentId) {
    ElMessage.info('「未分配 / 机器人接待」不是具体坐席，没有会话明细可下钻')
    return
  }
  drillVisible.value = true
  drillLoading.value = true
  drill.value = null
  try {
    drill.value = await fetchAgentDrill(row.agentId, {
      from: range.value?.[0],
      to: range.value?.[1],
    })
  } finally {
    drillLoading.value = false
  }
}

/** 下钻趋势：会话与消息各自按自己的最大值归一化 */
const drillTrend = computed(() => {
  const list = drill.value?.trend ?? []
  const maxSession = Math.max(1, ...list.map((item) => item.sessionCount))
  const maxMessage = Math.max(1, ...list.map((item) => item.messageCount))
  return list.map((item) => ({
    ...item,
    sessionHeight: Math.round((item.sessionCount / maxSession) * 100),
    messageHeight: Math.round((item.messageCount / maxMessage) * 100),
  }))
})

async function exportCsv() {
  const params = buildQuery()
  delete (params as { page?: number }).page
  delete (params as { pageSize?: number }).pageSize
  await downloadMetricsCsv(params, `坐席绩效-${range.value?.[0]}_${range.value?.[1]}.csv`)
}

function gotoWorkspace(sessionNo: string) {
  void router.push({ path: '/modules/workspace', query: { sessionNo } })
}

/** 首响超过 60 秒标黄、180 秒标红，和实时大屏同一套口径 */
function toneOf(seconds?: number | null) {
  if (!seconds) {
    return 'muted'
  }
  if (seconds >= 180) {
    return 'bad'
  }
  return seconds >= 60 ? 'warn' : 'good'
}

function csatClass(score?: number | null) {
  if (score == null) {
    return ''
  }
  if (score >= 4.5) {
    return 'good'
  }
  if (score >= 3.5) {
    return 'warn'
  }
  return 'bad'
}

function formatTime(value?: string | null) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.agent-metrics { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.role-line { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.load-error { margin-bottom: 12px; }
.load-error-tip { margin-top: 4px; font-size: 12px; line-height: 1.8; }
.filter-card { width: 100%; margin-bottom: 14px; }
.filter-form { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 12px; }
.filter-form :deep(.el-form-item) { margin: 4px 0; }
.filter-right { margin-left: auto; }
.w-180 { width: 180px; }
.w-260 { width: 260px; }
.main-card { width: 100%; }
.agent-cell { display: flex; align-items: center; gap: 10px; }
.avatar { width: 32px; height: 32px; border-radius: 10px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; background: linear-gradient(135deg, #1d4ed8, #3b82f6); }
.avatar-lg { width: 44px; height: 44px; border-radius: 12px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 800; font-size: 18px; background: linear-gradient(135deg, #1d4ed8, #3b82f6); }
.a-name { font-weight: 600; color: #1e293b; }
.a-sub { font-size: 12px; color: #94a3b8; }
.num { font-size: 15px; color: #0f172a; }
.num.strong { font-weight: 800; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.good { color: #16a34a; font-weight: 600; }
.warn { color: #d97706; font-weight: 600; }
.bad { color: #dc2626; font-weight: 600; }
.csat { font-weight: 700; }
.table-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; font-size: 12px; flex-wrap: wrap; }
.page-bar { margin-left: auto; }
.drawer-head { display: flex; align-items: center; gap: 12px; }
.head-name { font-size: 17px; font-weight: 700; color: #0f172a; }
.head-sub { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.drill-body { padding: 0 2px 8px; }
.section-title { margin: 14px 0 10px; font-weight: 700; color: #0f172a; font-size: 14px; }
.section-sub { margin-left: 8px; font-weight: 400; font-size: 12px; color: #94a3b8; }
.trend { display: flex; align-items: flex-end; gap: 8px; height: 120px; }
.trend-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend-bars { flex: 1; display: flex; align-items: flex-end; gap: 3px; width: 100%; }
.tb { flex: 1; min-height: 3px; border-radius: 3px 3px 0 0; }
.tb.session { background: linear-gradient(180deg, #34d399, #059669); }
.tb.message { background: linear-gradient(180deg, #60a5fa, #2563eb); }
.trend-day { margin-top: 6px; font-size: 10px; color: #94a3b8; }
.trend-csat { font-size: 10px; color: #d97706; }
</style>
