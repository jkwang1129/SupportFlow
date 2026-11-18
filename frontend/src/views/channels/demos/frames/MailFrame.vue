<template>
  <!-- 照原型 channel-email.html：邮箱界面 + 自动回执 + 规则解析 + 工单跟踪 -->
  <div class="mail">
    <div class="ml">
      <div class="mi active"><el-icon><Message /></el-icon>收件箱</div>
      <div class="mi"><el-icon><Promotion /></el-icon>已发送</div>
      <div class="mi"><el-icon><Tickets /></el-icon>工单提醒</div>
    </div>
    <div class="mr">
      <div class="mail-list">
        <div
          v-for="(item, index) in mails"
          :key="item.subject"
          class="mail-item"
          :class="{ active: index === active }"
          @click="active = index"
        >
          <div class="mi-from">{{ item.from }}</div>
          <div class="mi-main">
            <div class="mi-sub">{{ item.subject }}</div>
            <div class="mi-note">{{ item.note }}</div>
          </div>
          <div class="mi-time">{{ item.time }}</div>
        </div>
      </div>
      <div class="mhead">
        <span class="avatar a-30" style="background: linear-gradient(135deg, #f59e0b, #ea580c)">张</span>
        <div>
          <div class="mhead-title">张梦琪 &lt;mengqi.zhang@example.com&gt;</div>
          <div class="mhead-sub">{{ currentMail.subject }} · {{ currentMail.time }}</div>
        </div>
      </div>
      <div class="mbody">
        <p>{{ currentMail.body }}</p>

        <div v-if="active === 0" class="ticket-timeline">
          <div class="tl-title">处理时间线</div>
          <div class="tl-row"><span class="tl-dot" />邮件到达 · 自动生成工单 <b class="mono">TK-260828-021</b>（10:18）</div>
          <div class="tl-row"><span class="tl-dot" />秒级回执已发送给客户（10:18）</div>
          <div class="tl-row"><span class="tl-dot" />规则解析：意图「发票抬头变更」· 优先级「中」· 建议处理人「财务支持组」（10:19）</div>
          <div class="tl-row"><span class="tl-dot warn" />SLA：首次响应 30 分钟 · 结单 1 个工作日（10:19）</div>
        </div>

        <div class="ai-card">
          <div class="ai-title"><el-icon><MagicStick /></el-icon>规则解析结果</div>
          <div class="ai-row"><span>意图</span><b>发票抬头变更</b></div>
          <div class="ai-row"><span>优先级</span><b>中（1 个工作日内）</b></div>
          <div class="ai-row"><span>建议处理人</span><b>财务支持组 · 李会计</b></div>
          <div class="ai-row"><span>SLA</span><b>首次响应 30 分钟 / 结单 1 个工作日</b></div>
        </div>

        <div v-if="progressed" class="reply-box">
          <div class="rb-head">回访邮件（已发送）</div>
          <div class="rb-body">
            发票抬头已更新为「杭州SupportFlow」，新发票已发送到您的邮箱；工单 <b class="mono">TK-260828-021</b> 已结单，
            麻烦您确认，感谢您的耐心等待。
          </div>
        </div>
      </div>
      <div class="mfoot">
        <button v-if="!progressed" class="btn btn-primary btn-sm" type="button" @click="progressed = true">
          查看工单进展
        </button>
        <button v-else class="btn btn-outline btn-sm" type="button" @click="reset">重新体验</button>
        <span class="mfoot-note">邮件渠道已接入：收信自动建工单 + 秒级回执，回复走 SMTP 同主题回信</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const active = ref(0)
const progressed = ref(false)

const mails = [
  {
    from: '张梦琪',
    subject: '发票抬头修改为：杭州SupportFlow',
    note: '已受理 · 工单 TK-260828-021',
    time: '10:18',
    body: '你好，我们公司抬头改了，麻烦把这笔订单的发票抬头改成「杭州SupportFlow」，税号 91330100MA2XXXXXXX。谢谢！',
  },
  {
    from: 'SupportFlow 客服',
    subject: '回执：您的邮件已受理（工单 TK-260828-021）',
    note: '自动回复 · 预计 1 个工作日内处理',
    time: '10:18',
    body: '您好，您的邮件已受理，工单号 TK-260828-021。我们会在 1 个工作日内处理并回复，进展会同步到本邮箱。',
  },
  {
    from: '李明浩',
    subject: '咖啡机出水口漏水，申请上门检修',
    note: '已建单 · 派单杭州售后组',
    time: '09:42',
    body: '客服你好，上周买的咖啡机出水口一直漏水，能不能安排师傅上门看一下？我这边工作日白天都方便。',
  },
]

const currentMail = computed(() => mails[active.value])

function reset() {
  progressed.value = false
  active.value = 0
}

/** 详情页「重新体验」按钮：回到刚收到邮件的状态 */
defineExpose({ reset })
</script>

<style scoped>
.mi-main { flex: 1; min-width: 0; }
.mi-note { font-size: 11.5px; color: #8a8fa3; margin-top: 2px; }
.mhead-title { font-size: 13px; }
.mhead-sub { font-size: 11.5px; color: #8a8fa3; font-weight: 400; }
.mbody p { margin: 0 0 12px; }
.tl-title { font-size: 12.5px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
.tl-row { display: flex; gap: 8px; align-items: flex-start; font-size: 12.5px; color: #3f4357; padding: 3px 0; }
.tl-dot { width: 7px; height: 7px; border-radius: 50%; background: #10b981; margin-top: 6px; flex-shrink: 0; }
.tl-dot.warn { background: #f59e0b; }
.ai-card { margin-top: 14px; border: 1px solid #dbeafe; background: #f8fbff; border-radius: 10px; padding: 12px 14px; }
.ai-title { display: flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 700; color: #2563eb; margin-bottom: 8px; }
.ai-row { display: flex; justify-content: space-between; gap: 12px; font-size: 12.5px; color: #3f4357; padding: 2px 0; }
.ai-row span { color: #8a8fa3; }
.rb-head { padding: 8px 12px; font-size: 12px; color: #8a8fa3; background: #fafbfc; border-bottom: 1px solid #eef0f5; }
.rb-body { padding: 11px 12px; font-size: 12.5px; color: #3f4357; line-height: 1.8; }
.mfoot { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.mfoot-note { font-size: 11.5px; color: #8a8fa3; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
