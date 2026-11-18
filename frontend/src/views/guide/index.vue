<template>
  <div class="guide-page">
    <!-- 顶部导航 -->
    <header class="guide-nav">
      <div class="guide-brand">
        <img class="guide-logo" src="/supportflow-mark.svg" alt="SupportFlow" />
        <div>
          <div class="brand-title">SupportFlow 智能客服</div>
          <div class="brand-sub">企业开通引导</div>
        </div>
      </div>
      <div class="nav-right">
        <span v-if="guide?.companyName" class="company-name">{{ guide.companyName }}</span>
        <button class="nav-link" @click="openLogoutConfirm">退出登录</button>
      </div>
    </header>

    <main class="guide-wrap">
      <!-- 加载中 -->
      <div v-if="loading" class="guide-state-card">
        <el-icon class="is-loading" :size="30"><Loading /></el-icon>
        <p>正在加载开通状态…</p>
      </div>

      <!-- 加载失败 -->
      <div v-else-if="loadError" class="guide-state-card">
        <el-icon :size="36" color="#f59e0b"><WarningFilled /></el-icon>
        <p class="error-title">{{ loadError }}</p>
        <div class="state-actions">
          <el-button type="primary" @click="loadGuide">重新加载</el-button>
          <el-button @click="backToLogin">返回登录</el-button>
        </div>
      </div>

      <template v-else-if="guide">
        <!-- 步骤条 -->
        <div class="guide-stepper">
          <template v-for="(step, i) in stepper" :key="step.label">
            <div
              v-if="i > 0"
              class="stepper-line"
              :class="{ done: currentStep > i }"
            />
            <div
              class="stepper-step"
              :class="{ done: currentStep > i + 1, now: currentStep === i + 1 }"
            >
              <span class="step-no">
                <el-icon v-if="currentStep > i + 1"><Check /></el-icon>
                <template v-else>{{ i + 1 }}</template>
              </span>
              <span class="step-label">{{ step.label }}</span>
            </div>
          </template>
        </div>

        <!-- 阶段一：完善企业信息 / 驳回后修改 -->
        <div v-if="guide.stage === 'PENDING_PROFILE' || guide.stage === 'REJECTED'" class="guide-card">
          <div v-if="guide.stage === 'REJECTED'" class="reject-banner">
            <el-icon :size="18"><CircleCloseFilled /></el-icon>
            <div>
              <div class="reject-title">企业资料未通过审核（第 {{ guide.versionNo }} 次申请）</div>
              <div class="reject-reason">{{ guide.rejectReason || '资料核验未通过，请补充后重新提交' }}</div>
            </div>
          </div>

          <div class="card-head">
            <div>
              <div class="card-title">完善企业信息</div>
              <div class="card-sub">填写营业执照、注册地址、法人等专业信息，提交后进入企业审核</div>
            </div>
            <span class="badge amber">第 {{ guide.stage === 'REJECTED' ? 2 : 1 }} 步 / 共 3 步</span>
          </div>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            class="profile-form"
          >
            <div class="form-grid">
              <el-form-item label="企业名称" prop="companyName">
                <el-input v-model.trim="form.companyName" placeholder="与营业执照一致" maxlength="128" />
              </el-form-item>
              <el-form-item label="统一社会信用代码" prop="licenseNo">
                <el-input
                  v-model.trim="form.licenseNo"
                  placeholder="18 位统一社会信用代码"
                  maxlength="18"
                />
              </el-form-item>
            </div>

            <el-form-item label="营业执照扫描件" prop="licenseFileId">
              <div
                class="license-upload"
                :class="{ 'drag-active': dragActive, 'is-uploading': uploading }"
                @dragover.prevent
                @dragenter.prevent="onLicenseDragEnter"
                @dragleave.prevent="onLicenseDragLeave"
                @drop.prevent="onLicenseDrop"
              >
                <input
                  ref="licenseInputRef"
                  type="file"
                  class="license-input"
                  accept=".jpg,.jpeg,.png,.pdf"
                  @change="onLicenseChange"
                />
                <div v-if="uploading" class="up-loading">
                  <el-icon class="is-loading" :size="24"><Loading /></el-icon>
                  <p>正在上传营业执照…</p>
                  <span>文件较大时请稍候，不要关闭当前页面</span>
                </div>
                <div v-else-if="!licenseFileId" class="up-zone" @click="pickLicense">
                  <el-icon :size="24"><UploadFilled /></el-icon>
                  <p>{{ dragActive ? '松开鼠标即可上传' : '点击或拖拽文件到此处上传营业执照扫描件' }}</p>
                  <span>JPG / PNG / PDF，≤ 10MB</span>
                </div>
                <div v-else class="up-done">
                  <img
                    v-if="licensePreview && isImageLicense"
                    :src="licensePreview"
                    class="license-preview"
                    alt="营业执照"
                  />
                  <el-icon v-else :size="24" color="#10b981"><Document /></el-icon>
                  <div class="file-info">
                    <div class="file-name">{{ licenseFileName }}</div>
                    <div class="file-tip">已上传，审核仅用于企业实名核验</div>
                  </div>
                  <el-button size="small" @click.stop="resetLicense">重新上传</el-button>
                </div>
              </div>
            </el-form-item>

            <div class="form-grid">
              <el-form-item label="注册地址" prop="registerAddress">
                <el-input v-model.trim="form.registerAddress" placeholder="营业执照上的注册地址" maxlength="255" />
              </el-form-item>
              <el-form-item label="法人代表姓名" prop="legalPerson">
                <el-input v-model.trim="form.legalPerson" placeholder="法定代表人姓名" maxlength="64" />
              </el-form-item>
            </div>

            <div class="section-divider">管理员信息</div>

            <div class="form-grid">
              <el-form-item label="管理员姓名" prop="contactName">
                <el-input v-model.trim="form.contactName" placeholder="企业管理员姓名" maxlength="64" />
              </el-form-item>
              <el-form-item label="联系电话" prop="contactPhone">
                <el-input v-model.trim="form.contactPhone" placeholder="11 位手机号" maxlength="11" />
              </el-form-item>
            </div>
            <div class="form-grid">
              <el-form-item label="企业邮箱" prop="contactEmail">
                <el-input v-model.trim="form.contactEmail" placeholder="name@company.com" maxlength="128" />
              </el-form-item>
              <el-form-item label="所属行业 / 团队规模" prop="industry">
                <div class="inline-selects">
                  <el-select v-model="form.industry" placeholder="所属行业">
                    <el-option v-for="item in industryOptions" :key="item" :label="item" :value="item" />
                  </el-select>
                  <el-select v-model="form.scale" placeholder="团队规模">
                    <el-option v-for="item in scaleOptions" :key="item" :label="item" :value="item" />
                  </el-select>
                </div>
              </el-form-item>
            </div>

            <div class="privacy-tip">
              <el-icon :size="15"><InfoFilled /></el-icon>
              <span>营业执照与法人信息仅用于企业实名核验与合同签署，全程加密存储，不会向第三方披露。</span>
            </div>

            <el-button
              type="primary"
              size="large"
              class="submit-btn"
              :loading="submitting"
              @click="handleSubmit"
            >
              {{ guide.stage === 'REJECTED' ? '修改资料并重新提交' : '提交企业资料，进入审核' }}
            </el-button>
          </el-form>
        </div>

        <!-- 阶段二：审核中 -->
        <div v-else-if="guide.stage === 'PENDING_REVIEW'" class="guide-card">
          <div class="card-head">
            <div>
              <div class="card-title">企业资料已提交</div>
              <div class="card-sub">正在等待运营审核，请留意邮件 / 短信通知</div>
            </div>
            <span class="badge amber">第 2 步 / 共 3 步</span>
          </div>

          <div class="review-flow">
            <div class="flow-item done">
              <span class="flow-no"><el-icon><Check /></el-icon></span>
              <div>
                <div class="flow-title">企业资料已提交</div>
                <div class="flow-sub">申请单 {{ guide.applyNo }} · 第 {{ guide.versionNo }} 次申请</div>
              </div>
            </div>
            <div class="flow-item now">
              <span class="flow-no">2</span>
              <div>
                <div class="flow-title">运营审核中</div>
                <div class="flow-sub">核验营业执照、法人与企业信息，预计 1~2 个工作日内完成</div>
              </div>
            </div>
            <div class="flow-item">
              <span class="flow-no">3</span>
              <div>
                <div class="flow-title">审核通过 · 创建租户</div>
                <div class="flow-sub">通过后自动初始化，进入系统配置引导</div>
              </div>
            </div>
          </div>

          <div class="review-summary">
            <div class="summary-row"><span class="k">企业名称</span><span class="v">{{ form.companyName }}</span></div>
            <div class="summary-row"><span class="k">统一社会信用代码</span><span class="v">{{ form.licenseNo }}</span></div>
            <div class="summary-row"><span class="k">注册地址</span><span class="v">{{ form.registerAddress }}</span></div>
            <div class="summary-row"><span class="k">法人代表</span><span class="v">{{ form.legalPerson }}</span></div>
            <div class="summary-row">
              <span class="k">营业执照</span>
              <span class="v">
                <el-button
                  v-if="guide.licenseFileId"
                  link
                  type="primary"
                  @click="openLicensePreview"
                >
                  查看营业执照
                </el-button>
                <template v-else>{{ licenseFileName || '已上传' }}</template>
              </span>
            </div>
            <div class="summary-row">
              <span class="k">管理员</span>
              <span class="v">{{ form.contactName }} · {{ maskPhone(form.contactPhone) }}</span>
            </div>
          </div>

          <p class="review-note">
            审核结果由平台运营在企业审核台完成，审核通过后系统会自动开通租户，请留意站内通知。
          </p>
        </div>

        <!-- 阶段三：审核通过 / 系统就绪 -->
        <div v-else-if="guide.stage === 'APPROVED'" class="guide-card">
          <div class="card-head">
            <div>
              <div class="card-title success-title">🎉 审核通过 · 企业开通成功</div>
              <div class="card-sub">
                租户编码 {{ guide.tenantCode || '—' }} 已创建，完成以下配置即可开始接待客户
              </div>
            </div>
            <span class="badge green">第 3 步 / 共 3 步</span>
          </div>

          <div class="done-hero">
            <div class="ok-icon">
              <el-icon :size="30"><CircleCheckFilled /></el-icon>
            </div>
            <div class="done-title">SupportFlow 客服系统已就绪</div>
            <div class="done-sub">
              建议按以下顺序完成初始化：接入渠道 → 配置机器人 → 邀请成员 → 体验质检
            </div>
            <div v-if="guide.autoApproved" class="auto-approve-note">
              <el-icon><InfoFilled /></el-icon>
              企业邮箱域名命中平台白名单，本次资料已自动通过审核（无需等待运营人工核验）
            </div>
          </div>

          <div class="init-steps">
            <div v-for="(item, i) in initSteps" :key="item.label" class="init-step">
              <span class="init-icon-tile" :class="`tone-${i % 5}`">
                <el-icon :size="22"><component :is="item.icon" /></el-icon>
              </span>
              <div class="init-info">
                <div class="init-label">{{ i + 1 }}. {{ item.label }}</div>
                <div class="init-desc">{{ item.desc }}</div>
              </div>
              <span class="init-status">待配置</span>
            </div>
          </div>

          <div class="ready-actions">
            <el-button
              type="primary"
              size="large"
              @click="enterWorkspace"
            >
              进入工作台
            </el-button>
            <el-button size="large" @click="openLogoutConfirm">退出登录</el-button>
          </div>
        </div>
      </template>
    </main>

    <LogoutConfirmDialog
      v-model="logoutVisible"
      :name="userStore.name || ''"
      :account="userStore.userNo || ''"
      @confirm="doLogout"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { errorMessage } from '../../utils/message'
