<template>
  <div class="screen">
    <div class="screen-head">
      <div class="sh-left">
        <h1>SupportFlow 智能客服 · 实时数据大屏</h1>
        <p>{{ tenantText }} · 每 5 秒自动刷新 · 数据全部来自本系统的会话、消息、评价与坐席状态</p>
      </div>
      <div class="sh-right">
        <span class="clock">{{ clock }}</span>
        <el-button size="small" :loading="loading" @click="load">立即刷新</el-button>
        <el-button size="small" @click="router.push('/modules/analytics')">坐席绩效</el-button>
      </div>
    </div>

    <el-alert v-if="error" class="screen-error" type="error" show-icon :closable="false" :title="error" />

    <!-- 第一排：此刻在发生什么 -->
    <div class="kpi-row">
      <div class="kpi">
        <div class="kpi-label">在线坐席</div>
        <div class="kpi-value">{{ data?.onlineAgents ?? '—' }}<small>/{{ (data?.onlineAgents ?? 0) + (data?.busyAgents ?? 0) }} 在线</small></div>
        <div class="kpi-sub">忙碌 {{ data?.busyAgents ?? 0 }} 人</div>
      </div>
      <div class="kpi warn">
        <div class="kpi-label">排队等待</div>
        <div class="kpi-value">{{ data?.queuingSessions ?? '—' }}</div>
        <div class="kpi-sub">客户在等客服接入</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">进行中会话</div>
        <div class="kpi-value">{{ data?.activeSessions ?? '—' }}</div>
        <div class="kpi-sub">机器人接待 {{ data?.botSessions ?? 0 }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">今日会话</div>
        <div class="kpi-value">{{ data?.sessionToday ?? '—' }}</div>
        <div class="kpi-sub">机器人接待占比 {{ data?.botRatio ?? 0 }}%</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">今日消息</div>
        <div class="kpi-value">{{ data?.messageToday ?? '—' }}</div>
        <div class="kpi-sub">其中客户 {{ data?.customerMessageToday ?? 0 }} 条</div>
      </div>
      <div class="kpi" :class="firstResponseTone">
        <div class="kpi-label">平均首次响应</div>
        <div class="kpi-value">{{ durationText(data?.firstResponseSeconds) }}</div>
        <div class="kpi-sub">客户最后一句 → 坐席第一句</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">今日满意度</div>
        <div class="kpi-value">{{ data?.csatScoreToday ? Number(data.csatScoreToday).toFixed(2) : '—' }}</div>
        <div class="kpi-sub">{{ data?.csatCountToday ?? 0 }} 条评价</div>
      </div>
    </div>

    <div class="grid">
      <!-- 今日 24 小时会话趋势：自绘柱状（不引第三方图表库） -->
      <div class="panel span-2">
        <div class="panel-head">
          <span class="panel-title">今日 24 小时会话量</span>
          <span class="panel-sub">峰值 {{ peakHourText }}</span>
        </div>
        <div class="bars">
          <div v-for="item in hourlyBars" :key="item.hour" class="bar-wrap">
            <div class="bar" :style="{ height: item.height + '%' }" :title="`${item.hour}:00 · ${item.count} 条`">
              <span v-if="item.count" class="bar-num">{{ item.count }}</span>
            </div>
            <div class="bar-hour">{{ item.hour }}</div>
          </div>
        </div>
      </div>

      <!-- 坐席实时排行 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">坐席实时排行</span>
          <span class="panel-sub">按今日接待量</span>
        </div>
        <div v-if="data?.topAgents?.length" class="rank-list">
          <div v-for="(item, index) in data.topAgents" :key="item.agentId || item.agentName" class="rank-item">
            <span class="rank-no" :class="{ top3: index < 3 }">{{ index + 1 }}</span>
            <span class="rank-name">{{ item.agentName }}</span>
            <span class="rank-num">{{ item.sessionCount }} 单</span>
            <span class="rank-sub">{{ item.messageCount }} 条消息</span>
            <span class="rank-sub">{{ durationText(item.avgResponseSeconds) }}</span>
          </div>
        </div>
        <div v-else class="panel-empty">今天还没有接入的会话</div>
      </div>

      <!-- 渠道分布 -->
      <div class="panel">
        <div class="panel-head"><span class="panel-title">渠道分布</span><span class="panel-sub">今日</span></div>
        <div v-if="data?.channels?.length" class="dist-list">
          <div v-for="item in data.channels" :key="item.name" class="dist-item">
            <span class="dist-name">{{ item.name }}</span>
            <div class="dist-track"><div class="dist-fill" :style="{ width: ratio(item.count, channelTotal) + '%' }" /></div>
            <span class="dist-num">{{ item.count }}</span>
          </div>
        </div>
        <div v-else class="panel-empty">暂无数据</div>
      </div>

      <!-- 意图 Top -->
      <div class="panel">
        <div class="panel-head"><span class="panel-title">客户意图 Top</span><span class="panel-sub">机器人与大脑识别</span></div>
        <div v-if="data?.intents?.length" class="dist-list">
          <div v-for="item in data.intents" :key="item.name" class="dist-item">
            <span class="dist-name">{{ item.name }}</span>
            <div class="dist-track"><div class="dist-fill blue" :style="{ width: ratio(item.count, intentTotal) + '%' }" /></div>
            <span class="dist-num">{{ item.count }}</span>
          </div>
        </div>
        <div v-else class="panel-empty">暂无数据</div>
      </div>

      <!-- 情绪分布 -->
      <div class="panel">
        <div class="panel-head"><span class="panel-title">客户情绪</span><span class="panel-sub">负面情绪要盯</span></div>
        <div v-if="data?.emotions?.length" class="emotion-row">
          <div v-for="item in data.emotions" :key="item.name" class="emotion-chip" :class="emotionClass(item.name)">
            <div class="ec-name">{{ item.name }}</div>
            <div class="ec-num">{{ item.count }}</div>
          </div>
        </div>
        <div v-else class="panel-empty">暂无数据</div>
      </div>

      <!-- 近 14 天趋势 -->
      <div class="panel span-3">
        <div class="panel-head">
          <span class="panel-title">近 14 天趋势</span>
          <span class="panel-sub">会话量 / 消息量 / 满意度（数据来自坐席绩效日聚合）</span>
        </div>
        <div class="trend">
          <div v-for="item in trendBars" :key="item.day" class="trend-col">
            <div class="trend-bars">
              <div class="tb session" :style="{ height: item.sessionHeight + '%' }" :title="`会话 ${item.sessionCount}`" />
              <div class="tb message" :style="{ height: item.messageHeight + '%' }" :title="`消息 ${item.messageCount}`" />
            </div>
            <div class="trend-day">{{ item.day }}</div>
            <div class="trend-csat" :title="'满意度'">{{ item.csatScore ? Number(item.csatScore).toFixed(1) : '—' }}</div>
          </div>
        </div>
        <div class="legend">
          <span class="lg"><i class="dot session" /> 会话量</span>
          <span class="lg"><i class="dot message" /> 消息量</span>
          <span class="lg"><i class="dot csat" /> 下方数字是当天满意度</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchRealtime, durationText, type RealtimeMetrics } from '../../api/customer/metrics'

