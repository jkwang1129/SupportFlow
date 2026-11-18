import { request } from './request'
import type {
  CaptchaResult,
  ChangePasswordParams,
  LoginParams,
  LoginResult,
  MeResult,
  RegisterParams,
  RegisterCodeResult,
  RegisterPolicy,
  RegisterResult,
  ResetCodeResult,
  ResetPasswordParams,
  SendResetCodeParams,
  TwoFactorLoginParams,
} from '../types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/** 开发环境 Mock：后端联调时置 VITE_USE_MOCK=false */
function mockLogin(params: LoginParams): Promise<LoginResult> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (params.account && params.password.length >= 6) {
        resolve({
          token: 'mock-token-' + Date.now(),
          userId: 1,
          userNo: 'U00000000000000001',
          name: '张伟',
          userType: 1,
          tenantCode: 'T202609020001',
        })
      } else {
        reject(new Error('账号或密码错误'))
      }
    }, 400)
  })
}

/** 获取图形验证码（登录 / 注册共用） */
export function captchaApi(): Promise<CaptchaResult> {
  return request<CaptchaResult>({
    url: '/user/auth/captcha',
    method: 'get',
  })
}

/** 企业账号注册 */
export function registerApi(data: RegisterParams): Promise<RegisterResult> {
  return request<RegisterResult>({
    url: '/user/auth/register',
    method: 'post',
    data,
  })
}

/** 注册策略（公开）：注册页用来决定要不要显示「邮箱验证码」那一栏 */
export function registerPolicyApi(): Promise<RegisterPolicy> {
  return request<RegisterPolicy>({ url: '/user/auth/register-policy', method: 'get', silent: true })
}

/** 注册：发送邮箱验证码（平台开了「注册需手机 / 邮箱双重验证」时才有这一步） */
export function registerCodeApi(data: {
  email: string
  captchaId: string
  captchaCode: string
}): Promise<RegisterCodeResult> {
  return request<RegisterCodeResult>({
    url: '/user/auth/register/code',
    method: 'post',
    data,
  })
}

/** 账号密码登录 */
export function loginApi(data: LoginParams): Promise<LoginResult> {
  if (USE_MOCK) {
    return mockLogin(data)
  }
  return request<LoginResult>({
    url: '/user/auth/login',
    method: 'post',
    data,
  })
}

/**
 * 登录第二步：邮箱验证码换 token（平台开了「强制双因素认证」时才有这一步）。
 *
 * <p>挑战号来自第一步的响应，不需要再传账号密码。</p>
 */
export function loginTwoFactorApi(data: TwoFactorLoginParams): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/user/auth/login/2fa',
    method: 'post',
    data,
  })
}

/** 登录第二步：重发验证码（挑战号不变，60 秒内不能重复发） */
export function resendTwoFactorApi(challengeId: string): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/user/auth/login/2fa/resend',
    method: 'post',
    data: { challengeId },
  })
}

/** 当前登录用户（恢复会话 / 判断引导状态） */
export function meApi(): Promise<MeResult> {
  return request<MeResult>({
    url: '/user/auth/me',
    method: 'get',
  })
}

/** 退出登录 */
export function logoutApi(): Promise<void> {
  if (USE_MOCK) {
    return Promise.resolve()
  }
  return request<void>({
    url: '/auth/logout',
    method: 'post',
  })
}

/** 修改当前登录用户密码 */
export function changePasswordApi(data: ChangePasswordParams): Promise<void> {
  return request<void>({
    url: '/user/auth/password',
    method: 'put',
    data,
  })
}

/** 忘记密码：发送邮箱验证码 */
export function sendResetCodeApi(data: SendResetCodeParams): Promise<ResetCodeResult> {
  return request<ResetCodeResult>({
    url: '/user/auth/password/reset-code',
    method: 'post',
    data,
  })
}

/** 忘记密码：校验验证码并重置密码 */
export function resetPasswordApi(data: ResetPasswordParams): Promise<void> {
  return request<void>({
    url: '/user/auth/password/reset',
    method: 'post',
    data,
  })
}

/** 刷新访问令牌（审核通过后租户编码变更时调用） */
export function refreshTokenApi(): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/user/auth/refresh-token',
    method: 'post',
  })
}