import {
  fetchEnterpriseLicense,
  getEnterpriseGuide,
  submitEnterpriseReview,
  uploadEnterpriseLicense,
} from '../../api/enterprise'
import { fetchMemberAccess } from '../../api/member'
import { useUserStore } from '../../stores/user'
import LogoutConfirmDialog from '../../components/LogoutConfirmDialog.vue'
import type { EnterpriseGuideState } from '../../types'

const router = useRouter()
const userStore = useUserStore()

const ONBOARD_DONE_KEY = 'supportflow_onboard_done'

const guide = ref<EnterpriseGuideState | null>(null)
const loading = ref(true)
const loadError = ref('')
const submitting = ref(false)
const logoutVisible = ref(false)

const formRef = ref<FormInstance>()
const licenseInputRef = ref<HTMLInputElement>()
const licenseFileId = ref<string | null>(null)
const licenseFileName = ref('')
const licensePreview = ref('')
const isImageLicense = ref(false)
const uploading = ref(false)
const dragActive = ref(false)

// dragenter/dragleave 在子元素之间会反复触发，用计数器避免拖拽高亮闪烁
let dragDepth = 0

let reviewTimer: number | undefined

const industryOptions = ['电商零售', '企业服务', '金融保险', '教育', '智能硬件', '其他']
const scaleOptions = ['10 人以内', '10~50 人', '50~200 人', '200 人以上']

