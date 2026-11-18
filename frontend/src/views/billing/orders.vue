<template>
  <div class="orders-page">
    <div class="page-head">
      <div>
        <h2>我的订单</h2>
        <div class="sub">套餐购买、支付进度与账单记录</div>
      </div>
      <div class="page-head-actions">
        <el-button @click="exportOrders">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出订单
        </el-button>
        <el-button type="primary" @click="router.push('/modules/upgrade')">
          <el-icon class="btn-icon"><Plus /></el-icon>
          购买套餐
        </el-button>
      </div>
    </div>

    <!-- 概览（照原型 orders-kpis：订单总数 / 累计消费 / 待付款 / 已开票） -->
    <div class="kpi-grid">
      <div class="kpi">
        <div class="k-top"><span class="k-label">订单总数</span>
          <span class="k-icon blue"><el-icon :size="17"><Tickets /></el-icon></span>
        </div>
        <div class="k-value">{{ orderList.length }}</div>
      </div>
      <div class="kpi">
        <div class="k-top"><span class="k-label">累计消费</span>
          <span class="k-icon green"><el-icon :size="17"><CreditCard /></el-icon></span>
        </div>
        <div class="k-value">{{ money(paidAmount) }}</div>
      </div>
      <div class="kpi">
        <div class="k-top"><span class="k-label">待付款</span>
          <span class="k-icon amber"><el-icon :size="17"><Clock /></el-icon></span>
        </div>
        <div class="k-value">{{ money(pendingAmount) }}</div>
      </div>
      <div class="kpi">
        <div class="k-top"><span class="k-label">已开票</span>
          <span class="k-icon purple"><el-icon :size="17"><Files /></el-icon></span>
        </div>
        <div class="k-value">{{ invoicedCount }}<small>张</small></div>
        <div class="k-foot">开票需提供税号，联系平台开具</div>
      </div>
    </div>

    <!-- 订单列表 -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head">
          <span class="card-title">订单列表</span>
          <span class="muted">已支付订单即生效：套餐与限流配额同步更新</span>
        </div>
      </template>
      <el-table :data="orderList" v-loading="loading">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column label="订单号" min-width="190">
          <template #default="{ row }"><span class="mono cell-main">{{ row.orderNo }}</span></template>
        </el-table-column>
        <el-table-column label="套餐" width="130">
          <template #default="{ row }">{{ planNameOf(row.planId) }}</template>
        </el-table-column>
        <el-table-column label="周期" width="90">
          <template #default="{ row }">{{ row.cycle === 1 ? '年付' : '月付' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="140">
          <template #default="{ row }"><b>{{ money(row.amount) }}</b></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="orderTag(row.status)" effect="light">{{ orderText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发票" width="100">
          <template #default="{ row }">
            <span class="cell-sub">{{ row.invoiceStatus === 3 ? '已开具' : '未申请' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下单时间" min-width="160">
          <template #default="{ row }">{{ dateTimeText(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" link type="success" @click="continuePay(row)">
              去收银台
            </el-button>
            <el-button v-else link type="primary" @click="router.push('/modules/upgrade')">续费 / 升级</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有订单，点右上「购买套餐」开始" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 账单（这是本系统真实的账期结算数据） -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head">
          <span class="card-title">我的账单</span>
          <span class="muted">套餐费 + 超量费；已结算的账单不会因为重算而变化</span>
        </div>
      </template>
      <el-table :data="statements">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column label="账单号" min-width="180" prop="statementNo" />
        <el-table-column label="账期" width="110" prop="cycleKey" />
        <el-table-column label="套餐" width="120" prop="planName" />
        <el-table-column label="套餐费" width="120">
          <template #default="{ row }">{{ money(row.planAmount) }}</template>
        </el-table-column>
        <el-table-column label="超量费" width="120">
          <template #default="{ row }">
            <span :class="{ 'warn-text': Number(row.overageAmount) > 0 }">{{ money(row.overageAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应付" width="130">
          <template #default="{ row }"><b>{{ money(row.totalAmount) }}</b></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statementTag(row.status)" effect="light">{{ statementText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结算时间" min-width="160">
          <template #default="{ row }">{{ dateTimeText(row.settleTime) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有账单：平台按账期生成后这里会出现" :image-size="80" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorMessage } from '../../utils/message'
import { Clock, CreditCard, Download, Files, Plus, Tickets } from '@element-plus/icons-vue'
import {
  fetchOrders,
  fetchPlans,
  fetchStatements,
  money,
  type BillingOrderItem,
  type BillingStatementItem,
  type PlanItem,
} from '../../api/billing'

const router = useRouter()
const loading = ref(false)
const orderList = ref<BillingOrderItem[]>([])
const statements = ref<BillingStatementItem[]>([])
const plans = ref<PlanItem[]>([])

/** 累计消费（已支付）/ 待付款 / 已开票：都用真实订单算，不写死 */
const paidAmount = computed(() =>
  orderList.value.filter((o) => o.status === 2).reduce((sum, o) => sum + Number(o.amount || 0), 0),
)
const pendingAmount = computed(() =>
  orderList.value.filter((o) => o.status === 1).reduce((sum, o) => sum + Number(o.amount || 0), 0),
)
const invoicedCount = computed(() => orderList.value.filter((o) => o.invoiceStatus === 3).length)

function planNameOf(planId?: string) {
  const plan = plans.value.find((item) => String(item.id) === String(planId))
  return plan ? plan.planName : '—'
}

function orderText(status: number) {
  return { 1: '待支付', 2: '已支付', 3: '已取消', 4: '已退款' }[status] ?? '—'
}

function orderTag(status: number) {
  return ({ 1: 'warning', 2: 'success', 3: 'info', 4: 'danger' }[status] ?? 'info') as
    | 'warning'
    | 'success'
    | 'info'
    | 'danger'
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

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

async function load() {
  loading.value = true
  try {
    const [orderPage, statementPage, planList] = await Promise.all([
      fetchOrders({ page: 1, pageSize: 50 }),
      fetchStatements({ page: 1, pageSize: 20 }),
      fetchPlans(),
    ])
    orderList.value = orderPage.list
    statements.value = statementPage.list
    plans.value = planList
  } catch (e) {
    errorMessage(e, '订单加载失败')
  } finally {
    loading.value = false
  }
}

/** 待支付订单 → 收银台（直接落到「确认支付」这一步） */
function continuePay(row: BillingOrderItem) {
  void router.push({
    path: '/billing/checkout',
    query: {
      orderNo: row.orderNo,
      planId: row.planId,
      cycle: String(row.cycle),
      amount: String(row.amount ?? ''),
    },
  })
}

/** 导出订单（原型「导出订单」按钮）：CSV 走 Blob 下载，Excel 打开不乱码 */
function exportOrders() {
  if (!orderList.value.length) {
    ElMessage.warning('还没有订单可导出')
    return
  }
  const header = ['订单号', '套餐', '周期', '金额', '状态', '下单时间']
  const rows = orderList.value.map((order) => [
    order.orderNo,
    planNameOf(order.planId),
    order.cycle === 1 ? '年付' : '月付',
    Number(order.amount ?? 0).toFixed(2),
    orderText(order.status),
    dateTimeText(order.createTime),
  ])
  const csv = [header, ...rows].map((row) => row.join(',')).join('\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '我的订单.csv'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<style scoped>
.orders-page { width: 100%; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.page-head .sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.page-head-actions { display: flex; align-items: center; gap: 10px; }
.btn-icon { margin-right: 4px; }
.mt-16 { margin-top: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.card-title { font-size: 14.5px; font-weight: 600; }
.muted { color: #94a3b8; font-size: 12px; }
.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; }
.warn-text { color: #d97706; }

/* KPI（与数据概览同一套观感） */
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 4px; }
.kpi { background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 16px 16px 14px; }
.k-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.k-label { font-size: 12.5px; color: #475569; font-weight: 500; }
.k-value { font-size: 24px; font-weight: 700; color: #0f172a; letter-spacing: -0.5px; }
.k-value small { font-size: 13px; color: #94a3b8; margin-left: 2px; }
.k-foot { margin-top: 8px; font-size: 12px; color: #94a3b8; }
.k-icon { width: 32px; height: 32px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; }
.k-icon.blue { background: #eaf2fe; color: #3b82f6; }
.k-icon.green { background: #e7f8f0; color: #10b981; }
.k-icon.amber { background: #fef4e6; color: #f59e0b; }
.k-icon.purple { background: #e0f2fe; color: #0ea5e9; }

@media (max-width: 1200px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
