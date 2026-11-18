import { default as service, request } from '../request'

/**
 * 平台账号中心（用户管理）与平台角色权限。
 *
 * <p>两个页面都只对平台账号开放（后端每个接口都校验 user_type=1）。</p>
 */

/** 账号列表一行 */
export interface PlatformUserRow {
  user_id: string
  user_no: string
  name: string
  phone?: string | null
  email?: string | null
  /** 1-平台账号、2-企业账号 */
  user_type: number
  tenant_code: string
  /** 1-正常、2-停用 */
  status: number
  last_login_time?: string | null
  create_time?: string | null
  /** 角色 id（逗号分隔） */
  role_ids?: string | null
  /** 角色编码（逗号分隔） */
  role_codes?: string | null
  /** 角色名称（顿号分隔，可能多个角色） */
  role_names?: string | null
}

export interface PlatformUserPage {
  total: number
  page: number
  pageSize: number
  list: PlatformUserRow[]
}

export interface PlatformUserQuery {
  userType?: number
  status?: number
  tenantCode?: string
  keyword?: string
  page?: number
  pageSize?: number
}

export interface AssignableRole {
  id: string
  roleCode: string
  roleName: string
  roleType: number
}

export function fetchPlatformUsers(query: PlatformUserQuery = {}): Promise<PlatformUserPage> {
  return request<PlatformUserPage>({ url: '/user/platform/users', method: 'get', params: query })
}

/** 可分配角色：平台账号看平台角色，企业账号看该租户的企业角色 */
export function fetchAssignableRoles(tenantCode?: string): Promise<AssignableRole[]> {
  return request<AssignableRole[]>({
    url: '/user/platform/users/roles',
    method: 'get',
    params: { tenantCode },
  })
}

export function createPlatformUser(body: {
  userType: number
  name: string
  phone?: string
  email?: string
  tenantCode?: string
  password?: string
  roleIds?: Array<string | number>
}): Promise<{ userId: string; userNo: string; initialPassword: string }> {
  return request({ url: '/user/platform/users', method: 'post', data: body })
}

export function updatePlatformUser(
  userId: string,
  body: { name: string; phone?: string; email?: string },
): Promise<void> {
  return request({ url: `/user/platform/users/${userId}`, method: 'put', data: body })
}

/** 启用（1）/ 停用（2） */
export function updatePlatformUserStatus(userId: string, status: number): Promise<void> {
  return request({
    url: `/user/platform/users/${userId}/status`,
    method: 'post',
    params: { status },
  })
}

export function resetPlatformUserPassword(userId: string, password: string): Promise<void> {
  return request({
    url: `/user/platform/users/${userId}/password`,
    method: 'post',
    data: { password },
  })
}

/** 分配角色（全量覆盖） */
export function setPlatformUserRoles(userId: string, roleIds: Array<string | number>): Promise<void> {
  return request({ url: `/user/platform/users/${userId}/roles`, method: 'put', data: { roleIds } })
}

/**
 * 导出账号 CSV（按当前筛选）。
 *
 * <p>走原始 axios 实例而不是 `request()`：CSV 不是 `{code,data}` 结构，
 * 统一解包会把它当成业务失败。BOM 由后端写入，Excel 打开中文不乱码。</p>
 */
export async function downloadPlatformUsersCsv(
  query: PlatformUserQuery = {},
  fileName = '平台账号.csv',
) {
  const response = await service.get('/user/platform/users/export', {
    params: query,
    responseType: 'blob',
  })
  const blob = response.data as Blob
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

// ------------------------------------------------------------------ 角色权限

export interface PlatformRoleRow {
  id: string
  roleCode: string
  roleName: string
  remark?: string | null
  isFixed?: boolean
  isEnabled?: boolean
  permIds: string[]
  permCount: number
  userCount: number
}

export interface PermissionItem {
  id: string
  /** 权限编码 = 前端路由 path */
  permCode: string
  permName: string
  /** 1-菜单、2-按钮、3-接口 */
  permType: number
  parentId: string
  sortNo: number
}

export function fetchPlatformRoles(): Promise<PlatformRoleRow[]> {
  return request<PlatformRoleRow[]>({ url: '/user/platform/roles', method: 'get' })
}

/** 权限目录（角色权限页的勾选来源） */
export function fetchPermissions(): Promise<PermissionItem[]> {
  return request<PermissionItem[]>({ url: '/user/platform/roles/permissions', method: 'get' })
}

export function createPlatformRole(body: {
  roleCode: string
  roleName: string
  remark?: string
  permIds?: Array<string | number>
}): Promise<{ id: string; roleCode: string; roleName: string }> {
  return request({ url: '/user/platform/roles', method: 'post', data: body })
}

export function updatePlatformRole(
  roleId: string,
  body: { roleName: string; remark?: string; isEnabled?: boolean },
): Promise<void> {
  return request({ url: `/user/platform/roles/${roleId}`, method: 'put', data: body })
}

/** 保存角色权限（全量覆盖） */
export function saveRolePermissions(
  roleId: string,
  permIds: Array<string | number>,
): Promise<{ saved: number }> {
  return request({
    url: `/user/platform/roles/${roleId}/permissions`,
    method: 'put',
    data: { permIds },
  })
}

export function removePlatformRole(roleId: string): Promise<void> {
  return request({ url: `/user/platform/roles/${roleId}`, method: 'delete' })
}
