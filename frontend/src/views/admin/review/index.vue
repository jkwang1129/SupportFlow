<template>
  <div class="review-page">
    <div class="filter-bar">
      <div class="filter-left">
        <el-input
          v-model="keyword"
          placeholder="企业名称 / 申请单号 / 联系人"
          clearable
          style="width: 260px"
          @keyup.enter="load(1)"
        />
        <el-select v-model="status" placeholder="审核状态" clearable style="width: 150px" @change="load(1)">
          <el-option label="待审核" :value="1" />
          <el-option label="已通过" :value="2" />
          <el-option label="已驳回" :value="3" />
        </el-select>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </div>
      <el-button @click="load(1)">刷新</el-button>
    </div>

    <el-table :data="page.records" v-loading="loading" stripe>
      <el-table-column type="index" label="序号" width="70" :index="indexMethod" />
      <el-table-column prop="applyNo" label="申请单号" width="190" />
      <el-table-column prop="companyName" label="企业名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="industry" label="行业" width="130" />
      <el-table-column prop="contactName" label="联系人" width="110" />
      <el-table-column prop="submitTime" label="提交时间" width="170">
        <template #default="{ row }">{{ formatTime(row.submitTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type" effect="light">{{ statusTag(row.status).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="租户编号" width="170">
        <template #default="{ row }">
          <span v-if="row.tenantCode" class="mono tenant-code">{{ row.tenantCode }}</span>
          <span v-else class="muted">未开通</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.tenantCode" link type="primary" @click="goTenant(row.tenantCode)">
            查看租户
          </el-button>
          <el-button v-if="row.status === 1" link type="success" @click="openApprove(row)">通过</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="openReject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="page-bar"
      background
      layout="total, prev, pager, next, sizes"
      :total="page.total"
      :current-page="page.current"
      :page-size="page.size"
      :page-sizes="[10, 20, 50]"
      @current-change="(p:number) => load(p)"
      @size-change="(s:number) => { pageSize = s; load(1) }"
    />

    <!-- 企业审核详情 -->
    <el-dialog v-model="detailVisible" title="企业审核详情" width="1120px" class="review-detail-dialog" destroy-on-close @closed="clearLicensePreview">
      <div v-if="detail" class="review-detail">
        <div class="detail-grid">
          <div class="detail-block">
            <div class="block-head">
              <span class="block-title">企业工商信息</span>
              <el-tag :type="statusTag(detail.status).type" effect="light">{{ statusTag(detail.status).label }}</el-tag>
            </div>
            <div class="kv"><span>企业名称</span><b>{{ detail.companyName }}</b></div>
            <div class="kv"><span>统一社会信用代码</span><b class="mono">{{ detail.licenseNo || '—' }}</b></div>
            <div class="kv"><span>注册地址</span><b>{{ detail.registerAddress || '—' }}</b></div>
            <div class="kv"><span>法定代表人</span><b>{{ detail.legalPerson || '—' }}</b></div>
            <div class="kv"><span>所属行业</span><b>{{ detail.industry || '—' }}</b></div>
            <div class="kv"><span>团队规模</span><b>{{ detail.scale || '—' }}</b></div>
          </div>

          <div class="detail-block">
            <div class="block-title">申请与联系人</div>
            <div class="kv"><span>申请单号</span><b class="mono">{{ detail.applyNo }}</b></div>
            <div class="kv"><span>申请版本</span><b>第 {{ detail.versionNo }} 次申请</b></div>
            <div class="kv">
              <span>租户编号</span>
              <b v-if="detail.tenantCode">
                <span class="mono">{{ detail.tenantCode }}</span>
                <el-button link type="primary" @click="goTenant(detail.tenantCode!)">查看租户</el-button>
              </b>
              <b v-else>未开通</b>
            </div>
            <div class="kv"><span>提交时间</span><b>{{ formatTime(detail.submitTime) }}</b></div>
            <div class="kv"><span>联系人</span><b>{{ detail.contactName || '—' }}</b></div>
            <div class="kv"><span>联系电话</span><b>{{ detail.contactPhone || '—' }}</b></div>
            <div class="kv"><span>联系邮箱</span><b>{{ detail.contactEmail || '—' }}</b></div>
          </div>
        </div>

        <div class="license-panel">
          <div class="license-head">
            <div>
              <div class="block-title">营业执照核验材料</div>
              <div class="license-sub">原件扫描件，供运营核验企业主体信息</div>
            </div>
            <el-button
              v-if="licensePreviewUrl"
              type="primary"
              plain
              @click="openLicensePreview"
            >
              新窗口打开
            </el-button>
          </div>
          <div class="license-preview">
            <el-image
              v-if="licensePreviewUrl && licenseMime.startsWith('image/')"
              :src="licensePreviewUrl"
              fit="contain"
              :preview-src-list="[licensePreviewUrl]"
              preview-teleported
              class="license-image"
            />
            <iframe
              v-else-if="licensePreviewUrl && licenseMime.includes('pdf')"
              :src="licensePreviewUrl"
              class="license-iframe"
              title="营业执照"
            />
            <el-empty v-else description="未上传营业执照或加载失败" />
          </div>
        </div>

        <div v-if="detail.status === 3 && detail.rejectReason" class="reject-reason">
          <el-icon :size="18"><CircleCloseFilled /></el-icon>
          <div>
            <div class="reject-title">驳回原因</div>
            <div class="reject-text">{{ detail.rejectReason }}</div>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="detail-footer">
          <template v-if="detail?.status === 1">
            <el-button type="danger" @click="detailVisible = false; openReject(detail!)">驳回</el-button>
            <el-button type="primary" @click="detailVisible = false; openApprove(detail!)">审核通过</el-button>
          </template>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 通过确认 -->
    <el-dialog v-model="approveVisible" title="审核通过" width="440px">
      <p>确认通过「{{ current?.companyName }}」的企业开通申请吗？</p>
      <p class="tip">通过后将创建独立租户并开通SupportFlow 客服。</p>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doApprove">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 驳回 -->
    <el-dialog v-model="rejectVisible" title="审核驳回" width="480px">
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        maxlength="512"
        show-word-limit
        placeholder="请填写驳回原因（将展示给企业用户）"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  approveEnterpriseReview,
  getEnterpriseReview,
  pageEnterpriseReviews,
  rejectEnterpriseReview,
  type EnterpriseReviewItem,
  type EnterpriseReviewPage,
} from '../../../api/admin/review'
import { fetchEnterpriseLicense } from '../../../api/enterprise'

