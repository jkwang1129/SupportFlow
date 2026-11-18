<template>
  <div class="qa-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">质检中心</h2>
        <p class="page-sub">AI 全量初检 · 人工复核 · 规则引擎自动评分（会话一结束自动建任务，历史漏掉的用「发起全量质检」补扫）</p>
      </div>
      <div class="head-actions">
        <span v-if="overview" class="pass-badge">质检通过率 {{ overview.passRate }}%</span>
        <el-button :loading="loadingTasks" @click="loadAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button @click="router.push('/modules/qa/rules')">
          <el-icon class="btn-icon"><Stamp /></el-icon>
          规则配置
        </el-button>
        <el-button @click="openAiBatch">
          <el-icon class="btn-icon"><Finished /></el-icon>
          批量 AI 质检
        </el-button>
        <el-button @click="openManual()">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新增单个质检
        </el-button>
        <el-button type="primary" :loading="scanning" @click="startScan">
          <el-icon class="btn-icon"><Lightning /></el-icon>
          发起全量质检
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><DocumentChecked /></el-icon></div>
        <div><div class="stat-value">{{ overview?.total ?? '—' }}</div><div class="stat-label">质检任务</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><Clock /></el-icon></div>
        <div><div class="stat-value">{{ overview?.pending ?? '—' }}</div><div class="stat-label">待复核</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.passed ?? '—' }}</div><div class="stat-label">已通过</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><CircleCloseFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.rejected ?? '—' }}</div><div class="stat-label">已驳回</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><DataAnalysis /></el-icon></div>
        <div><div class="stat-value">{{ (overview?.avgAiScore ?? 0).toFixed(1) }}</div><div class="stat-label">AI 平均分</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.alertTotal ?? 0 }}</div><div class="stat-label">实时预警</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.alertPending ?? 0 }}</div><div class="stat-label">待处理预警</div></div>
      </div>
    </div>

    <el-card shadow="never" class="alert-card">
      <div class="alert-head">
        <div>
          <div class="alert-title">
            <el-icon :size="16"><BellFilled /></el-icon>
            实时预警 · 边聊边检
          </div>
          <div class="alert-sub">
            坐席和客户每发一句话就过一遍规则，命中敏感词 / 绝对化承诺 / 情绪未安抚的，当场推给坐席
          </div>
        </div>
        <div class="alert-head-right">
          <span class="alert-metric">累计 <b>{{ overview?.alertTotal ?? 0 }}</b></span>
          <span class="alert-metric warn">待处理 <b>{{ overview?.alertPending ?? 0 }}</b></span>
          <span class="alert-metric danger">严重 <b>{{ overview?.alertSevere ?? 0 }}</b></span>
          <el-button type="primary" @click="router.push('/modules/qa/alerts')">
            查看全部预警
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="main-card">
      <div class="filter-row">
        <el-radio-group v-model="statusFilter" @change="currentPage = 1">
          <el-radio-button :value="0">全部 {{ overview?.total ?? 0 }}</el-radio-button>
          <el-radio-button :value="1">待复核 {{ overview?.pending ?? 0 }}</el-radio-button>
          <el-radio-button :value="2">已通过 {{ overview?.passed ?? 0 }}</el-radio-button>
          <el-radio-button :value="3">已驳回 {{ overview?.rejected ?? 0 }}</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keyword"
          class="qa-search"
          placeholder="搜索任务号 / 会话 / 坐席"
          clearable
          @keyup.enter="loadTasks"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <el-table
        ref="taskTable"
        :data="pagedTasks"
        v-loading="loadingTasks"
        stripe
        @selection-change="selectedTasks = $event"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column type="index" label="序号" width="70" :index="indexMethod" />
        <el-table-column label="质检任务" min-width="220">
          <template #default="{ row }">
            <div class="task-no mono">{{ row.taskNo }}</div>
            <div class="cell-sub">
              {{ row.sessionName }}
              <el-tag
                v-if="row.alertCount"
                size="small"
                :type="row.alertSevere ? 'danger' : 'warning'"
                effect="light"
                class="alert-badge"
              >
                实时预警 {{ row.alertCount }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="坐席" min-width="150">
          <template #default="{ row }">
            <div class="member-cell">
              <div class="member-avatar">{{ row.agentName.slice(0, 1) }}</div>
              <span>{{ row.agentName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="质检时间" width="165">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="AI 初检" width="170">
          <template #default="{ row }">
            <div class="score-line">
              <b :class="scoreClass(row.aiScore)">{{ row.aiScore ?? '—' }}</b>
              <el-progress
                :percentage="Number(row.aiScore || 0)"
                :stroke-width="6"
                :show-text="false"
                :color="scoreColor(row.aiScore)"
                class="score-progress"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="复核评分" width="100">
          <template #default="{ row }">
            <span v-if="row.reviewScore != null" class="review-score">{{ row.reviewScore }}</span>
            <span v-else class="cell-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="100">
          <template #default="{ row }">
            <el-tag :type="riskTag(row.riskLevel)" effect="light">{{ row.riskText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <template v-if="row.status === 1">
              <el-button link type="success" @click="openReview(row, 1)">通过</el-button>
              <el-button link type="danger" @click="openReview(row, 2)">驳回重检</el-button>
            </template>
            <el-button v-else link type="primary" @click="openReview(row, 3)">重新复核</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="qa-empty">
            <div class="qa-empty-title">还没有质检任务</div>
            <div class="qa-empty-sub">
              会话结束后系统会自动按真实对话建一条任务并跑 AI 初检；
              历史会话可以点右上角「发起全量质检」补扫，也可以「新增单个质检」手工补录。
            </div>
          </div>
        </template>
      </el-table>
      <div class="table-foot">
        <div class="foot-left">
          <span class="cell-sub">共 {{ filteredTasks.length }} 项质检任务</span>
          <el-button
            v-if="selectedTasks.length"
            type="primary"
            plain
            size="small"
            @click="openBatchReview"
          >
            批量复核（{{ selectedTasks.length }}）
          </el-button>
        </div>
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="filteredTasks.length"
          layout="prev, pager, next"
          background
          size="small"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="质检任务详情" size="720px">
      <div v-if="detail" class="detail-body">
        <div class="detail-badges">
          <el-tag type="primary" effect="light">AI 初检 {{ detail.aiScore ?? '—' }}</el-tag>
          <el-tag v-if="detail.reviewScore != null" :type="scoreTag(detail.reviewScore)" effect="light">
            复核评分 {{ detail.reviewScore }}
          </el-tag>
          <el-tag :type="riskTag(detail.riskLevel)" effect="light">{{ detail.riskText }}</el-tag>
          <span class="detail-time">{{ formatTime(detail.createTime) }}</span>
        </div>
        <el-divider />
        <div class="info-card">
          <div class="info-row"><span class="k">任务编号</span><span class="v mono">{{ detail.taskNo }}</span></div>
          <div class="info-row"><span class="k">质检会话</span><span class="v">{{ detail.sessionName }}</span></div>
          <div class="info-row"><span class="k">被检坐席</span><span class="v">{{ detail.agentName }}</span></div>
          <div class="info-row"><span class="k">命中规则</span><span class="v">{{ detail.ruleNames.length ? detail.ruleNames.join('、') : '无' }}</span></div>
          <div class="info-row"><span class="k">AI 评语</span><span class="v">{{ detail.aiComment || '—' }}</span></div>
        </div>
        <div v-if="detail.alertCount" class="detail-section-title">
          会话期间实时预警（{{ detail.alertCount }} 条
          <template v-if="detail.alertSevere">，其中严重 {{ detail.alertSevere }} 条</template>）
        </div>
        <div v-if="detail.alertCount" class="alert-evidence">
          <div v-for="item in detail.alerts" :key="item.id" class="evidence-item">
            <div class="evidence-head">
              <el-tag
                size="small"
                effect="dark"
                :type="item.severity >= 3 ? 'danger' : item.severity === 1 ? 'info' : 'warning'"
              >
                {{ item.severityText }}
              </el-tag>
              <span class="evidence-rule">{{ item.ruleName }}</span>
              <span class="evidence-time">{{ formatTime(item.createTime) }}</span>
              <el-tag size="small" :type="item.status === 2 ? 'success' : 'danger'" effect="plain">
                {{ item.status === 2 ? '坐席已处理' : '未处理' }}
              </el-tag>
            </div>
            <div class="evidence-snippet">
              <span v-if="item.hitKeyword" class="hit-keyword">{{ item.hitKeyword }}</span>
              {{ item.snippet }}
            </div>
            <div v-if="item.advice" class="evidence-advice">{{ item.advice }}</div>
          </div>
        </div>

        <div class="detail-section-title">规则评分明细</div>
        <div v-for="result in detail.ruleResults" :key="result.name" class="result-item">
          <el-icon :size="18" :color="result.pass ? '#16a34a' : '#dc2626'">
            <CircleCheckFilled v-if="result.pass" /><CircleCloseFilled v-else />
          </el-icon>
          <div class="result-main">
            <div class="result-name">{{ result.name }}<span class="result-type">{{ result.type }}</span></div>
            <div class="cell-sub">{{ result.reason }}</div>
          </div>
          <el-tag :type="result.pass ? 'success' : 'danger'" size="small" effect="light">{{ result.pass ? '通过' : '不通过' }}</el-tag>
        </div>
        <div v-if="!detail.ruleResults.length" class="cell-sub">暂无规则明细</div>
      </div>
      <template #footer>
        <div class="drawer-foot">
          <el-button @click="detailVisible = false">关闭</el-button>
          <span class="spacer" />
          <el-button v-if="detail && detail.status === 1" type="danger" @click="openReview(detail, 2)">驳回重检</el-button>
          <el-button v-if="detail && detail.status === 1" type="success" @click="openReview(detail, 1)">复核通过</el-button>
          <el-button v-if="detail && detail.status !== 1" @click="openReview(detail, 3)">重新复核</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="reviewVisible"
      :title="reviewTitle"
      width="720px"
      class="review-dialog"
      :close-on-click-modal="false"
    >
      <div v-if="reviewTask" class="review-panel">
        <div class="review-summary" :class="`action-${reviewAction}`">
          <div class="review-summary-left">
            <div class="review-status-line">
              <span class="review-status-pill" :class="`pill-${reviewAction}`">
                <el-icon :size="14"><component :is="reviewActionIcon" /></el-icon>
                {{ reviewActionText }}
              </span>
              <span class="review-task-no mono">{{ reviewTask.taskNo }}</span>
            </div>
            <div class="review-task-title">{{ reviewTask.sessionName }}</div>
            <div class="review-agent-line">
              <span class="review-agent-avatar">{{ reviewTask.agentName.slice(0, 1) }}</span>
              <span>{{ reviewTask.agentName }}</span>
              <span class="dot-sep">·</span>
              <span class="review-muted">{{ formatTime(reviewTask.createTime) }}</span>
            </div>
          </div>
          <div class="review-score-box">
            <el-progress
              type="circle"
              :percentage="Number(reviewTask.aiScore || 0)"
              :width="74"
              :stroke-width="7"
              :color="scoreColor(reviewTask.aiScore)"
            >
              <span class="review-score-num">{{ reviewTask.aiScore ?? '—' }}</span>
            </el-progress>
            <div class="review-score-label">AI 初检分</div>
          </div>
        </div>

        <div class="review-meta">
          <div class="review-meta-item">
            <span class="label">质检会话</span>
            <span class="value">{{ reviewTask.sessionName }}</span>
          </div>
          <div class="review-meta-item">
            <span class="label">被检客服</span>
            <span class="value">{{ reviewTask.agentName }}</span>
          </div>
          <div class="review-meta-item">
            <span class="label">AI 风险</span>
            <span class="value"><el-tag :type="riskTag(reviewTask.riskLevel)" size="small" effect="light">{{ reviewTask.riskText }}</el-tag></span>
          </div>
          <div class="review-meta-item">
            <span class="label">命中规则</span>
            <span class="value">{{ reviewTask.ruleNames.length ? reviewTask.ruleNames.join('、') : '无' }}</span>
          </div>
        </div>

        <div v-if="reviewAction !== 3" class="review-score-section">
          <div class="review-section-title">
            复核评分
            <span class="review-score-current" :style="{ color: scoreColor(reviewScore) }">{{ reviewScore }} 分</span>
          </div>
          <div class="review-score-ruler">
            <div class="score-band good"><span>≥ 90</span><i>优秀</i></div>
            <div class="score-band mid"><span>80-89</span><i>良好</i></div>
            <div class="score-band warn"><span>60-79</span><i>及格</i></div>
            <div class="score-band bad"><span>&lt; 60</span><i>需改进</i></div>
          </div>
          <el-slider v-model="reviewScore" :min="0" :max="100" :step="1" show-input />
        </div>

        <div class="review-comment-section">
          <div class="review-section-title">
            复核意见
            <span v-if="reviewAction === 2" class="required-mark">必填</span>
          </div>
          <el-input
            v-model="reviewComment"
            type="textarea"
            :rows="4"
            maxlength="512"
            show-word-limit
            resize="none"
            :placeholder="reviewPlaceholder"
          />
        </div>
      </div>
      <template #footer>
        <div class="review-footer">
          <span class="review-footer-tip">{{ reviewActionText }}后，任务状态将更新为“{{ nextStatusText }}”</span>
          <div class="review-footer-actions">
            <el-button @click="reviewVisible = false">取消</el-button>
            <el-button
              class="review-confirm-btn"
              :type="reviewAction === 2 ? 'danger' : 'primary'"
              :loading="savingReview"
              @click="submitReview"
            >
              <el-icon v-if="!savingReview" :size="15"><component :is="reviewActionIcon" /></el-icon>
              {{ reviewAction === 1 ? '确认复核通过' : reviewAction === 2 ? '确认驳回重检' : '确认重新复核' }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="aiBatchVisible" title="批量 AI 质检" width="520px" :close-on-click-modal="false">
      <div class="manual-tip">
        已选择 <b>{{ selectedTasks.length }}</b> 条质检任务：{{ selectedTaskSummary }}
      </div>
      <div class="ai-batch-body">
        <el-icon class="ai-batch-icon" :size="30"><MagicStick /></el-icon>
        <div class="ai-batch-title">对选中任务执行 AI 初检</div>
        <div class="ai-batch-sub">AI 将重新分析会话内容、更新初检评分与命中规则，处理完成后任务回到“待复核”队列。</div>
      </div>
      <template #footer>
        <el-button @click="aiBatchVisible = false">取消</el-button>
        <el-button type="primary" :loading="aiBatchRunning" @click="submitAiBatch">开始 AI 质检</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchReviewVisible" title="批量复核（人工评分）" width="560px" :close-on-click-modal="false">
      <div class="manual-tip">
        已选择 <b>{{ selectedTasks.length }}</b> 条质检任务：{{ selectedTaskSummary }}
      </div>
      <el-form label-position="top">
        <el-form-item label="批量处理动作">
          <el-radio-group v-model="batchReviewAction">
            <el-radio-button :value="1">复核通过</el-radio-button>
            <el-radio-button :value="2">驳回重检</el-radio-button>
            <el-radio-button :value="3">重新复核</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="batchReviewAction !== 3" label="复核评分" required>
          <el-input-number v-model="batchReviewScore" :min="0" :max="100" :step="1" class="full-width" />
        </el-form-item>
        <el-form-item label="复核意见">
          <el-input
            v-model="batchReviewComment"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            resize="none"
            :placeholder="batchReviewAction === 2 ? '请填写驳回原因（必填）' : '选填，可补充处理说明'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchReviewVisible = false">取消</el-button>
        <el-button
          :type="batchReviewAction === 2 ? 'danger' : 'primary'"
          :loading="batchReviewing"
          @click="submitBatchReview"
        >
          {{ batchReviewAction === 1 ? '批量通过' : batchReviewAction === 2 ? '批量驳回' : '批量重新复核' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scanVisible" title="全量质检" width="460px" :close-on-click-modal="false" :show-close="false">
      <div class="scan-body">
        <el-icon class="is-loading scan-icon" :size="32"><Loading /></el-icon>
        <div class="scan-title">{{ scanProgress < 100 ? 'AI 正在扫描今日会话…' : '全量质检完成' }}</div>
        <div class="scan-text">{{ scanText }}</div>
        <el-progress :percentage="scanProgress" :stroke-width="10" :color="scanColor" />
      </div>
      <template #footer>
        <el-button v-if="scanProgress >= 100" type="primary" @click="scanVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="manualVisible" title="新增单个质检" width="620px" :close-on-click-modal="false">
      <div class="manual-tip">针对单个会话单独发起质检，任务将进入“待复核”队列。</div>
      <el-form label-position="top">
        <el-form-item label="质检会话名称" required>
          <el-input v-model="manualForm.sessionName" maxlength="128" placeholder="例如：C-2088 · 王女士售后咨询" />
        </el-form-item>
        <el-form-item label="接待客服" required>
          <el-input v-model="manualForm.agentName" maxlength="64" placeholder="例如：陈思远" />
        </el-form-item>
        <div class="manual-grid">
          <el-form-item label="AI 初检评分">
            <el-input-number v-model="manualForm.aiScore" :min="0" :max="100" :step="1" class="full-width" />
          </el-form-item>
          <el-form-item label="风险级别">
            <el-select v-model="manualForm.riskLevel" class="full-width">
              <el-option :value="1" label="低风险" />
              <el-option :value="2" label="中风险" />
              <el-option :value="3" label="高风险" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="命中质检规则（可多选）">
          <el-select
            v-model="manualForm.ruleNames"
            multiple
            collapse-tags
            collapse-tags-tooltip
            class="full-width"
            placeholder="选择本次质检命中的规则，不选表示无命中"
          >
            <el-option v-for="rule in rules" :key="rule.id" :label="rule.ruleName" :value="rule.ruleName" />
          </el-select>
        </el-form-item>
        <el-form-item label="质检备注">
          <el-input v-model="manualForm.comment" type="textarea" :rows="3" maxlength="512" show-word-limit resize="none" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingManual" @click="submitManual">创建质检任务</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  batchAiQaTasks,
  batchReviewQaTasks,
  createManualQaTask,
  fetchQaOverview,
  fetchQaRules,
  fetchQaTaskDetail,
  fetchQaTasks,
  reviewQaTask,
  scanQaTasks,
  type QaOverview,
  type QaRuleItem,
  type QaTaskDetail,
  type QaTaskItem,
} from '../../api/customer/qa'

const router = useRouter()
const overview = ref<QaOverview | null>(null)
const tasks = ref<QaTaskItem[]>([])
const rules = ref<QaRuleItem[]>([])
const detail = ref<QaTaskDetail | null>(null)
const loadingTasks = ref(false)
const loadingRules = ref(false)
const detailVisible = ref(false)
const reviewVisible = ref(false)
const savingReview = ref(false)
const scanning = ref(false)
const scanVisible = ref(false)
const manualVisible = ref(false)
const savingManual = ref(false)
const scanProgress = ref(0)
const scanText = ref('')
const statusFilter = ref(0)
const keyword = ref('')
const currentPage = ref(1)
const pageSize = 10
const taskTable = ref<{ clearSelection: () => void } | null>(null)
const selectedTasks = ref<QaTaskItem[]>([])
const aiBatchVisible = ref(false)
const aiBatchRunning = ref(false)
const batchReviewVisible = ref(false)
const batchReviewAction = ref(1)
const batchReviewScore = ref(90)
const batchReviewComment = ref('')
const batchReviewing = ref(false)

const reviewAction = ref(1)
const reviewTask = ref<QaTaskItem | null>(null)
const reviewScore = ref(90)
const reviewComment = ref('')

const manualForm = reactive({
  sessionName: '',
  agentName: '',
  aiScore: 85 as number,
  riskLevel: 1 as number,
  ruleNames: [] as string[],
  comment: '',
})

const reviewTitle = computed(() => {
  if (!reviewTask.value) return '人工复核'
  if (reviewAction.value === 1) return `复核通过 · ${reviewTask.value.taskNo}`
  if (reviewAction.value === 2) return `驳回重检 · ${reviewTask.value.taskNo}`
  return `重新复核 · ${reviewTask.value.taskNo}`
})

const reviewActionText = computed(() => {
  if (reviewAction.value === 1) return '复核通过'
  if (reviewAction.value === 2) return '驳回重检'
  return '重新复核'
})

const reviewActionIcon = computed(() => {
  if (reviewAction.value === 1) return 'CircleCheckFilled'
  if (reviewAction.value === 2) return 'CircleCloseFilled'
  return 'RefreshRight'
})

const nextStatusText = computed(() => {
  if (reviewAction.value === 1) return '已通过'
  if (reviewAction.value === 2) return '已驳回'
  return '待复核'
})

const reviewPlaceholder = computed(() => {
  if (reviewAction.value === 2) return '请填写驳回原因，例如：承诺时限未说明、话术不合规…（必填）'
  if (reviewAction.value === 1) return '可填写复核结论、亮点或改进建议（选填）'
  return '可填写重新复核的原因与关注点（选填）'
})

const filteredTasks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return tasks.value.filter((t) => {
    if (statusFilter.value !== 0 && t.status !== statusFilter.value) return false
    if (!kw) return true
    return (t.taskNo + t.sessionName + t.agentName).toLowerCase().includes(kw)
  })
})

const selectedTaskSummary = computed(() => {
  if (!selectedTasks.value.length) return '—'
  const first = selectedTasks.value[0].taskNo
  return selectedTasks.value.length > 1 ? `${first} 等 ${selectedTasks.value.length} 条` : first
})

const pagedTasks = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredTasks.value.slice(start, start + pageSize)
})

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadOverview(), loadTasks(), loadRules()])
}

