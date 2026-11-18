<template>
  <div class="settings-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">平台设置</h2>
        <p class="page-sub">注册审核策略 · 套餐价格 · 支付配置 · 平台公告</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="settings-tabs">
      <!-- ① 注册与审核 -->
      <el-tab-pane label="注册与审核" name="register">
        <el-card shadow="never" v-loading="loading.register">
          <div class="block-head">
            <div>
              <div class="block-title">注册与审核</div>
              <div class="block-sub">
                审核开关影响「企业审核」队列；白名单域名内注册将自动通过并初始化租户。
              </div>
            </div>
            <el-button type="primary" :loading="saving.register" @click="saveRegister">保存配置</el-button>
          </div>
          <el-form label-width="240px" class="settings-form">
            <el-form-item label="开放企业注册">
              <el-switch v-model="registerForm.allowRegister" />
              <span class="hint">关掉之后注册页会提示"暂未开放注册"</span>
            </el-form-item>
            <el-form-item label="新注册企业需运营审核（防滥用）">
              <el-switch v-model="registerForm.needReview" />
            </el-form-item>
            <el-form-item label="企业邮箱域名白名单自动放行">
              <el-select
                v-model="registerForm.autoApproveDomains"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="输入域名后回车，例如 example.com"
                class="w-full"
              />
            </el-form-item>
            <el-form-item label="注册需手机 / 邮箱双重验证">
              <el-switch v-model="registerForm.requireDoubleVerify" />
              <span class="hint">
                开启后注册页多一栏「邮箱验证码」：先点获取验证码（要过图形验证码），
                填对才能提交注册（执行点：user-service 注册链路）
              </span>
            </el-form-item>
            <el-form-item label="新租户试用时长（天）">
              <el-input-number v-model="registerForm.trialDays" :min="0" :max="365" />
              <span class="hint">审核通过后自动开免费版，试用期就是这个天数</span>
            </el-form-item>
          </el-form>
          <el-alert
            type="info"
            show-icon
            :closable="false"
            title="策略说明"
            description="审核开关影响「企业审核」队列；白名单域名内注册将自动通过并初始化租户。"
          />
        </el-card>
      </el-tab-pane>

      <!-- ② 套餐价格 -->
      <el-tab-pane label="套餐价格" name="plans">
        <el-card shadow="never" v-loading="loading.plans">
          <div class="block-head">
            <div>
              <div class="block-title">套餐价格</div>
              <div class="block-sub">保存后「版本与升级」页价格同步生效（同一份 plan 表）。</div>
            </div>
            <el-button type="primary" :loading="saving.plans" @click="savePlans">保存价格</el-button>
          </div>
          <el-table :data="plans" class="mt-12">
            <el-table-column label="套餐" width="140">
              <template #default="{ row }">
                <div class="cell-main">{{ row.planName }}</div>
                <div class="cell-sub mono">{{ row.planCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="年付价格（元）" width="180">
              <template #default="{ row }">
                <el-input-number v-model="row.priceYearly" :min="0" :precision="2" :step="100" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="月付价格（元）" width="180">
              <template #default="{ row }">
                <el-input-number v-model="row.priceMonthly" :min="0" :precision="2" :step="100" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="会话配额" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.quotaRobotSessions" :min="0" :step="1000" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="坐席配额" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.quotaSeats" :min="0" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="AI 调用配额" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.quotaAiCalls" :min="0" :step="100" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="超量单价（每千次）" width="170">
              <template #default="{ row }">
                <el-input-number v-model="row.overagePricePerK" :min="0" :precision="2" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="上架" width="90">
              <template #default="{ row }">
                <el-switch v-model="row.isEnabled" />
              </template>
            </el-table-column>
          </el-table>
          <div class="hint mt-12">超量单价填 0 表示该套餐不允许超量（免费版就是这么设计的漏斗）。</div>
        </el-card>
      </el-tab-pane>

      <!-- ③ 支付配置 -->
      <el-tab-pane label="支付配置" name="payment">
        <el-card shadow="never" v-loading="loading.payment">
          <div class="block-head">
            <div>
              <div class="block-title">支付配置</div>
              <div class="block-sub">收银台的支付方式与收款账户；对公转账是线下转账，永远可用。</div>
            </div>
            <el-button type="primary" :loading="saving.payment" @click="savePayment">保存配置</el-button>
          </div>
          <el-form label-width="200px" class="settings-form">
            <el-form-item label="启用在线支付（支付宝 / 微信）">
              <el-switch v-model="paymentForm.onlinePayEnabled" />
              <span class="hint">
                关掉之后企业收银台只剩「对公转账」；带支付宝 / 微信支付方式直接调接口也会被拒（服务端校验，不只是前端隐藏）
              </span>
            </el-form-item>
            <el-form-item label="支付宝商户号">
              <el-input v-model="paymentForm.alipayMerchantId" placeholder="支付宝商家 PID" />
            </el-form-item>
            <el-form-item label="微信支付商户号">
              <el-input v-model="paymentForm.wechatMerchantId" placeholder="微信支付商户号 mchid" />
            </el-form-item>
            <el-form-item label="对公账户名">
              <el-input v-model="paymentForm.corpAccountName" placeholder="收款企业全称" />
            </el-form-item>
            <el-form-item label="对公账号">
              <el-input v-model="paymentForm.corpAccountNo" placeholder="银行账号" />
            </el-form-item>
            <el-form-item label="开户行">
              <el-input v-model="paymentForm.corpBankName" placeholder="例如 招商银行杭州分行营业部" />
            </el-form-item>
          </el-form>
          <el-alert
            type="warning"
            show-icon
            :closable="false"
            title="演示环境：未接真实支付网关"
            description="这里的商户号与账户信息会展示在收银台的收款指引里；真正扣款仍需接入支付 SDK 与回调验签。"
          />
        </el-card>
      </el-tab-pane>

      <!-- ④ 平台公告 -->
      <el-tab-pane label="平台公告" name="announcement">
        <el-card shadow="never">
          <div class="block-head">
            <div>
              <div class="block-title">平台公告</div>
              <div class="block-sub">公告将同步推送给全部租户的通知中心。</div>
            </div>
            <el-button type="primary" @click="openAnnouncement(null)">
              <el-icon class="btn-icon"><Plus /></el-icon>
              发布公告
            </el-button>
          </div>

          <div class="filters mt-12">
            <el-select v-model="announcementQuery.status" placeholder="全部状态" clearable class="f-select" @change="loadAnnouncements(1)">
              <el-option label="草稿" :value="1" />
              <el-option label="已发布" :value="2" />
              <el-option label="已下线" :value="3" />
            </el-select>
            <el-select v-model="announcementQuery.announceType" placeholder="全部类型" clearable class="f-select" @change="loadAnnouncements(1)">
              <el-option v-for="item in ANNOUNCEMENT_TYPES" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input
              v-model="announcementQuery.keyword"
              placeholder="标题 / 内容关键字"
              clearable
              class="f-input"
              @keyup.enter="loadAnnouncements(1)"
              @clear="loadAnnouncements(1)"
            />
            <el-button type="primary" @click="loadAnnouncements(1)">查询</el-button>
          </div>

          <el-table :data="announcements" v-loading="loading.announcements" class="mt-12">
            <el-table-column type="index" label="序号" width="70" :index="indexOfAnnouncement" />
            <el-table-column label="标题" min-width="220" prop="title" show-overflow-tooltip />
            <el-table-column label="类型" width="120">
              <template #default="{ row }">{{ announceTypeText(row.announceType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="announcementTag(row.status)" effect="light">
                  {{ announcementStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布时间" width="170">
              <template #default="{ row }">{{ dateTimeText(row.publishTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <el-button link type="info" @click="openPreview(row)">预览</el-button>
                <el-button v-if="row.status !== 2" link type="primary" @click="openAnnouncement(row)">编辑</el-button>
                <el-button
                  v-if="row.status !== 2"
                  link
                  type="success"
                  @click="changeStatus(row, 2)"
                >
                  发布
                </el-button>
                <el-button v-else link type="warning" @click="changeStatus(row, 3)">下线</el-button>
                <el-button v-if="row.status !== 2" link type="danger" @click="doRemoveAnnouncement(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有公告" :image-size="80" />
            </template>
          </el-table>

          <el-pagination
            v-model:current-page="announcementQuery.page"
            class="pager"
            layout="total, prev, pager, next"
            :total="Number(announcementTotal) || 0"
            :page-size="announcementQuery.pageSize"
            @current-change="loadAnnouncements"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建 / 编辑公告 -->
    <el-dialog v-model="announcementOpen" :title="announcementForm.id ? '编辑公告' : '发布公告'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="announcementForm.title" placeholder="例如 9 月 6 日凌晨系统升级通知" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="announcementForm.announceType">
            <el-radio-button v-for="item in ANNOUNCEMENT_TYPES" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="announcementForm.content"
            type="textarea"
            :rows="5"
            placeholder="公告正文，会原样推送到租户的通知中心"
          />
        </el-form-item>
      </el-form>
      <p class="hint">保存后是草稿，点列表里的「发布」才会推送给全部租户。</p>
      <template #footer>
        <el-button @click="announcementOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving.announcement" @click="doSaveAnnouncement">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预览：企业成员右上角「消息中心」里看到的样子 -->
    <el-dialog v-model="previewOpen" title="预览 · 企业消息中心" width="520px">
      <p class="hint">
        发布后，全部租户成员在右上角铃铛「消息中心」的<strong>公告</strong>页签里看到的样式如下：
      </p>
      <div class="notify-preview">
        <div class="preview-icon">
          <el-icon :size="16"><Promotion /></el-icon>
        </div>
        <div class="preview-body">
          <div class="preview-title">
            {{ previewRow?.title }}
            <span class="unread-dot"></span>
          </div>
          <div class="preview-text">{{ previewRow?.content || '（无正文）' }}</div>
          <div class="preview-time">
            {{ previewRow?.publishTime ? dateTimeText(previewRow.publishTime) : '发布后显示发布时间' }}
            · {{ announceTypeText(previewRow?.announceType) }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import { Plus, Promotion } from '@element-plus/icons-vue'
import {
  changeAnnouncementStatus,
  fetchAnnouncements,
  fetchPaymentConfig,
  fetchPlatformPlans,
  fetchRegisterConfig,
  removeAnnouncement,
  saveAnnouncement,
  savePaymentConfig,
  savePlatformPlans,
  saveRegisterConfig,
  type AnnouncementRow,
  type PlatformPlanRow,
} from '../../../api/platform/settings'

const ANNOUNCEMENT_TYPES = [
  { value: 1, label: '系统公告' },
  { value: 2, label: '产品更新' },
  { value: 3, label: '维护通知' },
]

const activeTab = ref('register')
const loading = reactive({ register: false, plans: false, payment: false, announcements: false })
const saving = reactive({ register: false, plans: false, payment: false, announcement: false })

const registerForm = reactive({
  allowRegister: true,
  needReview: true,
  trialDays: 14,
  autoApproveDomains: [] as string[],
  requireDoubleVerify: false,
})

const plans = ref<PlatformPlanRow[]>([])

const paymentForm = reactive({
  onlinePayEnabled: true,
  alipayMerchantId: '',
  wechatMerchantId: '',
  corpAccountName: '',
  corpAccountNo: '',
  corpBankName: '',
})

const announcements = ref<AnnouncementRow[]>([])
const announcementTotal = ref(0)
const announcementQuery = reactive({
  status: undefined as number | undefined,
  announceType: undefined as number | undefined,
  keyword: '',
  page: 1,
  pageSize: 10,
})
const announcementOpen = ref(false)
const announcementForm = reactive({
  id: '' as string | null,
  title: '',
  content: '',
  announceType: 1,
})

/** 预览弹窗：看公告在企业「消息中心」里长什么样 */
const previewOpen = ref(false)
const previewRow = ref<AnnouncementRow | null>(null)

function openPreview(row: AnnouncementRow) {
  previewRow.value = row
  previewOpen.value = true
}

function announceTypeText(type?: number) {
  if (type == null) {
    return '—'
  }
  return ANNOUNCEMENT_TYPES.find((item) => item.value === type)?.label ?? `类型 ${type}`
}

function announcementStatusText(status: number) {
  return ({ 1: '草稿', 2: '已发布', 3: '已下线' } as Record<number, string>)[status] ?? `状态 ${status}`
}

function announcementTag(status: number) {
  return ({ 1: 'info', 2: 'success', 3: 'warning' } as Record<number, string>)[status] ?? 'info'
}

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function indexOfAnnouncement(index: number) {
  return (announcementQuery.page - 1) * announcementQuery.pageSize + index + 1
}

async function loadRegister() {
  loading.register = true
  try {
    const data = await fetchRegisterConfig()
    Object.assign(registerForm, {
      allowRegister: data.allowRegister,
      needReview: data.needReview,
      trialDays: Number(data.trialDays) || 0,
      autoApproveDomains: data.autoApproveDomains ?? [],
      requireDoubleVerify: data.requireDoubleVerify,
    })
  } catch (e) {
    errorMessage(e, '注册策略加载失败')
  } finally {
    loading.register = false
  }
}

async function saveRegister() {
  saving.register = true
  try {
    await saveRegisterConfig({ ...registerForm })
    ElMessage.success('注册审核策略已保存')
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.register = false
  }
}

async function loadPlans() {
  loading.plans = true
  try {
    plans.value = await fetchPlatformPlans()
  } catch (e) {
    errorMessage(e, '套餐加载失败')
  } finally {
    loading.plans = false
  }
}

async function savePlans() {
  saving.plans = true
  try {
    const result = await savePlatformPlans(
      plans.value.map((plan) => ({
        id: plan.id,
        priceYearly: plan.priceYearly,
        priceMonthly: plan.priceMonthly,
        quotaRobotSessions: plan.quotaRobotSessions,
        quotaSeats: plan.quotaSeats,
        quotaAiCalls: plan.quotaAiCalls,
        overagePricePerK: plan.overagePricePerK,
        isEnabled: plan.isEnabled,
      })),
    )
    ElMessage.success(`已保存 ${result.saved} 档套餐，升级页价格同步生效`)
    await loadPlans()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.plans = false
  }
}

async function loadPayment() {
  loading.payment = true
  try {
    const data = await fetchPaymentConfig()
    Object.assign(paymentForm, {
      onlinePayEnabled: data.onlinePayEnabled,
      alipayMerchantId: data.alipayMerchantId ?? '',
      wechatMerchantId: data.wechatMerchantId ?? '',
      corpAccountName: data.corpAccountName ?? '',
      corpAccountNo: data.corpAccountNo ?? '',
      corpBankName: data.corpBankName ?? '',
    })
  } catch (e) {
    errorMessage(e, '支付配置加载失败')
  } finally {
    loading.payment = false
  }
}

async function savePayment() {
  saving.payment = true
  try {
    await savePaymentConfig({ ...paymentForm })
    ElMessage.success('支付配置已保存')
    await loadPayment()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.payment = false
  }
}

async function loadAnnouncements(pageNo = announcementQuery.page) {
  announcementQuery.page = pageNo
  loading.announcements = true
  try {
    const data = await fetchAnnouncements({
      status: announcementQuery.status,
      announceType: announcementQuery.announceType,
      keyword: announcementQuery.keyword || undefined,
      page: pageNo,
      pageSize: announcementQuery.pageSize,
    })
    announcements.value = data.list
    announcementTotal.value = Number(data.total) || 0
  } catch (e) {
    errorMessage(e, '公告加载失败')
  } finally {
    loading.announcements = false
  }
}

function openAnnouncement(row: AnnouncementRow | null) {
  Object.assign(announcementForm, {
    id: row?.id ?? null,
    title: row?.title ?? '',
    content: row?.content ?? '',
    announceType: row?.announceType ?? 1,
  })
  announcementOpen.value = true
}

async function doSaveAnnouncement() {
  if (!announcementForm.title.trim()) {
    ElMessage.warning('请填写公告标题')
    return
  }
  saving.announcement = true
  try {
    await saveAnnouncement(announcementForm.id, {
      title: announcementForm.title.trim(),
      content: announcementForm.content,
      announceType: announcementForm.announceType,
    })
    ElMessage.success('已保存为草稿，点「发布」才会推送给租户')
    announcementOpen.value = false
    await loadAnnouncements(1)
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    saving.announcement = false
  }
}

async function changeStatus(row: AnnouncementRow, status: number) {
  const publish = status === 2
  try {
    await ElMessageBox.confirm(
      publish
        ? `发布后「${row.title}」会推送到全部租户的通知中心，确定吗？`
        : `下线后租户通知中心里已推送的这条不会撤回，确定下线吗？`,
      publish ? '发布公告' : '下线公告',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const result = await changeAnnouncementStatus(row.id, status)
    if (publish) {
      ElMessage.success(result.delivered >= 0
        ? `已发布，推送到 ${result.delivered} 个租户的通知中心`
        : '已发布，但推送到通知中心失败（可在公告列表里看审计日志排查）')
    } else {
      ElMessage.success('已下线')
    }
    await loadAnnouncements()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function doRemoveAnnouncement(row: AnnouncementRow) {
  try {
    await ElMessageBox.confirm(`确定删除公告「${row.title}」吗？`, '删除公告', {
      type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await removeAnnouncement(row.id)
    ElMessage.success('已删除')
    await loadAnnouncements()
  } catch (e) {
    errorMessage(e, '删除失败')
  }
}

onMounted(() => {
  void loadRegister()
  void loadPlans()
  void loadPayment()
  void loadAnnouncements(1)
})
</script>

<style scoped>
.settings-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.settings-tabs { margin-top: 6px; }

.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.block-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.block-sub { font-size: 12.5px; color: #94a3b8; margin-top: 4px; }
.settings-form { max-width: 720px; margin-top: 16px; }
.hint { margin-left: 10px; font-size: 12px; color: #94a3b8; }
.mt-12 { margin-top: 12px; }
.btn-icon { margin-right: 4px; }

.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.f-select { width: 150px; }
.f-input { width: 240px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.w-full { width: 100%; }

/* 预览弹窗：仿企业消息中心的一条公告 */
.notify-preview {
  display: flex;
  gap: 10px;
  padding: 14px;
  margin-top: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
}
.preview-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: #ecfdf5;
  color: #059669;
}
.preview-body { flex: 1; min-width: 0; }
.preview-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #0f172a;
  display: flex;
  align-items: center;
  gap: 6px;
}
.preview-text {
  font-size: 12.5px;
  color: #475569;
  margin-top: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}
.preview-time { font-size: 11.5px; color: #94a3b8; margin-top: 6px; }
.unread-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ef4444;
  flex-shrink: 0;
}
</style>