const stepper = [
  { label: '完善企业信息' },
  { label: '企业审核' },
  { label: '系统初始化' },
]

const initSteps = [
  { icon: 'Connection', label: '接入第一个渠道', desc: '官网 / 微信 / 小程序任选其一' },
  { icon: 'Cpu', label: '配置智能机器人', desc: '开启意图识别与多轮对话' },
  { icon: 'UserFilled', label: '邀请客服成员', desc: '创建坐席账号与技能组' },
  { icon: 'DataAnalysis', label: '发起首次质检', desc: 'AI 全量初检今日会话' },
]

const currentStep = computed(() => {
  const stage = guide.value?.stage
  if (stage === 'PENDING_PROFILE') return 1
  if (stage === 'PENDING_REVIEW' || stage === 'REJECTED') return 2
  if (stage === 'APPROVED') return 3
  return 0
})

const form = reactive({
  companyName: '',
  industry: '电商零售',
  scale: '10~50 人',
  licenseNo: '',
  registerAddress: '',
  legalPerson: '',
  contactName: '',
  contactPhone: '',
  contactEmail: '',
})

const rules: FormRules = {
  companyName: [{ required: true, message: '请输入企业名称', trigger: 'blur' }],
  licenseNo: [
    { required: true, message: '请输入统一社会信用代码', trigger: 'blur' },
    {
      pattern: /^[0-9A-HJ-NPQRTUWXY]{18}$/,
      message: '统一社会信用代码格式不正确',
      trigger: 'blur',
    },
  ],
  registerAddress: [{ required: true, message: '请输入注册地址', trigger: 'blur' }],
  legalPerson: [{ required: true, message: '请输入法人代表姓名', trigger: 'blur' }],
  contactName: [{ required: true, message: '请输入管理员姓名', trigger: 'blur' }],
  contactPhone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '联系电话格式不正确', trigger: 'blur' },
  ],
  contactEmail: [
    { required: true, message: '请输入企业邮箱', trigger: 'blur' },
    { type: 'email', message: '企业邮箱格式不正确', trigger: 'blur' },
  ],
}

