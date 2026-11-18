<template>
  <div class="enterprise-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">我的企业</h2>
        <p class="page-sub">查看企业资料与开通审核状态</p>
      </div>
      <el-button v-if="guide?.stage === 'PENDING_PROFILE' || guide?.stage === 'REJECTED'" type="primary" @click="router.push('/guide')">
        去完善企业信息
      </el-button>
    </div>

    <div v-if="loading" class="loading-card">
      <el-icon class="is-loading" :size="26"><Loading /></el-icon>
      <p>正在加载企业信息…</p>
    </div>

    <el-alert
      v-else-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="error-alert"
    />

    <template v-else-if="guide">
      <el-card shadow="never" class="status-card">
        <template #header>
          <span class="card-title">企业开通状态</span>
        </template>
        <div class="status-body">
          <div class="status-icon" :class="stageMeta.type">
            <el-icon :size="30"><component :is="stageMeta.icon" /></el-icon>
          </div>
          <div class="status-info">
            <div class="status-title">{{ stageMeta.title }}</div>
            <div class="status-sub">{{ stageMeta.desc }}</div>
            <div v-if="guide.tenantCode" class="tenant-code mono">租户编码：{{ guide.tenantCode }}</div>
          </div>
          <el-tag :type="stageMeta.tagType" effect="light" size="large">{{ stageMeta.badge }}</el-tag>
        </div>
      </el-card>

      <div class="info-grid">
        <el-card shadow="never">
          <template #header><span class="card-title">企业资料</span></template>
          <div class="info-list">
            <div class="info-row"><span class="k">企业名称</span><span class="v">{{ guide.companyName || '—' }}</span></div>
            <div class="info-row"><span class="k">统一社会信用代码</span><span class="v">{{ guide.licenseNo || '—' }}</span></div>
            <div class="info-row"><span class="k">注册地址</span><span class="v">{{ guide.registerAddress || '—' }}</span></div>
            <div class="info-row"><span class="k">法人代表</span><span class="v">{{ guide.legalPerson || '—' }}</span></div>
            <div class="info-row"><span class="k">所属行业 / 规模</span><span class="v">{{ guide.industry || '—' }} / {{ guide.scale || '—' }}</span></div>
            <div class="info-row"><span class="k">管理员</span><span class="v">{{ guide.contactName || '—' }}</span></div>
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">审核与营业执照</span></template>
          <div class="info-list">
            <div class="info-row"><span class="k">企业编码</span><span class="v mono">{{ guide.enterpriseCode }}</span></div>
            <div class="info-row"><span class="k">申请单号</span><span class="v mono">{{ guide.applyNo || '—' }}</span></div>
            <div class="info-row"><span class="k">提交版本</span><span class="v">第 {{ guide.versionNo ?? '—' }} 次申请</span></div>
            <div class="info-row">
              <span class="k">营业执照</span>
              <span v-if="licenseFileName" class="v file-cell">
                <a v-if="licensePreview" class="file-link" :href="licensePreview" target="_blank">预览 {{ licenseFileName }}</a>
                <template v-else>{{ licenseFileName }}</template>
              </span>
              <span v-else class="v">未上传</span>
            </div>
            <div v-if="guide.stage === 'REJECTED'" class="reject-box">
              <div class="reject-title">驳回原因</div>
              <div class="reject-text">{{ guide.rejectReason || '资料核验未通过，请修改后重新提交' }}</div>
            </div>
          </div>
        </el-card>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchEnterpriseLicense, getEnterpriseGuide } from '../../api/enterprise'
import { fetchMemberAccess } from '../../api/member'
import type { EnterpriseGuideState } from '../../types'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const guide = ref<EnterpriseGuideState | null>(null)
const licenseFileName = ref('')
const licensePreview = ref('')

const stageMeta = computed(() => {
  const stage = guide.value?.stage
  if (stage === 'PENDING_REVIEW') {
    return { type: 'pending', icon: 'Clock', title: '企业资料审核中', desc: '运营正在核验营业执照与法人信息，预计 1~2 个工作日内完成', badge: '审核中', tagType: 'warning' as const }
  }
  if (stage === 'REJECTED') {
    return { type: 'rejected', icon: 'CircleCloseFilled', title: '审核未通过', desc: '请根据驳回原因修改资料后重新提交', badge: '已驳回', tagType: 'danger' as const }
  }
  if (stage === 'APPROVED') {
    return { type: 'approved', icon: 'CircleCheckFilled', title: '企业已开通', desc: '审核已通过，SupportFlow 客服租户已就绪', badge: '已开通', tagType: 'success' as const }
  }
  return { type: 'todo', icon: 'EditPen', title: '企业资料待完善', desc: '完成企业资料填写后提交运营审核', badge: '待完善', tagType: 'info' as const }
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const access = await fetchMemberAccess()
    if (!access.canManage) {
      // 普通客服成员不展示企业信息，直接回到工作台
      router.replace('/dashboard')
      return
    }
    guide.value = await getEnterpriseGuide()
    if (guide.value?.licenseFileId) {
      try {
        const blob = await fetchEnterpriseLicense(guide.value.licenseFileId)
        if (blob.type.startsWith('image/')) {
          licensePreview.value = URL.createObjectURL(blob)
        }
        licenseFileName.value = `营业执照附件 (${Math.ceil(blob.size / 1024)} KB)`
      } catch {
        licenseFileName.value = '已上传'
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.enterprise-page {
  width: 100%;
  min-width: 0;
}

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 18px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  color: #0f172a;
}

.page-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.loading-card {
  text-align: center;
  padding: 50px 0;
  color: #64748b;
}

.error-alert {
  margin-bottom: 16px;
}

.status-card {
  margin-bottom: 18px;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #1e293b;
}

.status-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.status-icon {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-icon.pending { background: #fff7ed; color: #f59e0b; }
.status-icon.rejected { background: #fef2f2; color: #dc2626; }
.status-icon.approved { background: #ecfdf5; color: #10b981; }
.status-icon.todo { background: #eff6ff; color: #2563eb; }

.status-info {
  flex: 1;
}

.status-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.status-sub {
  font-size: 13px;
  color: #64748b;
  margin-top: 5px;
  line-height: 1.7;
}

.tenant-code {
  margin-top: 8px;
  color: #2563eb;
  font-size: 13px;
}

.info-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 20px;
}

.info-list {
  display: flex;
  flex-direction: column;
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px dashed #eef2f7;
  font-size: 13px;
}

.info-row:last-child {
  border-bottom: none;
}

.info-row .k {
  color: #94a3b8;
  flex-shrink: 0;
}

.info-row .v {
  color: #1e293b;
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.file-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.file-link {
  color: #2563eb;
  text-decoration: none;
}

.file-link:hover {
  text-decoration: underline;
}

.reject-box {
  margin-top: 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 12px;
}

.reject-title {
  font-size: 12.5px;
  font-weight: 700;
  color: #b91c1c;
}

.reject-text {
  font-size: 12.5px;
  color: #dc2626;
  margin-top: 4px;
  line-height: 1.6;
}

@media (max-width: 820px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
