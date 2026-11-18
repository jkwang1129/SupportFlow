<template>
  <div ref="bodyRef" class="dchat" :class="bgClass" :style="{ '--chat-accent': accent }">
    <div v-if="greeting" class="msg agent">
      <div class="avatar a-30" :style="{ background: accent }">{{ avatarChar }}</div>
      <div><div class="bubble">{{ greeting }}</div></div>
    </div>

    <template v-for="(msg, index) in messages" :key="index">
      <!-- 文本消息 -->
      <div v-if="msg.kind === 'text'" class="msg" :class="msg.side">
        <div v-if="msg.side === 'agent'" class="avatar a-30" :style="{ background: accent }">{{ avatarChar }}</div>
        <div>
          <div class="bubble">{{ msg.text }}</div>
          <div v-if="msg.kb" class="kb-line">📚 知识推荐 · {{ msg.kb }}</div>
        </div>
      </div>

      <!-- 公众号图文卡片 -->
      <div v-else-if="msg.kind === 'wx'" class="wx-card">
        <div class="wc-cover" :style="{ background: msg.cover || accent }">
          <el-icon :size="30"><component :is="msg.icon || 'Box'" /></el-icon>
        </div>
        <div class="wc-body">
          <div class="wc-title">{{ msg.title }}</div>
          <div class="wc-sub">{{ msg.lines?.[0] }}</div>
        </div>
        <div class="wc-foot">{{ msg.action }}</div>
      </div>

      <!-- 订单卡片 -->
      <div v-else-if="msg.kind === 'order'" class="order-card">
        <div class="oc-top">
          <span class="p" :style="{ background: msg.cover || accent }">
            <el-icon :size="22"><Box /></el-icon>
          </span>
          <div>
            <div class="wc-title">{{ msg.title }}</div>
            <div class="wc-sub">{{ msg.lines?.[0] }}</div>
            <div class="wc-sub">{{ msg.lines?.[1] }}</div>
          </div>
        </div>
        <div class="oc-btns">
          <button type="button">{{ msg.action || '查看详情' }}</button>
          <button type="button">联系客服</button>
        </div>
      </div>

      <!-- 商品卡片 -->
      <div v-else class="product-row">
        <span class="p" :style="{ background: msg.cover || accent }">
          <el-icon :size="19"><ShoppingBag /></el-icon>
        </span>
        <div>
          <div class="wc-title">{{ msg.title }}</div>
          <div class="wc-sub">{{ msg.lines?.[0] }}</div>
        </div>
        <span class="pr-go">{{ msg.action || '去看看' }} →</span>
      </div>
    </template>

    <div v-if="typing" class="msg agent">
      <div class="avatar a-30" :style="{ background: accent }">{{ avatarChar }}</div>
      <div><div class="bubble"><span class="typing"><span></span><span></span><span></span></span></div></div>
    </div>

    <div v-if="rating" class="csat-card">
      请为本次服务评分：
      <div class="stars">
        <span v-for="n in 5" :key="n" :class="{ on: n <= stars }" @click="rate(n)">★</span>
      </div>
      <div v-if="stars" class="csat-note">{{ stars >= 4 ? '已记录好评，谢谢支持～' : '已记录，我们会继续改进' }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'

/** 会话里的卡片：公众号图文 / 订单 / 商品 */
interface ChatCard {
  kind: 'wx' | 'order' | 'product'
  title: string
  lines?: string[]
  action?: string
  cover?: string
  icon?: string
}

interface ChatMessage {
  kind: 'text' | 'wx' | 'order' | 'product'
  side: 'agent' | 'customer'
  text?: string
  kb?: string
  title?: string
  lines?: string[]
  action?: string
  cover?: string
  icon?: string
}

const props = withDefaults(
  defineProps<{
    /** 气泡左侧头像里的字 */
    avatarChar?: string
    /** 主题色（机器人气泡、头像底色） */
    accent?: string
    /** 会话区背景类，例如 wx-bg / mp-bg */
    bgClass?: string
    greeting?: string
  }>(),
  { avatarChar: '云', accent: '#2563eb', bgClass: '', greeting: '' },
)

const messages = ref<ChatMessage[]>([])
const typing = ref(false)
const rating = ref(false)
const stars = ref(0)
const bodyRef = ref<HTMLElement | null>(null)

/** 演示用知识库：正式环境由「AI 客服大脑 + 企业知识库」实时生成 */
const faq: Array<{ keys: string[]; answer: string; kb?: string }> = [
  {
    keys: ['物流', '快递', '到哪', '订单', '发货', '配送'],
    answer: '已为您查到订单 OR202608281234 正在配送中，预计今日 18:00 前送达；因天气延误已发放 5 元关怀券。',
    kb: '物流异常自动关怀与补偿标准（SOP）',
  },
  {
    keys: ['退', '换', '运费'],
    answer: '7 天无理由退货：未拆封商品运费由买家承担；质量问题退货由商家承担并安排上门取件。需要我帮您发起退货吗？',
    kb: '7 天无理由退货的运费承担规则',
  },
  {
    keys: ['发票', '抬头', '开票'],
    answer: '电子发票支持自助修改抬头：订单页 → 申请开票 → 修改抬头，1 个工作日内重新开具并推送到您的邮箱。',
    kb: '发票抬头变更与重开申请流程',
  },
  {
    keys: ['积分', '会员'],
    answer: '会员积分有效期 24 个月，会员日双倍累积；联名款咖啡机需要 128,000 积分兑换。',
    kb: '铂金会员积分有效期与兑换规则',
  },
  {
    keys: ['优惠', '券', '折扣'],
    answer: '优惠券用不了通常是三种原因：已过期 / 未达门槛 / 品类限制。把券码发我，我帮您核实并补发。',
    kb: '优惠券使用失败常见原因',
  },
  {
    keys: ['售后', '维修', '上门', '漏水'],
    answer: '已为您登记售后诉求，工程师会在 4 小时内联系上门；同时为您建了一张工单，进度会同步给你。',
    kb: '售后上门服务登记与工单派发规则',
  },
]

function scrollToEnd() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

function pushCustomer(text: string) {
  messages.value.push({ kind: 'text', side: 'customer', text })
  scrollToEnd()
}

/** 推送一张卡片（可选先来一句引导语） */
function pushCard(card: ChatCard, intro?: string) {
  if (intro) {
    messages.value.push({ kind: 'text', side: 'agent', text: intro })
  }
  messages.value.push({ ...card, side: 'agent' })
  scrollToEnd()
}

function send(text: string) {
  const value = text.trim()
  if (!value) return
  pushCustomer(value)
  typing.value = true
  scrollToEnd()
  window.setTimeout(() => {
    typing.value = false
    if (value.includes('人工') || value.includes('客服')) {
      messages.value.push({
        kind: 'text',
        side: 'agent',
        text: '好的，正在为您转接人工客服，客服会在 30 秒内接入，请稍等～',
      })
      scrollToEnd()
      window.setTimeout(() => {
        messages.value.push({
          kind: 'text',
          side: 'agent',
          text: `您好，我是人工客服陈思远，已看到您之前的对话记录（${props.avatarChar === '云' ? '机器人' : '系统'}已同步），我来继续为您处理。`,
        })
        rating.value = true
        scrollToEnd()
      }, 900)
      return
    }
    const hit = faq.find((item) => item.keys.some((key) => value.includes(key)))
    messages.value.push(
      hit
        ? { kind: 'text', side: 'agent', text: hit.answer, kb: hit.kb }
        : {
            kind: 'text',
            side: 'agent',
            text: '收到～这个问题我需要确认一下，您也可以回复「转人工」接入真人客服，或补充订单号我帮您查询。',
          },
    )
    scrollToEnd()
  }, 850)
}

function rate(value: number) {
  stars.value = value
}

/** 重新体验：清空这一轮演示（公众号 / 小程序的「重新体验」按钮用） */
function reset() {
  messages.value = []
  typing.value = false
  rating.value = false
  stars.value = 0
  scrollToEnd()
}

defineExpose({ send, pushCustomer, pushCard, reset })
</script>

<style scoped>
/* 气泡/头像/打字动效来自演示页基础样式（.dc-scope 内），这里只补两张卡片的细节 */
.kb-line {
  margin-top: 6px;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 11.5px;
  line-height: 1.6;
}
.pr-go { margin-left: auto; color: var(--chat-accent, #2563eb); font-size: 12px; font-weight: 600; white-space: nowrap; }
.csat-note { margin-top: 6px; font-size: 11.5px; color: #6f7488; }
</style>
