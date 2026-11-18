<template>
  <div class="obs-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">可观测</h2>
        <p class="page-sub">
          调用链贯穿三端 · 阈值告警与处置 · AI 成本预算与超限动作
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="evaluating" @click="evaluateNow">
          <el-icon class="btn-icon"><Timer /></el-icon>
          立即评估
        </el-button>
        <el-button :loading="loading" @click="loadAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">待处理告警</div>
        <div class="stat-value warn">{{ events?.summary.pending ?? '—' }}</div>
        <div class="stat-sub">需要有人看一眼</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">严重</div>
        <div class="stat-value danger">{{ events?.summary.critical ?? '—' }}</div>
        <div class="stat-sub">告警总条数 {{ events?.summary.total ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">警告</div>
        <div class="stat-value">{{ events?.summary.warning ?? '—' }}</div>
        <div class="stat-sub">今日新增 {{ events?.summary.today ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">启用规则</div>
        <div class="stat-value">{{ enabledRuleCount }}</div>
        <div class="stat-sub">共 {{ rules.length }} 条</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">配了预算的租户</div>
        <div class="stat-value">{{ budgets.length }}</div>
        <div class="stat-sub">超限动作在调 AI 前生效</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="obs-tabs">
      <!-- 告警事件 -->
      <el-tab-pane label="告警事件" name="events">
        <el-card shadow="never">
          <div class="filter-row">
            <el-select v-model="eventQuery.handleStatus" class="w160" placeholder="全部状态" clearable>
              <el-option :value="1" label="待处理" />
              <el-option :value="2" label="已确认" />
              <el-option :value="3" label="已忽略" />
            </el-select>
            <el-select v-model="eventQuery.level" class="w160" placeholder="全部级别" clearable>
              <el-option :value="1" label="提示" />
              <el-option :value="2" label="警告" />
              <el-option :value="3" label="严重" />
            </el-select>
            <el-input v-model.trim="eventQuery.tenantCode" class="w220" placeholder="租户编号" clearable />
            <el-button type="primary" @click="reloadEvents">查询</el-button>
          </div>

          <el-table :data="events?.list ?? []" size="small">
            <el-table-column type="index" label="#" width="52" />
            <el-table-column label="级别" width="82">
              <template #default="{ row }">
                <el-tag size="small" :type="levelTag(row.level)" effect="light">{{ row.levelText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ruleName" label="规则" width="180" show-overflow-tooltip />
            <el-table-column label="范围" width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.tenantCode">{{ companyOf(row.tenantCode) }}</span>
                <span v-else class="muted">全平台</span>
              </template>
            </el-table-column>
            <el-table-column label="实际值 / 阈值" width="170">
              <template #default="{ row }">
                <span class="mono">{{ row.metricValue }}</span>
                <span class="muted"> / {{ row.threshold }}（{{ row.windowDays }}天）</span>
              </template>
            </el-table-column>
            <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="row.handleStatus === 1 ? 'warning' : 'info'" effect="plain">
                  {{ row.handleStatusText }}
                </el-tag>
                <span v-if="row.tenantCode && row.notified" class="muted notified">已通知企业</span>
              </template>
            </el-table-column>
            <el-table-column prop="eventTime" label="发生时间" width="160" />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <template v-if="row.handleStatus === 1">
                  <el-button link type="primary" @click="handleEvent(row, 2)">确认</el-button>
                  <el-button link type="info" @click="handleEvent(row, 3)">忽略</el-button>
                </template>
                <span v-else class="muted">{{ row.handlerName || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="eventQuery.page"
            class="obs-pager"
            layout="total, sizes, prev, pager, next, jumper"
            :total="events?.total ?? 0"
            :page-size="eventQuery.pageSize"
            :page-sizes="[10, 20, 50]"
            @current-change="loadEvents"
            @size-change="onEventSizeChange"
          />
        </el-card>
      </el-tab-pane>

      <!-- 告警规则 -->
      <el-tab-pane label="告警规则" name="rules">
        <el-card shadow="never">
          <div class="card-head">
            <div>
              <div class="card-title">阈值告警规则</div>
              <div class="card-sub">
                指标口径来自 AI 计量（只统计真实模型调用）；「按租户」的规则命中后会推一条消息到该企业的消息中心
              </div>
            </div>
            <el-button type="primary" @click="openRule(null)">新建规则</el-button>
          </div>

          <el-table :data="rules" size="small">
            <el-table-column type="index" label="#" width="52" />
            <el-table-column prop="ruleName" label="规则" width="170" show-overflow-tooltip />
            <el-table-column prop="metricText" label="指标" width="150" />
            <el-table-column label="条件" width="180">
              <template #default="{ row }">
                <span class="mono">{{ row.compareText }} {{ row.threshold }}</span>
                <span class="muted"> · 近 {{ row.windowDays }} 天</span>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="levelTag(row.level)" effect="light">{{ row.levelText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="scopeText" label="范围" width="90" />
            <el-table-column label="通知企业" width="90">
              <template #default="{ row }">{{ row.notifyTenant ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isEnabled ? 'success' : 'info'" effect="plain">
                  {{ row.isEnabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="updateTime" label="更新时间" width="160" />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRule(row)">编辑</el-button>
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 成本预算 -->
      <el-tab-pane label="成本预算" name="budgets">
        <el-card shadow="never">
          <div class="card-head">
            <div>
              <div class="card-title">AI 成本预算与超限处置</div>
              <div class="card-sub">
                超限动作有真实执行点：customer-service 在调模型之前先问成本闸门，
                「降级」跳过模型但仍接住客户，「暂停」直接转人工 —— 不是只写进配置
              </div>
            </div>
            <el-button type="primary" @click="openBudget(null)">新增/修改预算</el-button>
          </div>

          <el-table :data="budgets" size="small">
            <el-table-column type="index" label="#" width="52" />
            <el-table-column label="租户" width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ companyOf(row.tenantCode) }}</template>
            </el-table-column>
            <el-table-column label="本月已用 / 预算" width="220">
              <template #default="{ row }">
                <span class="mono">{{ money(row.usedCost) }}</span>
                <span class="muted"> / {{ row.monthlyBudget > 0 ? money(row.monthlyBudget) : '不限' }}</span>
                <el-progress
                  v-if="row.monthlyBudget > 0"
                  :percentage="Math.min(100, Number(row.usedRatio) || 0)"
                  :status="progressStatus(row)"
                  :stroke-width="6"
                  class="budget-bar"
                />
              </template>
            </el-table-column>
            <el-table-column label="预警线" width="90">
              <template #default="{ row }">{{ row.alertRatio }}%</template>
            </el-table-column>
            <el-table-column prop="overActionText" label="超限动作" width="150" />
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isEnabled ? 'success' : 'info'" effect="plain">
                  {{ row.isEnabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
            <el-table-column prop="updateTime" label="更新时间" width="160" />
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openBudget(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 调用链 -->
      <el-tab-pane label="调用链" name="trace">
        <el-card shadow="never">
          <div class="card-title">按调用链号查这一次请求</div>
          <div class="card-sub">
            号来自页面报错里的「请求号」：前端 → 网关 → 各服务 → AI 服务一路透传，
            这里显示的是它在 AI 侧留下的计量（模型 / token / 成本 / 耗时 / 成败）
          </div>
          <div class="filter-row">
            <el-input
              v-model.trim="traceId"
              class="w320"
              placeholder="调用链号，例如 3f7c19ab22d04b18"
              clearable
              @keyup.enter="searchTrace"
            />
            <el-button type="primary" :loading="traceLoading" @click="searchTrace">查询</el-button>
            <el-button v-if="lastTraceId" @click="useLastTrace">
              用最近一次（{{ lastTraceId }}）
            </el-button>
          </div>

          <el-alert
            v-if="traceSearched && !trace?.found"
            type="info"
            show-icon
            :closable="false"
            title="这个调用链号在 AI 计量里没有记录"
            description="三种可能：① 这次请求没有真的调模型（走了规则/检索/缓存）；② 服务是旧版本，没有透传调用链号；③ 号填错了。"
          />

          <el-table v-else-if="trace?.found" :data="trace.items" size="small">
            <el-table-column prop="time" label="时间" width="170" />
            <el-table-column prop="tenantCode" label="租户" width="170" />
            <el-table-column prop="modelKey" label="模型" width="150" />
            <el-table-column prop="sceneText" label="场景" width="110" />
            <el-table-column label="token（入/出）" width="140">
              <template #default="{ row }">{{ row.inputTokens }} / {{ row.outputTokens }}</template>
            </el-table-column>
            <el-table-column label="成本" width="100">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">{{ row.latencyMs == null ? '—' : `${row.latencyMs} ms` }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'" effect="light">
                  {{ row.success ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 规则编辑 -->
    <el-dialog v-model="ruleOpen" :title="ruleForm.id ? '编辑告警规则' : '新建告警规则'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="规则名称">
          <el-input v-model="ruleForm.ruleName" maxlength="64" placeholder="例如：失败率超过 10%" />
        </el-form-item>
        <el-form-item label="指标">
          <el-select v-model="ruleForm.metric" class="w240">
            <el-option v-for="item in rules_metrics" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="统计窗口">
          <el-input-number v-model="ruleForm.windowDays" :min="1" :max="90" />
          <span class="hint">天（1 = 只看今天）</span>
        </el-form-item>
        <el-form-item label="条件">
          <el-select v-model="ruleForm.compareOp" class="w120">
            <el-option :value="1" label="大于等于 ≥" />
            <el-option :value="2" label="小于等于 ≤" />
          </el-select>
          <el-input-number v-model="ruleForm.threshold" :min="0" :precision="2" class="ml8" />
        </el-form-item>
        <el-form-item label="级别">
          <el-radio-group v-model="ruleForm.level">
            <el-radio :value="1">提示</el-radio>
            <el-radio :value="2">警告</el-radio>
            <el-radio :value="3">严重</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="范围">
          <el-radio-group v-model="ruleForm.scope">
            <el-radio :value="1">全平台</el-radio>
            <el-radio :value="2">按租户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="命中后">
          <el-switch v-model="ruleForm.notifyTenant" :disabled="ruleForm.scope !== 2" />
          <span class="hint">推一条消息到该企业的消息中心（仅"按租户"可用）</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.isEnabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预算编辑 -->
    <el-dialog v-model="budgetOpen" :title="budgetForm.id ? '修改成本预算' : '新增成本预算'" width="520px">
      <el-form label-width="120px">
        <el-form-item label="租户">
          <el-select
            v-model="budgetForm.tenantCode"
            class="w280"
            filterable
            :disabled="!!budgetForm.id"
            placeholder="选择租户"
          >
            <el-option
              v-for="tenant in tenants"
              :key="tenant.tenantCode"
              :value="tenant.tenantCode"
              :label="`${tenant.companyName || tenant.tenantCode}（${tenant.tenantCode}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="月度预算（元）">
          <el-input-number v-model="budgetForm.monthlyBudget" :min="0" :precision="2" />
          <span class="hint">0 = 不限（只统计不拦）</span>
        </el-form-item>
        <el-form-item label="预警线">
          <el-input-number v-model="budgetForm.alertRatio" :min="1" :max="100" />
          <span class="hint">% 达到就先告警并通知企业</span>
        </el-form-item>
        <el-form-item label="超限动作">
          <el-radio-group v-model="budgetForm.overAction">
            <el-radio :value="1">仅告警</el-radio>
            <el-radio :value="2">降级到规则/检索</el-radio>
            <el-radio :value="3">暂停 AI（转人工）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="budgetForm.isEnabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="budgetForm.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBudgetRow">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Timer } from '@element-plus/icons-vue'
import { errorMessage } from '../../../utils/message'
import { getLastTraceId } from '../../../utils/trace'
import {
  createAlertRule,
  deleteAlertRule,
  evaluateAlertsNow,
  fetchAlertEvents,
  fetchAlertRules,
  fetchBudgets,
  fetchTrace,
  handleAlertEvent,
  saveBudget,
  updateAlertRule,
  type AlertEventPage,
  type AlertEventRow,
  type AlertRuleBody,
  type AlertRuleRow,
  type BudgetPage,
  type BudgetRow,
  type TraceResult,
} from '../../../api/platform/observe'
import { fetchTenantNames } from '../../../api/platform/tenant'

const tab = ref('events')
const loading = ref(false)
const saving = ref(false)
const evaluating = ref(false)

const rules = ref<AlertRuleRow[]>([])
const rules_metrics = ref<Array<{ value: number; label: string }>>([])
const events = ref<AlertEventPage | null>(null)
const budgets = ref<BudgetRow[]>([])
const tenants = ref<Array<{ tenantCode: string; companyName?: string | null }>>([])

const eventQuery = reactive<{ handleStatus?: number; level?: number; tenantCode: string; page: number; pageSize: number }>({
  handleStatus: undefined,
  level: undefined,
  tenantCode: '',
  page: 1,
  pageSize: 10,
})

const ruleOpen = ref(false)
const ruleForm = reactive<{
  id: string | null
  ruleName: string
  metric: number
  windowDays: number
  compareOp: number
  threshold: number
  level: number
  scope: number
  notifyTenant: boolean
  isEnabled: boolean
  remark: string
}>({
  id: null,
  ruleName: '',
  metric: 1,
  windowDays: 1,
  compareOp: 1,
  threshold: 10,
  level: 2,
  scope: 1,
  notifyTenant: true,
  isEnabled: true,
  remark: '',
})

const budgetOpen = ref(false)
const budgetForm = reactive<{
  id: string | null
  tenantCode: string
  monthlyBudget: number
  alertRatio: number
  overAction: number
  isEnabled: boolean
  remark: string
}>({
  id: null,
  tenantCode: '',
  monthlyBudget: 200,
  alertRatio: 80,
  overAction: 1,
  isEnabled: true,
  remark: '',
})

const traceId = ref('')
const trace = ref<TraceResult | null>(null)
const traceLoading = ref(false)
const traceSearched = ref(false)
const lastTraceId = ref('')

const enabledRuleCount = computed(() => rules.value.filter((item) => item.isEnabled).length)

function levelTag(level: number): 'info' | 'warning' | 'danger' {
  if (level === 3) return 'danger'
  if (level === 2) return 'warning'
  return 'info'
}

function money(value?: number | null): string {
  return `¥${Number(value ?? 0).toFixed(4)}`
}

function progressStatus(row: BudgetRow): 'success' | 'warning' | 'exception' {
  const ratio = Number(row.usedRatio) || 0
  if (ratio >= 100) return 'exception'
  if (ratio >= (row.alertRatio || 80)) return 'warning'
  return 'success'
}

function companyOf(tenantCode?: string | null): string {
  if (!tenantCode) {
    return '—'
  }
  const hit = tenants.value.find((item) => item.tenantCode === tenantCode)
  return hit?.companyName ? `${hit.companyName}（${tenantCode}）` : tenantCode
}

async function loadRules(): Promise<void> {
  const page = await fetchAlertRules()
  rules.value = page.items
  rules_metrics.value = page.metrics
}

async function loadEvents(): Promise<void> {
  events.value = await fetchAlertEvents({
    handleStatus: eventQuery.handleStatus,
    level: eventQuery.level,
    tenantCode: eventQuery.tenantCode,
    page: eventQuery.page,
    pageSize: eventQuery.pageSize,
  })
}

async function loadBudgets(): Promise<void> {
  const page: BudgetPage = await fetchBudgets()
  budgets.value = page.items
}

async function loadTenants(): Promise<void> {
  try {
    tenants.value = await fetchTenantNames()
  } catch {
    tenants.value = []
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([
      loadRules(),
      loadEvents(),
      loadBudgets(),
      loadTenants(),
    ])
  } catch (e) {
    errorMessage(e, '加载可观测数据失败')
  } finally {
    loading.value = false
  }
}

function reloadEvents(): void {
  eventQuery.page = 1
  void loadEvents()
}

function onEventSizeChange(): void {
  eventQuery.page = 1
  void loadEvents()
}

async function handleEvent(row: AlertEventRow, status: number): Promise<void> {
  let remark = ''
  if (status === 3) {
    try {
      const input = await ElMessageBox.prompt('说明一下为什么忽略（会记进审计）', '忽略告警', {
        inputPlaceholder: '例如：这次是压测造成的，已知悉',
        inputValidator: (value: string) => (value && value.trim() ? true : '请填写说明'),
        confirmButtonText: '确认忽略',
        cancelButtonText: '取消',
      })
      remark = input.value || ''
    } catch {
      return
    }
  }
  try {
    await handleAlertEvent(row.id, status, remark)
    ElMessage.success(status === 2 ? '已确认' : '已忽略')
    await loadEvents()
  } catch (e) {
    errorMessage(e, '处置失败')
  }
}

function openRule(row: AlertRuleRow | null): void {
  ruleForm.id = row?.id ?? null
  ruleForm.ruleName = row?.ruleName ?? ''
  ruleForm.metric = row?.metric ?? 1
  ruleForm.windowDays = row?.windowDays ?? 1
  ruleForm.compareOp = row?.compareOp ?? 1
  ruleForm.threshold = Number(row?.threshold ?? 10)
  ruleForm.level = row?.level ?? 2
  ruleForm.scope = row?.scope ?? 1
  ruleForm.notifyTenant = row?.notifyTenant ?? true
  ruleForm.isEnabled = row?.isEnabled ?? true
  ruleForm.remark = row?.remark ?? ''
  ruleOpen.value = true
}

async function saveRule(): Promise<void> {
  if (!ruleForm.ruleName.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  const body: AlertRuleBody = {
    ruleName: ruleForm.ruleName.trim(),
    metric: ruleForm.metric,
    windowDays: ruleForm.windowDays,
    compareOp: ruleForm.compareOp,
    threshold: ruleForm.threshold,
    level: ruleForm.level,
    scope: ruleForm.scope,
    notifyTenant: ruleForm.notifyTenant,
    isEnabled: ruleForm.isEnabled,
    remark: ruleForm.remark,
  }
  saving.value = true
  try {
    const page = ruleForm.id
      ? await updateAlertRule(ruleForm.id, body)
      : await createAlertRule(body)
    rules.value = page.items
    rules_metrics.value = page.metrics
    ruleOpen.value = false
    ElMessage.success('规则已保存')
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeRule(row: AlertRuleRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除规则「${row.ruleName}」？已产生的告警事件会保留。`, '删除告警规则', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    const page = await deleteAlertRule(row.id)
    rules.value = page.items
    ElMessage.success('已删除')
  } catch (e) {
    errorMessage(e, '删除失败')
  }
}

function openBudget(row: BudgetRow | null): void {
  budgetForm.id = row?.id ?? null
  budgetForm.tenantCode = row?.tenantCode ?? ''
  budgetForm.monthlyBudget = Number(row?.monthlyBudget ?? 200)
  budgetForm.alertRatio = Number(row?.alertRatio ?? 80)
  budgetForm.overAction = row?.overAction ?? 1
  budgetForm.isEnabled = row?.isEnabled ?? true
  budgetForm.remark = row?.remark ?? ''
  budgetOpen.value = true
}

async function saveBudgetRow(): Promise<void> {
  if (!budgetForm.tenantCode) {
    ElMessage.warning('请选择租户')
    return
  }
  if (budgetForm.overAction !== 1) {
    try {
      await ElMessageBox.confirm(
        budgetForm.overAction === 3
          ? '超限后该租户的 AI 会被暂停（客户消息直接转人工）。确认保存？'
          : '超限后该租户的 AI 会降级为规则与检索（不再调模型）。确认保存？',
        '确认超限动作',
        { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    const page = await saveBudget({
      tenantCode: budgetForm.tenantCode,
      monthlyBudget: budgetForm.monthlyBudget,
      alertRatio: budgetForm.alertRatio,
      overAction: budgetForm.overAction,
      isEnabled: budgetForm.isEnabled,
      remark: budgetForm.remark,
    })
    budgets.value = page.items
    budgetOpen.value = false
    ElMessage.success('预算已保存')
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function evaluateNow(): Promise<void> {
  evaluating.value = true
  try {
    const result = await evaluateAlertsNow()
    ElMessage.success(result.created > 0
      ? `本轮产生 ${result.created} 条新告警`
      : '本轮没有新的告警命中')
    await loadEvents()
  } catch (e) {
    errorMessage(e, '评估失败')
  } finally {
    evaluating.value = false
  }
}

async function searchTrace(): Promise<void> {
  if (!traceId.value) {
    ElMessage.warning('请填写调用链号')
    return
  }
  traceLoading.value = true
  try {
    trace.value = await fetchTrace(traceId.value)
    traceSearched.value = true
  } catch (e) {
    errorMessage(e, '查询失败')
  } finally {
    traceLoading.value = false
  }
}

function useLastTrace(): void {
  traceId.value = lastTraceId.value
  void searchTrace()
}

onMounted(() => {
  lastTraceId.value = getLastTraceId()
  void loadAll()
})
</script>

<style scoped>
.obs-page {
  padding: 0 4px 24px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.stat-card {
  padding: 14px 16px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #eef1f6;
}

.stat-label {
  font-size: 12px;
  color: #94a3b8;
}

.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.stat-value.warn {
  color: #d97706;
}

.stat-value.danger {
  color: #dc2626;
}

.stat-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.card-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.card-sub {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: #94a3b8;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.w120 {
  width: 120px;
}

.w160 {
  width: 160px;
}

.w220 {
  width: 220px;
}

.w240 {
  width: 240px;
}

.w280 {
  width: 280px;
}

.w320 {
  width: 320px;
}

.ml8 {
  margin-left: 8px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-weight: 600;
  color: #0f172a;
}

.muted {
  color: #94a3b8;
  font-size: 12px;
}

.notified {
  margin-left: 6px;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #94a3b8;
}

.budget-bar {
  margin-top: 6px;
}

.obs-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
