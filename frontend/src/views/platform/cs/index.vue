<template>
  <div class="cs-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">客户成功</h2>
        <p class="page-sub">租户健康度 · 续费风险 · 扩展商机 · 回访任务</p>
      </div>
      <div class="head-actions">
        <el-button @click="openModel">
          <el-icon class="btn-icon"><InfoFilled /></el-icon>
          健康模型
        </el-button>
        <el-button type="primary" :loading="loading" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <!-- KPI：健康度分布 + 风险 + 商机 + 回访 -->
    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon blue"><el-icon :size="18"><OfficeBuilding /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.tenants ?? '—' }}</div>
          <div class="kpi-label">租户总数</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon green"><el-icon :size="18"><CircleCheck /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.healthy ?? '—' }}</div>
          <div class="kpi-label">健康（{{ overview?.summary.avgHealthScore ?? 0 }} 分均分）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><View /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.watch ?? '—' }}</div>
          <div class="kpi-label">需关注</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.risky ?? '—' }}</div>
          <div class="kpi-label">有风险</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon red"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.highRisk ?? '—' }}</div>
          <div class="kpi-label">高续费风险（中风险 {{ overview?.summary.midRisk ?? 0 }}）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon purple"><el-icon :size="18"><TrendCharts /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.opportunities ?? '—' }}</div>
          <div class="kpi-label">扩展商机</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon amber"><el-icon :size="18"><Clock /></el-icon></div>
        <div>
          <div class="kpi-value">{{ overview?.summary.expiringIn30Days ?? '—' }}</div>
          <div class="kpi-label">30 天内到期（已过期 {{ overview?.summary.expired ?? 0 }}）</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon" :class="(tasks?.summary.overdue ?? 0) > 0 ? 'red' : 'blue'">
          <el-icon :size="18"><Calendar /></el-icon>
        </div>
        <div>
          <div class="kpi-value">{{ tasks?.summary.pending ?? '—' }}</div>
          <div class="kpi-label">待回访任务（逾期 {{ tasks?.summary.overdue ?? 0 }}）</div>
        </div>
      </div>
    </div>

    <!-- 健康度列表 -->
    <el-card shadow="never" class="full-card">
      <div class="card-head">
        <div>
          <div class="card-title">租户健康度</div>
          <div class="card-sub">健康分 = 用量 {{ model?.usage ?? 35 }}% + 活跃 {{ model?.active ?? 25 }}% + 到期 {{ model?.expire ?? 20 }}% + 趋势 {{ model?.trend ?? 20 }}%</div>
        </div>
        <div class="filters">
          <el-select v-model="query.healthLevel" class="w-140" placeholder="全部健康度" clearable>
            <el-option value="HEALTHY" label="健康" />
            <el-option value="WATCH" label="需关注" />
            <el-option value="RISK" label="有风险" />
          </el-select>
          <el-select v-model="query.renewRisk" class="w-140" placeholder="全部续费风险" clearable>
            <el-option :value="3" label="高" />
            <el-option :value="2" label="中" />
            <el-option :value="1" label="低" />
          </el-select>
          <el-checkbox v-model="query.onlyOpportunity">只看有商机</el-checkbox>
          <el-input v-model="query.keyword" class="w-200" clearable placeholder="企业名称 / 租户编号" />
        </div>
      </div>

      <el-table :data="filteredTenants" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="indexOfRow" />
        <el-table-column label="企业" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-main">{{ row.companyName || row.tenantCode }}</div>
            <div class="cell-sub mono">{{ row.tenantCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="套餐" width="100">
          <template #default="{ row }">{{ planName(row.planId) }}</template>
        </el-table-column>
        <el-table-column label="健康度" width="190">
          <template #default="{ row }">
            <div class="health-cell" :title="healthDetail(row)">
              <div class="health-bar">
                <span :style="{ width: row.healthScore + '%', background: healthColor(row.healthLevel) }"></span>
              </div>
              <span class="health-score">{{ row.healthScore }}</span>
              <el-tag size="small" :type="healthTag(row.healthLevel)" effect="light">
                {{ row.healthLevelText }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.tenantStatus === 1 ? 'success' : 'warning'" effect="light">
              {{ row.tenantStatusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="续费风险" width="130">
          <template #default="{ row }">
            <el-tooltip :content="row.renewRiskReason" placement="top">
              <el-tag size="small" :type="riskTag(row.renewRisk)" effect="light">
                {{ row.renewRiskText }}风险
              </el-tag>
            </el-tooltip>
            <span v-if="manualRisk(row)" class="manual-flag">人工</span>
          </template>
        </el-table-column>
        <el-table-column label="扩展商机" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.expandOpportunity === 1">
              <el-tag size="small" type="success" effect="light">有</el-tag>
              <span class="suggestion">{{ row.expandSuggestion }}</span>
            </template>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="到期" width="120">
          <template #default="{ row }">
            <span :class="{ danger: row.remainDays !== null && row.remainDays !== undefined && row.remainDays <= 30 }">
              {{ remainText(row.remainDays) }}
            </span>
            <div class="cell-sub">{{ (row.expireTime || '').slice(0, 10) || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="下次回访" width="150">
          <template #default="{ row }">
            <template v-if="followUpOf(row.tenantCode)?.nextVisitDate">
              <span :class="{ danger: (followUpOf(row.tenantCode)?.visitRemainDays ?? 99) < 0 }">
                {{ followUpOf(row.tenantCode)?.nextVisitDate }}
              </span>
              <div class="cell-sub">
                {{ followUpOf(row.tenantCode)?.csOwnerName || '未指定负责人' }}
              </div>
            </template>
            <el-button v-else link type="primary" @click="openFollowUp(row)">去安排</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openFollowUp(row)">安排回访</el-button>
            <el-button link type="success" @click="openTask(null, row)">建任务</el-button>
            <el-button link @click="openHealth(row)">明细</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的租户" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        class="pager"
        layout="total, prev, pager, next"
        :total="filteredTenants.length"
        :page-size="query.pageSize"
        @current-change="() => {}"
      />
    </el-card>

    <!-- 回访任务 -->
    <el-card shadow="never" class="full-card">
      <div class="card-head">
        <div>
          <div class="card-title">回访任务</div>
          <div class="card-sub">
            待回访 {{ tasks?.summary.pending ?? 0 }} · 今日到期 {{ tasks?.summary.dueToday ?? 0 }} ·
            逾期 {{ tasks?.summary.overdue ?? 0 }} · 已完成 {{ tasks?.summary.finished ?? 0 }}
          </div>
        </div>
        <div class="filters">
          <el-select v-model="taskQuery.status" class="w-140" placeholder="全部状态" clearable @change="loadTasks">
            <el-option :value="1" label="待回访" />
            <el-option :value="2" label="已完成" />
            <el-option :value="3" label="已取消" />
          </el-select>
          <el-button type="primary" @click="openTask(null, null)">
            <el-icon class="btn-icon"><Plus /></el-icon>
            新建回访任务
          </el-button>
        </div>
      </div>

      <el-table :data="tasks?.list || []" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="taskIndexOfRow" />
        <el-table-column label="任务" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-main">{{ row.title }}</div>
            <div class="cell-sub">{{ row.companyName || row.tenantCode }} · {{ row.tenantCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.taskTypeText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="110">
          <template #default="{ row }">{{ row.ownerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="回访时间" width="180">
          <template #default="{ row }">
            <span :class="{ danger: row.overdue }">{{ row.dueTime || '—' }}</span>
            <div v-if="row.overdue" class="cell-sub danger">已逾期 {{ -(row.dueRemainDays ?? 0) }} 天</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="taskStatusTag(row.status)" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回访结论" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.result">{{ row.result }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 1">
              <el-button link type="success" @click="openFinish(row)">完成</el-button>
              <el-button link @click="openTask(row, null)">编辑</el-button>
              <el-button link type="danger" @click="cancelTask(row)">取消</el-button>
            </template>
            <span v-else class="muted">{{ row.finishTime || '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有回访任务" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="taskQuery.page"
        class="pager"
        layout="total, prev, pager, next"
        :total="Number(tasks?.total) || 0"
        :page-size="taskQuery.pageSize"
        @current-change="loadTasks"
      />
    </el-card>

    <!-- 安排回访 -->
    <el-dialog v-model="followUpOpen" title="安排回访" width="560px">
      <el-form label-width="110px">
        <el-form-item label="租户">
          <span class="cell-main">{{ followUpForm.companyName }} · {{ followUpForm.tenantCode }}</span>
        </el-form-item>
        <el-form-item label="客户成功经理">
          <el-input v-model="followUpForm.csOwnerName" placeholder="例如：苏三" />
        </el-form-item>
        <el-form-item label="下次回访">
          <el-date-picker
            v-model="followUpForm.nextVisitTime"
            type="datetime"
            class="w-full"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择下次回访时间"
          />
        </el-form-item>
        <el-form-item label="续费风险">
          <el-radio-group v-model="followUpForm.renewRisk">
            <el-radio-button :value="1">低</el-radio-button>
            <el-radio-button :value="2">中</el-radio-button>
            <el-radio-button :value="3">高</el-radio-button>
          </el-radio-group>
          <span class="hint">留空则沿用自动判断（点开时已带出建议值）</span>
        </el-form-item>
        <el-form-item label="扩展商机">
          <el-radio-group v-model="followUpForm.expandOpportunity">
            <el-radio-button :value="1">有</el-radio-button>
            <el-radio-button :value="2">无</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="followUpForm.remark" type="textarea" :rows="3" placeholder="沟通要点 / 客户诉求 / 承诺事项" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="followUpOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveFollowUp">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建 / 编辑回访任务 -->
    <el-dialog v-model="taskOpen" :title="taskForm.id ? '编辑回访任务' : '新建回访任务'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="租户" required>
          <el-select
            v-model="taskForm.tenantCode"
            filterable
            class="w-full"
            placeholder="选择要回访的租户"
            @change="onTenantPicked"
          >
            <el-option
              v-for="row in overview?.items || []"
              :key="row.tenantCode"
              :value="row.tenantCode"
              :label="`${row.companyName || row.tenantCode}（${row.tenantCode}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="taskForm.taskType">
            <el-radio-button :value="1">续费</el-radio-button>
            <el-radio-button :value="2">健康</el-radio-button>
            <el-radio-button :value="3">扩展</el-radio-button>
            <el-radio-button :value="4">其它</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="taskForm.title" placeholder="例如：杭州智云续费沟通（本月底到期）" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="taskForm.ownerName" placeholder="留空记为你" />
        </el-form-item>
        <el-form-item label="回访时间">
          <el-date-picker
            v-model="taskForm.dueTime"
            type="datetime"
            class="w-full"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="计划什么时候回访"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="taskOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveTask">保存</el-button>
      </template>
    </el-dialog>

    <!-- 完成回访 -->
    <el-dialog v-model="finishOpen" title="完成回访" width="520px">
      <p class="dialog-tip">{{ finishForm.title }}</p>
      <el-input v-model="finishForm.result" type="textarea" :rows="4" placeholder="回访结论：客户反馈、下一步动作、是否需要产品/研发支持" />
      <template #footer>
        <el-button @click="finishOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doFinish">标记完成</el-button>
      </template>
    </el-dialog>

    <!-- 健康模型说明 -->
    <el-dialog v-model="modelOpen" title="健康模型" width="520px">
      <div class="model-row" v-for="item in modelRows" :key="item.label">
        <span class="model-label">{{ item.label }}</span>
        <div class="model-bar"><span :style="{ width: item.weight + '%', background: item.color }"></span></div>
        <span class="model-weight">{{ item.weight }}%</span>
        <span class="model-desc">{{ item.desc }}</span>
      </div>
      <p class="dialog-tip">{{ model?.note }}</p>
    </el-dialog>

    <!-- 健康度明细 -->
    <el-dialog v-model="healthOpen" title="健康度明细" width="560px">
      <template v-if="healthRow">
        <p class="dialog-tip">
          {{ healthRow.companyName }} · {{ healthRow.tenantCode }} · 健康分
          <b>{{ healthRow.healthScore }}</b>（{{ healthRow.healthLevelText }}）
        </p>
        <div class="model-row" v-for="item in rowModelRows(healthRow)" :key="item.label">
          <span class="model-label">{{ item.label }}</span>
          <div class="model-bar"><span :style="{ width: item.score + '%', background: item.color }"></span></div>
          <span class="model-weight">{{ item.score }}</span>
          <span class="model-desc">{{ item.desc }}</span>
        </div>
        <p class="dialog-tip">
          近 30 天：请求 {{ healthRow.requests30d.toLocaleString() }} · 会话 {{ healthRow.sessions30d.toLocaleString() }} ·
          AI 调用 {{ healthRow.aiCalls30d.toLocaleString() }} · 活跃 {{ healthRow.activeDays30d }} 天<br />
          续费风险：{{ healthRow.renewRiskText }}（{{ healthRow.renewRiskReason }}）
          <template v-if="healthRow.expandSuggestion"><br />扩展建议：{{ healthRow.expandSuggestion }}</template>
        </p>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import {
  BellFilled,
  Calendar,
  CircleCheck,
  Clock,
  InfoFilled,
  OfficeBuilding,
  Plus,
  Refresh,
  TrendCharts,
  View,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  createCsTask,
  fetchCsFollowUps,
  fetchCsOverview,
  fetchCsTasks,
  finishCsTask,
  saveCsFollowUp,
  updateCsTask,
  type CsFollowUp,
  type CsOverview,
  type CsTask,
  type CsTaskPage,
  type CsTenantRow,
} from '../../../api/platform/cs'
import { fetchPlatformPlans, type PlatformPlanRow } from '../../../api/platform/settings'

const loading = ref(false)
const saving = ref(false)
const overview = ref<CsOverview | null>(null)
const followUps = ref<CsFollowUp[]>([])
const tasks = ref<CsTaskPage | null>(null)
const plans = ref<PlatformPlanRow[]>([])

const query = reactive<{
  healthLevel?: string
  renewRisk?: number
  onlyOpportunity: boolean
  keyword: string
  page: number
  pageSize: number
}>({
  healthLevel: undefined,
  renewRisk: undefined,
  onlyOpportunity: false,
  keyword: '',
  page: 1,
  pageSize: 10,
})

const taskQuery = reactive<{ status?: number; page: number; pageSize: number }>({
  status: 1,
  page: 1,
  pageSize: 10,
})

const model = computed(() => overview.value?.model)

const modelRows = computed(() => [
  { label: '用量', weight: model.value?.usage ?? 35, color: '#2563eb', desc: '近 30 天请求量 vs 配额' },
  { label: '活跃', weight: model.value?.active ?? 25, color: '#059669', desc: '近 30 天有上报的天数占比' },
  { label: '到期', weight: model.value?.expire ?? 20, color: '#d97706', desc: '剩余天数（90 天以上满分）' },
  { label: '趋势', weight: model.value?.trend ?? 20, color: '#7c3aed', desc: '最近 7 天 vs 前 7 天用量' },
])

/** 前端筛选 + 前端分页：客户成功是"人工跟进"的活儿，租户量级小，不必再打服务端 */
const filteredTenants = computed(() => {
  const rows = overview.value?.items || []
  const keyword = query.keyword.trim().toLowerCase()
  const matched = rows.filter((row) => {
    if (query.healthLevel && row.healthLevel !== query.healthLevel) {
      return false
    }
    if (query.renewRisk && row.renewRisk !== query.renewRisk) {
      return false
    }
    if (query.onlyOpportunity && row.expandOpportunity !== 1) {
      return false
    }
    if (keyword) {
      const text = `${row.companyName || ''}${row.tenantCode}`.toLowerCase()
      if (!text.includes(keyword)) {
        return false
      }
    }
    return true
  })
  return matched.slice((query.page - 1) * query.pageSize, query.page * query.pageSize)
})

function followUpOf(tenantCode: string): CsFollowUp | undefined {
  return followUps.value.find((item) => item.tenantCode === tenantCode)
}

/** 人工覆盖过的风险值（与自动判断不同才标"人工"） */
function manualRisk(row: CsTenantRow): boolean {
  const follow = followUpOf(row.tenantCode)
  return Boolean(follow?.renewRisk) && follow?.renewRisk !== row.renewRisk
}

function planName(planId?: string | null): string {
  if (!planId) {
    return '未开套餐'
  }
  return plans.value.find((item) => item.id === planId)?.planName || '—'
}

function healthColor(level: string): string {
  if (level === 'HEALTHY') {
    return 'linear-gradient(90deg, #6ee7b7, #059669)'
  }
  if (level === 'WATCH') {
    return 'linear-gradient(90deg, #fcd34d, #d97706)'
  }
  return 'linear-gradient(90deg, #fca5a5, #dc2626)'
}

function healthTag(level: string): 'success' | 'warning' | 'danger' {
  if (level === 'HEALTHY') {
    return 'success'
  }
  return level === 'WATCH' ? 'warning' : 'danger'
}

function riskTag(risk: number): 'success' | 'warning' | 'danger' {
  if (risk === 3) {
    return 'danger'
  }
  return risk === 2 ? 'warning' : 'success'
}

function taskStatusTag(status: number): 'success' | 'info' | 'warning' {
  if (status === 2) {
    return 'success'
  }
  return status === 3 ? 'info' : 'warning'
}

function remainText(remainDays?: number | null): string {
  if (remainDays === null || remainDays === undefined) {
    return '未设到期'
  }
  if (remainDays < 0) {
    return `已过期 ${-remainDays} 天`
  }
  return `还剩 ${remainDays} 天`
}

function healthDetail(row: CsTenantRow): string {
  const parts = row.scoreParts
  return `用量 ${parts.usage} · 活跃 ${parts.active} · 到期 ${parts.expire} · 趋势 ${parts.trend}`
}

function rowModelRows(row: CsTenantRow) {
  return [
    { label: '用量', score: row.scoreParts.usage, color: '#2563eb', desc: `近 30 天 ${row.requests30d.toLocaleString()} 次请求` },
    { label: '活跃', score: row.scoreParts.active, color: '#059669', desc: `近 30 天活跃 ${row.activeDays30d} 天` },
    { label: '到期', score: row.scoreParts.expire, color: '#d97706', desc: remainText(row.remainDays) },
    { label: '趋势', score: row.scoreParts.trend, color: '#7c3aed', desc: `最近 7 天 ${row.requestsLast7.toLocaleString()} vs 前 7 天 ${row.requestsPrev7.toLocaleString()}` },
  ]
}

function indexOfRow(index: number): number {
  return (query.page - 1) * query.pageSize + index + 1
}

function taskIndexOfRow(index: number): number {
  return (taskQuery.page - 1) * taskQuery.pageSize + index + 1
}

// ------------------------------------------------------------------ 弹窗状态

const followUpOpen = ref(false)
const followUpForm = reactive({
  tenantCode: '',
  companyName: '',
  csOwnerName: '',
  nextVisitTime: '',
  renewRisk: 1 as number,
  expandOpportunity: 2 as number,
  healthScore: undefined as number | undefined,
  remark: '',
})

const taskOpen = ref(false)
const taskForm = reactive({
  id: '' as string | null,
  tenantCode: '',
  companyName: '',
  taskType: 2,
  title: '',
  ownerName: '',
  dueTime: '',
})

const finishOpen = ref(false)
const finishForm = reactive({ id: '', title: '', result: '' })

const modelOpen = ref(false)
const healthOpen = ref(false)
const healthRow = ref<CsTenantRow | null>(null)

function openModel(): void {
  modelOpen.value = true
}

function openHealth(row: CsTenantRow): void {
  healthRow.value = row
  healthOpen.value = true
}

function openFollowUp(row: CsTenantRow): void {
  const existing = followUpOf(row.tenantCode)
  Object.assign(followUpForm, {
    tenantCode: row.tenantCode,
    companyName: row.companyName,
    csOwnerName: existing?.csOwnerName || '',
    nextVisitTime: existing?.nextVisitTime || '',
    // 带出建议值：运营多数情况只是确认一下
    renewRisk: existing?.renewRisk || row.renewRisk,
    expandOpportunity: existing?.expandOpportunity || row.expandOpportunity,
    healthScore: row.healthScore,
    remark: existing?.remark || '',
  })
  followUpOpen.value = true
}

function openTask(task: CsTask | null, row: CsTenantRow | null): void {
  Object.assign(taskForm, {
    id: task?.id ?? null,
    tenantCode: task?.tenantCode ?? row?.tenantCode ?? '',
    companyName: task?.companyName ?? row?.companyName ?? '',
    taskType: task?.taskType ?? 2,
    title: task?.title ?? '',
    ownerName: task?.ownerName ?? '',
    dueTime: task?.dueTime ?? '',
  })
  taskOpen.value = true
}

function onTenantPicked(tenantCode: string): void {
  const row = (overview.value?.items || []).find((item) => item.tenantCode === tenantCode)
  taskForm.companyName = row?.companyName || ''
}

function openFinish(task: CsTask): void {
  Object.assign(finishForm, { id: task.id, title: task.title, result: '' })
  finishOpen.value = true
}

// ------------------------------------------------------------------ 动作

async function doSaveFollowUp(): Promise<void> {
  if (!followUpForm.tenantCode) {
    ElMessage.warning('缺少租户')
    return
  }
  saving.value = true
  try {
    await saveCsFollowUp({
      tenantCode: followUpForm.tenantCode,
      healthScore: followUpForm.healthScore,
      renewRisk: followUpForm.renewRisk,
      expandOpportunity: followUpForm.expandOpportunity,
      csOwnerName: followUpForm.csOwnerName || undefined,
      nextVisitTime: followUpForm.nextVisitTime || undefined,
      remark: followUpForm.remark || undefined,
    })
    ElMessage.success('随访安排已保存')
    followUpOpen.value = false
    await loadFollowUps()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function doSaveTask(): Promise<void> {
  if (!taskForm.tenantCode || !taskForm.title.trim()) {
    ElMessage.warning('请选择租户并填写标题')
    return
  }
  saving.value = true
  try {
    if (taskForm.id) {
      await updateCsTask(taskForm.id, {
        taskType: taskForm.taskType,
        title: taskForm.title,
        ownerName: taskForm.ownerName || undefined,
        dueTime: taskForm.dueTime || undefined,
      })
      ElMessage.success('任务已更新')
    } else {
      await createCsTask({
        tenantCode: taskForm.tenantCode,
        companyName: taskForm.companyName || undefined,
        taskType: taskForm.taskType,
        title: taskForm.title,
        ownerName: taskForm.ownerName || undefined,
        dueTime: taskForm.dueTime || undefined,
      })
      ElMessage.success('回访任务已创建')
    }
    taskOpen.value = false
    await loadTasks()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function doFinish(): Promise<void> {
  saving.value = true
  try {
    await finishCsTask(finishForm.id, { status: 2, result: finishForm.result || undefined })
    ElMessage.success('回访已完成')
    finishOpen.value = false
    await Promise.all([loadTasks(), loadFollowUps()])
  } catch (e) {
    errorMessage(e, '操作失败')
  } finally {
    saving.value = false
  }
}

async function cancelTask(task: CsTask): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定取消「${task.title}」吗？`, '取消回访任务', {
      type: 'warning',
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  try {
    await finishCsTask(task.id, { status: 3 })
    ElMessage.success('任务已取消')
    await loadTasks()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function loadFollowUps(): Promise<void> {
  try {
    const data = await fetchCsFollowUps()
    followUps.value = data.items || []
  } catch {
    followUps.value = []
  }
}

async function loadTasks(): Promise<void> {
  try {
    tasks.value = await fetchCsTasks({
      status: taskQuery.status || undefined,
      page: taskQuery.page,
      pageSize: taskQuery.pageSize,
    })
  } catch (e) {
    errorMessage(e, '回访任务加载失败')
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [tenantData, planList] = await Promise.all([
      fetchCsOverview(),
      fetchPlatformPlans().catch(() => [] as PlatformPlanRow[]),
    ])
    overview.value = tenantData
    plans.value = planList
    await Promise.all([loadFollowUps(), loadTasks()])
  } catch (e) {
    errorMessage(e, '客户成功数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.cs-page { width: 100%; min-width: 0; }

.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card { display: flex; align-items: center; gap: 10px; padding: 14px; background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; }
.kpi-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kpi-icon.blue { background: #eef4ff; color: #2563eb; }
.kpi-icon.green { background: #ecfdf5; color: #059669; }
.kpi-icon.amber { background: #fff7ed; color: #d97706; }
.kpi-icon.red { background: #fef2f2; color: #dc2626; }
.kpi-icon.purple { background: #f5f3ff; color: #7c3aed; }
.kpi-value { font-size: 19px; font-weight: 800; color: #0f172a; line-height: 1.25; }
.kpi-label { margin-top: 2px; font-size: 12px; color: #94a3b8; }

.full-card { margin-bottom: 16px; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.card-sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.w-140 { width: 140px; }
.w-200 { width: 200px; }
.w-full { width: 100%; }
.mt-12 { margin-top: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }

.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.muted { color: #94a3b8; font-size: 12.5px; }
.danger { color: #dc2626; }
.suggestion { margin-left: 8px; font-size: 12px; color: #059669; }
.manual-flag { margin-left: 6px; font-size: 11px; color: #b45309; }
.hint { margin-left: 10px; font-size: 12px; color: #94a3b8; }

.health-cell { display: flex; align-items: center; gap: 8px; }
.health-bar { flex: 1; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.health-bar span { display: block; height: 100%; border-radius: 999px; }
.health-score { width: 26px; text-align: right; font-size: 12.5px; font-weight: 700; color: #0f172a; }

.dialog-tip { margin: 0 0 10px; font-size: 13px; color: #64748b; line-height: 1.8; }
.model-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 12.5px; color: #64748b; }
.model-label { width: 40px; color: #334155; font-weight: 600; }
.model-bar { width: 110px; height: 8px; border-radius: 999px; background: #f1f5f9; overflow: hidden; }
.model-bar span { display: block; height: 100%; border-radius: 999px; }
.model-weight { width: 44px; text-align: right; color: #0f172a; font-weight: 600; }
.model-desc { flex: 1; }

@media (max-width: 1400px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
