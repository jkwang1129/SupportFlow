<template>
  <div class="training-center">
    <!-- ① 顶部态势：当前生效版本 + 关键指标 + 训练操作 -->
    <section class="tc-hero">
      <div class="tc-hero-main">
        <div class="tc-eyebrow">
          当前生效版本
          <span class="tc-pill" :class="onlineVersion ? 'is-online' : 'is-empty'">
            {{ onlineVersion ? onlineVersion.statusText : '尚未训练' }}
          </span>
          <span v-if="grayVersion" class="tc-pill is-gray">灰度中 {{ grayVersion.grayPercent }}%</span>
        </div>
        <div class="tc-version-row">
          <span class="tc-version" :class="{ 'is-empty': !onlineVersion }">
            {{ onlineVersion ? onlineVersion.versionNo : '—' }}
          </span>
          <span v-if="onlineVersion" class="tc-version-sub">
            {{ onlineVersion.modeText }}训练 · 上线于 {{ onlineVersion.publishTime || onlineVersion.createTime || '—' }}
          </span>
        </div>
        <div class="tc-remark">
          {{ onlineVersion ? onlineVersion.remark : '还没有训练过：点右侧「重新训练」产出第一个版本' }}
        </div>
        <div v-if="onlineVersion" class="tc-meta">
          <span>向量来源 {{ onlineVersion.baseModel || '—' }}</span>
          <i class="tc-dot" />
          <span>产出任务 {{ onlineVersion.jobNo || '—' }}</span>
          <i class="tc-dot" />
          <span>覆盖意图 {{ onlineVersion.intentCount }} 个</span>
        </div>
      </div>

      <div class="tc-metrics">
        <div class="tc-metric">
          <div class="tc-metric-label">识别率</div>
          <div class="tc-metric-value">
            {{ onlineVersion && onlineVersion.accuracy != null ? onlineVersion.accuracy : '—' }}<small>%</small>
          </div>
          <div class="tc-metric-foot" :class="accuracyDeltaClass">{{ accuracyDeltaText }}</div>
        </div>
        <div class="tc-metric">
          <div class="tc-metric-label">训练语料</div>
          <div class="tc-metric-value">{{ onlineVersion ? onlineVersion.sampleCount : training.corpus.total }}<small>条</small></div>
          <div class="tc-metric-foot">
            人工 {{ training.corpus.manual }} · 历史 {{ training.corpus.history }}
          </div>
        </div>
        <div class="tc-metric">
          <div class="tc-metric-label">灰度流量</div>
          <div class="tc-metric-value">{{ grayVersion ? grayVersion.grayPercent : 0 }}<small>%</small></div>
          <div class="tc-metric-foot">
            {{ grayVersion ? `其余走 ${onlineVersion?.versionNo || '上一版'}` : '全量（无灰度）' }}
          </div>
        </div>
      </div>

      <div class="tc-hero-actions">
        <div class="tc-train-form">
          <el-select v-model="trainForm.mode" class="tc-select" placeholder="训练方式">
            <el-option :value="2" label="增量训练（只补新语料）" />
            <el-option :value="1" label="全量训练（全部重训）" />
          </el-select>
          <el-input-number
            v-model="trainForm.grayPercent"
            :min="0"
            :max="100"
            :step="10"
            controls-position="right"
            class="tc-gray"
          />
          <el-button
            type="primary"
            :loading="startingTraining"
            :disabled="trainingRunning"
            @click="startTraining"
          >
            {{ trainingRunning ? '训练中…' : '重新训练' }}
          </el-button>
        </div>
        <div class="tc-hint">训练完灰度 %（0 = 直接全量上线）</div>
      </div>
    </section>

    <!-- ② 训练进度（后台线程真跑，进度写库，页面 2 秒轮询） -->
    <section v-if="currentJob" class="tc-card tc-progress">
      <div class="tc-card-head">
        <div class="tc-card-title">
          训练任务
          <span class="tc-job mono">{{ currentJob.jobNo }}</span>
        </div>
        <div class="tc-card-head-right">
          <span class="tc-job-status" :class="`s-${currentJob.status}`">{{ currentJob.statusText }}</span>
          <span class="tc-muted">{{ currentJob.startTime || currentJob.createTime || '—' }}</span>
        </div>
      </div>

      <el-steps :active="stageIndex" simple class="tc-steps">
        <el-step title="采集语料" />
        <el-step title="向量化" />
        <el-step title="评估识别率" />
        <el-step title="落库版本" />
      </el-steps>

      <el-progress
        :percentage="currentJob.progress"
        :status="currentJob.status === 4 ? 'exception' : currentJob.status === 3 ? 'success' : undefined"
        :stroke-width="10"
        class="tc-progress-bar"
      />
      <div class="tc-progress-foot">
        <span class="tc-muted">{{ currentJob.phase || '—' }}</span>
        <span class="tc-muted">
          语料 {{ currentJob.sampleCount }} 条 · 意图 {{ currentJob.intentCount }} 个
        </span>
        <span v-if="currentJob.resultVersion" class="tc-muted">产出 {{ currentJob.resultVersion }}</span>
        <span v-if="currentJob.status === 4" class="tc-danger">失败：{{ currentJob.errorMessage || '未知原因' }}</span>
      </div>
    </section>

    <!-- ③ 语料口径：训练到底用了什么，摆出来让人能核对 -->
    <section class="tc-strip">
      <div class="tc-strip-item">
        <span class="tc-strip-label">语料口径</span>
        <span class="tc-strip-value">
          人工维护 <b>{{ training.corpus.manual }}</b> 条 + 历史真实会话
          <b>{{ training.corpus.history }}</b> 条（弱标注门槛置信度 ≥ {{ training.corpus.historyMinConfidence }}）
        </span>
      </div>
      <div class="tc-strip-item">
        <span class="tc-strip-label">覆盖意图</span>
        <span class="tc-strip-value"><b>{{ training.corpus.intents }}</b> 个</span>
      </div>
      <div v-if="grayVersion" class="tc-strip-item">
        <span class="tc-strip-label">灰度规则</span>
        <span class="tc-strip-value">
          按「租户 + 会话」稳定哈希分配：<b>{{ grayVersion.grayPercent }}%</b> 走
          {{ grayVersion.versionNo }}，其余走 {{ onlineVersion?.versionNo || '上一版' }}
        </span>
      </div>
    </section>

    <!-- ④ 版本记录 -->
    <section class="tc-card">
      <div class="tc-card-head">
        <div>
          <div class="tc-card-title">版本记录</div>
          <div class="tc-card-sub">
            每次训练产出一条；「全量上线」会下线其它版本，灰度只影响按比例命中的会话
          </div>
        </div>
      </div>

      <el-table :data="training.versions" class="tc-table" size="small" row-key="versionNo">
        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            <div class="tc-version-cell">
              <span class="mono tc-version-no">{{ row.versionNo }}</span>
              <span v-if="onlineVersion?.versionNo === row.versionNo" class="tc-current">当前</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="modeText" label="类型" width="80" />
        <el-table-column prop="sampleCount" label="语料" width="90">
          <template #default="{ row }">{{ row.sampleCount }} 条</template>
        </el-table-column>
        <el-table-column label="识别率" width="200">
          <template #default="{ row }">
            <div v-if="row.accuracy != null" class="tc-accuracy">
              <div class="tc-accuracy-bar">
                <span :style="{ width: `${Math.max(2, row.accuracy)}%` }" />
              </div>
              <span class="tc-accuracy-text">{{ row.accuracy }}%</span>
            </div>
            <span v-else class="tc-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span class="tc-state" :class="`s-${row.status}`">{{ row.statusText }}</span>
            <span v-if="row.grayPercent > 0" class="tc-muted"> {{ row.grayPercent }}%</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(command: string) => onVersionCommand(command, row)">
              <el-button link type="primary">
                发布 / 回滚<el-icon class="tc-caret"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    command="publish"
                    :disabled="row.status === 3"
                  >
                    全量上线
                  </el-dropdown-item>
                  <el-dropdown-item command="gray" :disabled="row.status === 2">灰度 20%</el-dropdown-item>
                  <el-dropdown-item command="rollback" :disabled="row.status === 3" divided>
                    回滚到此版
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../utils/message'
import {
  getBotTraining,
  getBotTrainingJob,
  publishBotVersion,
  rollbackBotVersion,
  startBotTraining,
  type BotTrainingJob,
  type BotTrainingOverview,
  type BotVersionRow,
} from '../api/ai/bot'

