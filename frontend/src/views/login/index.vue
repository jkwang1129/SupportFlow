<template>
  <div class="auth-page">
    <el-card class="auth-card" :body-style="{ padding: '34px 38px 30px' }">
      <!-- 品牌 -->
      <div class="auth-brand">
        <img class="auth-logo" src="/supportflow-mark.svg" alt="SupportFlow" />
        <div>
          <div class="auth-title">SupportFlow 智能客服管理平台</div>
          <div class="auth-subtitle">SUPPORTFLOW CUSTOMER SERVICE</div>
        </div>
      </div>

      <div class="auth-head">
        <div class="auth-head-title">{{ step === 'twoFactor' ? '二次验证' : '登录' }}</div>
        <div class="auth-head-sub">
          {{ step === 'twoFactor'
            ? `为了账号安全，请输入发送到 ${maskedEmail} 的 6 位验证码`
            : '登录管理后台，继续为客户提供优质服务' }}
        </div>
      </div>

      <!-- 第一步：账号密码 + 图形验证码 -->
      <el-form
        v-if="step === 'password'"
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="账号" prop="account">
          <el-input
            v-model.trim="form.account"
            placeholder="手机号 / 邮箱 / 用户编号"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password" class="password-item">
          <template #label>
            <span class="label-line">
              <span>密码</span>
              <router-link class="forgot-link" :to="forgotPasswordTo">忘记密码？</router-link>
            </span>
          </template>
          <el-input
            v-model="form.password"
            type="password"
            show-password
            aria-label="密码"
            placeholder="请输入密码"
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
        <el-button
          type="primary"
          class="block-btn"
          size="large"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>

      <!-- 第二步：邮箱验证码（平台开了「强制双因素认证」才有这一步） -->
      <el-form
        v-else
        ref="twoFactorFormRef"
        :model="twoFactorForm"
        :rules="twoFactorRules"
        label-position="top"
        size="large"
        @keyup.enter="handleTwoFactorLogin"
      >
        <el-form-item label="邮箱验证码" prop="code">
          <div class="code-row">
            <el-input
              v-model.trim="twoFactorForm.code"
              placeholder="6 位数字验证码"
              maxlength="6"
              :prefix-icon="Key"
              autocomplete="one-time-code"
            />
            <el-button
              class="code-btn"
              :disabled="countdown > 0 || resending"
              :loading="resending"
              @click="handleResend"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '重新发送' }}
            </el-button>
          </div>
          <div class="code-hint">
            验证码 {{ expireSeconds ? Math.round(expireSeconds / 60) : 5 }} 分钟内有效，请勿转发给他人。
            <span v-if="debugCode" class="code-debug">本地联调验证码：{{ debugCode }}</span>
          </div>
        </el-form-item>
        <el-button
          type="primary"
          class="block-btn"
          size="large"
          :loading="loading"
          @click="handleTwoFactorLogin"
        >
          验证并登录
        </el-button>
        <el-button class="block-btn ghost-btn" size="large" @click="backToPassword">
          返回上一步
        </el-button>
      </el-form>

      <div class="auth-foot">
        还没有账号？
        <router-link class="auth-link" to="/register">注册企业账号</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Key, Lock, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { captchaApi, resendTwoFactorApi } from '../../api/user'
import { useUserStore } from '../../stores/user'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const twoFactorFormRef = ref<FormInstance>()
const loading = ref(false)
const resending = ref(false)

const captchaId = ref('')
const captchaImage = ref('')

/** 登录两步：password = 账号密码；twoFactor = 邮箱验证码 */
const step = ref<'password' | 'twoFactor'>('password')
const challengeId = ref('')
const maskedEmail = ref('')
const expireSeconds = ref(300)
const debugCode = ref('')
const countdown = ref(0)
let countdownTimer: number | undefined

const form = reactive({
  account: '',
  password: '',
  captchaCode: '',
})

const twoFactorForm = reactive({
  code: '',
})

const rules: FormRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

const twoFactorRules: FormRules = {
  code: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码是 6 位数字', trigger: 'blur' },
  ],
}

/** 账号框里填的是邮箱时，带到找回密码页自动填充 */
const forgotPasswordTo = computed(() => {
  const account = form.account.trim()
  return EMAIL_PATTERN.test(account)
    ? { path: '/forgot-password', query: { account } }
    : { path: '/forgot-password' }
})

