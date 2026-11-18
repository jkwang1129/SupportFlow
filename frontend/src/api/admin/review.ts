import { request } from '../request'

/** 企业审核列表行 */
export interface EnterpriseReviewItem {
  id: string
  enterpriseId: string
  enterpriseCode: string
  tenantCode?: string
  applyNo: string
  versionNo: number
  applicantId: string
  companyName: string
  industry?: string
  scale?: string
  contactName: string
  contactPhone?: string
  contactEmail?: string
  licenseNo?: string
  registerAddress?: string
  legalPerson?: string
  licenseFileId?: string | null
  status: number
  rejectReason?: string
  reviewerId?: string | null
  reviewTime?: string
  submitTime?: string
  enterpriseStatus?: number
}

export interface EnterpriseReviewPage {
  records: EnterpriseReviewItem[]
  total: number
  current: number
  size: number
  pages: number
}

/** 分页查询企业审核 */
export function pageEnterpriseReviews(params: {
  pageNum: number
  pageSize: number
  status?: number
  keyword?: string
}): Promise<EnterpriseReviewPage> {
  return request<EnterpriseReviewPage>({
    url: '/tenant/admin/reviews',
    method: 'get',
    params,
  })
}

/** 审核详情 */
export function getEnterpriseReview(reviewId: string): Promise<EnterpriseReviewItem> {
  return request<EnterpriseReviewItem>({
    url: `/tenant/admin/reviews/${reviewId}`,
    method: 'get',
  })
}

/** 审核通过 */
export function approveEnterpriseReview(reviewId: string): Promise<EnterpriseReviewItem> {
  return request<EnterpriseReviewItem>({
    url: `/tenant/admin/reviews/${reviewId}/approve`,
    method: 'post',
  })
}

/** 审核驳回 */
export function rejectEnterpriseReview(reviewId: string, reason: string): Promise<EnterpriseReviewItem> {
  return request<EnterpriseReviewItem>({
    url: `/tenant/admin/reviews/${reviewId}/reject`,
    method: 'post',
    data: { reason },
  })
}