onMounted(loadGuide)
onUnmounted(stopReviewTimer)

async function loadGuide() {
  loading.value = true
  loadError.value = ''
  try {
    // 页面刷新后先恢复会话，供顶部信息与退出弹窗使用
    if (!userStore.userId) {
      await userStore.fetchProfile()
    }
    // 平台账号不应停留在企业开通引导页
    if (userStore.userType === 1) {
      router.replace('/dashboard')
      return
    }
    if (userStore.userType === 2) {
      try {
        const access = await fetchMemberAccess()
        if (!access.canManage) {
          router.replace('/dashboard')
          return
        }
      } catch {
        // 老版本后端无成员权限接口时，继续按企业管理员处理
      }
    }
    guide.value = await getEnterpriseGuide()
    fillForm(guide.value)
    if (guide.value.stage === 'PENDING_REVIEW') {
      startReviewTimer()
    } else {
      stopReviewTimer()
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function fillForm(state: EnterpriseGuideState) {
  form.companyName = state.companyName || ''
  form.industry = state.industry || '电商零售'
  form.scale = state.scale || '10~50 人'
  form.licenseNo = state.licenseNo || ''
  form.registerAddress = state.registerAddress || ''
  form.legalPerson = state.legalPerson || ''
  form.contactName = state.contactName || ''
  form.contactPhone = state.contactPhone || ''
  form.contactEmail = state.contactEmail || ''
  licenseFileId.value = state.licenseFileId ?? null
  if (licenseFileId.value) {
    loadLicensePreview(licenseFileId.value)
  }
}

async function loadLicensePreview(fileId: string) {
  try {
    const blob = await fetchEnterpriseLicense(fileId)
    isImageLicense.value = blob.type.startsWith('image/')
    if (isImageLicense.value) {
      licensePreview.value = URL.createObjectURL(blob)
    }
  } catch {
    // 附件不可见时仍允许重新上传
  }
}

/**
 * 打开营业执照预览：图片/PDF 都会以新窗口打开，支持放大查看。
 */
async function openLicensePreview() {
  const fileId = guide.value?.licenseFileId
  if (!fileId) {
    ElMessage.warning('暂无可查看的营业执照')
    return
  }
  try {
    const blob = await fetchEnterpriseLicense(fileId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.target = '_blank'
    link.rel = 'noopener'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } catch {
    errorMessage(undefined, '营业执照加载失败，请稍后重试')
  }
}

function pickLicense() {
  if (uploading.value) return
  licenseInputRef.value?.click()
}

async function onLicenseChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空，保证连续选择同一个文件也能触发 change
  input.value = ''
  if (file) {
    await handleLicenseFile(file)
  }
}

function onLicenseDragEnter() {
  dragDepth += 1
  dragActive.value = true
}

function onLicenseDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) {
    dragActive.value = false
  }
}

function onLicenseDrop(event: DragEvent) {
  dragDepth = 0
  dragActive.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) {
    void handleLicenseFile(file)
  }
}