async function loadOverview() {
  try {
    overview.value = await fetchQaOverview()
  } catch {
    overview.value = null
  }
}

async function loadTasks() {
  loadingTasks.value = true
  try {
    tasks.value = await fetchQaTasks({})
    currentPage.value = 1
  } finally {
    loadingTasks.value = false
  }
}

async function loadRules() {
  loadingRules.value = true
  try {
    rules.value = await fetchQaRules()
  } finally {
    loadingRules.value = false
  }
}

function openManual() {
  manualForm.sessionName = ''
  manualForm.agentName = ''
  manualForm.aiScore = 85
  manualForm.riskLevel = 1
  manualForm.ruleNames = []
  manualForm.comment = ''
  manualVisible.value = true
}

async function submitManual() {
  if (!manualForm.sessionName.trim()) {
    ElMessage.warning('请填写质检会话名称')
    return
  }
  if (!manualForm.agentName.trim()) {
    ElMessage.warning('请填写接待客服')
    return
  }
  savingManual.value = true
  try {
    const task = await createManualQaTask({
      sessionName: manualForm.sessionName.trim(),
      agentName: manualForm.agentName.trim(),
      aiScore: manualForm.aiScore,
      riskLevel: manualForm.riskLevel,
      comment: manualForm.comment.trim() || undefined,
      ruleNames: manualForm.ruleNames.length ? manualForm.ruleNames : undefined,
    })
    manualVisible.value = false
    await loadAll()
    ElMessage.success(`单个质检任务已创建：${task.taskNo}`)
  } finally {
    savingManual.value = false
  }
}