const trainForm = reactive({ mode: 2, grayPercent: 0 })
const currentJob = ref<BotTrainingJob | null>(null)
const training = reactive<BotTrainingOverview>({
  onlineVersion: null,
  grayVersion: null,
  lastJob: null,
  jobs: [],
  versions: [],
  corpus: { manual: 0, history: 0, total: 0, intents: 0, historyMinConfidence: 60 },
})
const startingTraining = ref(false)
const trainingRunning = computed(
  () => !!currentJob.value && [1, 2].includes(currentJob.value.status),
)
let trainTimer: number | undefined
onUnmounted(stopPolling)
onMounted(loadTraining)

/** 当前生效版本（页面多处要用，抽出来避免模板里到处写 training.onlineVersion） */
const onlineVersion = computed(() => training.onlineVersion)
const grayVersion = computed(() => training.grayVersion)

/** 训练阶段 → el-steps 的 active（对应：采集语料 / 向量化 / 评估识别率 / 落库版本） */
const STAGE_KEYWORDS = ['采集语料', '向量化', '评估识别率', '落库版本']
const stageIndex = computed(() => {
  const job = currentJob.value
  if (!job) return 0
  if (job.status === 3) return STAGE_KEYWORDS.length
  const phase = job.phase || ''
  const index = STAGE_KEYWORDS.findIndex((keyword) => phase.includes(keyword))
  return index < 0 ? 0 : index
})

