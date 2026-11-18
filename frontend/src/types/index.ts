/** 统一后端响应结构（与后端 ApiResponse 对齐） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  requestId?: string
  timestamp?: number
}

/** 登录参数 */
export interface LoginParams {
  account: string
  password: string
  captchaId: string
  captchaCode: string
}

/** 登录返回 */
export interface LoginResult {
  /** 需要二次验证时为 null（此时不能进后台，得先拿验证码换 token） */
  token: string | null
  userId: number
  userNo: string
  name: string
  userType: number
  tenantCode?: string
  avatar?: string
  /** 是否还需要二次验证（安全中心开了「强制双因素认证」且账号在覆盖范围内） */
  needTwoFactor?: boolean | null
  /** 二次验证的挑战号（5 分钟内有效，第二步与重发都用它） */
  challengeId?: string | null
  /** 验证码发到哪个邮箱（掩码后的） */
  maskedEmail?: string | null
  /** 验证码有效期（秒） */
  expireSeconds?: number | null
  /** 本地联调：未配 SMTP 时后端带回的明文验证码（生产为空） */
  debugCode?: string | null
}

/** 登录第二步（双因素）入参 */
export interface TwoFactorLoginParams {
  challengeId: string
  code: string
}

/** 图形验证码 */
export interface CaptchaResult {
  captchaId: string
  imageBase64: string
  debugCode?: string
}

/** 企业账号注册参数（与登录页「注册企业账号」表单一致） */
export interface RegisterParams {
  companyName: string
  industry: string
  scale: string
  contactName: string
  phone: string
  email: string
  password: string
  captchaId: string
  captchaCode: string
  /** 邮箱验证码：平台开了「注册需手机 / 邮箱双重验证」时才必填 */
  verifyCode?: string
}

/** 注册策略（公开只读）：注册页据此决定要不要显示邮箱验证码那一栏 */
export interface RegisterPolicy {
  allowRegister: boolean
  requireDoubleVerify: boolean
}

/** 注册验证码发送结果 */
export interface RegisterCodeResult {
  maskedEmail: string
  expireSeconds: number
  /** 本地联调：没配 SMTP 时后端带回的明文验证码（生产为空） */
  debugCode?: string | null
}

/** 企业账号注册返回 */
export interface RegisterResult {
  userId: number
  userNo: string
  contactName: string
  phone: string
  email: string
  enterpriseId: number
  enterpriseCode: string
  enterpriseStatus: number
  tenantCode: string
  userType: number
}

/** /user/auth/me 返回的登录用户信息（JWT 载荷） */
export interface MeResult {
  userId: number
  userNo: string
  name: string
  userType: number
  tenantCode?: string
  /** 当前账号的角色编码（平台账号=平台角色；企业账号=企业角色） */
  roles?: string[]
  /**
   * 平台菜单权限（perm_code = 前端路由 path）。
   *
   * 出现 "*" 表示超管（没有绑定任何平台角色的平台账号）；
   * 企业账号不下发权限，菜单仍按 userType 与企业角色判断。
   */
  permissions?: string[]
}

/** 企业开通引导状态 */
export interface EnterpriseGuideState {
  enterpriseId: number
  enterpriseCode: string
  /** PENDING_PROFILE / PENDING_REVIEW / REJECTED / APPROVED */
  stage: string
  companyName?: string
  industry?: string
  scale?: string
  licenseNo?: string
  registerAddress?: string
  legalPerson?: string
  licenseFileId?: string | null
  contactName?: string
  contactPhone?: string
  contactEmail?: string
  applyNo?: string
  versionNo?: number | null
  rejectReason?: string
  submitTime?: string
  tenantCode?: string
  /** 是否由平台策略自动放行（关掉人工审核 / 企业邮箱域名命中白名单） */
  autoApproved?: boolean
}

/** 企业资料提交参数 */
export interface EnterpriseProfilePayload {
  companyName: string
  industry: string
  scale: string
  licenseNo: string
  registerAddress: string
  legalPerson: string
  licenseFileId: string
  contactName: string
  contactPhone: string
  contactEmail: string
}

/** 营业执照上传结果 */
export interface LicenseUploadResult {
  fileId: string
  fileName: string
  fileSize: number
}

/** 修改密码参数 */
export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

/** 忘记密码：发送邮箱验证码参数 */
export interface SendResetCodeParams {
  account: string
}

/**
 * 忘记密码：发送验证码结果。
 * 出于安全考虑，账号不存在时后端返回空 maskedEmail（防账号枚举）。
 */
export interface ResetCodeResult {
  /** 脱敏后的收件邮箱，例如 zh***@example.com */
  maskedEmail: string
  /** 验证码有效期（分钟） */
  expireMinutes: number
}

/** 忘记密码：校验验证码并重置密码参数 */
export interface ResetPasswordParams {
  account: string
  code: string
  newPassword: string
}