async function startScan() {
  scanning.value = true
  scanVisible.value = true
  scanProgress.value = 0
  scanText.value = '正在找最近结束、还没质检过的会话…'
  try {
    // 扫描的是真实会话：已结束、还没有质检任务的单子会被补进来并跑一遍 AI 初检
    const result = await scanQaTasks()
    for (let step = 20; step <= 100; step += 10) {
      scanProgress.value = step
      scanText.value = step < 100
        ? `正在执行规则引擎，扫描进度 ${step}%`
        : `扫描完成：${result.scanned ?? 0} 条已结束会话，生成 ${result.created} 项待复核任务`
      await new Promise((resolve) => setTimeout(resolve, 180))
    }
    await loadAll()
    if (result.created > 0) {
      ElMessage.success(`全量质检完成，新增 ${result.created} 项待复核任务`)
    } else {
      ElMessage.info('没有待质检的会话：最近结束的会话都已质检过')
    }
  } catch {
    scanVisible.value = false
  } finally {
    scanning.value = false
  }
}

async function openDetail(row: QaTaskItem) {
  detailVisible.value = true
  detail.value = null
  try {
    detail.value = await fetchQaTaskDetail(row.taskNo)
  } catch {
    detailVisible.value = false
  }
}

function openReview(row: QaTaskItem, action: number) {
  reviewTask.value = row
  reviewAction.value = action
  reviewScore.value = Number(row.reviewScore ?? row.aiScore ?? 90)
  reviewComment.value = ''
  reviewVisible.value = true
}