onMounted(refreshCaptcha)

async function refreshCaptcha() {
  try {
    const res = await captchaApi()
    captchaId.value = res.captchaId
    captchaImage.value = res.imageBase64
  } catch {
    captchaImage.value = ''
  }
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await userStore.login({
      account: form.account,
      password: form.password,
      captchaId: captchaId.value,
      captchaCode: form.captchaCode,
    })
    // 平台开了「强制双因素认证」：这一步还没登录成功，去第二步拿验证码换 token
    if (res?.needTwoFactor) {
      enterTwoFactorStep(res)
      return
    }
    ElMessage.success('登录成功')
    // 平台管理员固定进后台；企业成员统一走 /entry 做角色分流，
    // 普通客服不会进入 /guide，也不会触发企业资料接口
    router.push(userStore.userType === 1 ? '/dashboard' : '/entry')
  } catch {
    refreshCaptcha()
    form.captchaCode = ''
  } finally {
    loading.value = false
  }
}

/** 进入第二步：记住挑战号、掩码邮箱与有效期，并开始重发倒计时 */
function enterTwoFactorStep(res: {
  challengeId?: string | null
  maskedEmail?: string | null
  expireSeconds?: number | null
  debugCode?: string | null
}): void {
  challengeId.value = res.challengeId || ''
  maskedEmail.value = res.maskedEmail || '账号绑定邮箱'
  expireSeconds.value = Number(res.expireSeconds || 300)
  debugCode.value = res.debugCode || ''
  twoFactorForm.code = ''
  step.value = 'twoFactor'
  startCountdown()
  ElMessage.success(`验证码已发送到 ${maskedEmail.value}`)
}

/** 第二步：验证码换 token */
async function handleTwoFactorLogin(): Promise<void> {
  const valid = await twoFactorFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.loginTwoFactor({ challengeId: challengeId.value, code: twoFactorForm.code })
    ElMessage.success('登录成功')
    router.push(userStore.userType === 1 ? '/dashboard' : '/entry')
  } catch {
    twoFactorForm.code = ''
  } finally {
    loading.value = false
  }
}

/** 重发验证码（挑战号不变；60 秒倒计时结束后才能点） */
async function handleResend(): Promise<void> {
  if (countdown.value > 0 || resending.value) return
  resending.value = true
  try {
    const res = await resendTwoFactorApi(challengeId.value)
    maskedEmail.value = res.maskedEmail || maskedEmail.value
    debugCode.value = res.debugCode || ''
    twoFactorForm.code = ''
    startCountdown()
    ElMessage.success('验证码已重新发送')
  } catch {
    // 失败原因（已发过 / 过期）由 request 层提示，这里只需要不打断页面
  } finally {
    resending.value = false
  }
}

/** 回到第一步：挑战号作废，重新走账号密码 */
function backToPassword(): void {
  stopCountdown()
  step.value = 'password'
  challengeId.value = ''
  maskedEmail.value = ''
  debugCode.value = ''
  twoFactorForm.code = ''
  form.captchaCode = ''
  refreshCaptcha()
}

function startCountdown(): void {
  stopCountdown()
  countdown.value = 60
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown(): void {
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
    countdownTimer = undefined
  }
  countdown.value = 0
}

onUnmounted(stopCountdown)
</script>

<style scoped>
.auth-page {
  height: 100%;
  min-height: 640px;
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
  width: 440px;
  margin: 24px auto;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
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
  margin-bottom: 18px;
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

.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.password-item :deep(.el-form-item__label) {
  display: flex;
  align-items: center;
  width: 100%;
}

.label-line {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.forgot-link {
  font-size: 13px;
  font-weight: 600;
  color: #2563eb;
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

.block-btn {
  width: 100%;
}

/* 第二步：验证码输入 + 重发按钮（与图形验证码那一行同一套排版） */
.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

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

.ghost-btn {
  margin: 10px 0 0;
}

.auth-foot {
  text-align: center;
  margin-top: 18px;
  font-size: 13px;
  color: #94a3b8;
}

.auth-link {
  color: #2563eb;
  font-weight: 600;
}
</style>
