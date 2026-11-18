<template>
  <div class="native-page" v-loading="loading">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">{{ channelTitle }} · 原生接入配置</h2>
        <p class="page-sub">
          配好回调地址与密钥，端上就能直接把消息送进平台；会话、工单、质检、统计都走同一条链路
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/channels')">返回渠道接入</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </div>

    <el-alert
      v-if="detail && !detail.configured"
      class="mb-16"
      type="warning"
      show-icon
      :closable="false"
      title="还没保存过配置"
      description="填好下面的必填项并保存后，才能跑联调模拟器。密钥类字段保存后不会再回显，留空表示不修改。"
    />

    <el-card shadow="never" class="mb-16">
      <template #header><span class="card-title">回调地址（填到端上 / 网关）</span></template>
      <div v-if="webhookEntries.length" class="hook-list">
        <div v-for="item in webhookEntries" :key="item.key" class="hook-row">
          <el-tag size="small" effect="plain">{{ hookLabel(item.key) }}</el-tag>
          <span class="mono hook-url">{{ item.value }}</span>
          <el-button link type="primary" size="small" @click="copy(item.value)">复制</el-button>
        </div>
        <div class="hook-tip">
          回调需要签名：请求头带 <span class="mono">X-SupportFlow-Timestamp</span>（毫秒）与
          <span class="mono">X-SupportFlow-Signature</span>，值为 HMAC-SHA256(密钥, 时间戳 + "\n" + 请求体) 的十六进制。
        </div>
      </div>
      <div v-else class="muted">保存配置后这里会显示回调地址</div>
    </el-card>

    <el-card shadow="never" class="mb-16">
      <template #header><span class="card-title">接入配置</span></template>
      <el-form label-position="top">
        <el-form-item v-for="field in detail?.fields || []" :key="field.name" :required="field.required">
          <template #label>
            {{ field.label }}
            <el-tag v-if="field.secret && detail?.values[field.name + 'Configured']" size="small" type="success" effect="plain">
              已配置
            </el-tag>
          </template>
          <el-input
            v-if="field.type === 'text' || field.type === 'secret'"
            v-model="form[field.name]"
            :type="field.secret ? 'password' : 'text'"
            :show-password="field.secret"
            :placeholder="field.secret && detail?.values[field.name + 'Configured'] ? '留空表示不修改' : field.placeholder"
          />
          <el-select
            v-else-if="field.type === 'select'"
            v-model="form[field.name]"
            :placeholder="field.placeholder || '请选择'"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="opt in field.options || []"
              :key="opt"
              :label="optionLabel(field, opt)"
              :value="opt"
            />
          </el-select>
          <el-input
            v-else
            v-model="form[field.name]"
            type="textarea"
            :autosize="{ minRows: field.type === 'json' ? 4 : 3, maxRows: 12 }"
            :placeholder="field.placeholder"
            class="mono"
          />
          <div v-if="field.help" class="field-help">{{ field.help }}</div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="sim-head">
          <span class="card-title">联调模拟器</span>
          <span class="muted">{{ detail?.simulator?.hint }}</span>
        </div>
      </template>
      <div class="sim-actions">
        <el-input
          v-if="detail?.channelType !== 5"
          v-model="simulateContent"
          :placeholder="detail?.simulator?.contentPlaceholder"
          style="max-width: 520px"
        />
        <el-input
          v-if="detail?.channelType === 6"
          v-model="simulateSubject"
          placeholder="邮件主题（可选）"
          style="max-width: 320px"
        />
        <el-button type="primary" :loading="simulating" @click="runSimulate">跑一遍联调</el-button>
      </div>
      <div v-if="steps.length" class="sim-steps">
        <div v-for="(step, index) in steps" :key="index" class="sim-step">
          <div class="step-name">step {{ index + 1 }} · {{ step.name }}</div>
          <pre class="mono">{{ pretty(step.result) }}</pre>
        </div>
        <el-alert
          v-if="simulateNote"
          type="info"
          show-icon
          :closable="false"
          :title="simulateNote"
        />
      </div>
    </el-card>

    <!-- 400 热线：通话记录（呼入 → IVR 按键 → 坐席接听 → 录音 → 挂机建单） -->
    <el-card v-if="detail?.channelType === 5" shadow="never">
      <template #header>
        <div class="sim-head">
          <span class="card-title">通话记录</span>
          <span class="muted">话务侧每推一个事件就更新一行；未接电话只留记录、不建单</span>
        </div>
      </template>

      <div class="call-kpis">
        <div class="call-kpi">
          <div class="kpi-value">{{ calls.summary.total ?? 0 }}</div>
          <div class="kpi-label">今日呼入</div>
        </div>
        <div class="call-kpi">
          <div class="kpi-value">{{ calls.summary.finished ?? 0 }}</div>
          <div class="kpi-label">已完成</div>
        </div>
        <div class="call-kpi">
          <div class="kpi-value">{{ calls.summary.transferred ?? 0 }}</div>
          <div class="kpi-label">转人工</div>
        </div>
        <div class="call-kpi">
          <div class="kpi-value">{{ calls.summary.missed ?? 0 }}</div>
          <div class="kpi-label">未接通</div>
        </div>
        <div class="call-kpi">
          <div class="kpi-value">{{ durationText(calls.summary.avgDuration ?? 0) }}</div>
          <div class="kpi-label">平均通话时长</div>
        </div>
      </div>

      <div class="call-filters">
        <el-select v-model="callQuery.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option :value="1" label="呼入中" />
          <el-option :value="2" label="已转人工" />
          <el-option :value="3" label="已完成" />
          <el-option :value="4" label="未接通" />
        </el-select>
        <el-input
          v-model.trim="callQuery.keyword"
          placeholder="号码 / 呼叫号 / 客户号 / 工单号"
          style="width: 300px"
          clearable
          @keyup.enter="loadCalls(1)"
        />
        <el-button type="primary" @click="loadCalls(1)">查询</el-button>
        <el-button @click="loadCalls(calls.page)">刷新</el-button>
      </div>

      <el-table :data="calls.list" size="small">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ row.createTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="主叫号码" width="140">
          <template #default="{ row }">
            <div class="cell-main mono">{{ row.caller || '—' }}</div>
            <div v-if="row.callId" class="cell-sub mono">{{ row.callId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="IVR 按键" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ivrPath || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="callStatusTone(row.status)" size="small">{{ callStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="90">
          <template #default="{ row }">{{ row.durationSeconds == null ? '—' : durationText(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="坐席" width="100">
          <template #default="{ row }">{{ row.agentName || '—' }}</template>
        </el-table-column>
        <el-table-column label="录音" width="90">
          <template #default="{ row }">
            <el-link v-if="row.recordingUrl" type="primary" :href="row.recordingUrl" target="_blank">播放</el-link>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="通话小结" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="pre-line">{{ row.transcript || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="通话转写" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.asrText" class="pre-line">{{ row.asrText }}</span>
            <span v-else-if="row.asrStatus === 2" class="muted">{{ row.asrHint || '转写失败' }}</span>
            <span v-else class="muted">待转写</span>
          </template>
        </el-table-column>
        <el-table-column label="工单" width="150">
          <template #default="{ row }">
            <router-link
              v-if="row.ticketNo"
              class="link"
              :to="{ path: '/tickets', query: { keyword: row.ticketNo } }"
            >{{ row.ticketNo }}</router-link>
            <span v-else class="muted">未建单</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="calls.total > 0"
        class="call-pager"
        layout="total, sizes, prev, pager, next"
        :total="Number(calls.total)"
        :current-page="Number(calls.page)"
        :page-size="Number(calls.pageSize)"
        :page-sizes="[10, 20, 50]"
        @current-change="loadCalls"
        @size-change="onCallSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getNativeConfig,
  listPhoneCalls,
  saveNativeConfig,
  simulateNative,
  type NativeConfigDetail,
  type PhoneCallPage,
  type SimulateStep,
} from '../../api/customer/nativeChannel'
import { channelTypeLabel } from '../../api/customer/channel'

const route = useRoute()
const router = useRouter()
const channelId = String(route.params.channelId || '')

const loading = ref(true)
const saving = ref(false)
const simulating = ref(false)
const detail = ref<NativeConfigDetail | null>(null)
const form = reactive<Record<string, string>>({})
const steps = ref<SimulateStep[]>([])
const simulateNote = ref('')
const simulateContent = ref('')
const simulateSubject = ref('')

/** 400 通话记录（只有电话渠道会加载） */
const calls = reactive<PhoneCallPage>({
  total: 0,
  page: 1,
  pageSize: 10,
  summary: { total: 0, finished: 0, transferred: 0, missed: 0, avgDuration: 0 },
  list: [],
})
const callQuery = reactive<{ status?: number; keyword: string }>({ status: undefined, keyword: '' })

// 标题用渠道类型，不用渠道名：邮件渠道的名字就是邮箱地址（service@xx.com），
// 直接铺在标题里既长又像在裸奔，用「邮件工单 / 官方 App / 400 电话热线」更清楚。
const channelTitle = computed(() => channelTypeLabel(detail.value?.channelType))

const webhookEntries = computed(() =>
  Object.entries(detail.value?.webhookUrls || {}).map(([key, value]) => ({ key, value })),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await getNativeConfig(channelId)
    detail.value = data
    Object.keys(form).forEach((key) => delete form[key])
    data.fields.forEach((field) => {
      // 密钥字段一律不回显，也不预填
      form[field.name] = field.secret ? '' : String(data.values[field.name] ?? '')
    })
    simulateContent.value = data.simulator?.contentPlaceholder && data.channelType !== 5
      ? String(data.simulator.contentPlaceholder)
      : ''
    // 电话渠道顺带把通话记录拉出来（别的渠道没有这张表，不用请求）
    if (data.channelType === 5) {
      await loadCalls(1)
    }
  } finally {
    loading.value = false
  }
}

/** 400 通话记录：分页 + 今日概况 */
async function loadCalls(page = calls.page): Promise<void> {
  try {
    const data = await listPhoneCalls(channelId, {
      status: callQuery.status,
      keyword: callQuery.keyword,
      page: Math.max(1, Number(page) || 1),
      pageSize: Number(calls.pageSize) || 10,
    })
    calls.total = Number(data.total) || 0
    calls.page = Number(data.page) || 1
    calls.pageSize = Number(data.pageSize) || 10
    calls.summary = data.summary || calls.summary
    calls.list = data.list || []
  } catch {
    // 通话记录拉不到不影响配置页：留空即可（提示由 request 层给）
  }
}

function onCallSizeChange(size: number): void {
  calls.pageSize = size
  loadCalls(1)
}

function callStatusText(status: number): string {
  return { 1: '呼入中', 2: '已转人工', 3: '已完成', 4: '未接通' }[status] || '未知'
}

function callStatusTone(status: number): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  if (status === 3) return 'success'
  if (status === 2) return 'warning'
  if (status === 4) return 'info'
  return 'primary'
}

/** 秒 → mm:ss */
function durationText(seconds: number): string {
  const total = Math.max(0, Math.round(Number(seconds) || 0))
  if (!total) return '—'
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}

async function save() {
  saving.value = true
  try {
    await saveNativeConfig(channelId, { ...form })
    ElMessage.success('配置已保存，可以跑联调模拟器了')
    await load()
  } finally {
    saving.value = false
  }
}

async function runSimulate() {
  simulating.value = true
  try {
    const data = await simulateNative(channelId, {
      content: simulateContent.value,
      subject: simulateSubject.value,
    })
    steps.value = data.steps
    simulateNote.value = data.note
    ElMessage.success('联调完成，可以到「在线客服」「工单中心」看结果')
  } finally {
    simulating.value = false
  }
}

function hookLabel(key: string) {
  return { message: 'App 消息', event: '呼叫事件', inbound: '邮件进线' }[key] || key
}

/** 下拉项的中文说明：值本身很短（imap / webhook），这里补上"是哪条方案" */
function optionLabel(field: { name: string }, opt: string) {
  const labels: Record<string, string> = {
    'inboundMode:imap': 'imap · 平台直连企业邮箱收信（方案A）',
    'inboundMode:webhook': 'webhook · 企业邮件网关推送（方案B）',
    'outboundMode:smtp': 'smtp · 平台用企业邮箱直发（方案A）',
    'outboundMode:webhook': 'webhook · 推给企业邮件网关（方案B）',
  }
  return labels[`${field.name}:${opt}`] || opt
}

async function copy(text: string) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

function pretty(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2)
}
</script>

<style scoped>
.native-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; gap: 10px; }
.card-title { font-size: 14.5px; font-weight: 600; color: #0f172a; }
.mb-16 { margin-bottom: 16px; }
.hook-list { display: grid; gap: 10px; }
.hook-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hook-url { font-size: 13px; color: #0f172a; word-break: break-all; }
.hook-tip { font-size: 12px; line-height: 1.8; color: #64748b; }
.field-help { margin-top: 6px; font-size: 12px; line-height: 1.7; color: #94a3b8; }
.sim-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.sim-actions { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.sim-steps { margin-top: 14px; display: grid; gap: 12px; }
.sim-step { border: 1px solid #e6e8ef; border-radius: 10px; overflow: hidden; }
.step-name { padding: 8px 12px; background: #f8fafc; font-size: 12.5px; font-weight: 600; color: #0f172a; }
.sim-step pre { margin: 0; padding: 12px; background: #0b1220; color: #c7d7f5; font-size: 12px; line-height: 1.7; overflow-x: auto; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.muted { color: #94a3b8; font-size: 12.5px; }

/* 400 通话记录 */
.call-kpis { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.call-kpi { padding: 12px 14px; background: #f8fafc; border: 1px solid #eef1f6; border-radius: 12px; }
.kpi-value { font-size: 20px; font-weight: 800; color: #0f172a; }
.kpi-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.call-filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.call-pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; color: #0f172a; }
.cell-sub { margin-top: 2px; font-size: 11px; color: #94a3b8; }
.pre-line { white-space: pre-line; }
.link { color: #2563eb; text-decoration: none; }
</style>