function openAiBatch() {
  if (!selectedTasks.value.length) {
    ElMessage.warning('请先在列表中勾选需要执行 AI 质检的任务')
    return
  }
  aiBatchVisible.value = true
}

async function submitAiBatch() {
  const rows = selectedTasks.value
  if (!rows.length) return
  aiBatchRunning.value = true
  try {
    const result = await batchAiQaTasks(rows.map((r) => r.taskNo))
    aiBatchVisible.value = false
    selectedTasks.value = []
    taskTable.value?.clearSelection()
    await loadAll()
    ElMessage.success(`AI 质检完成，共处理 ${result.processed} 条任务，已进入待复核队列`)
  } finally {
    aiBatchRunning.value = false
  }
}

function openBatchReview() {
  if (!selectedTasks.value.length) {
    ElMessage.warning('请先勾选需要复核的质检任务')
    return
  }
  batchReviewAction.value = 1
  batchReviewScore.value = 90
  batchReviewComment.value = ''
  batchReviewVisible.value = true
}

async function submitBatchReview() {
  const rows = selectedTasks.value
  if (!rows.length) return
  if (batchReviewAction.value !== 3) {
    const pending = rows.every((r) => r.status === 1)
    if (!pending) {
      ElMessage.warning('“复核通过/驳回”只能批量处理待复核任务，请重新勾选')
      return
    }
    if (batchReviewAction.value === 2 && !batchReviewComment.value.trim()) {
      ElMessage.warning('批量驳回时必须填写复核意见')
      return
    }
  }
  batchReviewing.value = true
  try {
    const result = await batchReviewQaTasks(rows.map((r) => r.taskNo), {
      action: batchReviewAction.value,
      score: batchReviewAction.value === 3 ? undefined : batchReviewScore.value,
      comment: batchReviewComment.value.trim() || undefined,
    })
    batchReviewVisible.value = false
    selectedTasks.value = []
    taskTable.value?.clearSelection()
    await loadAll()
    ElMessage.success(`批量处理完成，共处理 ${result.processed} 条质检任务`)
  } finally {
    batchReviewing.value = false
  }
}

