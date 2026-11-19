<template>
  <img :src="source || undefined" :alt="failed ? '图片加载失败，请重新打开会话' : name || '图片'" loading="lazy" />
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import axios from 'axios'
import { getToken } from '../utils/auth'
const props = defineProps<{ fileId?: string; sessionNo: string; visitorToken?: string; name?: string }>()
const emit = defineEmits<{ ready: [url: string] }>()
const source = ref('')
const failed = ref(false)
watch(() => [props.fileId, props.sessionNo, props.visitorToken], async (_, __, cleanup) => {
  const controller = new AbortController()
  let objectUrl = ''
  let active = true
  cleanup(() => {
    active = false
    controller.abort()
    if (objectUrl) URL.revokeObjectURL(objectUrl)
  })
  source.value = ''
  failed.value = false
  if (!props.fileId || !/^[0-9]+$/.test(props.fileId) || !props.sessionNo) return
  const headers: Record<string, string> = props.visitorToken
    ? { 'X-Visitor-Token': props.visitorToken }
    : { Authorization: `Bearer ${getToken() || ''}` }
  try {
    const response = await axios.get(`/customer/sessions/attachments/${props.fileId}`, {
      baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
      params: { sessionNo: props.sessionNo }, headers, responseType: 'blob',
      signal: controller.signal, timeout: 15000,
    })
    if (!active) return
    if (!String(response.headers['content-type'] || '').startsWith('image/')) {
      throw new Error('Image access was rejected')
    }
    objectUrl = URL.createObjectURL(response.data)
    source.value = objectUrl
    emit('ready', objectUrl)
  } catch {
    if (active) failed.value = true
  }
}, { immediate: true })
</script>