const router = useRouter()
const data = ref<RealtimeMetrics | null>(null)
const loading = ref(false)
const error = ref('')
const clock = ref('')
let timer: number | undefined
let clockTimer: number | undefined

const tenantText = computed(() => {
  const raw = localStorage.getItem('supportflow_admin_tenant') || ''
  return raw ? `租户 ${raw}` : '当前租户'
})

/** 今日 24 小时：把后端返回的稀疏点补成 0~23 的连续柱状 */
const hourlyBars = computed(() => {
  const counts = new Map<number, number>()
  for (const item of data.value?.hourly ?? []) {
    counts.set(item.hour, item.count)
  }
  const list = Array.from({ length: 24 }, (_, hour) => ({ hour, count: counts.get(hour) ?? 0 }))
  const max = Math.max(1, ...list.map((item) => item.count))
  return list.map((item) => ({ ...item, height: Math.round((item.count / max) * 100) }))
})

const peakHourText = computed(() => {
  const list = hourlyBars.value
  const peak = list.reduce((a, b) => (b.count > a.count ? b : a), list[0])
  return peak && peak.count ? `${peak.hour}:00 · ${peak.count} 条` : '暂无'
})

const channelTotal = computed(() => (data.value?.channels ?? []).reduce((sum, item) => sum + item.count, 0))
const intentTotal = computed(() => (data.value?.intents ?? []).reduce((sum, item) => sum + item.count, 0))

