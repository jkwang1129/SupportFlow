import { request } from './request'

export interface MemberRoleOption {
  code: string
  name: string
}

export interface MemberItem {
  /** 后端把 Long 统一序列化成字符串，前端也保持字符串，避免精度丢失（19 位数字 JS 存不下） */
  userId: string
  userNo: string
  name: string
  phone?: string
  email?: string
  avatar?: string
  roleCode: string
  roleName: string
  status: number
  lastLoginTime?: string
  joinTime?: string
}

export interface InviteItem {
  id: string
  inviteCode: string
  phone?: string
  email?: string
  roleCode?: string
  roleName?: string
  inviterName?: string
  usedByName?: string
  status: number
  statusText: string
  expireTime?: string
  createTime?: string
}

export interface CreateInviteResult {
  id: string
  inviteCode: string
  roleCode: string
  roleName: string
  expireTime: string
  phone?: string
  email?: string
  emailSent: boolean
}

export interface InvitePreview {
  inviteCode: string
  roleCode?: string
  roleName?: string
  inviterName?: string
  phoneMasked?: string
  emailMasked?: string
  expireTime?: string
}

export interface AcceptResult {
  userId: number
  userNo: string
  name: string
  phone?: string
  email?: string
  roleCode: string
  roleName: string
  tenantCode: string
}

export interface MemberAccess {
  canManage: boolean
  roleCode?: string
  roleName?: string
}

/** 当前企业用户是否可管理成员及角色信息 */
export function fetchMemberAccess(): Promise<MemberAccess> {
  // 权限探测：企业未开通时后端会返回业务错误，属于预期结果，不弹全局提示
  return request<MemberAccess>({ url: '/user/members/access', method: 'get', silent: true })
}

/** 同事（转接会话选人用，任何企业成员可看，只返回必要字段） */
export interface ColleagueOption {
  /** 同上：全程用字符串传，转成 number 会四舍五入到 100 的倍数 */
  userId: string
  name: string
  userNo: string
  avatar?: string
}

export function listColleagues(): Promise<ColleagueOption[]> {
  return request<ColleagueOption[]>({ url: '/user/members/colleagues', method: 'get' })
}

/** 可邀请角色 */
export function listMemberRoles(): Promise<MemberRoleOption[]> {
  return request<MemberRoleOption[]>({ url: '/user/members/roles', method: 'get' })
}

/** 企业成员列表 */
export function listMembers(): Promise<MemberItem[]> {
  return request<MemberItem[]>({ url: '/user/members', method: 'get' })
}

/** 邀请记录 */
export function listMemberInvites(): Promise<InviteItem[]> {
  return request<InviteItem[]>({ url: '/user/members/invites', method: 'get' })
}

/** 创建邀请 */
export function createMemberInvite(data: {
  phone?: string
  email?: string
  roleCode: string
  expireDays?: number
}): Promise<CreateInviteResult> {
  return request<CreateInviteResult>({ url: '/user/members/invites', method: 'post', data })
}

/** 撤销邀请 */
export function revokeMemberInvite(inviteCode: string): Promise<void> {
  return request<void>({ url: `/user/members/invites/${inviteCode}/revoke`, method: 'post' })
}

/** 重发邀请邮件 */
export function resendMemberInvite(inviteCode: string): Promise<boolean> {
  return request<boolean>({ url: `/user/members/invites/${inviteCode}/resend`, method: 'post' })
}

/** 公共邀请预览 */
export function fetchInvitePreview(code: string): Promise<InvitePreview> {
  return request<InvitePreview>({ url: `/user/members/invites/${code}/preview`, method: 'get' })
}

/** 接受邀请 */
export function acceptMemberInvite(
  code: string,
  data: { name: string; password: string; phone?: string; email?: string },
): Promise<AcceptResult> {
  return request<AcceptResult>({ url: `/user/members/invites/${code}/accept`, method: 'post', data })
}