/** 识别率与上一版（同为已上线过、有识别率的版本）的对比：这里只做展示，不编数字 */
const accuracyDeltaText = computed(() => {
  const current = onlineVersion.value
  if (!current || current.accuracy == null) return '暂无基线'
  const previous = training.versions.find(
    (item) => item.versionNo !== current.versionNo && item.accuracy != null,
  )
  if (!previous || previous.accuracy == null) return '首个版本'
  const diff = Number((current.accuracy - previous.accuracy).toFixed(2))
  if (diff === 0) return `较 ${previous.versionNo} 持平`
  return `较 ${previous.versionNo} ${diff > 0 ? '+' : ''}${diff}%`
})

const accuracyDeltaClass = computed(() => {
  if (accuracyDeltaText.value.startsWith('较 ')) {
    return accuracyDeltaText.value.includes('-') ? 'is-down' : 'is-up'
  }
  return ''
})

/** 训练中心：首屏拉一次；如果最近一次任务还在跑，接着轮询 */
async function loadTraining(): Promise<void> {
  try {
    applyTraining(await getBotTraining())
    if (trainingRunning.value && currentJob.value) {
      startPolling(currentJob.value.jobNo)
    }
  } catch {
    // 训练中心拉不到不影响页面其它部分（AI 服务没起时页面照常能用）
  }
}

function applyTraining(data: BotTrainingOverview): void {
  training.onlineVersion = data.onlineVersion
  training.grayVersion = data.grayVersion
  training.lastJob = data.lastJob
  training.jobs = data.jobs
  training.versions = data.versions
  training.corpus = data.corpus
  currentJob.value = data.lastJob
}