async function submitReview() {
  if (!reviewTask.value) return
  if (reviewAction.value !== 3 && (reviewScore.value == null || reviewScore.value < 0)) {
    ElMessage.warning('请填写复核评分')
    return
  }
  if (reviewAction.value === 2 && !reviewComment.value.trim()) {
    ElMessage.warning('驳回时必须填写复核意见')
    return
  }
  savingReview.value = true
  try {
    await reviewQaTask(reviewTask.value.taskNo, {
      action: reviewAction.value,
      score: reviewAction.value === 3 ? undefined : reviewScore.value,
      comment: reviewComment.value.trim() || undefined,
    })
    reviewVisible.value = false
    detailVisible.value = false
    await loadAll()
    ElMessage.success(
      reviewAction.value === 1 ? '复核已通过' : reviewAction.value === 2 ? '已驳回重检' : '任务已重新进入待复核队列',
    )
  } finally {
    savingReview.value = false
  }
}

function indexMethod(index: number) {
  return (currentPage.value - 1) * pageSize + index + 1
}

/** 表格里的时间统一显示到秒（后端给的是 ISO 串，带 T 和毫秒/微秒） */
function formatTime(time?: string | null) {
  if (!time) {
    return '—'
  }
  const text = String(time).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function scoreClass(score?: number) {
  if (score == null) return ''
  if (score >= 90) return 'score-good'
  if (score >= 80) return 'score-mid'
  return 'score-bad'
}

function scoreColor(score?: number) {
  if (score == null) return '#94a3b8'
  if (score >= 90) return '#16a34a'
  if (score >= 80) return '#d97706'
  return '#dc2626'
}

function scoreTag(score?: number) {
  if (score == null) return 'info'
  if (score >= 90) return 'success'
  if (score >= 80) return 'warning'
  return 'danger'
}

function riskTag(level: number) {
  if (level === 1) return 'info'
  if (level === 2) return 'warning'
  return 'danger'
}

function statusTag(status: number) {
  if (status === 1) return 'warning'
  if (status === 2) return 'success'
  return 'danger'
}

function scanColor() {
  return scanProgress.value >= 100 ? '#16a34a' : '#2563eb'
}
</script>

<style scoped>
.qa-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 12px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.pass-badge { color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 999px; font-size: 13px; padding: 7px 14px; }
.btn-icon { margin-right: 4px; }
.stat-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 14px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; color: #fff; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-icon.orange { background: #fff7ed; color: #ea580c; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
/* 实时预警：边聊边检的告警面板 */
.alert-card { width: 100%; margin-bottom: 16px; border: 1px solid #fed7aa; }
.alert-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.alert-title { display: flex; align-items: center; gap: 6px; font-size: 15px; font-weight: 700; color: #9a3412; }
.alert-sub { margin-top: 4px; font-size: 12px; color: #b45309; }
.alert-head-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.alert-metric { font-size: 13px; color: #9a3412; }
.alert-metric b { font-size: 16px; }
.alert-metric.warn b { color: #c2410c; }
.alert-metric.danger b { color: #dc2626; }
.alert-snippet { font-size: 13px; color: #0f172a; }
.alert-advice { margin-top: 2px; font-size: 12px; color: #b45309; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.main-card { width: 100%; }
.alert-badge { margin-left: 6px; }
.alert-evidence { display: flex; flex-direction: column; gap: 10px; margin-bottom: 6px; }
.evidence-item { border: 1px solid #fed7aa; background: #fffbf5; border-radius: 8px; padding: 10px 12px; }
.evidence-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.evidence-rule { font-weight: 600; font-size: 13px; color: #9a3412; }
.evidence-time { font-size: 12px; color: #b45309; }
.evidence-snippet { font-size: 13px; color: #334155; }
.evidence-advice { margin-top: 2px; font-size: 12px; color: #b45309; }
.hit-keyword {
  display: inline-block;
  margin-right: 4px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fee2e2;
  color: #b91c1c;
  font-weight: 600;
}
.qa-empty { padding: 26px 0; }
.qa-empty-title { font-size: 15px; font-weight: 700; color: #334155; }
.qa-empty-sub { margin-top: 8px; font-size: 13px; color: #94a3b8; line-height: 1.8; }
.filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.qa-search { width: 260px; }
.task-no { font-weight: 700; color: #1e293b; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.cell-sub { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.member-cell { display: flex; align-items: center; gap: 8px; color: #475569; }
.member-avatar { width: 28px; height: 28px; border-radius: 8px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; background: linear-gradient(135deg, #1d4ed8, #3b82f6); flex-shrink: 0; font-size: 13px; }
.score-line { display: flex; align-items: center; gap: 8px; }
.score-line b { min-width: 28px; }
.score-good { color: #16a34a; }
.score-mid { color: #d97706; }
.score-bad { color: #dc2626; }
.score-progress { flex: 1; min-width: 70px; }
.review-score { display: inline-block; color: #fff; background: linear-gradient(135deg, #1d4ed8, #3b82f6); border-radius: 999px; padding: 2px 10px; font-weight: 700; }
.table-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; }
.foot-left { display: flex; align-items: center; gap: 14px; }
.rule-entry-card { margin-top: 14px; width: 100%; }
.rule-entry { display: flex; align-items: center; gap: 14px; padding: 4px 2px; }
.rule-entry-icon { width: 42px; height: 42px; border-radius: 12px; background: #eef4ff; color: #2563eb; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.rule-entry-info { flex: 1; min-width: 0; }
.rule-entry-title { font-weight: 700; color: #0f172a; }
.rule-entry-sub { margin-top: 4px; color: #94a3b8; font-size: 12px; }
.arrow-icon { margin-left: 6px; }
.rule-card { margin-top: 14px; width: 100%; }
.rule-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-weight: 700; color: #0f172a; }
.card-sub { font-size: 12px; color: #94a3b8; margin-top: 3px; }
.rule-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; min-height: 96px; }
.rule-item { display: flex; align-items: center; gap: 10px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 12px 14px; }
.rule-icon { width: 34px; height: 34px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid #e2e8f0; }
.tone-1 { background: #fef2f2; color: #dc2626; }
.tone-2 { background: #fffbeb; color: #d97706; }
.tone-3 { background: #eef4ff; color: #2563eb; }
.tone-4 { background: #f5f3ff; color: #7c3aed; }
.rule-name { font-weight: 700; color: #1e293b; font-size: 13px; }
.rule-name-cell { display: flex; align-items: center; gap: 9px; }
.rule-icon.mini { width: 26px; height: 26px; border-radius: 7px; }
.weight-value { color: #334155; font-weight: 600; }
.rule-sub { color: #94a3b8; font-size: 11px; margin-top: 3px; }
.rule-info { flex: 1; min-width: 0; }
.empty-rules { grid-column: 1 / -1; text-align: center; color: #94a3b8; padding: 30px 0; }
.detail-badges { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.detail-time { margin-left: auto; color: #94a3b8; font-size: 12px; }
.info-card { background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 12px; padding: 6px 16px; }
.info-row { display: flex; justify-content: space-between; gap: 20px; padding: 9px 0; color: #475569; font-size: 13px; }
.info-row + .info-row { border-top: 1px dashed #e2e8f0; }
.info-row .v { color: #0f172a; text-align: right; }
.detail-section-title { margin: 18px 0 10px; font-weight: 700; color: #0f172a; font-size: 14px; }
.result-item { display: flex; align-items: flex-start; gap: 10px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 11px 14px; margin-bottom: 8px; }
.result-main { flex: 1; min-width: 0; }
.result-name { font-weight: 600; color: #1e293b; font-size: 13px; }
.result-type { color: #94a3b8; font-weight: 400; font-size: 11px; margin-left: 8px; }
.drawer-foot { display: flex; align-items: center; gap: 10px; width: 100%; }
.spacer { flex: 1; }
.form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.full-width { width: 100%; }
.enable-line { display: flex; align-items: center; justify-content: space-between; border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px 14px; color: #475569; }
.review-tip { background: #eff6ff; border: 1px solid #dbeafe; color: #1d4ed8; font-size: 13px; padding: 10px 14px; border-radius: 10px; margin-bottom: 16px; }
.review-panel { max-height: 62vh; overflow-y: auto; padding: 2px 2px 4px; }
.review-summary { display: flex; align-items: center; justify-content: space-between; gap: 18px; border-radius: 14px; padding: 18px 20px; border: 1px solid #e6e8ef; }
.review-summary { border-left: 4px solid #cbd5e1; background: #fff; }
.review-summary.action-1 { border-left-color: #10b981; background: #ffffff; }
.review-summary.action-2 { border-left-color: #ef4444; background: #ffffff; }
.review-summary.action-3 { border-left-color: #3b82f6; background: #ffffff; }
.review-summary-left { min-width: 0; }
.review-status-line { display: flex; align-items: center; gap: 10px; }
.review-status-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 700; border-radius: 999px; padding: 4px 10px; }
.pill-1 { color: #047857; background: #ecfdf5; border: 1px solid #a7f3d0; }
.pill-2 { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; }
.pill-3 { color: #1d4ed8; background: #eff6ff; border: 1px solid #bfdbfe; }
.review-task-no { color: #64748b; font-size: 12px; }
.review-task-title { margin-top: 9px; font-size: 18px; font-weight: 800; color: #0f172a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.review-agent-line { display: flex; align-items: center; gap: 7px; margin-top: 9px; color: #475569; font-size: 13px; }
.review-agent-avatar { width: 22px; height: 22px; border-radius: 7px; background: linear-gradient(135deg, #1d4ed8, #3b82f6); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; }
.dot-sep { color: #cbd5e1; }
.review-muted { color: #94a3b8; }
.review-score-box { text-align: center; flex-shrink: 0; }
.review-score-num { font-weight: 800; color: #0f172a; font-size: 18px; }
.review-score-label { margin-top: 7px; font-size: 11px; color: #94a3b8; }
.review-meta { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px 28px; background: #fafbfc; border: 1px solid #eef2f6; border-radius: 12px; padding: 14px 18px; margin: 14px 0; }
.review-meta-item { display: flex; justify-content: space-between; gap: 14px; font-size: 13px; }
.review-meta-item .label { color: #94a3b8; flex-shrink: 0; }
.review-meta-item .value { color: #1e293b; text-align: right; word-break: break-all; }
.review-section-title { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-weight: 700; color: #0f172a; font-size: 14px; }
.review-score-current { margin-left: auto; font-size: 16px; font-weight: 800; }
.review-score-ruler { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 10px; }
.score-band { border-radius: 9px; padding: 8px 12px; display: flex; align-items: center; gap: 6px; border: 1px solid #eef2f6; background: #fff; color: #334155; font-size: 12px; }
.score-band::before { content: ''; width: 6px; height: 6px; border-radius: 50%; }
.score-band.good::before { background: #10b981; box-shadow: 0 0 0 3px rgba(16,185,129,.12); }
.score-band.mid::before { background: #3b82f6; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.score-band.warn::before { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245,158,11,.12); }
.score-band.bad::before { background: #ef4444; box-shadow: 0 0 0 3px rgba(239,68,68,.12); }
.score-band span { font-weight: 600; }
.score-band i { font-style: normal; color: #94a3b8; margin-left: auto; }
.review-comment-section { margin-top: 16px; }
.required-mark { color: #dc2626; background: #fee2e2; border-radius: 999px; font-size: 11px; font-weight: 600; padding: 1px 8px; }
.review-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; }
.review-footer-tip { color: #94a3b8; font-size: 12px; }
.review-footer-actions { display: flex; gap: 10px; }
.review-confirm-btn { border-radius: 8px; font-weight: 600; min-width: 132px; }
.scan-body { text-align: center; padding: 12px 6px 6px; }
.manual-tip { background: #f0f7ff; border: 1px solid #dbeafe; color: #1e40af; border-radius: 10px; padding: 10px 14px; font-size: 13px; margin-bottom: 16px; }
.ai-batch-body { text-align: center; padding: 14px 0 10px; }
.ai-batch-icon { color: #2563eb; }
.ai-batch-title { margin-top: 12px; font-weight: 700; color: #0f172a; font-size: 16px; }
.ai-batch-sub { margin: 8px auto 0; max-width: 360px; color: #64748b; font-size: 13px; line-height: 1.8; }
.manual-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.scan-icon { color: #2563eb; }
.scan-title { margin-top: 14px; font-weight: 700; color: #0f172a; font-size: 16px; }
.scan-text { color: #64748b; font-size: 13px; margin: 8px 0 20px; }
@media (max-width: 1200px) { .stat-row { grid-template-columns: repeat(3, 1fr); } .rule-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
