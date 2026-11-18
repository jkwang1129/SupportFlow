<template>
  <!-- 照原型 channel-web.html：浏览器框里内嵌「客户官网 + 右下角客服挂件」 -->
  <div class="browser">
    <div class="btop">
      <span class="dotc"><i style="background: #ff5f57" /><i style="background: #febc2e" /><i style="background: #28c840" /></span>
      <span class="url">
        <el-icon><Lock /></el-icon>{{ siteUrl }}
      </span>
      <button class="btn btn-soft btn-sm" type="button" @click="reload">
        <el-icon><Refresh /></el-icon>重新体验
      </button>
    </div>
    <iframe ref="frameRef" src="/channel-demo/web.html" title="网站渠道演示" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ChannelDemoSpec } from '../../../../data/channelDemos'

withDefaults(defineProps<{ spec: ChannelDemoSpec; siteUrl?: string }>(), {
  siteUrl: 'https://www.supportflow.example.com',
})

const frameRef = ref<HTMLIFrameElement | null>(null)

/** 重新体验：重载 iframe，等价于原型里的「重新体验」按钮 */
function reload() {
  if (frameRef.value) {
    frameRef.value.src = `/channel-demo/web.html?t=${Date.now()}`
  }
}
</script>
