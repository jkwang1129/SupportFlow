<template>
  <div class="auth-page">
    <el-card class="auth-card" :body-style="{ padding: '28px 38px 24px' }">
      <!-- 品牌 -->
      <div class="auth-brand">
        <img class="auth-logo" src="/supportflow-mark.svg" alt="SupportFlow" />
        <div>
          <div class="auth-title">SupportFlow 智能客服管理平台</div>
          <div class="auth-subtitle">SUPPORTFLOW CUSTOMER SERVICE</div>
        </div>
      </div>

      <div class="auth-head">
        <div class="auth-head-title">注册企业账号</div>
        <div class="auth-head-sub">注册后将进入运营审核，审核通过即可开通SupportFlow 客服</div>
      </div>

      <!-- 注册成功 -->
      <div v-if="registered" class="register-success">
        <div class="success-icon">
          <el-icon :size="44" color="#10b981"><CircleCheckFilled /></el-icon>
        </div>
        <div class="success-title">注册成功 🎉</div>
        <div class="success-sub">企业账号已创建，提交审核通过后即可开通SupportFlow 客服</div>
        <div class="success-steps">
          <div class="step">
            <span class="step-no done">1</span>
            <div>
              <div class="step-title">账号注册成功</div>
              <div class="step-sub">企业账号已创建</div>
            </div>
          </div>
          <div class="step">
            <span class="step-no">2</span>
            <div>
              <div class="step-title">完善企业信息</div>
              <div class="step-sub">营业执照、注册地址、法人等专业信息</div>
            </div>
          </div>
          <div class="step">
            <span class="step-no">3</span>
            <div>
              <div class="step-title">平台审核</div>
              <div class="step-sub">审核通过后自动开通，可开始接待客户</div>
            </div>
          </div>
        </div>
        <el-button type="primary" class="block-btn" size="large" @click="goLogin">
          返回登录
        </el-button>
      </div>

      <!-- 注册表单 -->
      <el-form
        v-else
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
      >
        <div class="section-tip">企业信息</div>
        <el-form-item label="企业名称" prop="companyName">
          <el-input
            v-model.trim="form.companyName"
            placeholder="例：杭州云智网络科技有限公司"
            maxlength="128"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="所属行业" prop="industry">
            <el-select v-model="form.industry" placeholder="请选择行业" class="grid-item">
              <el-option v-for="item in industryOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="团队规模" prop="scale">
            <el-select v-model="form.scale" placeholder="请选择规模" class="grid-item">
              <el-option v-for="item in scaleOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
        </div>

        <div class="section-tip">管理员账号</div>
        <el-form-item label="联系人姓名" prop="contactName">
          <el-input v-model.trim="form.contactName" placeholder="姓名" maxlength="64" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="手机号" prop="phone">
            <el-input v-model.trim="form.phone" placeholder="11 位手机号" maxlength="11" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input
              v-model.trim="form.email"
              placeholder="name@company.com"
              maxlength="128"
            />
          </el-form-item>
        </div>
        <el-form-item label="设置密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="不少于 8 位，需同时包含字母与数字"
            maxlength="64"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-form-item label="验证码" prop="captchaCode">
          <div class="captcha-row">
            <el-input
              v-model.trim="form.captchaCode"
              placeholder="输入右侧字符"
              maxlength="4"
              :prefix-icon="Key"
            />
            <img
              class="captcha-img"
              :src="captchaImage"
              alt="验证码"
              title="看不清？点击刷新"
              @click="refreshCaptcha"
            />
          </div>
        </el-form-item>
        <!-- 平台开了「注册需手机 / 邮箱双重验证」时才要求这一栏 -->
        <el-form-item v-if="policy.requireDoubleVerify" label="邮箱验证码" prop="verifyCode">
          <div class="captcha-row">
            <el-input
              v-model.trim="form.verifyCode"
              placeholder="6 位数字验证码"
              maxlength="6"
              :prefix-icon="Key"
              autocomplete="one-time-code"
            />
            <el-button
              class="code-btn"
              :disabled="codeCountdown > 0 || sendingCode"
              :loading="sendingCode"
              @click="sendRegisterCode"
            >
              {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
          <div class="code-hint">
            验证码会发到上面填写的邮箱，5 分钟内有效。
            <span v-if="debugCode" class="code-debug">本地联调验证码：{{ debugCode }}</span>
          </div>
        </el-form-item>
        <el-alert
          v-if="policy.allowRegister === false"
          type="warning"
          show-icon
          :closable="false"
          class="mb-12"
          title="平台当前未开放企业注册"
          description="请联系平台运营开通后再试；已注册的账号可以直接登录。"
        />
        <div class="agreement">
          <el-checkbox v-model="agreed" class="agree-check">
            <span class="agree-text">我已阅读并同意</span>
            <a
              class="agree-link"
              href="/legal.html?t=terms"
              target="_blank"
              rel="noopener"
              @click.stop
            >《服务协议》</a>
            <span class="agree-text">与</span>
            <a
              class="agree-link"
              href="/legal.html?t=privacy"
              target="_blank"
              rel="noopener"
              @click.stop
            >《隐私政策》</a>
            <span class="agree-text">，并确认企业信息真实有效</span>
          </el-checkbox>
        </div>
        <el-button
          type="primary"
          class="block-btn"
          size="large"
          :loading="loading"
          @click="handleRegister"
        >
          提交注册申请
        </el-button>
      </el-form>

      <div v-if="!registered" class="auth-foot">
        已有账号？
        <router-link class="auth-link" to="/login">直接登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheckFilled, Key, Lock } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { captchaApi, registerApi, registerCodeApi, registerPolicyApi } from '../../api/user'

