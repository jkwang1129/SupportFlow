import { defineStore } from 'pinia'
import { loginApi, loginTwoFactorApi, logoutApi, meApi, refreshTokenApi } from '../api/user'
import { clearToken, getToken, setTenantCode, setToken } from '../utils/auth'
import type { LoginParams, LoginResult, TwoFactorLoginParams } from '../types'

interface UserState {
  token: string
  name: string
  userId: number
  userNo: string
  userType: number
  tenantCode: string
  /** 当前账号的角色编码（展示用） */
  roles: string[]
  /** 平台菜单权限（perm_code = 路由 path；"*" = 超管） */
  permissions: string[]
  /** /me 是否已经回来过：没回来之前不隐藏菜单，避免刷新页面时菜单闪一下空白 */
  permsLoaded: boolean
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    token: getToken(),
    name: '',
    userId: 0,
    userNo: '',
    userType: 0,
    tenantCode: '',
    roles: [],
    permissions: [],
    permsLoaded: false,
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    /**
     * 平台侧菜单是否可见。
     *
     * <p>三条规则：① 权限还没拉回来（刚刷新）→ 先显示，别闪空白；
     * ② 拿到 "*" → 超管，全都能看；③ 否则按 perm_code 精确匹配。</p>
     */
    hasPerm: (state) => (path: string) => {
      if (!state.permsLoaded) return true
      return state.permissions.includes('*') || state.permissions.includes(path)
    },
  },
  actions: {
    /**
     * 第一步登录。
     *
     * <p>返回原始结果：`needTwoFactor=true` 时**还没登录成功**（后端没发 token），
     * 调用方要拿 challengeId 走第二步（{@link loginTwoFactor}）；否则这里已经把登录态落好。</p>
     */
    async login(params: LoginParams): Promise<LoginResult> {
      const res = await loginApi(params)
      if (res?.needTwoFactor) {
        return res
      }
      this.applyLoginResult(res)
      return res
    },
    /** 第二步：验证码换 token，成功后登录态就位 */
    async loginTwoFactor(params: TwoFactorLoginParams): Promise<LoginResult> {
      const res = await loginTwoFactorApi(params)
      this.applyLoginResult(res)
      return res
    },
    applyLoginResult(res: LoginResult) {
      if (!res.token) {
        // 防御：拿不到 token 就不该落登录态（正常路径不会走到这里）
        throw new Error('登录未完成：缺少访问令牌')
      }
      this.token = res.token
      this.userId = res.userId
      this.userNo = res.userNo
      this.name = res.name
      this.userType = res.userType
      this.tenantCode = res.tenantCode || ''
      setToken(res.token)
      setTenantCode(res.tenantCode || '')
    },
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 后端会话接口暂缺时也不阻塞本地退出
      } finally {
        this.reset()
      }
    },
    /** 恢复会话：调用 /me 刷新当前用户信息 */
    async fetchProfile() {
      const me = await meApi()
      this.userId = me.userId
      this.userNo = me.userNo
      this.name = me.name
      this.userType = me.userType
      this.tenantCode = me.tenantCode || ''
      this.roles = me.roles ?? []
      this.permissions = me.permissions ?? []
      this.permsLoaded = true
      setTenantCode(me.tenantCode || '')
    },
    /** 刷新 token：以后端数据库中的最新租户编码重新签发 */
    async refreshToken() {
      const res = await refreshTokenApi()
      this.applyLoginResult(res)
    },
    reset() {
      this.token = ''
      this.name = ''
      this.userId = 0
      this.userNo = ''
      this.userType = 0
      this.tenantCode = ''
      this.roles = []
      this.permissions = []
      this.permsLoaded = false
      clearToken()
    },
  },
})
