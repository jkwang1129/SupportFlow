<template>
  <!-- 微信公众号 -->
  <PhoneShell v-if="kind === 'wechat'" time="9:41" signal="•••">
    <div class="ch-head wx-head">
      <button class="back" type="button"><el-icon><ArrowLeft /></el-icon></button>
      <div class="avatar a-30" style="background: #07c160">云</div>
      <div>
        <div class="t">SupportFlow 商城服务号</div>
        <div class="s">SupportFlow 商城 · 服务号</div>
      </div>
      <span style="margin-left: auto; color: #fff; font-size: 17px">⋯</span>
    </div>
    <ChannelChatMock
      ref="chatRef"
      bg-class="wx-bg"
      accent="#07c160"
      avatar-char="云"
      :greeting="spec.chat.greeting"
    />
    <div class="menu-row wx-menu">
      <button type="button" @click="menu('客服咨询')">客服咨询</button>
      <button type="button" @click="menu('我的订单')">我的订单</button>
      <button type="button" @click="menu('会员中心')">会员中心</button>
    </div>
  </PhoneShell>

  <!-- 微信小程序 -->
  <PhoneShell v-else-if="kind === 'miniapp'" time="10:02" signal="5G • 88%">
    <div class="ch-head mp-head">
      <button class="back" type="button"><el-icon><ArrowLeft /></el-icon></button>
      <div>
        <div class="t">SupportFlow 商城</div>
        <div class="s">在线客服 · 已接入</div>
      </div>
      <span style="margin-left: auto; color: #8a8fa3; font-size: 15px">⋯</span>
    </div>
    <div class="quick-chips">
      <button v-for="item in spec.chat.quick" :key="item" type="button" @click="ask(item)">{{ item }}</button>
    </div>
    <ChannelChatMock ref="chatRef" bg-class="mp-bg" accent="#2563eb" avatar-char="云" :greeting="spec.chat.greeting" />
    <div class="dinput">
      <input v-model="draft" placeholder="请输入您的问题…" @keydown.enter="send" />
      <button class="btn btn-primary" type="button" @click="send">发送</button>
    </div>
  </PhoneShell>

  <!-- 官方 App -->
  <PhoneShell v-else time="10:26" signal="5G • 100%" :status-style="{ background: '#2563eb', color: '#fff' }">
    <div class="ch-head app-head">
      <button class="back" type="button"><el-icon><ArrowLeft /></el-icon></button>
      <div>
        <div class="t">客服中心</div>
        <div class="s">在线 · 平均 20 秒响应</div>
      </div>
      <span style="margin-left: auto; color: #fff; font-size: 15px">⋯</span>
    </div>
    <ChannelChatMock ref="chatRef" accent="#7c3aed" avatar-char="云" :greeting="spec.chat.greeting" />
    <div class="app-tools">
      <button type="button" @click="ask('查物流')">查物流</button>
      <button type="button" @click="pushProduct">看商品</button>
      <button type="button" @click="ask('申请售后')">申请售后</button>
    </div>
    <div class="dinput" style="background: #fff">
      <input v-model="draft" placeholder="输入消息…" @keydown.enter="send" />
      <button class="btn btn-primary" type="button" @click="send">发送</button>
    </div>
  </PhoneShell>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import PhoneShell from './PhoneShell.vue'
import ChannelChatMock from '../../../../components/ChannelChatMock.vue'
import type { ChannelDemoSpec, ChatCard } from '../../../../data/channelDemos'

defineProps<{
  kind: 'wechat' | 'miniapp' | 'app'
  spec: ChannelDemoSpec
}>()

const chatRef = ref<InstanceType<typeof ChannelChatMock> | null>(null)
const draft = ref('')

const orderCard: ChatCard = {
  kind: 'order',
  title: '订单 OR202608281234 · 云雀智能音箱 Pro',
  lines: ['已发货 · 顺丰速运 · 预计今日 18:00 前送达', '因中转延误已自动发放 5 元关怀券'],
  action: '查看物流详情',
  cover: 'linear-gradient(135deg,#07c160,#059669)',
}

const wxCard: ChatCard = {
  kind: 'wx',
  title: '物流异常关怀提醒',
  lines: ['您的订单因天气原因中转延误，已自动补偿 5 元关怀券'],
  action: 'SupportFlow 商城服务号 · 模板消息',
  cover: 'linear-gradient(135deg,#3b82f6,#2563eb)',
  icon: 'Box',
}

const productCard: ChatCard = {
  kind: 'product',
  title: '「云雀」智能音箱 Pro',
  lines: ['到手价 ¥699 · 支持蓝牙 5.3 / 语音助手'],
  action: '加入购物车',
  cover: 'linear-gradient(135deg,#f59e0b,#ea580c)',
}

/** 公众号底部菜单：客服咨询 / 我的订单 / 会员中心 */
function menu(name: string) {
  if (name === '我的订单') {
    chatRef.value?.pushCustomer('我的订单')
    window.setTimeout(() => {
      chatRef.value?.pushCard(orderCard, '已为您查到最近一笔订单，物流进展如下 👇')
    }, 600)
    return
  }
  if (name === '会员中心') {
    chatRef.value?.pushCustomer('会员中心')
    window.setTimeout(() => {
      chatRef.value?.pushCard(
        {
          kind: 'wx',
          title: '铂金会员权益',
          lines: ['积分 96,800 · 有效期 24 个月 · 会员日双倍积分'],
          action: '会员中心 · 点击查看积分明细',
          cover: 'linear-gradient(135deg,#7c3aed,#2563eb)',
          icon: 'Present',
        },
        '您的会员权益如下 👇',
      )
    }, 600)
    return
  }
  chatRef.value?.pushCustomer('客服咨询')
  window.setTimeout(() => {
    chatRef.value?.pushCard(wxCard)
  }, 700)
}

function send() {
  chatRef.value?.send(draft.value)
  draft.value = ''
}

/** 小程序 / App 的快捷提问 + 详情页按钮：体验转人工 / 体验商品卡片 / 重新体验 */
function ask(text: string) {
  chatRef.value?.send(text)
}

function pushProduct() {
  chatRef.value?.pushCustomer('看商品')
  window.setTimeout(() => {
    chatRef.value?.pushCard(productCard, '为您推荐店内热销：')
  }, 600)
}

function reset() {
  chatRef.value?.reset()
  draft.value = ''
}

defineExpose({ ask, pushProduct, reset })
</script>