async function startTraining(): Promise<void> {
  startingTraining.value = true
  try {
    const result = await startBotTraining({
      mode: trainForm.mode,
      grayPercent: trainForm.grayPercent,
    })
    applyTraining(result.overview)
    currentJob.value = result.job
    startPolling(result.job.jobNo)
    ElMessage.success(`训练已开始（${result.job.modeText}）`)
  } catch {
    // 失败原因（比如已有任务在跑）由 request 层提示
  } finally {
    startingTraining.value = false
  }
}

function startPolling(jobNo: string): void {
  stopPolling()
  trainTimer = window.setInterval(async () => {
    try {
      const job = await getBotTrainingJob(jobNo)
      currentJob.value = job
      if (job.status === 3) {
        stopPolling()
        await loadTraining()
        ElMessage.success(`训练完成：产出 ${job.resultVersion}（识别率见版本记录）`)
      } else if (job.status === 4) {
        stopPolling()
        await loadTraining()
        errorMessage(undefined, `训练失败：${job.errorMessage || '未知原因'}`)
      }
    } catch {
      // 单次轮询失败（网络抖动）不打断整个流程，下一次继续
    }
  }, 2000)
}

function stopPolling(): void {
  if (trainTimer) {
    window.clearInterval(trainTimer)
    trainTimer = undefined
  }
}

/** 版本行操作：全量上线 / 灰度 20% / 回滚到此版 */
async function onVersionCommand(command: string, row: BotVersionRow): Promise<void> {
  if (command === 'publish') {
    await publishVersion(row, 0)
    return
  }
  if (command === 'gray') {
    await publishVersion(row, 20)
    return
  }
  if (command === 'rollback') {
    await rollbackVersion(row)
  }
}

