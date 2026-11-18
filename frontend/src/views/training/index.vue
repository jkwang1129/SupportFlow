<template>
  <div class="training-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">模型训练</h2>
        <p class="page-sub">
          意图语料向量化 · 版本记录 · 灰度切换（与「智能机器人 → 模型训练」是同一份能力）
        </p>
      </div>
    </div>
    <!-- 和智能机器人页里的「模型训练」页签共用同一个组件，避免两处逻辑各写一份 -->
    <BotTrainingCenter />
  </div>
</template>

<script setup lang="ts">
import BotTrainingCenter from '../../components/BotTrainingCenter.vue'
</script>

<style scoped>
.training-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
</style>
