<template>
  <el-dialog
    :model-value="modelValue"
    class="logout-confirm-dialog"
    width="420px"
    :show-close="true"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="lc-body">
      <div class="lc-icon">
        <el-icon :size="26"><SwitchButton /></el-icon>
      </div>
      <div class="lc-content">
        <div class="lc-title">退出登录</div>
        <div class="lc-desc">确定要退出当前登录账号吗？</div>
        <div class="lc-account">
          <div class="lc-name">{{ name || '—' }}</div>
          <div class="lc-no mono">{{ account || '' }}</div>
        </div>
      </div>
    </div>
    <div class="lc-note">
      退出后需要重新登录才能继续使用SupportFlow 智能客服后台，未保存的页面操作可能丢失。
    </div>
    <template #footer>
      <div class="lc-footer">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" @click="handleConfirm">确认退出</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: boolean
  name: string
  account: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
}>()

function handleConfirm() {
  emit('confirm')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.lc-body {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.lc-icon {
  width: 50px;
  height: 50px;
  border-radius: 14px;
  background: #eff6ff;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.lc-content {
  flex: 1;
  min-width: 0;
  padding-top: 2px;
}

.lc-title {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.lc-desc {
  font-size: 13px;
  color: #64748b;
  margin-top: 5px;
}

.lc-account {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  padding: 9px 12px;
}

.lc-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

.lc-no {
  font-size: 11px;
  color: #94a3b8;
}

.lc-note {
  margin-top: 14px;
  font-size: 12px;
  line-height: 1.7;
  background: #eff6ff;
  border: 1px solid #dbeafe;
  color: #1d4ed8;
  border-radius: 10px;
  padding: 9px 12px;
}

.lc-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