async function publishVersion(row: BotVersionRow, grayPercent: number): Promise<void> {
  if (grayPercent === 0) {
    try {
      await ElMessageBox.confirm(
        `把 ${row.versionNo} 全量上线？其余版本会下线（识别立刻按这个版本的语料走）。`,
        '全量上线',
        { type: 'warning', confirmButtonText: '确定上线', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  try {
    applyTraining(await publishBotVersion(row.versionNo, grayPercent))
    ElMessage.success(grayPercent > 0
      ? `${row.versionNo} 已灰度 ${grayPercent}%（按会话稳定分配）`
      : `${row.versionNo} 已全量上线`)
  } catch {
    // 提示由 request 层给
  }
}

async function rollbackVersion(row: BotVersionRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `回滚到 ${row.versionNo}？该版本会重新全量上线，当前版本下线。`,
      '回滚版本',
      { type: 'warning', confirmButtonText: '确定回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    applyTraining(await rollbackBotVersion(row.versionNo))
    ElMessage.success(`已回滚到 ${row.versionNo}`)
  } catch {
    // 提示由 request 层给
  }
}
</script>

<style scoped>
/* 模型训练中心 · 控制台风格（态势卡 / 指标组 / 阶段进度 / 版本表） */
.training-center { display: flex; flex-direction: column; gap: 14px; }

/* ---------------------------------------------------------------- 顶部态势 */
.tc-hero {
  display: grid;
  grid-template-columns: minmax(280px, 1.4fr) minmax(360px, 1.6fr) auto;
  gap: 24px;
  align-items: center;
  padding: 22px 24px;
  background: linear-gradient(135deg, #f8fbff 0%, #ffffff 55%, #f4f7ff 100%);
  border: 1px solid #e3ebf8;
  border-radius: 16px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.tc-eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #64748b;
}

.tc-pill {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}

.tc-pill.is-online { color: #047857; background: #ecfdf5; border-color: #a7f3d0; }
.tc-pill.is-gray { color: #b45309; background: #fffbeb; border-color: #fde68a; }
.tc-pill.is-empty { color: #64748b; background: #f1f5f9; border-color: #e2e8f0; }

.tc-version-row { display: flex; align-items: baseline; gap: 12px; margin: 8px 0 6px; }
.tc-version { font-size: 32px; font-weight: 800; letter-spacing: 0.5px; color: #1d4ed8; line-height: 1.1; }
.tc-version.is-empty { color: #cbd5e1; }
.tc-version-sub { font-size: 12px; color: #94a3b8; }
.tc-remark { font-size: 13px; color: #334155; line-height: 1.7; }
.tc-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 8px; font-size: 12px; color: #94a3b8; }
.tc-dot { width: 3px; height: 3px; border-radius: 50%; background: #cbd5e1; }

.tc-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.tc-metric {
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 12px;
}
.tc-metric-label { font-size: 12px; color: #94a3b8; }
.tc-metric-value { margin-top: 4px; font-size: 24px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.tc-metric-value small { margin-left: 2px; font-size: 12px; font-weight: 600; color: #94a3b8; }
.tc-metric-foot { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.tc-metric-foot.is-up { color: #047857; }
.tc-metric-foot.is-down { color: #dc2626; }

.tc-hero-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.tc-train-form { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }
.tc-select { width: 210px; }
.tc-gray { width: 130px; }
.tc-hint { font-size: 12px; color: #94a3b8; }

/* ---------------------------------------------------------------- 卡片与进度 */
.tc-card {
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 16px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.tc-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.tc-card-title { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 700; color: #0f172a; }
.tc-card-sub { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.tc-card-head-right { display: flex; align-items: center; gap: 12px; }
.tc-job { font-size: 12px; color: #475569; }
.tc-job-status { font-size: 12px; font-weight: 700; }
.tc-job-status.s-1 { color: #64748b; }
.tc-job-status.s-2 { color: #2563eb; }
.tc-job-status.s-3 { color: #047857; }
.tc-job-status.s-4 { color: #dc2626; }
.tc-muted { font-size: 12px; color: #94a3b8; }
.tc-danger { font-size: 12px; color: #dc2626; font-weight: 600; }
.tc-steps { margin-bottom: 16px; }
.tc-progress-bar { margin-bottom: 10px; }
.tc-progress-foot { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }

/* ---------------------------------------------------------------- 口径条 */
.tc-strip {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
  padding: 12px 20px;
  background: #f8fafc;
  border: 1px dashed #e2e8f0;
  border-radius: 12px;
  font-size: 12px;
}
.tc-strip-item { display: flex; align-items: center; gap: 8px; }
.tc-strip-label { color: #94a3b8; }
.tc-strip-value { color: #475569; }
.tc-strip-value b { color: #0f172a; }

/* ---------------------------------------------------------------- 版本表 */
.tc-table :deep(.el-table__cell) { padding: 10px 0; }
.tc-version-cell { display: flex; align-items: center; gap: 6px; }
.tc-version-no { font-size: 13px; font-weight: 700; color: #0f172a; }
.tc-current {
  padding: 0 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}
.tc-accuracy { display: flex; align-items: center; gap: 8px; }
.tc-accuracy-bar { flex: 1; height: 6px; background: #eef2f7; border-radius: 999px; overflow: hidden; }
.tc-accuracy-bar span { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #60a5fa, #1d4ed8); }
.tc-accuracy-text { width: 48px; font-size: 12px; font-weight: 600; color: #334155; }
.tc-state { font-size: 12px; font-weight: 700; }
.tc-state.s-1 { color: #2563eb; }
.tc-state.s-2 { color: #b45309; }
.tc-state.s-3 { color: #047857; }
.tc-state.s-4 { color: #64748b; }
.tc-caret { margin-left: 2px; }
.mono { font-family: Menlo, Consolas, monospace; }

/* 窄屏回退：态势卡从三列变两列再变单列，避免横向溢出 */
@media (max-width: 1440px) {
  .tc-hero { grid-template-columns: minmax(260px, 1fr) minmax(320px, 1fr); }
  .tc-hero-actions { grid-column: 1 / -1; align-items: flex-start; }
  .tc-train-form { justify-content: flex-start; }
}

@media (max-width: 1100px) {
  .tc-hero { grid-template-columns: 1fr; }
  .tc-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