const router = useRouter()

const keyword = ref('')
const status = ref<number>()
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const submitting = ref(false)

const page = reactive<EnterpriseReviewPage>({
  records: [],
  total: 0,
  current: 1,
  size: 10,
  pages: 0,
})

const detailVisible = ref(false)
const approveVisible = ref(false)
const rejectVisible = ref(false)
const detail = ref<EnterpriseReviewItem | null>(null)
const current = ref<EnterpriseReviewItem | null>(null)
const rejectReason = ref('')
const licensePreviewUrl = ref('')
const licenseMime = ref('')

onMounted(() => load(1))

async function load(pageNo: number) {
  pageNum.value = pageNo
  loading.value = true
  try {
    const res = await pageEnterpriseReviews({
      pageNum: pageNo,
      pageSize: pageSize.value,
      status: status.value,
      keyword: keyword.value || undefined,
    })
    // 分页的四个值必须全是 Number：后端把 Long 序列化成字符串（防雪花 ID 丢精度），
    // 直接 Object.assign 会让 el-pagination 收到 "4" 这种字符串，控制台报
    // "Expected Number with value 4, got String"（数字还照样显示，所以很容易被忽略）
    Object.assign(page, {
      ...res,
      total: Number(res.total) || 0,
      current: Number(res.current) || 1,
      size: Number(res.size) || 10,
      pages: Number(res.pages) || 0,
    })
  } finally {
    loading.value = false
  }
}

function indexMethod(index: number) {
  return (pageNum.value - 1) * pageSize.value + index + 1
}

/** 跳到「租户管理」并带上租户号，直接定位到这条租户 */
function goTenant(tenantCode?: string) {
  if (!tenantCode) return
  detailVisible.value = false
  void router.push({ path: '/platform/tenants', query: { keyword: tenantCode } })
}

async function openDetail(row: EnterpriseReviewItem) {
  detail.value = await getEnterpriseReview(row.id)
  clearLicensePreview()
  if (detail.value?.licenseFileId) {
    try {
      const blob = await fetchEnterpriseLicense(detail.value.licenseFileId)
      licensePreviewUrl.value = URL.createObjectURL(blob)
      licenseMime.value = blob.type || 'application/pdf'
    } catch {
      licensePreviewUrl.value = ''
      ElMessage.warning('营业执照加载失败')
    }
  }
  detailVisible.value = true
}

