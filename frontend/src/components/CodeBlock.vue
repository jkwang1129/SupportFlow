<template>
  <figure class="code-block">
    <figcaption>
      <span class="cb-label">{{ label || lang }}</span>
      <button type="button" class="cb-copy" @click="copy">{{ copied ? '已复制' : '复制' }}</button>
    </figcaption>
    <pre><code>{{ code }}</code></pre>
  </figure>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    code: string
    /** 代码语言，仅用于右上角标签（不做语法高亮，避免多引一个依赖） */
    lang?: string
    /** 自定义标签，优先级高于 lang */
    label?: string
  }>(),
  { lang: 'bash', label: '' },
)

const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1800)
  } catch {
    copied.value = false
  }
}
</script>

<style scoped>
.code-block { margin: 12px 0; border: 1px solid #e6e8ef; border-radius: 10px; overflow: hidden; background: #0b1220; }
figcaption { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 12px; background: #131c31; border-bottom: 1px solid #1e293b; }
.cb-label { font-size: 11.5px; color: #94a3b8; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.cb-copy { border: 1px solid #334155; background: transparent; color: #cbd5e1; font-size: 11.5px; border-radius: 6px; padding: 3px 9px; cursor: pointer; }
.cb-copy:hover { border-color: #60a5fa; color: #93c5fd; }
pre { margin: 0; padding: 13px 14px; overflow-x: auto; }
code { color: #c7d7f5; font-size: 12.5px; line-height: 1.75; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre; }
</style>