/** 近 14 天：会话与消息两组柱子，各自按自己的最大值归一化 */
const trendBars = computed(() => {
  const list = data.value?.trend ?? []
  const maxSession = Math.max(1, ...list.map((item) => item.sessionCount))
  const maxMessage = Math.max(1, ...list.map((item) => item.messageCount))
  return list.map((item) => ({
    ...item,
    sessionHeight: Math.round((item.sessionCount / maxSession) * 100),
    messageHeight: Math.round((item.messageCount / maxMessage) * 100),
  }))
})

/** 首响超过 60 秒标黄，超过 180 秒标红——大屏上要一眼看出服务在变慢 */
const firstResponseTone = computed(() => {
  const seconds = data.value?.firstResponseSeconds ?? 0
  if (seconds >= 180) {
    return 'danger'
  }
  return seconds >= 60 ? 'warn' : 'good'
})

function ratio(part: number, total: number) {
  return total <= 0 ? 0 : Math.round((part / total) * 100)
}

function emotionClass(name: string) {
  if (name === '愤怒' || name === '不满') {
    return 'bad'
  }
  if (name === '焦虑') {
    return 'warn'
  }
  return 'normal'
}

function tickClock() {
  const now = new Date()
  clock.value = now.toLocaleTimeString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await fetchRealtime()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  tickClock()
  void load()
  timer = window.setInterval(load, 5000)
  clockTimer = window.setInterval(tickClock, 1000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
  if (clockTimer) {
    window.clearInterval(clockTimer)
  }
})
</script>

