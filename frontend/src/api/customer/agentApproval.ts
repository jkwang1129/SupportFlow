import { request } from '../request'

export type ApprovalDecision = 'approve' | 'deny' | 'revoke'
type ActiveStatus = 'PENDING' | 'APPROVED'

export interface ApprovalReference {
  source_event_id: string
  run_id: string
  status: ActiveStatus
  expires_at: string
}

export interface ApprovalProposal {
  approval_id: string
  arguments_sha256: string
  status: ActiveStatus
  expires_at: string
  title: string
  content: string
}

export interface ApprovalProgress {
  state: 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  attempt: number
  step_count: number
}

const APPROVALS = '/customer/agent-approvals'

function proposalPath(reference: ApprovalReference) {
  return `${APPROVALS}/events/${encodeURIComponent(reference.source_event_id)}/runs/${encodeURIComponent(reference.run_id)}`
}

export function activeApproval(sessionNo: string, signal: AbortSignal) {
  return request<ApprovalReference | null>({
    url: `${APPROVALS}/sessions/${encodeURIComponent(sessionNo)}/active`, signal, silent: true,
  })
}

export function readApproval(reference: ApprovalReference, signal: AbortSignal) {
  return request<ApprovalProposal>({
    url: `${proposalPath(reference)}/proposal`, signal, silent: true,
  })
}

export function readApprovalProgress(reference: ApprovalReference, signal: AbortSignal) {
  return request<ApprovalProgress>({
    url: `${proposalPath(reference)}/progress`, signal, silent: true,
  })
}

export function latestApprovalProgress(sessionNo: string, signal: AbortSignal) {
  return request<ApprovalProgress | null>({
    url: `${APPROVALS}/sessions/${encodeURIComponent(sessionNo)}/latest-progress`, signal, silent: true,
  })
}

export function decideApproval(reference: ApprovalReference, proposal: ApprovalProposal, decision: ApprovalDecision) {
  return request<{ approval_id: string; status: 'APPROVED' | 'DENIED' | 'REVOKED' }>({
    url: `${proposalPath(reference)}/decisions`,
    method: 'POST',
    data: { approval_id: proposal.approval_id, arguments_sha256: proposal.arguments_sha256, decision },
    silent: true,
  })
}
