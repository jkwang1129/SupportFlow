<template>
  <div class="phone">
    <div class="screen" :style="screenStyle">
      <span class="notch" />
      <div class="statusbar" :style="statusStyle">
        <span>{{ time }}</span>
        <span class="sig">{{ signal }}</span>
      </div>
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { CSSProperties } from 'vue'

withDefaults(
  defineProps<{
    time?: string
    signal?: string
    statusStyle?: CSSProperties
    screenStyle?: CSSProperties
  }>(),
  {
    time: '9:41',
    signal: '5G • 88%',
    statusStyle: () => ({}),
    screenStyle: () => ({}),
  },
)
</script>
