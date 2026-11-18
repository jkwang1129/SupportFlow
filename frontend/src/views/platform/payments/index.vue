<template>
  <div class="payments-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">收款流水</h2>
        <p class="page-sub">
          真的收到的钱（对账口径）：预付费「买套餐」与后付费「账期账单」各写一条流水——
          订单只是"要收多少"的意向，这里才是到账事实
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="load(1)">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon teal"><el-icon :size="18"><Wallet /></el-icon></div>
        <div>
          <div class="stat-value">{{ money(page.amount) }}</div>
          <div class="stat-label">筛选结果合计（只算已到账）</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Tickets /></el-icon></div>
        <div>
          <div class="stat-value">{{ money(overview.month) }}</div>
          <div class="stat-label">本月收款</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div>
          <div class="stat-value">{{ money(overview.today) }}</div>
          <div class="stat-label">今日收款</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Money /></el-icon></div>
        <div>
          <div class="stat-value">{{ overview.totalCount }}</div>
          <div class="stat-label">
            累计收款笔数（订单 {{ overview.orderCount }} · 账单 {{ overview.statementCount }}）
          </div>
        </div>
      </div>
    </div>

    <el-card shadow="never" class="mt-16">
      <div class="filters">
        <el-date-picker
          v-model="query.range"
          type="daterange"
          unlink-panels
          range-separator="至"
          start-placeholder="到账开始日"
          end-placeholder="到账结束日"
          value-format="YYYY-MM-DD"
          class="f-range"
        />
        <el-select v-model="query.bizType" placeholder="业务类型" clearable class="f-select-sm">
          <el-option label="订单·套餐" :value="1" />
          <el-option label="账期账单" :value="2" />
        </el-select>
        <el-select v-model="query.payMethodCode" placeholder="支付方式" clearable class="f-select-sm">
          <el-option label="支付宝" :value="1" />
          <el-option label="微信" :value="2" />
          <el-option label="对公转账" :value="3" />
        </el-select>
        <el-input
          v-model="query.tenantCode"
          placeholder="租户编码"
          clearable
          class="f-input-sm"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-input
          v-model="query.keyword"
          placeholder="收款单号 / 订单号 / 账单号"
          clearable
          class="f-input"
          @keyup.enter="load(1)"
          @clear="load(1)"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <!-- 按支付方式汇总：对账时一眼看出"钱从哪个渠道来" -->
      <div v-if="page.byMethod.length" class="method-row">
        <span class="method-tip">支付方式分布：</span>
        <el-tag
          v-for="item in page.byMethod"
          :key="item.payMethodCode"
          size="small"
          effect="plain"
          class="method-tag"
        >
          {{ methodText(item.payMethodCode) }} {{ money(item.amount) }}（{{ item.count }} 笔）
        </el-tag>
      </div>

      <el-table :data="list" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="indexOf" />
        <el-table-column label="到账时间" min-width="170">
          <template #default="{ row }">{{ dateTimeText(row.payTime) }}</template>
        </el-table-column>
        <el-table-column label="企业名称" min-width="190">
          <template #default="{ row }">
            <span :class="{ muted: !companyOf(row.tenantCode) }">
              {{ companyOf(row.tenantCode) || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="租户编码" width="170">
          <template #default="{ row }">
            <div class="mono cell-main">{{ row.tenantCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="业务" min-width="200">
          <template #default="{ row }">
            <div class="cell-main">
              <el-tag size="small" :type="row.bizType === 2 ? 'warning' : 'primary'" effect="light">
                {{ row.bizTypeText }}
              </el-tag>
              <span class="mono ml-6">{{ row.bizNo || '—' }}</span>
            </div>
            <div class="cell-sub">{{ row.remark || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130">
          <template #default="{ row }">
            <b class="amount">{{ money(row.amount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="支付方式" width="110">
          <template #default="{ row }">{{ row.payMethodText }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 2 ? 'success' : (row.status === 3 ? 'danger' : 'warning')"
                    effect="light">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收款单号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.payNo }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的收款流水" :image-size="80" />
        </template>
      </el-table>

      <p class="foot-tip">
        收款流水的两条来源：企业买套餐支付成功 → 订单收款；平台确认账期账单收款 → 账单收款。
        同一笔业务只落一条（重复支付与三方回调重推都被唯一索引拦住）。
      </p>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="Number(page.total) || 0"
        :page-sizes="[10, 20, 50]"
        @current-change="loadList"
        @size-change="() => load(1)"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errorMessage } from '../../../utils/message'
import { CircleCheckFilled, Money, Refresh, Search, Tickets, Wallet } from '@element-plus/icons-vue'
import { fetchPayments, money, type PaymentRow } from '../../../api/billing'
import { fetchTenantNames } from '../../../api/platform/tenant'

const loading = ref(false)
const list = ref<PaymentRow[]>([])
/** 租户编码 → 企业名称（流水里只有编码，企业名在 tenant_db） */
const tenantNames = ref<Record<string, string>>({})
const page = reactive({
  total: 0,
  amount: 0,
  overview: { total: 0, totalCount: 0, month: 0, today: 0, orderCount: 0, statementCount: 0 },
  byMethod: [] as Array<{ payMethodCode: number; payMethod: string; count: number; amount: number }>,
})

const query = reactive({
  range: [] as string[],
  bizType: undefined as number | undefined,
  payMethodCode: undefined as number | undefined,
  tenantCode: '',
  keyword: '',
  page: 1,
  pageSize: 10,
})

/** 累计/本月/今日来自后端概览（全量口径，不受筛选影响） */
const overview = computed(() => page.overview)

/** 序号：翻页后从 1 开始（不是全局序号） */
function indexOf(index: number) {
  return (query.page - 1) * query.pageSize + index + 1
}

function methodText(code?: number | null) {
  return ({ 1: '支付宝', 2: '微信支付', 3: '对公转账' } as Record<number, string>)[code ?? 0] ?? '—'
}

function companyOf(tenantCode?: string | null) {
  if (!tenantCode) return ''
  return tenantNames.value[tenantCode] ?? ''
}

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function loadList() {
  void load(query.page || 1)
}

async function load(pageNo: number) {
  query.page = pageNo
  loading.value = true
  try {
    const data = await fetchPayments({
      bizType: query.bizType,
      payMethodCode: query.payMethodCode,
      tenantCode: query.tenantCode || undefined,
      keyword: query.keyword || undefined,
      from: query.range?.[0] || undefined,
      to: query.range?.[1] || undefined,
      page: pageNo,
      pageSize: query.pageSize,
    })
    list.value = data.list
    page.total = Number(data.total) || 0
    page.amount = Number(data.amount) || 0
    page.overview = data.overview ?? page.overview
    page.byMethod = data.byMethod ?? []
  } catch (e) {
    errorMessage(e, '收款流水加载失败')
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.range = []
  query.bizType = undefined
  query.payMethodCode = undefined
  query.tenantCode = ''
  query.keyword = ''
  void load(1)
}

async function loadTenantNames() {
  try {
    const rows = await fetchTenantNames()
    const map: Record<string, string> = {}
    for (const row of rows) {
      if (row.tenantCode) {
        map[row.tenantCode] = row.companyName || ''
      }
    }
    tenantNames.value = map
  } catch {
    // 目录拿不到就只显示租户编号，不影响对账
    tenantNames.value = {}
  }
}

onMounted(async () => {
  await Promise.all([load(1), loadTenantNames()])
})
</script>

<style scoped>
.payments-page { padding: 0 4px 24px; }
.head-actions { display: flex; gap: 8px; }
.stat-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; background: #fff; border: 1px solid #eef1f6; border-radius: 10px; }
.stat-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; }
.stat-icon.teal { background: #ecfdf5; color: #0f766e; }
.stat-icon.blue { background: #eff6ff; color: #1d4ed8; }
.stat-icon.green { background: #f0fdf4; color: #15803d; }
.stat-icon.purple { background: #f5f3ff; color: #6d28d9; }
.stat-value { font-size: 20px; font-weight: 700; color: #0f172a; }
.stat-label { font-size: 12px; color: #94a3b8; }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.f-range { width: 260px; }
.f-select-sm { width: 130px; }
.f-input-sm { width: 170px; }
.f-input { width: 240px; }
.method-row { margin-top: 12px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.method-tip { font-size: 12px; color: #94a3b8; }
.method-tag { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.foot-tip { margin: 10px 0 0; font-size: 12px; color: #94a3b8; }
.cell-main { font-size: 13px; color: #0f172a; }
.cell-sub { margin-top: 2px; font-size: 12px; color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.amount { color: #0f766e; }
.muted { color: #94a3b8; }
.ml-6 { margin-left: 6px; }
.mt-12 { margin-top: 12px; }
.mt-16 { margin-top: 16px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