const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const registered = ref(false)
const agreed = ref(false)

const captchaId = ref('')
const captchaImage = ref('')

/** 注册策略：是否开放注册、是否要求邮箱双重验证（页面据此显示验证码栏） */
const policy = reactive({ allowRegister: true, requireDoubleVerify: false })
const sendingCode = ref(false)
const codeCountdown = ref(0)
const debugCode = ref('')
let codeTimer: number | undefined

const form = reactive({
  companyName: '',
  industry: '',
  scale: '',
  contactName: '',
  phone: '',
  email: '',
  password: '',
  captchaCode: '',
  verifyCode: '',
})

const industryOptions = ['电商零售', '企业服务', '金融保险', '教育', '智能硬件', '其他']
const scaleOptions = ['10 人以内', '10~50 人', '50~200 人', '200 人以上']

const rules: FormRules = {
  companyName: [{ required: true, message: '请输入企业名称', trigger: 'blur' }],
  industry: [{ required: true, message: '请选择所属行业', trigger: 'change' }],
  scale: [{ required: true, message: '请选择团队规模', trigger: 'change' }],
  contactName: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/,
      message: '密码需不少于 8 位，且同时包含字母和数字',
      trigger: 'blur',
    },
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  verifyCode: [{ required: true, message: '请输入邮箱验证码', trigger: 'blur' }],
}

onMounted(async () => {
  await refreshCaptcha()
  try {
    Object.assign(policy, await registerPolicyApi())
    // 策略要求双重验证时，「邮箱验证码」才是必填项（否则会让用户白填）
    rules.verifyCode = policy.requireDoubleVerify
      ? [{ required: true, message: '请输入邮箱验证码', trigger: 'blur' }]
      : []
  } catch {
    // 拿不到策略就按"不需要验证码"走，后端该拒还是会拒
  }
})

onUnmounted(stopCodeCountdown)

/** 获取邮箱验证码：要先填对图形验证码（防止拿这个接口给任意邮箱发信） */
async function sendRegisterCode(): Promise<void> {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    ElMessage.warning('请先填写正确的邮箱')
    return
  }
  if (!form.captchaCode) {
    ElMessage.warning('请先填写图形验证码')
    return
  }
  sendingCode.value = true
  try {
    const res = await registerCodeApi({
      email: form.email,
      captchaId: captchaId.value,
      captchaCode: form.captchaCode,
    })
    debugCode.value = res.debugCode || ''
    ElMessage.success(`验证码已发送到 ${res.maskedEmail}`)
    startCodeCountdown()
    // 图形验证码是一次性的：发码用掉了，换一张新的给用户
    form.captchaCode = ''
    await refreshCaptcha()
  } catch {
    refreshCaptcha()
    form.captchaCode = ''
  } finally {
    sendingCode.value = false
  }
}

function startCodeCountdown(): void {
  stopCodeCountdown()
  codeCountdown.value = 60
  codeTimer = window.setInterval(() => {
    codeCountdown.value -= 1
    if (codeCountdown.value <= 0) {
      stopCodeCountdown()
    }
  }, 1000)
}

