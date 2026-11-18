<template>
  <div class="billing-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">计费与账单</h2>
        <p class="page-sub">
          套餐 · 用量计量 · 账期结算：账单 = 套餐费 + 超量费，重算幂等，已结算的不再变动
        </p>
      </div>
      <div class="head-actions">
        <el-date-picker
          v-model="cycle"
          type="month"
          value-format="YYYY-MM"
          placeholder="账期"
          class="cycle-picker"
          @change="refreshAll"
        />
        <el-button :loading="loading" @click="refreshAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="generateOpen = true">
          <el-icon class="btn-icon"><Plus /></el-icon>
          生成账单
        </el-button>
        <el-button :loading="batchRunning" @click="doGenerateAll">
          <el-icon class="btn-icon"><Files /></el-icon>
          生成全部租户
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Tickets /></el-icon></div>
        <div><div class="stat-value">{{ overview?.statements ?? '—' }}</div><div class="stat-label">本期账单</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Money /></el-icon></div>
        <div><div class="stat-value">{{ money(overview?.totalAmount) }}</div><div class="stat-label">应收合计</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ money(overview?.settledAmount) }}</div><div class="stat-label">已收</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><Clock /></el-icon></div>
        <div><div class="stat-value">{{ money(overview?.pendingAmount) }}</div><div class="stat-label">待收</div></div>
      </div>
      <div class="stat-card" :class="{ warn: (overview?.overdueCount ?? 0) > 0 }">
        <div class="stat-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.overdueCount ?? '—' }}</div><div class="stat-label">逾期账单</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon teal"><el-icon :size="18"><Wallet /></el-icon></div>
        <div>
          <div class="stat-value">{{ money(overview?.paidAmount) }}</div>
          <div class="stat-label">本期收款（订单 {{ overview?.paidOrders ?? 0 }} 笔）</div>
        </div>
      </div>
    </div>

    <div class="grid-12 mt-16">
      <el-card shadow="never" class="col-8">
        <template #header>
          <div class="card-head">
            <span>账单列表（{{ cycle }}）</span>
            <span class="muted">点「结算」= 收到钱；「作废」用于错账（作废后可重新生成）</span>
          </div>
        </template>
        <el-table :data="statements" v-loading="loading">
          <el-table-column type="index" label="序号" width="70" />
          <el-table-column label="账单号 / 租户" min-width="230">
            <template #default="{ row }">
              <div class="cell-main mono">{{ row.statementNo }}</div>
              <div class="cell-sub mono">{{ row.tenantCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="账期" width="100" prop="cycleKey" />
          <el-table-column label="套餐" width="110" prop="planName" />
          <el-table-column label="套餐费" width="110">
            <template #default="{ row }">{{ money(row.planAmount) }}</template>
          </el-table-column>
          <el-table-column label="超量费" width="110">
            <template #default="{ row }">
              <span :class="{ 'warn-text': Number(row.overageAmount) > 0 }">{{ money(row.overageAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="应付" width="120">
            <template #default="{ row }"><b>{{ money(row.totalAmount) }}</b></template>
          </el-table-column>
          <el-table-column label="说明" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ muted: !row.remark }">{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statementTag(row.status)" effect="light">{{ statementText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.status === 2" @click="settle(row, true)">结算</el-button>
              <el-button link type="danger" :disabled="row.status === 2" @click="settle(row, false)">作废</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="这个账期还没有账单，点右上「生成账单」" :image-size="80" />
          </template>
        </el-table>
        <el-pagination
          v-model:current-page="page"
          class="pager"
          layout="total, prev, pager, next"
          :total="Number(total) || 0"
          :page-size="pageSize"
          @current-change="loadStatements"
        />
      </el-card>

      <el-card shadow="never" class="col-4">
        <template #header><div class="card-head"><span>套餐分布（{{ cycle }}）</span></div></template>
        <div v-for="item in overview?.planDistribution ?? []" :key="item.name" class="dist-row">
          <span class="dist-name">{{ item.name }}</span>
          <span class="dist-bar">
            <i :style="{ width: distWidth(item.cnt) + '%' }" />
          </span>
          <span class="dist-cnt">{{ item.cnt }} 户 · {{ money(item.amount) }}</span>
        </div>
        <el-empty
          v-if="!(overview?.planDistribution ?? []).length"
          description="这个账期还没有账单"
          :image-size="70"
        />
      </el-card>
    </div>

    <!-- 收款明细有单独一页（带一堆查询条件），这里只留入口 + 本账期金额 -->
    <el-card shadow="never" class="mt-16">
      <div class="payment-entry">
        <div>
          <div class="cell-main">真实收的钱：本账期 {{ money(overview?.paidAmount) }}（{{ overview?.paidOrders ?? 0 }} 笔）</div>
          <div class="cell-sub">
            预付费（买套餐）走订单、后付费（超量）走下面的账期账单——明细在「收款流水」页按时间 / 租户 / 套餐 / 支付方式查
          </div>
        </div>
        <el-button type="primary" plain @click="router.push('/platform/payments')">
          <el-icon class="btn-icon"><Wallet /></el-icon>
          查看收款流水
        </el-button>
      </div>
    </el-card>

    <!-- 生成账单 -->
    <el-dialog v-model="generateOpen" title="生成 / 重算账期账单" width="520px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="同一租户同一账期只会有一张账单"
        description="重复生成只是重算金额（幂等）；已结算的账单不会被改动，避免收了钱金额又变。"
      />
      <el-form label-width="110px" class="mt-12">
        <el-form-item label="租户编码">
          <el-input v-model="generateForm.tenantCode" placeholder="例如 T202609050000002" />
        </el-form-item>
        <el-form-item label="账期">
          <el-input v-model="generateForm.cycleKey" placeholder="YYYY-MM，留空表示本月" />
        </el-form-item>
        <el-form-item label="套餐 ID">
          <el-input v-model="generateForm.planId" placeholder="留空表示用原有套餐" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doGenerate">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import { CircleCheckFilled, Clock, Files, Money, Plus, Refresh, Tickets, Wallet, WarningFilled } from '@element-plus/icons-vue'
import {
  fetchBillingOverview,
  fetchStatements,
  generateStatement,
  generateAllStatements,
  money,
  settleStatement,
  type BillingOverview,
  type BillingStatementItem,
} from '../../../api/billing'

const router = useRouter()

const loading = ref(false)
const submitting = ref(false)
/** 批量出账（生成全部租户）进行中 */
const batchRunning = ref(false)
const cycle = ref(new Date().toISOString().slice(0, 7))
const overview = ref<BillingOverview | null>(null)
const statements = ref<BillingStatementItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10

const generateOpen = ref(false)
const generateForm = reactive({ tenantCode: '', cycleKey: '', planId: '' })

const maxCount = computed(() =>
  Math.max(1, ...(overview.value?.planDistribution ?? []).map((item) => Number(item.cnt) || 0)),
)

function distWidth(count: number) {
  return Math.max(6, Math.round((Number(count) / maxCount.value) * 100))
}

function statementText(status: number) {
  return { 1: '待结算', 2: '已结算', 3: '已逾期', 4: '已作废' }[status] ?? '—'
}

function statementTag(status: number) {
  return ({ 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' }[status] ?? 'info') as
    | 'warning'
    | 'success'
    | 'danger'
    | 'info'
}

async function loadOverview() {
  try {
    overview.value = await fetchBillingOverview(cycle.value)
  } catch (e) {
    errorMessage(e, '计费概览加载失败')
  }
}

async function loadStatements() {
  loading.value = true
  try {
    const data = await fetchStatements({ cycleKey: cycle.value, page: page.value, pageSize })
    statements.value = data.list
    total.value = Number(data.total) || 0
  } catch (e) {
    errorMessage(e, '账单列表加载失败')
  } finally {
    loading.value = false
  }
}

function refreshAll() {
  page.value = 1
  void loadOverview()
  void loadStatements()
}

async function doGenerate() {
  if (!generateForm.tenantCode.trim()) {
    ElMessage.warning('请填写租户编码')
    return
  }
  submitting.value = true
  try {
    const statement = await generateStatement({
      tenantCode: generateForm.tenantCode.trim(),
      cycleKey: generateForm.cycleKey.trim() || cycle.value,
      planId: generateForm.planId.trim() || undefined,
    })
    ElMessage.success(`账单 ${statement.statementNo} 应付 ${money(statement.totalAmount)}`)
    generateOpen.value = false
    refreshAll()
  } catch (e) {
    errorMessage(e, '生成失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 生成全部租户账单：与每月 1 号的自动出账走同一段逻辑（逐户独立事务）。
 * 用来补跑某个账期，或者手工把没出过账的租户补齐。
 */
async function doGenerateAll() {
  batchRunning.value = true
  try {
    const result = await generateAllStatements(cycle.value)
    if (result.error) {
      // 拿不到租户清单 ≠ 平台里没有租户：把真实原因摆出来，别让人以为是数据问题
      errorMessage(undefined, `没拿到活跃租户清单，本次没生成账单：${result.error}`)
    } else if (result.tenants === 0) {
      ElMessage.warning('平台里还没有「已开通且有套餐」的租户，本次没生成账单')
    } else if (result.generatedCount === 0) {
      ElMessage.warning(`共 ${result.tenants} 个租户，全部跳过：${result.skipped[0]?.reason ?? ''}`)
    } else {
      ElMessage.success(
        `账期 ${result.cycleKey}：生成 ${result.generatedCount} 张账单，跳过 ${result.skippedCount} 个租户`,
      )
    }
    refreshAll()
  } catch (e) {
    errorMessage(e, '批量出账失败')
  } finally {
    batchRunning.value = false
  }
}

async function settle(row: BillingStatementItem, settleIt: boolean) {
  try {
    await ElMessageBox.confirm(
      settleIt
        ? `确认已收到 ${money(row.totalAmount)}？（结算后金额不再变动）`
        : '作废后这张账单不再计入应收，可重新生成，确定吗？',
      settleIt ? '账单结算' : '作废账单',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await settleStatement(row.statementNo, { tenantCode: row.tenantCode, settle: settleIt })
    ElMessage.success(settleIt ? '已结算' : '已作废')
    refreshAll()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

onMounted(async () => {
  refreshAll()
})
</script>

<style scoped>
.billing-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.cycle-picker { width: 150px; }
.btn-icon { margin-right: 4px; }
.mt-12 { margin-top: 12px; }
.mt-16 { margin-top: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.muted { color: #94a3b8; font-size: 12px; }
.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.warn-text { color: #d97706; }

.stat-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-card.warn { border-color: #fcd34d; background: #fffbeb; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-icon.teal { background: #ecfeff; color: #0d9488; }
.payment-entry { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.payment-entry .cell-main { font-size: 14px; font-weight: 700; color: #0f172a; }
.payment-entry .cell-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.stat-value { font-size: 19px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.grid-12 { display: grid; grid-template-columns: repeat(12, 1fr); gap: 16px; }
.col-8 { grid-column: span 8; }
.col-4 { grid-column: span 4; }
.pager { margin-top: 12px; justify-content: flex-end; }

.dist-row { display: grid; grid-template-columns: 84px 1fr auto; align-items: center; gap: 10px; padding: 8px 0; font-size: 12.5px; color: #475569; border-bottom: 1px dashed #eef2f7; }
.dist-row:last-child { border-bottom: none; }
.dist-name { color: #1e293b; font-weight: 600; }
.dist-bar { height: 6px; background: #f1f5f9; border-radius: 999px; overflow: hidden; }
.dist-bar i { display: block; height: 100%; background: linear-gradient(90deg, #60a5fa, #2563eb); border-radius: 999px; }
.dist-cnt { color: #94a3b8; font-size: 11.5px; }
</style>