<style scoped>
/* 大屏是深色底：和后台的浅色页面刻意区分——它是要投到会议室大屏上看的 */
.screen { min-height: 100%; padding: 18px 20px 28px; background: radial-gradient(1200px 600px at 20% -10%, #1b2a4a 0%, #0d1526 45%, #0a0f1c 100%); color: #e6edf7; border-radius: 12px; }
.screen-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.screen-head h1 { margin: 0; font-size: 22px; letter-spacing: 1px; color: #ffffff; }
.screen-head p { margin: 6px 0 0; font-size: 12px; color: #7d90b0; }
.sh-right { display: flex; align-items: center; gap: 10px; }
.clock { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 18px; color: #7dd3fc; }
.screen-error { margin-bottom: 12px; }

.kpi-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; margin-bottom: 14px; }
.kpi { padding: 12px 14px; border-radius: 12px; background: linear-gradient(180deg, rgba(37, 99, 235, .18), rgba(15, 23, 42, .6)); border: 1px solid rgba(96, 165, 250, .22); }
.kpi-label { font-size: 12px; color: #9fb3d1; }
.kpi-value { margin-top: 6px; font-size: 26px; font-weight: 800; color: #ffffff; line-height: 1.1; }
.kpi-value small { margin-left: 6px; font-size: 12px; font-weight: 500; color: #7d90b0; }
.kpi-sub { margin-top: 6px; font-size: 11px; color: #7d90b0; }
.kpi.warn { border-color: rgba(251, 191, 36, .35); background: linear-gradient(180deg, rgba(251, 191, 36, .16), rgba(15, 23, 42, .6)); }
.kpi.danger { border-color: rgba(248, 113, 113, .4); }
.kpi.good .kpi-value { color: #6ee7b7; }

.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.panel { padding: 14px 16px; border-radius: 12px; background: rgba(15, 23, 42, .72); border: 1px solid rgba(96, 165, 250, .16); }
.span-2 { grid-column: span 2; }
.span-3 { grid-column: span 3; }
.panel-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; }
.panel-title { font-size: 14px; font-weight: 700; color: #dbeafe; }
.panel-sub { font-size: 11px; color: #7d90b0; }
.panel-empty { padding: 24px 0; text-align: center; font-size: 12px; color: #64748b; }

/* 24 小时柱状：纯 CSS，不引图表库 */
.bars { display: flex; align-items: flex-end; gap: 4px; height: 180px; }
.bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.bar { width: 100%; min-height: 3px; border-radius: 3px 3px 0 0; background: linear-gradient(180deg, #38bdf8, #1d4ed8); display: flex; align-items: flex-start; justify-content: center; transition: height .4s ease; }
.bar-num { font-size: 10px; color: #0b1220; font-weight: 700; }
.bar-hour { margin-top: 6px; font-size: 10px; color: #64748b; }

/* 排行 / 分布 */
.rank-list { display: flex; flex-direction: column; gap: 8px; }
.rank-item { display: grid; grid-template-columns: 22px 1fr auto auto auto; align-items: center; gap: 8px; font-size: 12px; color: #cbd5e1; }
.rank-no { width: 20px; height: 20px; border-radius: 6px; background: rgba(96, 165, 250, .18); color: #93c5fd; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; }
.rank-no.top3 { background: rgba(56, 189, 248, .35); color: #e0f2fe; }
.rank-name { color: #e2e8f0; font-weight: 600; }
.rank-num { color: #6ee7b7; font-weight: 700; }
.rank-sub { color: #7d90b0; font-size: 11px; }
.dist-list { display: flex; flex-direction: column; gap: 10px; }
.dist-item { display: grid; grid-template-columns: 84px 1fr 36px; align-items: center; gap: 10px; font-size: 12px; }
.dist-name { color: #cbd5e1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dist-track { height: 8px; border-radius: 999px; background: rgba(148, 163, 184, .16); overflow: hidden; }
.dist-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #22d3ee, #2563eb); }
.dist-fill.blue { background: linear-gradient(90deg, #a78bfa, #7c3aed); }
.dist-num { color: #93c5fd; text-align: right; font-weight: 600; }
.emotion-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(80px, 1fr)); gap: 8px; }
.emotion-chip { padding: 10px 12px; border-radius: 10px; background: rgba(148, 163, 184, .12); border: 1px solid rgba(148, 163, 184, .18); text-align: center; }
.emotion-chip .ec-name { font-size: 12px; color: #cbd5e1; }
.emotion-chip .ec-num { margin-top: 4px; font-size: 20px; font-weight: 800; color: #e2e8f0; }
.emotion-chip.warn { border-color: rgba(251, 191, 36, .35); }
.emotion-chip.bad { border-color: rgba(248, 113, 113, .45); }
.emotion-chip.bad .ec-num { color: #fca5a5; }

/* 近 14 天趋势 */
.trend { display: flex; align-items: flex-end; gap: 10px; height: 160px; }
.trend-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend-bars { flex: 1; display: flex; align-items: flex-end; gap: 3px; width: 100%; }
.tb { flex: 1; min-height: 3px; border-radius: 3px 3px 0 0; transition: height .4s ease; }
.tb.session { background: linear-gradient(180deg, #34d399, #059669); }
.tb.message { background: linear-gradient(180deg, #60a5fa, #2563eb); }
.trend-day { margin-top: 6px; font-size: 10px; color: #64748b; }
.trend-csat { font-size: 10px; color: #fbbf24; }
.legend { display: flex; gap: 16px; margin-top: 10px; font-size: 11px; color: #7d90b0; }
.lg { display: inline-flex; align-items: center; gap: 5px; }
.dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.dot.session { background: #34d399; }
.dot.message { background: #60a5fa; }
.dot.csat { background: #fbbf24; }
</style>
