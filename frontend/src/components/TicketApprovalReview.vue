<template>
  <el-button size="small" @click="openApproval">工单审批</el-button>
  <el-dialog
    v-model="visible"
    title="工单审批"
    width="min(560px, 92vw)"
    append-to-body
    :show-close="!submitting"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
  >
    <div class="approval-dialog" aria-live="polite" :aria-busy="loading || submitting || checkingProgress">
      <p v-if="loading">正在查询当前会话的工单任务…</p>
      <el-alert v-else-if="failure" :title="failure" type="warning" :closable="false" show-icon />
      <template v-else-if="proposal || progress">
        <p>{{ progress ? progressLabels[progress.state] : proposal?.status === 'APPROVED' ? '已批准，执行前可撤销' : '请核对将写入工单的标题和正文' }}</p>
        <dl class="approval-terms">
          <template v-if="proposal">
            <dt>标题</dt>
            <dd>{{ proposal.title }}</dd>
            <dt>正文</dt>
            <dd>{{ proposal.content }}</dd>
            <dt>有效期至</dt>
            <dd>{{ new Date(proposal.expires_at).toLocaleString() }}</dd>
          </template>
          <template v-if="progress">
            <dt>执行轮数</dt>
            <dd>{{ progress.attempt }}</dd>
            <dt>记录步骤</dt>
            <dd>{{ progress.step_count }}</dd>
          </template>
        </dl>
      </template>
      <p v-else>当前会话没有可审查的工单提案。</p>
    </div>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">关闭</el-button>
      <el-button :disabled="loading || submitting || checkingProgress" @click="load">刷新</el-button>
      <template v-if="proposal?.status === 'PENDING'">
        <el-button :disabled="submitting" @click="submit('deny')">拒绝</el-button>
        <el-button type="primary" :disabled="submitting" @click="submit('approve')">批准</el-button>
      </template>
      <template v-else-if="proposal?.status === 'APPROVED' || progress">
        <el-button :loading="checkingProgress" :disabled="submitting" @click="loadProgress">查询执行进度</el-button>
        <el-button v-if="proposal?.status === 'APPROVED' && !terminal" type="danger" plain :disabled="submitting || checkingProgress" @click="submit('revoke')">
          撤销批准
        </el-button>
      </template>
      <span v-if="submitting" class="approval-feedback" role="status">正在提交…</span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { activeApproval, decideApproval, latestApprovalProgress, readApproval, readApprovalProgress } from '../api/customer/agentApproval'
import type { ApprovalDecision, ApprovalProgress, ApprovalProposal, ApprovalReference } from '../api/customer/agentApproval'

const props = defineProps<{ sessionNo: string }>()
const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const proposal = ref<ApprovalProposal | null>(null)
const progress = ref<ApprovalProgress | null>(null)
const checkingProgress = ref(false)
const terminal = computed(() => progress.value && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(progress.value.state))
const progressLabels: Record<ApprovalProgress['state'], string> = {
  PENDING: '已批准，等待执行',
  RUNNING: '任务执行中',
  WAITING_APPROVAL: '等待审批',
  RETRY_WAIT: '任务等待重试',
  SUCCEEDED: '任务执行完成',
  FAILED: '任务执行失败',
  CANCELLED: '任务已取消',
}
const failure = ref('')
let reference: ApprovalReference | null = null
// Retained only for this mounted workspace, reviewer and session.
let approvedReference: ApprovalReference | null = null
let loader: AbortController | undefined
let progressLoader: AbortController | undefined
let mounted = true

function statusOf(error: unknown) {
  return isAxiosError(error) ? error.response?.status : undefined
}

function messageOf(error: unknown) {
  switch (statusOf(error)) {
    case 403: return '当前账号没有工单审批权限。'
    case 404: return '会话或提案已不可审查。'
    case 409: return '审批状态已变化或过期，请刷新。'
    default: return '审批服务暂不可用，请稍后刷新确认状态。'
  }
}