function stopCodeCountdown(): void {
  if (codeTimer) {
    window.clearInterval(codeTimer)
    codeTimer = undefined
  }
  codeCountdown.value = 0
}

async function refreshCaptcha() {
  try {
    const res = await captchaApi()
    captchaId.value = res.captchaId
    captchaImage.value = res.imageBase64
  } catch {
    captchaImage.value = ''
  }
}

async function handleRegister() {
  if (!agreed.value) {
    ElMessage.warning('请先阅读并同意《服务协议》与《隐私政策》')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (policy.allowRegister === false) {
    ElMessage.warning('平台当前未开放企业注册，请联系平台运营')
    return
  }
  loading.value = true
  try {
    await registerApi({
      companyName: form.companyName,
      industry: form.industry,
      scale: form.scale,
      contactName: form.contactName,
      phone: form.phone,
      email: form.email,
      password: form.password,
      captchaId: captchaId.value,
      captchaCode: form.captchaCode,
      verifyCode: policy.requireDoubleVerify ? form.verifyCode : undefined,
    })
    registered.value = true
  } catch {
    refreshCaptcha()
    form.captchaCode = ''
  } finally {
    loading.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>

<style scoped>
.auth-page {
  height: 100%;
  min-height: 720px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow-y: auto;
  background:
    radial-gradient(900px 500px at 12% -10%, rgba(59, 130, 246, 0.28), transparent 60%),
    radial-gradient(800px 500px at 105% 10%, rgba(14, 165, 233, 0.22), transparent 55%),
    #f6f7f9;
}

.auth-card {
  width: 500px;
  margin: 24px auto;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  color: #2563eb;
}

.auth-logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.22);
}

.auth-title {
  font-size: 19px;
  font-weight: 700;
  color: #0f172a;
}

.auth-subtitle {
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 1.5px;
  margin-top: 2px;
}

.auth-head {
  margin-bottom: 14px;
}

.auth-head-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.auth-head-sub {
  font-size: 13px;
  color: #94a3b8;
  margin-top: 6px;
}

.section-tip {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin: 4px 0 10px;
  padding-left: 8px;
  border-left: 3px solid #2563eb;
  line-height: 1;
}

.auth-card :deep(.el-form-item) {
  margin-bottom: 15px;
}

.auth-card :deep(.el-form-item__label) {
  margin-bottom: 4px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.grid-item {
  width: 100%;
}

.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

/* 邮箱验证码：输入框 + 获取验证码（与图形验证码那一行同一套排版） */
.code-btn {
  flex-shrink: 0;
  min-width: 118px;
}

.code-hint {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.7;
  margin-top: 8px;
}

.code-debug {
  color: #d97706;
  font-weight: 600;
}

.mb-12 {
  margin-bottom: 12px;
}

.captcha-img {
  width: 118px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 8px;
  border: 1px solid #d8dee6;
  cursor: pointer;
  object-fit: cover;
  background: #f1f5f9;
}

.agreement {
  margin-bottom: 12px;
}

.agree-check {
  height: auto;
  align-items: flex-start;
  color: #64748b;
}

.agree-check :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.6;
  font-size: 12px;
  color: #64748b;
  vertical-align: middle;
}

.agree-text {
  color: #64748b;
}

.agree-link {
  color: #2563eb;
  text-decoration: none;
  font-weight: 600;
}

.agree-link:hover {
  color: #1d4ed8;
  text-decoration: underline;
}

.block-btn {
  width: 100%;
}

.auth-foot {
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
  color: #94a3b8;
}

.auth-link {
  color: #2563eb;
  font-weight: 600;
}

.register-success {
  text-align: center;
  padding: 6px 0;
}

.success-icon {
  margin-bottom: 8px;
}

.success-title {
  font-size: 19px;
  font-weight: 700;
  color: #0f172a;
}

.success-sub {
  color: #64748b;
  font-size: 13px;
  margin: 8px 0 20px;
}

.success-steps {
  text-align: left;
  margin-bottom: 20px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px dashed #e2e8f0;
}

.step:last-child {
  border-bottom: none;
}

.step-no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.step-no.done {
  background: #d1fae5;
  color: #059669;
}

.step-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.step-sub {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}
</style>
