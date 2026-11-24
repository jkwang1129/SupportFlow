<template>
  <section class="approval-timeline" aria-label="任务事件">
    <h3>任务事件</h3>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-else-if="!events.length">暂无已记录的任务事件。</p>
    <ol v-if="events.length" :start="events[0]?.seq">
      <li v-for="event in events" :key="event.seq">
        <time :datetime="event.occurred_at">{{ new Date(event.occurred_at).toLocaleTimeString() }}</time>
        {{ labels[event.event_type] }}<span v-if="event.step_no"> · 步骤 {{ event.step_no }}</span><span v-if="event.outcome"> · {{ outcomes[event.outcome] }}</span>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { isAxiosError } from 'axios'
import { readApprovalEvents, type ApprovalEvent, type ApprovalRunReference, type ApprovalTerminalState } from '../api/customer/agentApproval'

const props = defineProps<{ reference: ApprovalRunReference; terminal: boolean }>()
const emit = defineEmits<{ settled: [state: ApprovalTerminalState]; unavailable: [error: unknown] }>()
const labels: Record<ApprovalEvent['event_type'], string> = {
  run_started: '任务开始',
  step_started: '步骤开始',
  step_completed: '步骤结束',
  tool_started: '工具调用开始',
  tool_completed: '工具调用结束',
  approval_required: '等待坐席审批',
  run_completed: '任务结束 · 成功',
  run_failed: '任务结束 · 失败',
  run_cancelled: '任务结束 · 已取消',
}
const outcomes = { SUCCEEDED: '成功', FAILED: '失败', UNKNOWN: '结果待核实' }
const terminalStates: Partial<Record<ApprovalEvent['event_type'], ApprovalTerminalState>> = {
  run_completed: 'SUCCEEDED', run_failed: 'FAILED', run_cancelled: 'CANCELLED',
}
const pollInterval = 2000
const events = ref<ApprovalEvent[]>([])
const notice = ref('')
let cursor = 0
let retryDelay = pollInterval
let stopped = false
let timer: number | undefined
let pending: AbortController | undefined

function schedule(delay: number) {
  window.clearTimeout(timer)
  if (!stopped && !pending && navigator.onLine) timer = window.setTimeout(() => void poll(), delay)
}

function disconnect() {
  if (stopped) return
  window.clearTimeout(timer)
  pending?.abort()
  notice.value = '网络连接已中断，恢复后继续显示任务事件。'
}

function applyPage(page: Awaited<ReturnType<typeof readApprovalEvents>>) {
  if (page?.schema_version !== 'agent-run-events-v1' || page.run_id !== props.reference.run_id
      || !Array.isArray(page.events) || page.events.length > 16 || typeof page.has_more !== 'boolean')
    throw new Error('Invalid event page')
  const fresh = page.events.filter((event) => {
    if (!Number.isInteger(event.seq) || event.seq < 1) throw new Error('Invalid event sequence')
    return event.seq > cursor
  })
  const next = cursor + fresh.length
  if (page.next_seq !== next || fresh.some((event, index) =>
    event.seq !== cursor + index + 1 || !Object.prototype.hasOwnProperty.call(labels, event.event_type)
    || (event.step_no !== null && (!Number.isInteger(event.step_no) || event.step_no < 1 || event.step_no > 100))
    || (event.outcome !== null && !Object.prototype.hasOwnProperty.call(outcomes, event.outcome))
    || !Number.isFinite(Date.parse(event.occurred_at))))
    throw new Error('Invalid event cursor')
  if (fresh.length) {
    events.value = [...events.value, ...fresh].slice(-32)
    cursor = next
  }
  const last = fresh[fresh.length - 1]
  return { more: page.has_more && fresh.length > 0, settled: last && terminalStates[last.event_type] }
}

async function poll() {
  if (stopped || pending || !navigator.onLine) return
  const request = new AbortController()
  const wasTerminal = props.terminal
  pending = request
  let delay = pollInterval
  try {
    const page = await readApprovalEvents(props.reference, cursor, request.signal)
    if (request.signal.aborted) return
    const result = applyPage(page)
    retryDelay = pollInterval
    notice.value = ''
    if (result.settled || (wasTerminal && !result.more)) {
      stopped = true
      if (result.settled && !props.terminal) emit('settled', result.settled)
    }
    delay = result.more ? 0 : pollInterval
  } catch (error) {
    if (request.signal.aborted) return
    const status = isAxiosError(error) ? error.response?.status : undefined
    if (status === 401 || status === 403 || status === 404 || status === 410) {
      stopped = true
      events.value = []
      emit('unavailable', error)
    } else if (isAxiosError(error) && (!status || status === 408 || status === 425 || status === 429 || status >= 500)) {
      notice.value = '暂时无法读取任务事件，正在重试。'
      delay = retryDelay
      retryDelay = Math.min(retryDelay * 2, 16000)
    } else {
      stopped = true
      notice.value = '暂时无法继续读取任务事件，请刷新审批窗口。'
    }
  } finally {
    pending = undefined
    schedule(request.signal.aborted ? 0 : delay)
  }
}

function reconnect() {
  retryDelay = pollInterval
  schedule(0)
}

onMounted(() => {
  window.addEventListener('online', reconnect)
  window.addEventListener('offline', disconnect)
  if (navigator.onLine) schedule(0)
  else disconnect()
})

onUnmounted(() => {
  stopped = true
  window.clearTimeout(timer)
  pending?.abort()
  window.removeEventListener('online', reconnect)
  window.removeEventListener('offline', disconnect)
})
</script>

<style scoped>
.approval-timeline { margin-top: 16px; border-top: 1px solid var(--el-border-color-light); padding-top: 12px; }
.approval-timeline h3 { margin: 0 0 8px; font-size: 14px; }
.approval-timeline p { margin: 0 0 8px; color: var(--el-text-color-secondary); }
.approval-timeline ol { margin: 0; padding-left: 24px; max-height: 180px; overflow: auto; }
.approval-timeline li { padding: 4px 0; }
.approval-timeline time { margin-right: 8px; color: var(--el-text-color-secondary); }
</style>