function showFailure(error: unknown) {
  const status = statusOf(error)
  if (status === 403 || status === 404) approvedReference = null
  failure.value = messageOf(error)
}

function clear() {
  loader?.abort()
  loader = undefined
  progressLoader?.abort()
  progressLoader = undefined
  loading.value = false
  checkingProgress.value = false
  reference = null
  proposal.value = null
  progress.value = null
  failure.value = ''
}

async function load() {
  if (!visible.value || submitting.value) return
  clear()
  const pending = new AbortController()
  loader = pending
  loading.value = true
  try {
    const found = await activeApproval(props.sessionNo, pending.signal)
    if (pending.signal.aborted) return
    if (found) {
      approvedReference = found.status === 'APPROVED' ? found : null
      try {
        const original = await readApproval(found, pending.signal)
        if (!pending.signal.aborted) {
          reference = found
          proposal.value = original
          approvedReference = original.status === 'APPROVED' ? found : null
        }
        return
      } catch (error) {
        // A live approved proposal can be consumed between discovery and preview.
        if (pending.signal.aborted || found.status !== 'APPROVED' || statusOf(error) !== 404) throw error
      }
    }
    const previous = approvedReference
    const state = previous
      ? await readApprovalProgress(previous, pending.signal)
      : await latestApprovalProgress(props.sessionNo, pending.signal)
    if (!pending.signal.aborted) {
      reference = previous
      progress.value = state
    }
  } catch (error) {
    if (!pending.signal.aborted) showFailure(error)
  } finally {
    if (loader === pending) {
      loader = undefined
      loading.value = false
    }
  }
}

function openApproval() {
  visible.value = true
  void load()
}

async function loadProgress() {
  if (!visible.value || submitting.value || checkingProgress.value) return
  if (!reference) {
    if (progress.value) await load()
    return
  }
  const request = new AbortController()
  progressLoader = request
  checkingProgress.value = true
  try {
    const state = await readApprovalProgress(reference, request.signal)
    if (!request.signal.aborted) {
      progress.value = state
      if (terminal.value) proposal.value = null
    }
  } catch (error) {
    if (!request.signal.aborted) {
      const status = statusOf(error)
      if (status === 403 || status === 404) {
        clear()
        showFailure(error)
      } else {
        ElMessage.warning('暂时无法获取执行进度，请稍后再查。')
      }
    }
  } finally {
    if (progressLoader === request) {
      progressLoader = undefined
      checkingProgress.value = false
    }
  }
}

async function submit(decision: ApprovalDecision) {
  if (!visible.value || !proposal.value || !reference || submitting.value || checkingProgress.value || terminal.value) return
  const original = proposal.value
  const selected = reference
  submitting.value = true
  failure.value = ''
  try {
    const result = await decideApproval(selected, original, decision)
    if (!mounted) return
    if (result.status === 'APPROVED') {
      approvedReference = selected
      proposal.value = { ...original, status: 'APPROVED' }
      ElMessage.success('已批准，等待执行。')
    } else {
      approvedReference = null
      clear()
      ElMessage.success(result.status === 'DENIED' ? '已拒绝提案。' : '已撤销批准。')
    }
  } catch (error) {
    if (!mounted) return
    clear()
    showFailure(error)
  } finally {
    if (mounted) submitting.value = false
  }
}

watch(visible, (open) => { if (!open) clear() })
onUnmounted(() => {
  mounted = false
  clear()
})
</script>

<style scoped>
.approval-dialog p { margin: 0 0 12px; }
.approval-terms { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 8px 16px; margin: 0; }
.approval-terms dt { font-weight: 600; }
.approval-terms dd { margin: 0; max-height: 240px; overflow: auto; overflow-wrap: anywhere; white-space: pre-wrap; }
.approval-feedback { display: block; margin-top: 10px; }
</style>
