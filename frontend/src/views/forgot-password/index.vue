<template>
  <div class="auth-page">
    <el-card class="auth-card" :body-style="{ padding: '32px 38px 26px' }">
      <!-- 品牌 -->
      <div class="auth-brand">
        <img class="auth-logo" src="/supportflow-mark.svg" alt="SupportFlow" />
        <div>
          <div class="auth-title">SupportFlow 智能客服管理平台</div>
          <div class="auth-subtitle">SUPPORTFLOW CUSTOMER SERVICE</div>
        </div>
      </div>

      <div class="auth-head">
        <div class="auth-head-title">找回密码</div>
        <div class="auth-head-sub">通过账号绑定的邮箱验证身份，快速重置登录密码</div>
      </div>

      <!-- 步骤条 -->
      <el-steps v-if="!done" class="reset-steps" :active="step - 1" align-center finish-status="success">
        <el-step title="验证身份" description="获取邮箱验证码" />
        <el-step title="重置密码" description="设置新的登录密码" />
      </el-steps>

      <!-- 重置成功 -->
      <div v-if="done" class="reset-success">
        <div class="success-icon">
          <el-icon :size="46" color="#2563eb"><CircleCheckFilled /></el-icon>
        </div>
        <div class="success-title">密码重置成功</div>
        <div class="success-sub">请使用新密码重新登录，建议妥善保管并定期更换</div>
        <el-button type="primary" class="block-btn" size="large" @click="goLogin">
          返回登录
        </el-button>
      </div>

      <template v-else>
        <!-- 第一步：验证身份 -->
        <el-form
          v-if="step === 1"
          ref="step1Ref"
          :model="step1Form"
          :rules="step1Rules"
          label-position="top"
          size="large"
          @keyup.enter="handleSendCode"
        >
          <el-form-item label="账号" prop="account">
            <el-input
              v-model.trim="step1Form.account"
              placeholder="手机号 / 邮箱 / 用户编号"
              :prefix-icon="User"
              clearable
            />
          </el-form-item>
          <el-button
            type="primary"
            class="block-btn"
            size="large"
            :loading="sending"
            @click="handleSendCode"
          >
            获取邮箱验证码
          </el-button>
        </el-form>

        <!-- 第二步：重置密码 -->
        <el-form
          v-else
          ref="step2Ref"
          :model="step2Form"
          :rules="step2Rules"
          label-position="top"
          size="large"
          @keyup.enter="handleReset"
        >
          <el-alert
            v-if="maskedEmail"
            class="reset-alert"
            type="info"
            :closable="false"
            show-icon
          >
            <template #title>
              验证码已发送至 <b>{{ maskedEmail }}</b>，{{ expireMinutes }} 分钟内有效
            </template>
          </el-alert>
          <el-alert
            v-else
            class="reset-alert"
            type="info"
            :closable="false"
            show-icon
          >
            <template #title>
              若该账号已绑定邮箱，验证码将发送至其关联邮箱，{{ expireMinutes }} 分钟内有效
            </template>
          </el-alert>

          <el-form-item label="邮箱验证码" prop="code">
            <div class="code-row">
              <el-input
                v-model.trim="step2Form.code"
                placeholder="请输入 6 位验证码"
                maxlength="6"
                :prefix-icon="Key"
              />
              <el-button
                class="code-btn"
                :disabled="countdown > 0"
                :loading="sending"
                @click="handleResend"
              >
                {{ countdown > 0 ? `${countdown}s 后重发` : '重新获取' }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="step2Form.newPassword"
              type="password"
              show-password
              placeholder="不少于 8 位，且包含字母和数字"
              :prefix-icon="Lock"
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="step2Form.confirmPassword"
              type="password"
              show-password
              placeholder="请再次输入新密码"
              :prefix-icon="Lock"
            />
          </el-form-item>
          <el-button
            type="primary"
            class="block-btn"
            size="large"
            :loading="submitting"
            @click="handleReset"
          >
            重置密码
          </el-button>
          <el-button class="block-btn back-btn" text @click="backToStep1">
            <el-icon><ArrowLeft /></el-icon>
            返回上一步
          </el-button>
        </el-form>
      </template>

      <div v-if="!done" class="auth-foot">
        想起密码了？
        <router-link class="auth-link" to="/login">返回登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CircleCheckFilled, Key, Lock, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { resetPasswordApi, sendResetCodeApi } from '../../api/user'

const router = useRouter()
const route = useRoute()

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const step = ref(1)
const done = ref(false)
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
const maskedEmail = ref('')
const expireMinutes = ref(10)

const step1Ref = ref<FormInstance>()
const step2Ref = ref<FormInstance>()

const step1Form = reactive({
  // 登录页填了邮箱时，带过来自动填充（手机号 / 用户编号不预填）
  account: routeAccount(),
})

const step2Form = reactive({
  code: '',
  newPassword: '',
  confirmPassword: '',
})

const step1Rules: FormRules = {
  account: [{ required: true, message: '请输入手机号 / 邮箱 / 用户编号', trigger: 'blur' }],
}

const step2Rules: FormRules = {
  code: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/,
      message: '密码需不少于 8 位，且同时包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== step2Form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

/** 从路由 query 取登录页带过来的邮箱（仅接受合法邮箱格式） */
function routeAccount(): string {
  const account = route.query.account
  return typeof account === 'string' && EMAIL_PATTERN.test(account) ? account : ''
}

let timer: ReturnType<typeof setInterval> | null = null

function startCountdown(seconds = 60) {
  countdown.value = seconds
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

/** 发送验证码（复用：第一步校验表单，第二步直接重发） */
async function sendCode(isResend: boolean) {
  sending.value = true
  try {
    const res = await sendResetCodeApi({ account: step1Form.account })
    maskedEmail.value = res.maskedEmail || ''
    expireMinutes.value = res.expireMinutes || 10
    if (isResend) {
      step2Form.code = ''
      ElMessage.success('验证码已重新发送')
    } else {
      step.value = 2
      ElMessage.success('验证码已发送，请查收邮箱')
    }
    startCountdown(60)
  } catch {
    // 错误提示由请求层统一处理
  } finally {
    sending.value = false
  }
}

/** 第一步：校验账号后发送邮箱验证码 */
async function handleSendCode() {
  const valid = await step1Ref.value?.validate().catch(() => false)
  if (!valid) return
  await sendCode(false)
}

/** 第二步：重新获取验证码 */
async function handleResend() {
  await sendCode(true)
}

/** 第二步：校验验证码并重置密码 */
async function handleReset() {
  const valid = await step2Ref.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await resetPasswordApi({
      account: step1Form.account,
      code: step2Form.code,
      newPassword: step2Form.newPassword,
    })
    done.value = true
  } catch {
    // 错误提示由请求层统一处理
  } finally {
    submitting.value = false
  }
}

function backToStep1() {
  step.value = 1
  step2Form.code = ''
}

function goLogin() {
  router.push('/login')
}
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
  width: 460px;
  margin: 24px auto;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
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

.reset-steps {
  margin-bottom: 22px;
}

.reset-steps :deep(.el-step__title) {
  font-size: 13px;
  font-weight: 600;
}

.reset-steps :deep(.el-step__description) {
  font-size: 12px;
}

.reset-steps :deep(.el-step__head.is-process) {
  color: #2563eb;
  border-color: #2563eb;
}

.reset-steps :deep(.el-step__title.is-process) {
  color: #2563eb;
}

.reset-alert {
  margin-bottom: 16px;
  border-radius: 10px;
}

.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.code-btn {
  flex-shrink: 0;
  min-width: 118px;
}

.reset-success {
  text-align: center;
  padding: 8px 0 4px;
}

.success-icon {
  margin-bottom: 14px;
}

.success-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.success-sub {
  font-size: 13px;
  color: #94a3b8;
  margin: 10px 0 22px;
  line-height: 1.7;
}

.block-btn {
  width: 100%;
}

.back-btn {
  width: 100%;
  margin: 10px 0 0;
  color: #64748b;
}

.auth-foot {
  text-align: center;
  margin-top: 16px;
  font-size: 13px;
  color: #94a3b8;
}

.auth-link {
  color: #2563eb;
  font-weight: 600;
}
</style>
