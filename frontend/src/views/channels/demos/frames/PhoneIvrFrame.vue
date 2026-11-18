<template>
  <!-- 照原型 channel-phone.html：深色通话界面 + AI 语音导航按键 + 挂机小结 -->
  <PhoneShell
    time="10:31"
    :signal="ended ? '通话结束' : `通话中 • ${clock}`"
    :status-style="{ background: 'transparent', color: '#fff' }"
    :screen-style="{ background: '#0f172a' }"
  >
    <div class="call-body">
      <div class="avatar-lg">云</div>
      <div class="call-name">{{ human ? '人工坐席 · 陈思远' : '400-820-8855 · SupportFlow 客服热线' }}</div>
      <div class="call-state">{{ human ? '已接通 · 通话录音转写中' : 'AI 语音导航中' }}</div>
      <div class="call-timer">{{ clock }}</div>
      <div class="ivr-wrap">
        <div v-for="(line, index) in lines" :key="index" class="ivr-line" :class="{ self: line.self }" v-html="line.html" />
      </div>
      <div v-if="!ended" class="keypad">
        <button v-for="key in keypad" :key="key.digit" type="button" @click="press(key.digit)">
          {{ key.digit }}<small v-if="key.label">{{ key.label }}</small>
        </button>
      </div>
      <button v-if="!ended" class="call-end" type="button" @click="hangup">
        <el-icon :size="20"><PhoneFilled /></el-icon>
      </button>
      <div v-else class="summary-card">
        <div class="sc-title">通话小结（挂机自动生成）</div>
        <div class="sc-row"><span>通话时长</span><b>{{ clock }}</b></div>
        <div class="sc-row"><span>客户诉求</span><b>{{ summary.intent }}</b></div>
        <div class="sc-row"><span>处理动作</span><b>{{ summary.action }}</b></div>
        <div class="sc-row"><span>工单号</span><b class="mono">{{ summary.ticket }}</b></div>
        <div class="sc-row"><span>SLA</span><b>{{ summary.sla }}</b></div>
        <button class="btn btn-primary sc-btn" type="button" @click="restart">再体验一次</button>
      </div>
    </div>
  </PhoneShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import PhoneShell from './PhoneShell.vue'

const seconds = ref(12)
const human = ref(false)
const ended = ref(false)
const chosen = ref('')
const lines = ref<Array<{ html: string; self?: boolean }>>([])
let timer: number | null = null

const keypad = [
  { digit: '1', label: '订单查询' },
  { digit: '2', label: '售后' },
  { digit: '3', label: '投诉' },
  { digit: '4' },
  { digit: '5' },
  { digit: '6' },
  { digit: '7' },
  { digit: '8' },
  { digit: '9' },
  { digit: '*' },
  { digit: '0', label: '人工' },
  { digit: '#' },
]

const clock = computed(() => {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(Math.floor(seconds.value / 60))}:${pad(seconds.value % 60)}`
})

const summary = computed(() => {
  if (chosen.value === '1') {
    return { intent: '查询订单物流', action: '语音播报物流并短信同步', ticket: '—（无需人工，已闭环）', sla: '已解决' }
  }
  if (chosen.value === '2') {
    return { intent: '售后上门检修', action: '派单杭州售后组 · 4 小时内联系', ticket: 'TK-260828-021', sla: '24 小时内首次响应' }
  }
  if (chosen.value === '3') {
    return { intent: '服务态度投诉', action: '通知主管与质检专员回电', ticket: 'TK-260828-022', sla: '2 小时内回电' }
  }
  return { intent: '转人工咨询', action: '排队接入坐席 1024', ticket: 'TK-260828-023', sla: '24 小时内首次响应' }
})

function tick() {
  if (ended.value) return
  seconds.value += 1
  timer = window.setTimeout(tick, 1000)
}

function line(html: string, self = false) {
  lines.value.push({ html, self })
}

function delay(fn: () => void, ms: number) {
  window.setTimeout(fn, ms)
}

function press(digit: string) {
  if (ended.value) return
  if (human.value) {
    line(`（语音）<b>${digit}</b>`, true)
    return
  }
  chosen.value = digit
  if (digit === '1') {
    line('<b>您</b>：按 1', true)
    line('AI：正在为您查询订单 OR202608281234 的物流状态…')
    delay(() => line('AI：订单正在配送中，预计今日 18:00 前送达。因天气延误已发放 5 元关怀券，如需转人工请按 0。'), 1200)
  } else if (digit === '2') {
    line('<b>您</b>：按 2', true)
    line('AI：已为您登记售后上门检修，工程师会在 4 小时内与您联系。')
    delay(() => line('AI：已自动创建工单 <b>TK-260828-021</b>，SLA 24 小时内首次响应。'), 1200)
  } else if (digit === '3') {
    line('<b>您</b>：按 3', true)
    line('AI：投诉已记录，主管会在 2 小时内回电，给您带来不便非常抱歉。')
  } else if (digit === '0') {
    line('<b>您</b>：按 0 转人工', true)
    line('AI：正在为您转接人工坐席，请稍候…')
    delay(() => {
      human.value = true
      line('人工：您好，我是客服陈思远。已看到您查询的订单 OR202608281234，包裹因天气原因延误，已发放 5 元关怀券，需要我为您加急吗？')
      delay(() => line('<b>您</b>：好的，麻烦尽快，我比较着急。', true), 1100)
      delay(() => line('人工：明白，已为您标记加急，今天 16:00 前短信同步最新进度。还有其他可以帮您吗？'), 2200)
    }, 1300)
  } else {
    line(`<b>您</b>：按 ${digit}`, true)
    line('AI：这个按键暂时没有对应业务，1 订单查询、2 售后、3 投诉、0 转人工。')
  }
}

function hangup() {
  ended.value = true
  if (timer) {
    window.clearTimeout(timer)
    timer = null
  }
}

function restart() {
  ended.value = false
  human.value = false
  chosen.value = ''
  seconds.value = 12
  lines.value = []
  tick()
}

tick()

/** 详情页「体验语音导航」按钮：从头演一遍 */
defineExpose({ restart })

onBeforeUnmount(() => {
  if (timer) window.clearTimeout(timer)
})
</script>

<style scoped>
.ivr-wrap { width: 100%; max-height: 190px; overflow-y: auto; }
.summary-card .sc-title { font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
.summary-card .sc-row { display: flex; justify-content: space-between; gap: 12px; font-size: 12.5px; color: #3f4357; padding: 4px 0; }
.summary-card .sc-row span { color: #8a8fa3; }
.summary-card .sc-btn { width: 100%; margin-top: 10px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