/**
 * 校验并上传营业执照：点击选择与拖拽上传共用同一套逻辑。
 */
async function handleLicenseFile(file: File) {
  if (uploading.value) return
  const typeOk = /\.(jpe?g|png|pdf)$/i.test(file.name)
  if (!typeOk) {
    ElMessage.warning('仅支持 JPG / PNG / PDF 格式的营业执照')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('营业执照文件不能超过 10MB')
    return
  }
  uploading.value = true
  try {
    const res = await uploadEnterpriseLicense(file)
    licenseFileId.value = res.fileId
    licenseFileName.value = res.fileName
    isImageLicense.value = /\.(jpe?g|png)$/i.test(res.fileName)
    if (isImageLicense.value) {
      licensePreview.value = URL.createObjectURL(file)
    }
    ElMessage.success('营业执照上传成功')
  } finally {
    uploading.value = false
  }
}

function resetLicense() {
  licenseFileId.value = null
  licenseFileName.value = ''
  licensePreview.value = ''
  isImageLicense.value = false
}

async function handleSubmit() {
  if (!licenseFileId.value) {
    ElMessage.warning('请先上传营业执照扫描件')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    guide.value = await submitEnterpriseReview({
      companyName: form.companyName,
      industry: form.industry,
      scale: form.scale,
      licenseNo: form.licenseNo,
      registerAddress: form.registerAddress,
      legalPerson: form.legalPerson,
      licenseFileId: licenseFileId.value,
      contactName: form.contactName,
      contactPhone: form.contactPhone,
      contactEmail: form.contactEmail,
    })
    ElMessage.success('企业资料已提交，进入运营审核')
    startReviewTimer()
  } catch {
    // 错误提示由统一拦截器处理
  } finally {
    submitting.value = false
  }
}

function enterWorkspace() {
  localStorage.setItem(ONBOARD_DONE_KEY, 'true')
  // 系统初始化第 1 步为渠道接入，进入工作台后默认打开该页面
  router.push('/channels')
}

function doLogout() {
  userStore.reset()
  localStorage.removeItem(ONBOARD_DONE_KEY)
  router.push('/login')
}

async function openLogoutConfirm() {
  // 确保账号信息已恢复，弹窗里展示真实姓名与账号
  if (!userStore.userId) {
    try {
      await userStore.fetchProfile()
    } catch {
      // 拉取失败时按原信息弹窗，不阻断退出操作
    }
  }
  logoutVisible.value = true
}

function backToLogin() {
  userStore.reset()
  localStorage.removeItem(ONBOARD_DONE_KEY)
  router.push('/login')
}

function maskPhone(phone?: string) {
  if (!phone || phone.length < 11) return phone || '—'
  return `${phone.slice(0, 3)}****${phone.slice(7)}`
}

