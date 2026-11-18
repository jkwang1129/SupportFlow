<template>
  <div class="dc-scope">
    <nav class="demo-nav">
      <div class="brand">
        <span class="logo-mark">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </span>
        渠道效果演示
      </div>
      <a href="#" @click.prevent="router.push('/channels')">← 返回渠道接入</a>
      <span class="spacer" />
      <a href="#" @click.prevent="router.push('/dashboard')">管理后台</a>
    </nav>

    <section class="hub-hero">
      <div class="inner">
        <span class="badge"><span class="dot green" />六大渠道 · 一处配置全端生效</span>
        <h1>渠道接入效果<span class="grad">演示中心</span></h1>
        <p>每个渠道接入后，客户真实看到的样子。点击任意渠道，即可体验完整的智能客服交互流程。</p>
        <div class="stats">
          <div><b>6</b><span>接入渠道</span></div>
          <div><b>30min</b><span>平均接入时间</span></div>
          <div><b>1</b><span>统一工作台</span></div>
          <div><b>7×24</b><span>机器人接待</span></div>
        </div>
      </div>
    </section>

    <div class="hub-grid">
      <a
        v-for="spec in channelDemos"
        :key="spec.key"
        class="hub-card"
        href="#"
        @click.prevent="router.push(`/channel-demos/${spec.key}`)"
      >
        <div class="hc-banner" :style="{ background: spec.banner }">
          <svg class="pat" viewBox="0 0 100 100" preserveAspectRatio="none" v-html="spec.patternSvg" />
          <span class="hc-cloud">
            <svg viewBox="0 0 24 24"><path d="M7 18h10a4 4 0 0 0 .9-7.9A6 6 0 0 0 6.2 8.3 4.5 4.5 0 0 0 7 18z"/></svg>
          </span>
          <span class="hc-icon-big"><el-icon :size="32"><component :is="spec.icon" /></el-icon></span>
          <span class="hc-tag">{{ spec.tag }}</span>
        </div>
        <div class="hc-body">
          <div class="hc-name">{{ spec.title }}</div>
          <div class="hc-desc">{{ spec.tagline }}</div>
          <div class="hc-meta">
            <span>今日会话 <b>{{ spec.demoStats.sessions }}</b></span>
            <span>满意度 <b>{{ spec.demoStats.csat }}</b></span>
            <span>机器人解决 <b>{{ spec.demoStats.bot }}</b></span>
          </div>
          <div class="hc-go">
            查看演示
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>
          </div>
        </div>
      </a>
    </div>

    <div class="hub-process">
      <div class="pc"><span class="n">1</span><div><b>创建渠道</b><span>选择渠道类型并命名</span></div></div>
      <div class="pc"><span class="n">2</span><div><b>获取接入密钥</b><span>自动生成 AppKey 与 Secret</span></div></div>
      <div class="pc"><span class="n">3</span><div><b>集成 SDK</b><span>前端一行代码即可嵌入</span></div></div>
      <div class="pc"><span class="n">4</span><div><b>联调上线</b><span>沙箱测试通过后正式发布</span></div></div>
    </div>

    <div class="hub-trust">
      <div class="t">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-3.5 8-10V5l-8-3-8 3v7c0 6.5 8 10 8 10z"/><path d="m8.5 12 2.5 2.5 4.5-5"/></svg>
        <b>99.9% SLA</b>服务可用性
      </div>
      <div class="t">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        <b>统一工作台</b>一处接待全渠道
      </div>
      <div class="t">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>
        <b>30 分钟</b>平均接入上线
      </div>
    </div>

    <div class="hub-footer">
      <span>接入流程：创建渠道 → 获取密钥 → 集成 SDK → 联调上线。本页即「联调上线」后的客户真实体验。</span>
      <span style="margin-left: auto"><a href="#" @click.prevent="router.push('/channels')">前往后台配置渠道 →</a></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { channelDemos } from '../../../data/channelDemos'
import '../../../assets/styles/demo-channels.css'

const router = useRouter()
</script>

<style scoped>
/* 只补一点基础样式没覆盖的：真实数据说明的链接色、卡片的可点击反馈 */
.hub-footer a { color: var(--dc-brand); font-weight: 600; }
.hub-card { display: block; }
</style>
