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
    <div class="approval-dialog" aria-live="polite" :aria-busy="loading || submitting">
      <p v-if="loading">正在获取当前会话的审批提案…</p>
      <el-alert v-else-if="failure" :title="failure" type="warning" :closable="false" show-icon />
      <template v-else-if="proposal">
        <p>{{ proposal.status === 'APPROVED' ? '已批准，执行前可撤销' : '请核对将写入工单的标题和正文' }}</p>
        <dl class="approval-terms">
          <dt>标题</dt>
          <dd>{{ proposal.title }}</dd>
          <dt>正文</dt>
          <dd>{{ proposal.content }}</dd>
          <dt>有效期至</dt>
          <dd>{{ new Date(proposal.expires_at).toLocaleString() }}</dd>
        </dl>
      </template>
      <p v-else>当前会话没有可审查的工单提案。</p>
    </div>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">关闭</el-button>
      <el-button :disabled="loading || submitting" @click="load">刷新</el-button>
      <template v-if="proposal?.status === 'PENDING'">
        <el-button :disabled="submitting" @click="submit('deny')">拒绝</el-button>
        <el-button type="primary" :disabled="submitting" @click="submit('approve')">批准</el-button>
      </template>
      <el-button v-else-if="proposal?.status === 'APPROVED'" type="danger" plain :disabled="submitting" @click="submit('revoke')">
        撤销批准
      </el-button>
      <span v-if="submitting" class="approval-feedback" role="status">正在提交…</span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { activeApproval, decideApproval, readApproval } from '../api/customer/agentApproval'
import type { ApprovalDecision, ApprovalProposal, ApprovalReference } from '../api/customer/agentApproval'

const props = defineProps<{ sessionNo: string }>()
const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const proposal = ref<ApprovalProposal | null>(null)
const failure = ref('')
let reference: ApprovalReference | null = null
let loader: AbortController | undefined
let mounted = true

function messageOf(error: unknown) {
  switch (isAxiosError(error) ? error.response?.status : undefined) {
    case 403: return '当前账号没有工单审批权限。'
    case 404: return '会话或提案已不可审查。'
    case 409: return '审批状态已变化或过期，请刷新。'
    default: return '审批服务暂不可用，请稍后刷新确认状态。'
  }
}

function clear() {
  loader?.abort()
  loader = undefined
  loading.value = false
  reference = null
  proposal.value = null
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
    if (!found || pending.signal.aborted) return
    const original = await readApproval(found, pending.signal)
    if (!pending.signal.aborted) {
      reference = found
      proposal.value = original
    }
  } catch (error) {
    if (!pending.signal.aborted) failure.value = messageOf(error)
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

async function submit(decision: ApprovalDecision) {
  if (!visible.value || !proposal.value || !reference || submitting.value) return
  const original = proposal.value
  submitting.value = true
  failure.value = ''
  try {
    const result = await decideApproval(reference, original, decision)
    if (!mounted) return
    if (result.status === 'APPROVED') {
      proposal.value = { ...original, status: 'APPROVED' }
      ElMessage.success('已批准，等待执行。')
    } else {
      clear()
      ElMessage.success(result.status === 'DENIED' ? '已拒绝提案。' : '已撤销批准。')
    }
  } catch (error) {
    if (!mounted) return
    clear()
    failure.value = messageOf(error)
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
