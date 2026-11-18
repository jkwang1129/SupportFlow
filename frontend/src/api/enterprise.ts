import service, { request } from './request'
import type {
  EnterpriseGuideState,
  EnterpriseProfilePayload,
  LicenseUploadResult,
} from '../types'

/** 查询当前企业开通引导状态 */
export function getEnterpriseGuide(): Promise<EnterpriseGuideState> {
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my',
    method: 'get',
  })
}

/** 暂存企业资料 */
export function saveEnterpriseProfile(data: EnterpriseProfilePayload): Promise<EnterpriseGuideState> {
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my/profile',
    method: 'post',
    data,
  })
}

/** 提交企业资料，进入审核 */
export function submitEnterpriseReview(data: EnterpriseProfilePayload): Promise<EnterpriseGuideState> {
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my/submit',
    method: 'post',
    data,
  })
}

/** 上传营业执照扫描件 */
export function uploadEnterpriseLicense(file: File): Promise<LicenseUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  return request<LicenseUploadResult>({
    url: '/customer/files/enterprise/license',
    method: 'post',
    data: formData,
  })
}

/** 拉取营业执照附件内容（用于预览） */
export async function fetchEnterpriseLicense(fileId: string): Promise<Blob> {
  const response = await service.get(`/customer/files/enterprise/${fileId}`, {
    responseType: 'blob',
  })
  return response.data as Blob
}
