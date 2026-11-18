<template>
  <div class="sec-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">安全中心</h2>
        <p class="page-sub">安全策略 · API 密钥 · 安全事件 · 数据导出审批</p>
      </div>
      <el-button :loading="loading" @click="loadAll">
        <el-icon class="btn-icon"><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <el-tabs v-model="tab" class="sec-tabs">
      <!-- 安全策略 -->
      <el-tab-pane label="安全策略" name="policy">
        <el-card shadow="never">
          <div class="card-title">平台安全策略</div>
          <div class="card-sub">
            每一项都对应一个真实执行点：登录失败锁定与双因素在 user-service 登录链路、IP 白名单在登录接口、
            导出审批在导出接口、水印与空闲登出在前端
          </div>
          <el-form label-width="200px" class="policy-form">
            <el-form-item label="登录失败锁定阈值（次）">
              <el-input-number v-model="policyForm.loginFailThreshold" :min="0" :max="20" />
              <span class="hint">0 = 不锁定；user-service 登录链路按它计数</span>
            </el-form-item>
            <el-form-item label="锁定时长（分钟）">
              <el-input-number v-model="policyForm.loginLockMinutes" :min="0" :max="1440" />
              <span class="hint">到点自动解锁</span>
            </el-form-item>
            <el-form-item label="管理端登录 IP 白名单">
              <el-switch v-model="policyForm.ipWhitelistEnabled" />
              <el-input
                v-model="policyForm.ipWhitelist"
                class="whitelist"
                placeholder="逗号分隔，例如 127.0.0.1,10.0.0.8"
              />
              <span class="hint">只在登录接口生效；开启前请把当前 IP 加进去，否则会被挡在门外</span>
            </el-form-item>
            <el-form-item label="敏感数据导出需审批">
              <el-switch v-model="policyForm.exportNeedApproval" />
              <span class="hint">客户 360 等含个人信息的导出；关掉也仍会写审计</span>
            </el-form-item>
            <el-form-item label="管理端水印">
              <el-switch v-model="policyForm.watermarkEnabled" />
              <span class="hint">页面右上角显示"当前账号 + 时间"水印</span>
            </el-form-item>
            <el-form-item label="空闲自动登出（分钟）">
              <el-input-number v-model="policyForm.sessionTimeoutMinutes" :min="5" :max="1440" />
            </el-form-item>
            <el-form-item label="强制双因素认证">
              <el-switch v-model="policyForm.twoFactorRequired" />
              <el-select
                v-model="policyForm.twoFactorScope"
                class="scope-select"
                :disabled="!policyForm.twoFactorRequired"
              >
                <el-option :value="1" label="仅平台账号与企业管理员" />
                <el-option :value="2" label="全部账号（含坐席）" />
              </el-select>
              <span class="hint">
                开启后登录分两步：密码通过后往账号绑定邮箱发 6 位验证码，验证通过才发 token
                （执行点：user-service 登录链路）。
              </span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="savePolicy">保存安全策略</el-button>
              <span class="hint" v-if="policy?.updateTime">
                上次保存 {{ policy.updateTime }}<template v-if="policy.editor"> · {{ policy.editor }}</template>
              </span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- API 密钥 -->
      <el-tab-pane label="API 密钥" name="keys">
        <div class="kpi-row">
          <div class="kpi"><div class="kpi-value">{{ keys?.summary.total ?? 0 }}</div><div class="kpi-label">密钥总数</div></div>
          <div class="kpi"><div class="kpi-value">{{ keys?.summary.enabled ?? 0 }}</div><div class="kpi-label">启用中</div></div>
          <div class="kpi"><div class="kpi-value">{{ keys?.summary.calls ?? 0 }}</div><div class="kpi-label">累计调用</div></div>
        </div>
        <el-card shadow="never">
          <div class="card-head">
            <div>
              <div class="card-title">开放 API 密钥</div>
              <div class="card-sub">库里只存哈希，明文只在生成时显示一次；停用立即失效</div>
            </div>
            <el-button type="primary" @click="keyOpen = true">
              <el-icon class="btn-icon"><Plus /></el-icon>
              生成密钥
            </el-button>
          </div>
          <el-table :data="keys?.items || []" class="mt-12">
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column label="用途" min-width="160" prop="keyName" show-overflow-tooltip />
            <el-table-column label="企业" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-main">{{ companyOf(row.tenantCode) }}</div>
                <div class="cell-sub mono">{{ row.tenantCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="密钥" width="150">
              <template #default="{ row }"><span class="mono">{{ row.keyPrefix }}</span></template>
            </el-table-column>
            <el-table-column label="权限" width="90" prop="permissions" />
            <el-table-column label="调用次数" width="100">
              <template #default="{ row }">{{ row.callCount }}</template>
            </el-table-column>
            <el-table-column label="最后使用" width="170">
              <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'" effect="light">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link :type="row.status === 1 ? 'danger' : 'primary'" @click="toggleKey(row)">
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="还没有密钥" :image-size="80" /></template>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 安全事件 -->
      <el-tab-pane label="安全事件" name="events">
        <div class="kpi-row">
          <div class="kpi"><div class="kpi-value">{{ events?.summary.total ?? 0 }}</div><div class="kpi-label">累计事件</div></div>
          <div class="kpi"><div class="kpi-value danger">{{ events?.summary.pending ?? 0 }}</div><div class="kpi-label">待处理</div></div>
          <div class="kpi"><div class="kpi-value">{{ events?.summary.today ?? 0 }}</div><div class="kpi-label">今日</div></div>
          <div class="kpi"><div class="kpi-value">{{ events?.summary.last7 ?? 0 }}</div><div class="kpi-label">近 7 天</div></div>
        </div>
        <el-card shadow="never">
          <div class="card-head">
            <div>
              <div class="card-title">越权与限流事件</div>
              <div class="card-sub">数据来自探测侧（网关与数据面）：租户头与令牌不一致、跨租户访问、被限流、RLS 拦截</div>
            </div>
            <div class="filters">
              <el-select v-model="eventQuery.eventType" class="w-160" placeholder="全部类型" clearable @change="loadEvents">
                <el-option v-for="item in events?.typeDistribution || []" :key="item.eventType"
                           :value="item.eventType" :label="`${item.eventTypeText}（${item.events}）`" />
              </el-select>
              <el-select v-model="eventQuery.handleStatus" class="w-140" placeholder="全部状态" clearable @change="loadEvents">
                <el-option :value="1" label="待处理" />
                <el-option :value="2" label="已确认" />
                <el-option :value="3" label="已忽略" />
              </el-select>
            </div>
          </div>
          <el-table :data="events?.list || []" class="mt-12">
            <el-table-column type="index" label="序号" width="70" :index="eventIndexOfRow" />
            <el-table-column label="发生时间" width="170">
              <template #default="{ row }">{{ row.eventTime || '—' }}</template>
            </el-table-column>
            <el-table-column label="类型" width="170">
              <template #default="{ row }">
                <el-tag size="small" :type="eventTag(row.eventType)" effect="light">{{ row.eventTypeText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="租户" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-main">{{ row.companyName || row.tenantCode }}</div>
                <div class="cell-sub mono">{{ row.tenantCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="请求" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="mono">{{ row.requestMethod }} {{ row.requestUri }}</div>
                <div class="cell-sub">{{ row.clientIp || '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="详情" min-width="220" show-overflow-tooltip prop="detail" />
            <el-table-column label="处置" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="row.handleStatus === 1 ? 'warning' : 'success'" effect="light">
                  {{ row.handleStatusText }}
                </el-tag>
                <div v-if="row.handlerName" class="cell-sub">{{ row.handlerName }} · {{ row.handleTime }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <template v-if="row.handleStatus === 1">
                  <el-button link type="success" @click="handleEvent(row, 2)">确认</el-button>
                  <el-button link @click="handleEvent(row, 3)">忽略</el-button>
                </template>
                <span v-else class="muted">已处置</span>
              </template>
            </el-table-column>
            <template #empty><el-empty description="没有安全事件" :image-size="80" /></template>
          </el-table>
          <el-pagination
            v-model:current-page="eventQuery.page"
            class="pager"
            layout="total, prev, pager, next"
            :total="Number(events?.total) || 0"
            :page-size="eventQuery.pageSize"
            @current-change="loadEvents"
          />
        </el-card>
      </el-tab-pane>

      <!-- 导出审批 -->
      <el-tab-pane label="导出审批" name="exports">
        <div class="kpi-row">
          <div class="kpi"><div class="kpi-value danger">{{ exportsData?.summary.pending ?? 0 }}</div><div class="kpi-label">待审批</div></div>
          <div class="kpi"><div class="kpi-value">{{ exportsData?.summary.approved ?? 0 }}</div><div class="kpi-label">已通过</div></div>
          <div class="kpi"><div class="kpi-value">{{ exportsData?.summary.rejected ?? 0 }}</div><div class="kpi-label">已驳回</div></div>
        </div>
        <el-card shadow="never">
          <div class="card-head">
            <div>
              <div class="card-title">数据导出申请</div>
              <div class="card-sub">申请 → 审批 → 实际导出闭环在同一行：能回答"批了多少、实际导了多少"</div>
            </div>
            <el-select v-model="exportQuery.status" class="w-140" placeholder="全部状态" clearable @change="loadExports">
              <el-option :value="1" label="待审批" />
              <el-option :value="2" label="已通过" />
              <el-option :value="3" label="已驳回" />
            </el-select>
          </div>
          <el-table :data="exportsData?.list || []" class="mt-12">
            <el-table-column type="index" label="序号" width="70" :index="exportIndexOfRow" />
            <el-table-column label="申请时间" width="170">
              <template #default="{ row }">{{ row.requestTime || '—' }}</template>
            </el-table-column>
            <el-table-column label="企业" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-main">{{ companyOf(row.tenantCode) }}</div>
                <div class="cell-sub mono">{{ row.tenantCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.exportTypeText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="申请人" width="120" prop="requesterName" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'warning' : row.status === 2 ? 'success' : 'info'" effect="light">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="实际导出" width="180">
              <template #default="{ row }">
                <template v-if="row.exportTime">
                  {{ row.exportRows }} 行 · {{ row.exportTime }}
                </template>
                <span v-else class="muted">{{ row.status === 2 ? '已通过、还没导' : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="审批 / 驳回原因" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.rejectReason" class="danger">{{ row.rejectReason }}</span>
                <span v-else>{{ row.approverName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 1">
                  <el-button link type="success" @click="doApprove(row)">通过</el-button>
                  <el-button link type="danger" @click="doReject(row)">驳回</el-button>
                </template>
                <span v-else class="muted">已处理</span>
              </template>
            </el-table-column>
            <template #empty><el-empty description="没有导出申请" :image-size="80" /></template>
          </el-table>
          <el-pagination
            v-model:current-page="exportQuery.page"
            class="pager"
            layout="total, prev, pager, next"
            :total="Number(exportsData?.total) || 0"
            :page-size="exportQuery.pageSize"
            @current-change="loadExports"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 生成密钥 -->
    <el-dialog v-model="keyOpen" title="生成 API 密钥" width="520px">
      <el-form label-width="110px">
        <el-form-item label="租户" required>
          <el-input v-model="keyForm.tenantCode" placeholder="例如 T202609050000002" />
        </el-form-item>
        <el-form-item label="用途名称" required>
          <el-input v-model="keyForm.keyName" placeholder="例如 企业自有系统对接（只读）" />
        </el-form-item>
        <el-form-item label="权限">
          <el-input v-model="keyForm.permissions" placeholder="默认 read" />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="keyForm.expireTime" type="datetime" class="w-full"
                          value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空表示不过期" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateKey">生成</el-button>
      </template>
    </el-dialog>

    <!-- 明文只显示这一次 -->
    <el-dialog v-model="secretOpen" title="密钥已生成（只显示这一次）" width="560px">
      <p class="dialog-tip">请立刻复制保存：库里只存哈希，关掉这个窗口就再也看不到明文了。</p>
      <el-input v-model="secretValue" readonly class="mono" />
      <template #footer>
        <el-button @click="copySecret">复制</el-button>
        <el-button type="primary" @click="secretOpen = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { fetchTenantNames } from '../../../api/platform/tenant'
import {
  approveExport,
  createApiKey,
  fetchApiKeys,
  fetchExports,
  fetchSecurityEvents,
  fetchSecurityPolicy,
  handleSecurityEvent,
  rejectExport,
  saveSecurityPolicy,
  updateApiKeyStatus,
  type ApiKeyPage,
  type ApiKeyRow,
  type ExportPage,
  type ExportRow,
  type SecurityEventPage,
  type SecurityEventRow,
  type SecurityPolicyView,
} from '../../../api/platform/security'

const tab = ref('policy')
const loading = ref(false)
const saving = ref(false)
const policy = ref<SecurityPolicyView | null>(null)
/** 租户编码 → 企业名：导出申请里只存了 code，名称用平台租户目录映射（收款流水页同一套做法） */
const tenantNames = ref<Record<string, string>>({})
const keys = ref<ApiKeyPage | null>(null)
const events = ref<SecurityEventPage | null>(null)
const exportsData = ref<ExportPage | null>(null)

const policyForm = reactive({
  loginFailThreshold: 5,
  loginLockMinutes: 15,
  ipWhitelistEnabled: false,
  ipWhitelist: '127.0.0.1',
  exportNeedApproval: true,
  watermarkEnabled: true,
  sessionTimeoutMinutes: 120,
  twoFactorRequired: false,
  twoFactorScope: 1,
})

const eventQuery = reactive<{ eventType?: number; handleStatus?: number; page: number; pageSize: number }>({
  eventType: undefined,
  handleStatus: undefined,
  page: 1,
  pageSize: 10,
})

const exportQuery = reactive<{ status?: number; page: number; pageSize: number }>({
  status: 1,
  page: 1,
  pageSize: 10,
})

const keyOpen = ref(false)
const keyForm = reactive({ tenantCode: '', keyName: '', permissions: 'read', expireTime: '' })
const secretOpen = ref(false)
const secretValue = ref('')

function eventTag(type: number): 'danger' | 'warning' | 'info' | 'primary' {
  if (type === 2 || type === 4) return 'danger'
  if (type === 1) return 'warning'
  return 'info'
}

function eventIndexOfRow(index: number): number {
  return (eventQuery.page - 1) * eventQuery.pageSize + index + 1
}

function exportIndexOfRow(index: number): number {
  return (exportQuery.page - 1) * exportQuery.pageSize + index + 1
}

async function loadPolicy(): Promise<void> {
  const data = await fetchSecurityPolicy()
  policy.value = data
  Object.assign(policyForm, {
    loginFailThreshold: Number(data.loginFailThreshold ?? 5),
    loginLockMinutes: Number(data.loginLockMinutes ?? 15),
    ipWhitelistEnabled: Boolean(data.ipWhitelistEnabled),
    ipWhitelist: data.ipWhitelist || '127.0.0.1',
    exportNeedApproval: data.exportNeedApproval !== false,
    watermarkEnabled: data.watermarkEnabled !== false,
    sessionTimeoutMinutes: Number(data.sessionTimeoutMinutes ?? 120),
    twoFactorRequired: Boolean(data.twoFactorRequired),
    twoFactorScope: Number(data.twoFactorScope ?? 1) === 2 ? 2 : 1,
  })
}

async function loadKeys(): Promise<void> {
  keys.value = await fetchApiKeys()
}

async function loadEvents(): Promise<void> {
  events.value = await fetchSecurityEvents(eventQuery)
}

async function loadExports(): Promise<void> {
  exportsData.value = await fetchExports(exportQuery)
}

function companyOf(tenantCode?: string | null): string {
  if (!tenantCode) {
    return '—'
  }
  return tenantNames.value[tenantCode] || '（未填企业名）'
}

async function loadTenantNames(): Promise<void> {
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
    // 目录拿不到就只显示租户编号，不影响审批动作
    tenantNames.value = {}
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([
      loadPolicy().catch(() => undefined),
      loadKeys().catch(() => undefined),
      loadEvents().catch(() => undefined),
      loadExports().catch(() => undefined),
      loadTenantNames().catch(() => undefined),
    ])
  } finally {
    loading.value = false
  }
}

async function savePolicy(): Promise<void> {
  // 开双因素前先把话说清楚：发不出验证码就登不进来（fail-closed），
  // 所以前提是邮件通道已配置——这句话必须出现在点保存的那一刻。
  const turningOn = policyForm.twoFactorRequired && !policy.value?.twoFactorRequired
  if (turningOn) {
    try {
      await ElMessageBox.confirm(
        '开启后所有命中范围的账号登录都要先收邮箱验证码。'
        + '请先确认邮件通道已配置（MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD）——'
        + '发不出验证码时登录会被拒绝，需要到 ops_db.security_policy 关掉这一项才能恢复。确定开启吗？',
        '开启强制双因素认证',
        { type: 'warning', confirmButtonText: '确定开启', cancelButtonText: '再想想' },
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    await saveSecurityPolicy({ ...policyForm })
    ElMessage.success('安全策略已保存（策略值会在 30 秒内被各执行点读到）')
    await loadPolicy()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function doCreateKey(): Promise<void> {
  if (!keyForm.tenantCode.trim() || !keyForm.keyName.trim()) {
    ElMessage.warning('请填写租户与用途名称')
    return
  }
  saving.value = true
  try {
    const result = await createApiKey({
      tenantCode: keyForm.tenantCode.trim(),
      keyName: keyForm.keyName.trim(),
      permissions: keyForm.permissions || undefined,
      expireTime: keyForm.expireTime || undefined,
    })
    keyOpen.value = false
    secretValue.value = result.apiKey
    secretOpen.value = true
    await loadKeys()
  } catch (e) {
    errorMessage(e, '生成失败')
  } finally {
    saving.value = false
  }
}

async function copySecret(): Promise<void> {
  try {
    await navigator.clipboard.writeText(secretValue.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

async function toggleKey(row: ApiKeyRow): Promise<void> {
  try {
    await updateApiKeyStatus(row.id, row.status === 1 ? 2 : 1)
    ElMessage.success(row.status === 1 ? '已停用' : '已启用')
    await loadKeys()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function handleEvent(row: SecurityEventRow, status: number): Promise<void> {
  let remark = ''
  if (status === 3) {
    try {
      const input = await ElMessageBox.prompt('说明一下为什么忽略（会记进审计）', '忽略安全事件', {
        confirmButtonText: '确定忽略',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：客户自己在改请求头，已电话确认',
      })
      remark = input.value || ''
    } catch {
      return
    }
  }
  try {
    await handleSecurityEvent(row.id, status, remark || undefined)
    ElMessage.success(status === 2 ? '已确认' : '已忽略')
    await loadEvents()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function doApprove(row: ExportRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `通过后，${row.tenantCode} 可以导出一次「${row.exportTypeText}」（一次审批只能导一次）。确定吗？`,
      '通过导出申请',
      { type: 'warning', confirmButtonText: '确定通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await approveExport(row.id)
    ElMessage.success('已通过，并已通知申请企业')
    await loadExports()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function doReject(row: ExportRow): Promise<void> {
  let reason = ''
  try {
    const input = await ElMessageBox.prompt('填写驳回原因（会通知申请企业）', '驳回导出申请', {
      confirmButtonText: '确定驳回',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：导出范围过大，请缩小到具体客户群',
    })
    reason = input.value || ''
  } catch {
    return
  }
  try {
    await rejectExport(row.id, reason)
    ElMessage.success('已驳回，并已通知申请企业')
    await loadExports()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.sec-page { width: 100%; min-width: 0; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.btn-icon { margin-right: 4px; }
.sec-tabs { width: 100%; }
.card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.card-sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.policy-form { max-width: 860px; margin-top: 18px; }
.hint { margin-left: 10px; font-size: 12px; color: #94a3b8; }
.whitelist { width: 320px; margin-left: 12px; }
.scope-select { width: 260px; margin-left: 12px; }
.kpi-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.kpi { padding: 14px 16px; background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; }
.kpi-value { font-size: 20px; font-weight: 800; color: #0f172a; }
.kpi-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.w-140 { width: 140px; }
.w-160 { width: 160px; }
.w-full { width: 100%; }
.mt-12 { margin-top: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.muted { color: #94a3b8; font-size: 12.5px; }
.danger { color: #dc2626; }
.dialog-tip { margin: 0 0 10px; font-size: 13px; color: #64748b; line-height: 1.7; }
</style>