function startReviewTimer() {
  stopReviewTimer()
  reviewTimer = window.setInterval(async () => {
    try {
      const latest = await getEnterpriseGuide()
      if (latest.stage !== 'PENDING_REVIEW') {
        guide.value = latest
        stopReviewTimer()
      }
    } catch {
      // 轮询失败静默，下次重试
    }
  }, 30000)
}

function stopReviewTimer() {
  if (reviewTimer) {
    window.clearInterval(reviewTimer)
    reviewTimer = undefined
  }
}
</script>

<style scoped>
.guide-page {
  min-height: 100%;
  background:
    radial-gradient(1000px 520px at 12% -10%, rgba(59, 130, 246, 0.22), transparent 60%),
    radial-gradient(760px 460px at 105% 12%, rgba(14, 165, 233, 0.16), transparent 55%),
    #f6f7f9;
}

.guide-nav {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 14px;
  height: 60px;
  padding: 0 28px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid #e6e8ef;
}

.guide-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.guide-logo {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.22);
}

.brand-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.brand-sub {
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 0.6px;
}

.nav-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 16px;
}

.company-name {
  font-size: 13px;
  color: #64748b;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-link {
  border: none;
  background: none;
  color: #2563eb;
  font-size: 13px;
  cursor: pointer;
  padding: 6px 2px;
}

.nav-link:hover {
  color: #1d4ed8;
}

.guide-wrap {
  max-width: 900px;
  margin: 0 auto;
  padding: 26px 22px 72px;
}

.guide-stepper {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: 4px 0 22px;
}

.stepper-step {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-no {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  background: #e6e8f0;
  color: #94a3b8;
  transition: all 0.2s;
}

.stepper-step.done .step-no {
  background: #d1fae5;
  color: #059669;
}

.stepper-step.now .step-no {
  background: #2563eb;
  color: #fff;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.14);
}

.step-label {
  font-size: 13px;
  font-weight: 600;
  color: #94a3b8;
}

.stepper-step.done .step-label,
.stepper-step.now .step-label {
  color: #1e293b;
}

.stepper-line {
  width: 62px;
  height: 2px;
  border-radius: 2px;
  background: #e6e8f0;
}

.stepper-line.done {
  background: #10b981;
}

.guide-card {
  background: #fff;
  border: 1px solid #e6e8ef;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.07);
  padding: 24px 28px 28px;
  animation: fadeUp 0.28s ease;
}

@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 20px;
}

.card-title {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.card-sub {
  font-size: 13px;
  color: #94a3b8;
  margin-top: 5px;
  line-height: 1.6;
}

.badge {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
}

.badge.amber {
  color: #b45309;
  background: #fef3c7;
}

.badge.green {
  color: #047857;
  background: #d1fae5;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.profile-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.profile-form :deep(.el-form-item__label) {
  margin-bottom: 3px;
}

.section-divider {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin: 2px 0 14px;
  padding-left: 8px;
  border-left: 3px solid #2563eb;
  line-height: 1;
}

.license-upload {
  width: 100%;
}

.license-upload.drag-active .up-zone,
.license-upload.drag-active .up-done {
  border-color: #2563eb;
  background: #eff6ff;
}

.license-upload.drag-active .up-zone {
  color: #2563eb;
}

.license-upload.is-uploading {
  cursor: progress;
}

.license-input {
  display: none;
}

.up-zone {
  border: 1.5px dashed #c7d2e2;
  border-radius: 12px;
  padding: 22px 16px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  color: #94a3b8;
}

.up-zone:hover {
  border-color: #2563eb;
  background: #eff6ff;
  color: #2563eb;
}

.up-zone p {
  margin: 6px 0 2px;
  font-size: 13px;
  color: #475569;
}

.up-zone span {
  font-size: 12px;
  color: #94a3b8;
}

.up-loading {
  border: 1.5px dashed #2563eb;
  border-radius: 12px;
  padding: 22px 16px;
  text-align: center;
  background: #eff6ff;
  color: #2563eb;
}

.up-loading p {
  margin: 6px 0 2px;
  font-size: 13px;
  color: #2563eb;
}

.up-loading span {
  font-size: 12px;
  color: #7ba0e8;
}

.up-done {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #ecfdf5;
  border: 1px solid #cdeeda;
  border-radius: 12px;
  padding: 12px 14px;
}

.license-preview {
  width: 64px;
  height: 44px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #d1fae5;
}

.file-info {
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 13px;
  font-weight: 600;
  color: #065f46;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-tip {
  font-size: 12px;
  color: #6ee7b7;
  margin-top: 2px;
}

.inline-selects {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  width: 100%;
}

.privacy-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  padding: 10px 12px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
  margin: 2px 0 18px;
}

