<template>
  <div class="tenants-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">租户管理</h2>
        <p class="page-sub">
          多租户深度隔离：行级安全（RLS）兜底 · 独立库升级 · 租户级限流，都在这里看和调
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="refreshAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><OfficeBuilding /></el-icon></div>
        <div><div class="stat-value">{{ overview?.total ?? '—' }}</div><div class="stat-label">租户总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.active ?? '—' }}</div><div class="stat-label">正常使用</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Lock /></el-icon></div>
        <div>
          <div class="stat-value">{{ overview?.dedicated ?? '—' }}</div>
          <div class="stat-label">独立库（Schema / 实例）</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><Upload /></el-icon></div>
        <div>
          <div class="stat-value">{{ overview?.versionBehind ?? '—' }}</div>
          <div class="stat-label">库版本落后（最新 v{{ overview?.latestVersion ?? 0 }}）</div>
        </div>
      </div>
      <div class="stat-card" :class="{ warn: (overview?.todayRejected ?? 0) > 0 }">
        <div class="stat-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div>
          <div class="stat-value">{{ overview?.todayRejected ?? '—' }}</div>
          <div class="stat-label">今日被限流拒绝</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.frozen ?? '—' }}</div><div class="stat-label">已冻结</div></div>
      </div>
    </div>

    <el-card shadow="never" class="mt-16">
      <div class="filters">
        <el-radio-group v-model="query.status" @change="reloadFromFirstPage">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">正常</el-radio-button>
          <el-radio-button :value="2">已冻结</el-radio-button>
        </el-radio-group>
        <el-select
          v-model="query.isolationMode"
          placeholder="隔离模式"
          clearable
          class="f-select"
          @change="reloadFromFirstPage"
        >
          <el-option label="共享库" :value="1" />
          <el-option label="独立 Schema" :value="2" />
          <el-option label="独立实例" :value="3" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="搜索企业名称 / 租户编码"
          clearable
          class="f-input"
          @keyup.enter="reloadFromFirstPage"
          @clear="reloadFromFirstPage"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="f-tip">
          RLS 兜底策略已启用：漏写 <code>tenant_code</code> 的 SQL 也查不到别家数据
        </span>
      </div>

      <el-table :data="list" v-loading="loading" class="mt-12" row-key="tenantCode">
        <el-table-column type="index" label="序号" width="70" :index="indexOf" />
        <el-table-column label="企业" min-width="200">
          <template #default="{ row }">
            <div class="cell-main">{{ row.companyName || '（未填企业名）' }}</div>
            <div class="cell-sub">{{ row.industry || '—' }} · 加入于 {{ dateText(row.createTime) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="租户标识" min-width="190">
          <template #default="{ row }">
            <div class="mono cell-main">{{ row.tenantCode }}</div>
            <div class="cell-sub">{{ modeText(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="隔离模式" width="130">
          <template #default="{ row }">
            <el-tag :type="row.isolationMode === 1 ? 'info' : 'success'" effect="light">
              {{ row.isolationModeText }}
            </el-tag>
            <div v-if="row.schemaName" class="cell-sub mono">{{ row.schemaName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="库版本" width="120">
          <template #default="{ row }">
            <span class="cell-main">v{{ row.dbVersion }}</span>
            <div v-if="isBehind(row)" class="cell-sub warn-text">
              落后 v{{ overview?.latestVersion }}
            </div>
            <div v-else class="cell-sub">已最新</div>
          </template>
        </el-table-column>
        <el-table-column label="限流" width="150">
          <template #default="{ row }">
            <div class="cell-main">{{ row.qpsLimit > 0 ? row.qpsLimit + ' QPS' : '不限 QPS' }}</div>
            <div class="cell-sub">日 {{ row.dailyRequestLimit > 0 ? fmt(row.dailyRequestLimit) : '不限' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="今日用量" width="150">
          <template #default="{ row }">
            <div class="cell-main">{{ fmt(row.todayRequests) }} 次</div>
            <div class="cell-sub" :class="{ 'warn-text': row.todayRejected > 0 }">
              被拒 {{ row.todayRejected }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="安全事件" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.todaySecurityEvents > 0" type="danger" effect="light">
              今日 {{ row.todaySecurityEvents }}
            </el-tag>
            <span v-else class="cell-sub">无</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="light">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.tenantCode)">详情</el-button>
            <el-button link type="primary" @click="openQuota(row)">调配额</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleFreeze(row)"
            >
              {{ row.status === 1 ? '冻结' : '解冻' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的租户" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="Number(total) || 0"
        :page-sizes="[10, 20, 50]"
        @current-change="loadList"
        @size-change="reloadFromFirstPage"
      />
    </el-card>

    <!-- 详情抽屉：隔离与限流 / 升级历史 / 安全事件 -->
    <el-drawer v-model="detailOpen" :title="detail?.companyName || detail?.tenantCode || '租户详情'" size="760px">
      <div v-if="detail" class="detail">
        <div class="detail-head">
          <div>
            <div class="detail-title">{{ detail.companyName || '（未填企业名）' }}</div>
            <div class="cell-sub mono">{{ detail.tenantCode }}</div>
          </div>
          <el-tag :type="detail.status === 1 ? 'success' : 'danger'" effect="light">
            {{ detail.statusText }}
          </el-tag>
        </div>

        <div class="metric-row">
          <div class="metric"><div class="m-val">{{ detail.isolationModeText }}</div><div class="m-label">隔离模式</div></div>
          <div class="metric"><div class="m-val">v{{ detail.currentVersion }}</div><div class="m-label">库版本</div></div>
          <div class="metric"><div class="m-val">{{ detail.qpsLimit }}</div><div class="m-label">QPS 上限</div></div>
          <div class="metric"><div class="m-val">{{ fmt(detail.todayRequests) }}</div><div class="m-label">今日请求</div></div>
          <div class="metric"><div class="m-val warn-text">{{ detail.todayRejected }}</div><div class="m-label">今日被限流</div></div>
        </div>

        <el-alert
          v-if="!detail.dataPlaneReachable"
          class="mt-12"
          type="warning"
          show-icon
          :closable="false"
          title="customer-service 暂时连不上：括号里的版本是控制面兜底值，升级操作会失败"
        />

        <el-divider content-position="left">隔离与限流</el-divider>
        <el-descriptions :column="2" border size="default">
          <el-descriptions-item label="隔离模式">
            {{ detail.isolationModeText }}
            <el-button
              v-if="detail.isolationMode === 1"
              link
              type="primary"
              @click="openModeSwitch"
            >升级到独立 Schema</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="目标 schema">{{ detail.schemaName || 'public' }}</el-descriptions-item>
          <el-descriptions-item label="QPS 上限">{{ detail.qpsLimit }}</el-descriptions-item>
          <el-descriptions-item label="每天请求上限">{{ fmt(detail.dailyRequestLimit) }}</el-descriptions-item>
          <el-descriptions-item label="限流开关">{{ detail.quotaEnabled ? '已启用' : '已关闭' }}</el-descriptions-item>
          <el-descriptions-item label="库版本">
            v{{ detail.currentVersion }}
            <span v-if="detail.latestVersion"> / 最新 v{{ detail.latestVersion }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最近升级">{{ dateTimeText(detail.upgradedAt) }}</el-descriptions-item>
          <el-descriptions-item label="待执行脚本">
            {{ detail.pending?.length ? detail.pending.length + ' 个' : '无' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions">
          <el-button type="primary" :loading="upgrading" @click="doUpgrade">
            <el-icon class="btn-icon"><Upload /></el-icon>
            {{ detail.pending?.length ? `升级到 v${detail.latestVersion}` : '校验并升级到最新' }}
          </el-button>
          <el-button @click="openQuota(detail)">调整限流配额</el-button>
        </div>
        <div v-if="detail.pending?.length" class="pending-list">
          <div class="cell-sub">本次会执行：</div>
          <div v-for="step in detail.pending" :key="step.version" class="pending-item">
            <span class="mono">v{{ step.version }}</span>
            <span>{{ step.title }}</span>
            <span class="cell-sub mono">{{ step.script }}</span>
          </div>
        </div>

        <el-divider content-position="left">升级历史（控制面下发 + 数据面执行，两边都留痕）</el-divider>
        <el-table :data="detail.upgrades" size="small">
          <el-table-column label="版本" width="110">
            <template #default="{ row }">v{{ row.fromVersion }} → v{{ row.toVersion }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'warning'" effect="light">
                {{ row.status === 2 ? '成功' : row.status === 3 ? '失败' : '进行中' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="脚本" width="80">
            <template #default="{ row }">{{ row.scriptCount }} 个</template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.durationMs }} ms</template>
          </el-table-column>
          <el-table-column label="操作人" width="110" prop="operatorName" />
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">{{ dateTimeText(row.startedAt) }}</template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">安全事件（篡改租户头 / 跨租户访问 / 被限流）</el-divider>
        <el-table :data="detail.securityEvents" size="small">
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag :type="row.eventType === 3 ? 'warning' : 'danger'" effect="light">
                {{ eventTypeText(row.eventType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="详情" min-width="240" prop="detail" />
          <el-table-column label="来源" width="150">
            <template #default="{ row }">
              <div class="mono cell-sub">{{ row.requestUri }}</div>
              <div class="cell-sub">{{ row.clientIp }}</div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ dateTimeText(row.eventTime) }}</template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有安全事件（这是好事）" :image-size="60" />
          </template>
        </el-table>
      </div>
    </el-drawer>

    <!-- 限流配额弹窗 -->
    <el-dialog v-model="quotaOpen" title="调整租户限流配额" width="460px">
      <el-form label-width="130px">
        <el-form-item label="QPS 上限">
          <el-input-number v-model="quotaForm.qpsLimit" :min="0" :max="100000" />
          <span class="form-tip">0 = 不限</span>
        </el-form-item>
        <el-form-item label="每天请求上限">
          <el-input-number v-model="quotaForm.dailyRequestLimit" :min="0" :max="100000000" :step="10000" />
        </el-form-item>
        <el-form-item label="每天会话上限">
          <el-input-number v-model="quotaForm.dailySessionLimit" :min="0" :max="10000000" :step="1000" />
        </el-form-item>
        <el-form-item label="每天 AI 调用上限">
          <el-input-number v-model="quotaForm.aiDailyLimit" :min="0" :max="10000000" :step="1000" />
        </el-form-item>
        <el-form-item label="启用限流">
          <el-switch v-model="quotaForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="quotaForm.remark" placeholder="例如：企业版套餐，2026-09 上调" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 隔离模式切换弹窗 -->
    <el-dialog v-model="modeOpen" title="升级到独立 Schema" width="520px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="接口只做登记与校验，不会搬数据"
        description="把共享库的数据迁到独立 schema 是运维动作（导出 → 建 schema → 导入 → 校验）。登记完成后，后续的库升级就会被应用到该 schema。"
      />
      <el-form label-width="110px" class="mt-12">
        <el-form-item label="schema 名">
          <el-input v-model="modeForm.schemaName" placeholder="例如 cc_t202609050000002" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modeOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMode">确认登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import {
  BellFilled,
  CircleCheckFilled,
  Lock,
  OfficeBuilding,
  Refresh,
  Search,
  Upload,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  fetchTenantIsolationDetail,
  fetchTenantIsolationOverview,
  fetchTenantIsolationPage,
  freezeTenant,
  switchTenantIsolationMode,
  updateTenantQuota,
  upgradeTenantSchema,
  type IsolationMode,
  type TenantIsolationDetail,
  type TenantIsolationOverview,
  type TenantIsolationQuery,
  type TenantIsolationRow,
} from '../../../api/platform/tenant'

const loading = ref(false)
const saving = ref(false)
const upgrading = ref(false)
const overview = ref<TenantIsolationOverview | null>(null)
const list = ref<TenantIsolationRow[]>([])
const total = ref(0)
const route = useRoute()

const query = reactive<TenantIsolationQuery>({
  status: undefined,
  isolationMode: undefined,
  keyword: '',
  page: 1,
  pageSize: 10,
})

const detailOpen = ref(false)
const detail = ref<TenantIsolationDetail | null>(null)

const quotaOpen = ref(false)
const quotaTarget = ref<string>('')
const quotaForm = reactive({
  qpsLimit: 20,
  dailyRequestLimit: 200000,
  dailySessionLimit: 20000,
  aiDailyLimit: 5000,
  enabled: true,
  remark: '',
})

const modeOpen = ref(false)
const modeTarget = ref<string>('')
const modeForm = reactive({ schemaName: '' })

/** 序号：翻页后从 1 开始（不是全局序号） */
function indexOf(index: number) {
  return (Number(query.page) - 1) * Number(query.pageSize) + index + 1
}

function fmt(value?: number | null) {
  return value == null ? '0' : Number(value).toLocaleString('zh-CN')
}

function dateText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 10) : '—'
}

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function modeText(row: TenantIsolationRow) {
  return row.isolationMode === 1 ? '共享库模式' : `库：${row.schemaName || 'public'}`
}

function isBehind(row: TenantIsolationRow) {
  return overview.value ? row.dbVersion < overview.value.latestVersion : false
}

function eventTypeText(type: number) {
  return (
    { 1: '篡改租户头', 2: '跨租户访问', 3: '被限流', 4: 'RLS 拦截' }[type] ?? `类型 ${type}`
  )
}

async function loadOverview() {
  try {
    overview.value = await fetchTenantIsolationOverview()
  } catch {
    // 概览失败不影响列表
  }
}

async function loadList() {
  loading.value = true
  try {
    const page = await fetchTenantIsolationPage(query)
    list.value = page.list
    // 基础模块把 Long 序列化成字符串，分页只收 Number
    total.value = Number(page.total) || 0
  } catch (e) {
    errorMessage(e, '租户列表加载失败')
  } finally {
    loading.value = false
  }
}

function reloadFromFirstPage() {
  query.page = 1
  void loadList()
}

function refreshAll() {
  void loadOverview()
  void loadList()
}

async function openDetail(tenantCode: string) {
  detailOpen.value = true
  detail.value = null
  try {
    detail.value = await fetchTenantIsolationDetail(tenantCode)
  } catch (e) {
    errorMessage(e, '租户详情加载失败')
  }
}

function openQuota(row: TenantIsolationRow | TenantIsolationDetail) {
  quotaTarget.value = row.tenantCode
  const source = 'dailySessionLimit' in row ? row : null
  quotaForm.qpsLimit = row.qpsLimit
  quotaForm.dailyRequestLimit = row.dailyRequestLimit
  quotaForm.dailySessionLimit = source ? source.dailySessionLimit : 20000
  quotaForm.aiDailyLimit = source ? source.aiDailyLimit : 5000
  quotaForm.enabled = row.quotaEnabled
  quotaForm.remark = source?.quotaRemark ?? ''
  quotaOpen.value = true
}

async function saveQuota() {
  saving.value = true
  try {
    await updateTenantQuota(quotaTarget.value, { ...quotaForm })
    ElMessage.success('配额已更新，网关最迟 30 秒生效')
    quotaOpen.value = false
    refreshAll()
    if (detailOpen.value) {
      detail.value = await fetchTenantIsolationDetail(quotaTarget.value)
    }
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

function openModeSwitch() {
  modeTarget.value = detail.value?.tenantCode ?? ''
  modeForm.schemaName = `cc_${(detail.value?.tenantCode ?? '').toLowerCase()}`
  modeOpen.value = true
}

async function saveMode() {
  saving.value = true
  try {
    detail.value = await switchTenantIsolationMode(modeTarget.value, 2 as IsolationMode, modeForm.schemaName)
    ElMessage.success('已登记为独立 Schema 模式，记得让运维把数据迁过去')
    modeOpen.value = false
    refreshAll()
  } catch (e) {
    errorMessage(e, '切换失败')
  } finally {
    saving.value = false
  }
}

async function doUpgrade() {
  if (!detail.value) return
  upgrading.value = true
  try {
    detail.value = await upgradeTenantSchema(detail.value.tenantCode)
    ElMessage.success('已升级到最新版本（升级历史里能看到每一步）')
    refreshAll()
  } catch (e) {
    errorMessage(e, '升级失败')
  } finally {
    upgrading.value = false
  }
}

async function toggleFreeze(row: TenantIsolationRow) {
  const freeze = row.status === 1
  try {
    await ElMessageBox.confirm(
      freeze
        ? `冻结后「${row.companyName || row.tenantCode}」的所有请求都会被网关拒绝（403），确定吗？`
        : `解冻后「${row.companyName || row.tenantCode}」恢复访问，确定吗？`,
      freeze ? '冻结租户' : '解冻租户',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await freezeTenant(row.tenantCode, freeze)
    ElMessage.success(freeze ? '已冻结' : '已解冻')
    refreshAll()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

// 支持从「企业审核」页带 ?keyword=<租户号> 跳过来，直接定位到这条租户
onMounted(() => {
  const fromQuery = route.query.keyword
  if (typeof fromQuery === 'string' && fromQuery.trim()) {
    query.keyword = fromQuery.trim()
  }
  refreshAll()
})
</script>

<style scoped>
.tenants-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.mt-12 { margin-top: 12px; }
.mt-16 { margin-top: 16px; }

.stat-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-card.warn { border-color: #fcd34d; background: #fffbeb; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.f-select { width: 150px; }
.f-input { width: 260px; }
.f-tip { font-size: 12px; color: #94a3b8; }
.f-tip code { padding: 1px 5px; border-radius: 4px; background: #f1f5f9; color: #475569; }
.pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.warn-text { color: #d97706; }
.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; }

.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-title { font-size: 17px; font-weight: 700; color: #0f172a; }
.metric-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-top: 14px; }
.metric { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 10px 12px; }
.m-val { font-size: 16px; font-weight: 700; color: #0f172a; }
.m-label { font-size: 11.5px; color: #94a3b8; margin-top: 2px; }
.detail-actions { display: flex; gap: 10px; margin-top: 12px; }
.pending-list { margin-top: 10px; }
.pending-item { display: grid; grid-template-columns: 60px 1fr 200px; gap: 8px; font-size: 12.5px; color: #475569; padding: 4px 0; }
.form-tip { margin-left: 8px; font-size: 12px; color: #94a3b8; }
</style>
