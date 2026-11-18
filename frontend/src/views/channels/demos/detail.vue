<template>
  <div class="dc-scope" :class="{ 'phone-theme': spec?.key === 'phone' }">
    <nav class="demo-nav">
      <div class="brand">
        <span class="logo-mark">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </span>
        渠道效果演示
      </div>
      <a href="#" @click.prevent="router.push('/channel-demos')">← 返回演示中心</a>
      <span class="spacer" />
      <a href="#" @click.prevent="router.push('/channels')">管理后台</a>
    </nav>

    <el-empty v-if="!spec" description="没有这个渠道的演示页">
      <el-button type="primary" @click="router.push('/channel-demos')">返回演示中心</el-button>
    </el-empty>

    <template v-else>
      <div class="chan-hero" :style="{ background: spec.banner }">
        <svg class="ch-bg" viewBox="0 0 100 100" preserveAspectRatio="none" v-html="spec.patternSvg" />
        <div class="ch-inner">
          <span class="ch-logo">
            <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          </span>
          <div>
            <h2>{{ spec.title }}</h2>
            <p>{{ spec.tagline }}</p>
          </div>
          <span class="ch-state"><span class="dot" />{{ stateText }}</span>
        </div>
      </div>

      <div class="demo-layout">
        <div class="demo-info">
          <span class="badge" :class="badgeClass">{{ spec.badge }}</span>
          <h1>{{ spec.title }}</h1>
          <p class="sub">{{ spec.desc }}</p>
          <div class="di-stats">
            <div><b>{{ spec.demoStats.sessions }}</b><span>今日会话</span></div>
            <div><b>{{ spec.demoStats.csat }}</b><span>满意度</span></div>
            <div><b>{{ spec.demoStats.bot }}</b><span>机器人解决</span></div>
          </div>

          <div class="card di-card">
            <div class="card-head"><div class="card-title">演示要点</div></div>
            <div class="card-body" style="padding-top: 6px">
              <div v-for="(point, index) in spec.points" :key="point.title" class="feat">
                <span class="fi" :style="toneOf(index)">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
                </span>
                <div>
                  <b>{{ point.title }}</b>
                  <p>{{ point.desc }}</p>
                </div>
              </div>
            </div>
          </div>

          <div class="di-actions">
            <button class="btn btn-primary" type="button" @click="runAction">{{ spec.actionLabel }}</button>
            <a class="btn btn-outline" href="#" @click.prevent="router.push('/channel-demos')">返回演示中心</a>
          </div>
        </div>

        <div class="demo-stage">
          <WebFrame v-if="spec.key === 'web'" :spec="spec" />
          <PhoneChatFrame v-else-if="chatKind" ref="chatRef" :kind="chatKind" :spec="spec" />
          <PhoneIvrFrame v-else-if="spec.key === 'phone'" ref="ivrRef" />
          <MailFrame v-else ref="mailRef" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { demoByKey } from '../../../data/channelDemos'
import WebFrame from './frames/WebFrame.vue'
import PhoneChatFrame from './frames/PhoneChatFrame.vue'
import PhoneIvrFrame from './frames/PhoneIvrFrame.vue'
import MailFrame from './frames/MailFrame.vue'
import '../../../assets/styles/demo-channels.css'

const route = useRoute()
const router = useRouter()
const spec = computed(() => demoByKey(String(route.params.key || '')))
const chatRef = ref<InstanceType<typeof PhoneChatFrame> | null>(null)
const ivrRef = ref<InstanceType<typeof PhoneIvrFrame> | null>(null)
const mailRef = ref<InstanceType<typeof MailFrame> | null>(null)

/** 网站 / 公众号 / 小程序共用手机里的 IM 会话演示 */
const chatKind = computed(() => {
  const key = spec.value?.key
  return key === 'wechat' || key === 'miniapp' || key === 'app' ? key : ''
})

/** 演示页统一按原型标注"已接入"（真实接入状态在渠道接入列表里看） */
const stateText = computed(() => '已接入')
const badgeClass = computed(() => (spec.value?.key === 'wechat' ? 'b-green' : spec.value?.key === 'miniapp' ? 'b-purple' : 'b-blue'))

const tones = [
  { background: 'var(--green-soft)', color: 'var(--green)' },
  { background: 'var(--blue-soft)', color: 'var(--blue)' },
  { background: 'var(--amber-soft)', color: 'var(--amber)' },
  { background: 'var(--purple-soft)', color: 'var(--purple)' },
]

function toneOf(index: number) {
  return tones[index % tones.length]
}

/** 主按钮：照原型各渠道页的动作（全屏体验 / 重新体验 / 体验转人工 / 体验商品卡片 / 体验语音导航） */
function runAction() {
  const action = spec.value?.action
  if (action === 'fullscreen') {
    window.open('/channel-demo/web.html', '_blank', 'noopener')
    return
  }
  if (action === 'ask') {
    chatRef.value?.ask(spec.value?.actionArg || '转人工')
    return
  }
  if (action === 'card') {
    chatRef.value?.pushProduct()
    return
  }
  if (action === 'ivr') {
    ivrRef.value?.restart()
    return
  }
  // reset：公众号 / 小程序的"重新体验"，邮件页的"重新体验"
  chatRef.value?.reset()
  mailRef.value?.reset()
}
</script>

<style scoped>
/* 页面结构与样式都来自原型 demo-channels.css，这里不再加自定义样式 */
</style>