.submit-btn {
  display: block;
  width: 340px;
  margin: 0 auto;
}

.reject-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 12px;
  padding: 12px 14px;
  color: #b91c1c;
  margin-bottom: 18px;
}

.reject-title {
  font-size: 13px;
  font-weight: 700;
}

.reject-reason {
  font-size: 12px;
  margin-top: 3px;
  color: #dc2626;
}

.review-flow {
  margin: 4px 0 18px;
}

.flow-item {
  display: flex;
  gap: 12px;
  position: relative;
  padding-bottom: 18px;
}

.flow-item:last-child {
  padding-bottom: 0;
}

.flow-item::before {
  content: '';
  position: absolute;
  left: 14px;
  top: 30px;
  bottom: 2px;
  width: 2px;
  background: #e6e8f0;
}

.flow-item:last-child::before {
  display: none;
}

.flow-no {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  background: #e6e8f0;
  color: #94a3b8;
  z-index: 1;
}

.flow-item.done .flow-no {
  background: #d1fae5;
  color: #059669;
}

.flow-item.now .flow-no {
  background: #2563eb;
  color: #fff;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.14);
}

.flow-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
}

.flow-sub {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
  line-height: 1.6;
}

.review-summary {
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 7px 0;
  border-bottom: 1px dashed #e6e8f0;
  font-size: 13px;
}

.summary-row:last-child {
  border-bottom: none;
}

.summary-row .k {
  color: #94a3b8;
  flex-shrink: 0;
}

.summary-row .v {
  color: #1e293b;
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.review-note {
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  margin-top: 14px;
}

.success-title {
  color: #059669;
}

.done-hero {
  text-align: center;
  padding: 8px 0 18px;
}

.ok-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  margin: 0 auto 12px;
  background: #d1fae5;
  color: #059669;
  display: flex;
  align-items: center;
  justify-content: center;
}

.done-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.done-sub {
  font-size: 13px;
  color: #64748b;
  margin-top: 6px;
}

.auto-approve-note {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 6px 12px;
  border-radius: 999px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  font-size: 12px;
}

.init-steps {
  display: grid;
  gap: 10px;
}

.init-step {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 12px;
  padding: 13px 16px;
}

.init-icon-tile {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.18);
}

.init-icon-tile.tone-0 {
  background: linear-gradient(135deg, #1d4ed8, #60a5fa);
}

.init-icon-tile.tone-1 {
  background: linear-gradient(135deg, #2563eb, #38bdf8);
}

.init-icon-tile.tone-2 {
  background: linear-gradient(135deg, #0284c7, #38bdf8);
}

.init-icon-tile.tone-3 {
  background: linear-gradient(135deg, #3b82f6, #93c5fd);
}

.init-icon-tile.tone-4 {
  background: linear-gradient(135deg, #1e40af, #3b82f6);
}

.init-info {
  flex: 1;
  min-width: 0;
}

.init-label {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
}

.init-desc {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}

.init-status {
  font-size: 12px;
  color: #b45309;
  background: #fef3c7;
  padding: 3px 10px;
  border-radius: 999px;
  flex-shrink: 0;
}

.ready-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 22px;
}

.guide-state-card {
  background: #fff;
  border-radius: 16px;
  padding: 56px 20px;
  text-align: center;
  color: #64748b;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.06);
}

.guide-state-card p {
  margin: 14px 0 0;
  font-size: 14px;
}

.error-title {
  color: #b45309;
}

.state-actions {
  margin-top: 18px;
  display: flex;
  justify-content: center;
  gap: 10px;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .inline-selects {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .card-head {
    flex-direction: column;
  }

  .guide-nav {
    padding: 0 16px;
  }
}
</style>