function openApprove(row: EnterpriseReviewItem) {
  current.value = row
  approveVisible.value = true
}

function openReject(row: EnterpriseReviewItem) {
  current.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doApprove() {
  if (!current.value) return
  submitting.value = true
  try {
    await approveEnterpriseReview(current.value.id)
    ElMessage.success('审核通过，租户已开通')
    approveVisible.value = false
    await load(pageNum.value)
  } finally {
    submitting.value = false
  }
}

async function doReject() {
  if (!current.value) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  submitting.value = true
  try {
    await rejectEnterpriseReview(current.value.id, rejectReason.value.trim())
    ElMessage.success('已驳回申请')
    rejectVisible.value = false
    await load(pageNum.value)
  } finally {
    submitting.value = false
  }
}

function openLicensePreview() {
  if (!licensePreviewUrl.value) return
  const link = document.createElement('a')
  link.href = licensePreviewUrl.value
  link.target = '_blank'
  link.rel = 'noopener'
  link.click()
}

function clearLicensePreview() {
  if (licensePreviewUrl.value) {
    URL.revokeObjectURL(licensePreviewUrl.value)
  }
  licensePreviewUrl.value = ''
  licenseMime.value = ''
}

function statusTag(value: number) {
  if (value === 1) return { label: '待审核', type: 'warning' as const }
  if (value === 2) return { label: '已通过', type: 'success' as const }
  return { label: '已驳回', type: 'danger' as const }
}

function formatTime(time?: string) {
  return time ? time.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.review-page {
  width: 100%;
  min-width: 0;
}

.filter-bar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-left {
  display: flex;
  gap: 10px;
}

.muted {
  color: #94a3b8;
  font-size: 13px;
}

.tenant-code { color: #334155; }

.page-bar {
  margin-top: 16px;
  justify-content: flex-end;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-block {
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 12px;
  padding: 14px 16px;
}

.block-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 8px;
}

.kv {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px dashed #e2e8f0;
  font-size: 13px;
}

.kv span {
  color: #94a3b8;
  flex-shrink: 0;
}

.kv b {
  color: #1e293b;
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.reject-reason {
  margin-top: 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 12px;
  color: #b91c1c;
  font-size: 13px;
  line-height: 1.7;
}

.tip {
  color: #94a3b8;
  font-size: 13px;
}

@media (max-width: 800px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
/* 审核详情弹窗：teleport 到 body，使用全局样式 */
.review-detail-dialog .el-dialog__header {
  font-weight: 700;
  color: #0f172a;
}

.review-detail-dialog .review-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-height: 72vh;
  overflow: auto;
  padding: 2px 4px;
}

.review-detail-dialog .detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.review-detail-dialog .detail-block {
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 14px;
  padding: 16px 18px;
}

.review-detail-dialog .block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.review-detail-dialog .block-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.review-detail-dialog .kv {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 8px 0;
  border-bottom: 1px dashed #e2e8f0;
  font-size: 13px;
}

.review-detail-dialog .kv span {
  color: #94a3b8;
  flex-shrink: 0;
}

.review-detail-dialog .kv b {
  color: #1e293b;
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.review-detail-dialog .mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.review-detail-dialog .license-panel {
  border: 1px solid #e6e8ef;
  border-radius: 14px;
  padding: 16px 18px;
  background: #fff;
}

.review-detail-dialog .license-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.review-detail-dialog .license-sub {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.review-detail-dialog .license-preview {
  min-height: 260px;
  background: #f8fafc;
  border: 1px dashed #d8dee6;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.review-detail-dialog .license-image {
  width: 100%;
  height: 360px;
}

.review-detail-dialog .license-iframe {
  width: 100%;
  height: 360px;
  border: none;
}

.review-detail-dialog .reject-reason {
  display: flex;
  gap: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 12px;
  padding: 12px 14px;
  color: #b91c1c;
}

.review-detail-dialog .reject-title {
  font-size: 13px;
  font-weight: 700;
}

.review-detail-dialog .reject-text {
  font-size: 13px;
  line-height: 1.7;
  margin-top: 3px;
}

.review-detail-dialog .detail-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 900px) {
  .review-detail-dialog .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
