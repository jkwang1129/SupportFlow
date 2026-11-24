import { request } from '../request'

export type ApprovalDecision = 'approve' | 'deny' | 'revoke'
type ActiveStatus = 'PENDING' | 'APPROVED'

export interface ApprovalRunReference {
  source_event_id: string
  run_id: string
}

export interface ApprovalReference extends ApprovalRunReference {
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

export type ApprovalTerminalState = 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface ApprovalProgress {
  state: 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'RETRY_WAIT' | ApprovalTerminalState
  attempt: number
  step_count: number
  write_status?: 'UNCERTAIN' | 'COMMITTED'
}

export interface ApprovalEvent {
  seq: number
  event_type: 'run_started' | 'step_started' | 'step_completed' | 'tool_started' | 'tool_completed'
    | 'approval_required' | 'run_completed' | 'run_failed' | 'run_cancelled'
  occurred_at: string
  step_no: number | null
  outcome: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | null
}

interface ApprovalEventPage {
  schema_version: 'agent-run-events-v1'
  run_id: string
  next_seq: number
  has_more: boolean
  events: ApprovalEvent[]
}

const APPROVALS = '/customer/agent-approvals'

function proposalPath(reference: ApprovalRunReference) {
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

export function readApprovalProgress(reference: ApprovalRunReference, signal: AbortSignal) {
  return request<ApprovalProgress>({
    url: `${proposalPath(reference)}/progress`, signal, silent: true,
  })
}

export function latestApprovalProgress(sessionNo: string, signal: AbortSignal) {
  return request<(ApprovalProgress & { timeline?: ApprovalRunReference }) | null>({
    url: `${APPROVALS}/sessions/${encodeURIComponent(sessionNo)}/latest-progress`,
    params: { includeTimeline: true }, signal, silent: true,
  })
}

export function readApprovalEvents(reference: ApprovalRunReference, afterSeq: number, signal: AbortSignal) {
  return request<ApprovalEventPage>({
    url: `${proposalPath(reference)}/timeline`, params: { afterSeq }, timeout: 8000, signal, silent: true,
  })
}

export function cancelApprovalRun(reference: ApprovalRunReference) {
  return request<ApprovalProgress>({ url: `${proposalPath(reference)}/cancel`, method: 'POST', silent: true })
}

export function decideApproval(reference: ApprovalReference, proposal: ApprovalProposal, decision: ApprovalDecision) {
  return request<{ approval_id: string; status: 'APPROVED' | 'DENIED' | 'REVOKED' }>({
    url: `${proposalPath(reference)}/decisions`,
    method: 'POST',
    data: { approval_id: proposal.approval_id, arguments_sha256: proposal.arguments_sha256, decision },
    silent: true,
  })
}
