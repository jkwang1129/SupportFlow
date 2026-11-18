<template>
  <div class="checkout-page">
    <div class="page-head">
      <div>
        <h2>购买套餐</h2>
        <div class="sub">订单确认 → 支付确认 → 支付 → 完成</div>
      </div>
      <div class="page-head-actions">
        <el-button text @click="router.push('/modules/upgrade')">
          <el-icon class="btn-icon"><Back /></el-icon>
          返回版本与升级
        </el-button>
      </div>
    </div>

    <!-- 步骤条：4 步对应 4 个页面态 -->
    <el-steps :active="step" align-center finish-status="success" class="steps">
      <el-step title="确认订单" description="核对套餐与金额" />
      <el-step title="确认支付" description="选择支付方式" />
      <el-step :title="payMethod === 3 ? '对公转账' : '扫码支付'" description="按提示完成付款" />
      <el-step title="完成" description="套餐生效结果" />
    </el-steps>

    <div v-if="!plan" class="empty-wrap">
      <el-empty description="没有找到要购买的套餐，请从「版本与升级」重新选择" :image-size="90">
        <el-button type="primary" @click="router.push('/modules/upgrade')">去选套餐</el-button>
      </el-empty>
    </div>

    <div v-else class="checkout-body">
      <div class="main-col">
        <!-- ① 订单确认 -->
        <el-card v-if="step === 0" shadow="never">
          <template #header><span class="card-title">确认订单</span></template>
          <div class="plan-line">
            <div>
              <div class="plan-name">{{ plan.planName }}</div>
              <div class="plan-desc">{{ planTag(plan) }}</div>
            </div>
            <div class="plan-price">
              {{ amountText(amount) }}<small v-if="amount > 0">/ {{ cycle === 1 ? '年' : '月' }}</small>
            </div>
          </div>
          <!-- 年付要说清楚"省在哪"：原价按月付折算，减掉年付优惠才是应付 -->
          <div v-if="discountAmount > 0" class="cycle-save">
            年付原价（按月付折算）{{ money(listAmount) }}，已优惠 {{ money(discountAmount) }}
          </div>

          <el-radio-group v-model="cycle" class="cycle-switch" @change="noop">
            <el-radio-button :value="1">年付（省 2 个月）</el-radio-button>
            <el-radio-button :value="2">月付</el-radio-button>
          </el-radio-group>

          <div class="switch-note" v-if="usage?.planName">
            当前套餐：{{ usage.planName }}（{{ usage.planExpireTime ? '到期 ' + dateText(usage.planExpireTime) : '未设置到期' }}）
            → 购买后立即生效，限流配额同步更新。
          </div>

          <el-descriptions :column="2" border class="mt-12">
            <el-descriptions-item label="开通后到期时间">{{ expirePreview }}</el-descriptions-item>
            <el-descriptions-item label="坐席数">{{ plan.quotaSeats }} 个</el-descriptions-item>
            <el-descriptions-item label="机器人会话">{{ fmt(plan.quotaRobotSessions) }} 次/月</el-descriptions-item>
            <el-descriptions-item label="AI 调用">{{ fmt(plan.quotaAiCalls) }} 次/月</el-descriptions-item>
            <el-descriptions-item label="超量计费">
              {{ plan.overagePricePerK > 0 ? money(plan.overagePricePerK) + '/千次' : '不允许超量' }}
            </el-descriptions-item>
            <el-descriptions-item label="发票">
              支付完成后可在「我的订单」查看，发票按实际到账金额开具
            </el-descriptions-item>
          </el-descriptions>

          <el-checkbox v-model="agreed" class="agreed">
            我已阅读并同意《SupportFlow 智能客服服务协议》与《自动续费规则》
          </el-checkbox>

          <div class="actions">
            <el-button type="primary" size="large" :disabled="!agreed" :loading="submitting" @click="submitOrder">
              提交订单
            </el-button>
          </div>
        </el-card>

        <!-- ② 支付确认 -->
        <el-card v-else-if="step === 1" shadow="never">
          <template #header><span class="card-title">确认支付</span></template>
          <div class="pay-summary">
            <span>应付金额</span>
            <b>{{ amountText(payableAmount) }}</b>
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="订单号">{{ orderNo }}</el-descriptions-item>
            <el-descriptions-item label="商品">{{ plan.planName }} · {{ cycle === 1 ? '年付' : '月付' }}</el-descriptions-item>
            <el-descriptions-item label="有效期">1 {{ cycle === 1 ? '年' : '个月' }}</el-descriptions-item>
          </el-descriptions>

          <div class="pay-methods">
            <el-alert
              v-if="payConfig && !payConfig.onlinePayEnabled"
              type="warning"
              show-icon
              :closable="false"
              title="平台已关闭在线支付，当前仅支持对公转账"
              description="需要支付宝 / 微信收款，请联系平台运营在「平台设置 → 支付配置」里开启。"
              class="pay-alert"
            />
            <div
              v-for="method in payMethods"
              :key="method.id"
              class="pay-method"
              :class="{ active: payMethod === method.id, disabled: !method.enabled }"
              @click="pickMethod(method.id)"
            >
              <el-icon :size="22" :class="method.iconClass"><component :is="method.icon" /></el-icon>
              <div>
                <div class="pm-name">
                  {{ method.name }}
                  <el-tag v-if="!method.enabled" size="small" type="info" effect="light">已关闭</el-tag>
                </div>
                <div class="pm-desc">{{ method.desc }}</div>
              </div>
              <el-icon v-if="payMethod === method.id" class="pm-check"><Select /></el-icon>
            </div>
          </div>

          <div class="actions">
            <el-button @click="step = 0">上一步</el-button>
            <el-button type="primary" size="large" @click="step = 2">去支付 {{ amountText(payableAmount) }}</el-button>
          </div>
        </el-card>

        <!-- ③ 支付页（按支付方式给不同的收款指引） -->
        <el-card v-else-if="step === 2" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">{{ payMethod === 3 ? '对公转账' : '扫码支付' }}</span>
              <span class="muted">应付 {{ amountText(payableAmount) }}</span>
            </div>
          </template>

          <!-- 0 元订单（免费版）不会走到这一步，这里只是防御：真出现也不让用户对着二维码发愣 -->
          <el-alert
            v-if="orderAmount <= 0"
            type="success"
            show-icon
            :closable="false"
            title="免费套餐无需支付，点下方按钮直接开通"
          />

          <div v-else-if="payMethod !== 3" class="qr-wrap">
            <div class="qr-box">
              <el-icon :size="64" class="qr-icon"><component :is="currentMethod.icon" /></el-icon>
              <div class="qr-tip">演示环境未接入真实支付网关</div>
            </div>
            <div class="qr-side">
              <div class="qr-title">请使用{{ currentMethod.name }}扫码支付</div>
              <ul class="qr-steps">
                <li>打开 {{ currentMethod.name }} → 扫一扫</li>
                <li>核对收款方为「SupportFlow 智能客服」与金额 {{ amountText(payableAmount) }}</li>
                <li>支付成功后点击下方按钮，套餐立即生效</li>
              </ul>
              <div class="qr-amount">¥ {{ payableAmount.toFixed(2) }}</div>
              <div class="muted mt-8" v-if="currentMerchantText">
                收款商户号：{{ currentMerchantText }}（请在付款页面核对一致）
              </div>
              <el-button type="primary" size="large" :loading="submitting" @click="confirmPaid">
                我已完成支付
              </el-button>
              <div class="muted mt-8">
                真实环境这里由支付网关回调（验签）驱动，不需要用户手动确认。
              </div>
            </div>
          </div>

          <div v-else class="bank-wrap">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="收款户名">{{ corpAccountName }}</el-descriptions-item>
              <el-descriptions-item label="开户银行">{{ corpBankName }}</el-descriptions-item>
              <el-descriptions-item label="银行账号">{{ corpAccountNo }}</el-descriptions-item>
              <el-descriptions-item label="转账金额">{{ amountText(payableAmount) }}</el-descriptions-item>
              <el-descriptions-item label="汇款备注">请务必填写订单号 {{ orderNo }}</el-descriptions-item>
            </el-descriptions>
            <el-alert
              v-if="payConfig && !payConfig.corpEnabled"
              class="mt-12"
              type="warning"
              show-icon
              :closable="false"
              title="平台还没配置对公账户信息"
              description="下方展示的是示例账户，请先联系平台运营在「平台设置 → 支付配置」里填写真实收款账户。"
            />
            <el-alert
              class="mt-12"
              type="info"
              show-icon
              :closable="false"
              title="转账到账后 1 个工作日内开通"
              description="转账完成后点下方按钮，平台核对到账即开通（也可以让平台在「计费与账单」里确认收款）。"
            />
            <div class="actions">
              <el-button @click="step = 1">换个支付方式</el-button>
              <el-button type="primary" size="large" :loading="submitting" @click="confirmPaid">
                我已完成转账
              </el-button>
            </div>
          </div>
        </el-card>

        <!-- ④ 结果页 -->
        <el-card v-else shadow="never" class="result-card">
          <div class="result" :class="{ ok: paidOk, fail: !paidOk }">
            <el-icon :size="56" class="result-icon">
              <component :is="paidOk ? CircleCheckFilled : CircleCloseFilled" />
            </el-icon>
            <div class="result-title">{{ paidOk ? '支付成功，套餐已生效' : '支付未完成' }}</div>
            <div class="result-sub" v-if="paidOk">
              {{ plan.planName }} · {{ cycle === 1 ? '年付' : '月付' }} · 到期 {{ dateText(resultExpire) }}
            </div>
            <div class="result-sub" v-else>{{ errorMessage || '支付未完成，订单仍在「我的订单」里，可继续支付' }}</div>

            <el-descriptions :column="2" border class="result-detail">
              <el-descriptions-item label="订单号">{{ orderNo }}</el-descriptions-item>
              <el-descriptions-item label="支付金额">{{ amountText(payableAmount) }}</el-descriptions-item>
              <el-descriptions-item label="支付方式">{{ currentMethod.name }}</el-descriptions-item>
              <el-descriptions-item label="限流配额">
                {{ paidOk ? `${plan.quotaRobotSessions * 10} QPS` : '未生效' }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="actions center">
              <template v-if="paidOk">
                <el-button type="primary" @click="router.push('/modules/orders')">查看我的订单</el-button>
                <el-button @click="router.push('/modules/upgrade')">继续浏览套餐</el-button>
              </template>
              <template v-else>
                <el-button type="primary" @click="step = 1">重新支付</el-button>
                <el-button @click="router.push('/modules/orders')">稍后在订单里支付</el-button>
              </template>
            </div>
          </div>
        </el-card>
      </div>

      <!-- 右侧订单摘要（1~3 步都显示，随时能核对自己买的是什么） -->
      <div v-if="step < 3" class="side-col">
        <el-card shadow="never">
          <template #header><span class="card-title">订单摘要</span></template>
          <div class="sum-row"><span>套餐</span><b>{{ plan.planName }}</b></div>
          <div class="sum-row"><span>计费周期</span><b>{{ cycle === 1 ? '年付' : '月付' }}</b></div>
          <div class="sum-row">
            <span>{{ cycle === 1 ? '套餐原价（月付折算）' : '套餐费' }}</span>
            <b>{{ amountText(listAmount) }}</b>
          </div>
          <div class="sum-row" v-if="discountAmount > 0">
            <span>年付优惠</span><b class="save">- {{ money(discountAmount) }}</b>
          </div>
          <el-divider />
          <div class="sum-total"><span>应付</span><b>{{ amountText(payableAmount) }}</b></div>
          <div class="muted mt-8">价格含税 · 升级随时生效 · 无隐藏费用</div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorMessage as showErrorMessage } from '../../utils/message'
import {
  Back,
  CircleCheckFilled,
  CircleCloseFilled,
  CreditCard,
  Money,
  Select,
  Wallet,
} from '@element-plus/icons-vue'
import {
  createOrder,
  fetchConsolePaymentConfig,
  fetchPlans,
  fetchUsage,
  money,
  payOrder,
  type ConsolePaymentConfig,
  type PlanItem,
  type UsageView,
} from '../../api/billing'

const router = useRouter()

/** 4 步：0-订单确认、1-支付确认、2-支付、3-结果 */
const step = ref(0)
const submitting = ref(false)
const agreed = ref(false)
const cycle = ref(1)
const payMethod = ref(1)
/** 平台支付配置：哪个渠道能用、对公账户信息（收银台按它决定能选什么） */
const payConfig = ref<ConsolePaymentConfig | null>(null)
const plans = ref<PlanItem[]>([])
const usage = ref<UsageView | null>(null)
const planId = ref('')
const orderNo = ref('')
const orderAmount = ref(0)
const paidOk = ref(false)
const errorMessage = ref('')
const resultExpire = ref<string | null>(null)

/**
 * 支付方式。
 *
 * <p>`enabled` 由平台「支付配置」决定：在线支付关掉时对应按钮置灰并给出原因，
 * 而不是点了之后被服务端拒绝（服务端那层校验仍然保留，双保险）。</p>
 */
const payMethods = computed(() => [
  {
    id: 1,
    name: '支付宝',
    desc: payConfig.value && !payConfig.value.alipayEnabled
      ? '平台已关闭该收款方式'
      : '扫码支付，实时到账',
    icon: Wallet,
    iconClass: 'pm-blue',
    enabled: payConfig.value ? payConfig.value.alipayEnabled : true,
  },
  {
    id: 2,
    name: '微信支付',
    desc: payConfig.value && !payConfig.value.wechatEnabled
      ? '平台已关闭该收款方式'
      : '扫码支付，实时到账',
    icon: CreditCard,
    iconClass: 'pm-green',
    enabled: payConfig.value ? payConfig.value.wechatEnabled : true,
  },
  {
    id: 3,
    name: '对公转账',
    desc: payConfig.value?.corpEnabled ? '到账后 1 个工作日内开通' : '到账后 1 个工作日内开通（账户信息待平台配置）',
    icon: Money,
    iconClass: 'pm-amber',
    enabled: true,
  },
])

/** 当前选中的支付方式（payMethods 是 computed，这里从里面取） */
function pickMethod(id: number): void {
  const target = payMethods.value.find((item) => item.id === id)
  if (target && !target.enabled) {
    ElMessage.warning(`${target.name}已被平台关闭，请换一种支付方式`)
    return
  }
  payMethod.value = id
}

const plan = computed(() => plans.value.find((item) => String(item.id) === String(planId.value)) ?? null)
const currentMethod = computed(
  () => payMethods.value.find((item) => item.id === payMethod.value) ?? payMethods.value[0],
)

/** 对公账户：平台配过就用平台配的，没配过退回示例账户（同时页面提示"待配置"） */
const corpAccountName = computed(() => payConfig.value?.corpAccountName || '杭州SupportFlow 演示商户')
const corpBankName = computed(() => payConfig.value?.corpBankName || '招商银行杭州分行营业部')
const corpAccountNo = computed(() => payConfig.value?.corpAccountNo || '5719 0200 8888 6666')

/** 当前在线支付方式的收款商户号（掩码）：付款页对一眼收款方是不是这家 */
const currentMerchantText = computed(() => {
  if (!payConfig.value || payMethod.value === 3) {
    return ''
  }
  const raw = payMethod.value === 1 ? payConfig.value.alipayMerchantId : payConfig.value.wechatMerchantId
  return raw && raw !== '未填' ? raw : ''
})

const amount = computed(() =>
  plan.value ? Number(cycle.value === 1 ? plan.value.priceYearly : plan.value.priceMonthly) : 0,
)
/**
 * 应付金额（页面展示用）。
 *
 * <p>订单还没创建时 `orderAmount` 是 0，直接显示它就会出现"套餐 5,980，应付 ¥0.00"这种
 * 自相矛盾的摘要——所以这里回退到按套餐算出来的金额；订单创建之后两者本来就相等。</p>
 */
const payableAmount = computed(() => (orderAmount.value > 0 ? orderAmount.value : amount.value))
/**
 * 年付原价 = 月价 × 12（用来算"省了多少"）。
 *
 * <p>摘要必须按「原价 − 年付优惠 = 应付」写：之前把年付价当"套餐费"、又减一次优惠，
 * 读起来像 5,980 − 1,196，而应付还是 5,980，自相矛盾。</p>
 */
const listAmount = computed(() => {
  if (!plan.value) return 0
  return cycle.value === 1
    ? Number(plan.value.priceMonthly) * 12
    : Number(plan.value.priceMonthly)
})

/** 年付优惠金额（月付时是 0） */
const discountAmount = computed(() =>
  Math.max(0, Math.round(listAmount.value - amount.value)),
)

/** 到期时间预览：从今天起顺延 1 年 / 1 个月（与后端 applyPlan 的算法一致） */
const expirePreview = computed(() => {
  const now = new Date()
  if (cycle.value === 1) {
    now.setFullYear(now.getFullYear() + 1)
  } else {
    now.setMonth(now.getMonth() + 1)
  }
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
})

function fmt(value?: number | null) {
  return value == null ? '0' : Number(value).toLocaleString('zh-CN')
}

function dateText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 10) : '—'
}

/** 金额文案：0 元（免费版）显示"免费"，不显示 ¥0.00 */
function amountText(value?: number | null) {
  return !value || value <= 0 ? '免费' : money(value)
}

function planTag(item: PlanItem) {
  if (item.quotaSeats <= 5) return '个人与小微团队入门体验'
  if (item.quotaSeats <= 20) return '成长型团队的日常客服'
  return '规模化服务与全量质检'
}

function noop() {
  // 切换周期只需要重算金额，这里留个钩子便于以后接"优惠券"
}

/** ① → 创建订单（落库），拿到订单号与金额 */
async function submitOrder() {
  if (!plan.value) return
  submitting.value = true
  try {
    const order = await createOrder({ planId: plan.value.id, cycle: cycle.value })
    orderNo.value = order.orderNo
    orderAmount.value = Number(order.amount ?? amount.value)
    // 免费版是 0 元订单：没有"确认支付/支付"这两步的意义，直接开通
    if (orderAmount.value <= 0) {
      await confirmPaid()
      return
    }
    step.value = 1
    ElMessage.success('订单已创建，请确认支付方式')
  } catch (e) {
    showErrorMessage(e, '创建订单失败')
  } finally {
    submitting.value = false
  }
}

/** ③ → 确认已付款：调用支付接口让套餐生效 */
async function confirmPaid() {
  if (!plan.value || !orderNo.value) return
  submitting.value = true
  try {
    const result = await payOrder(orderNo.value, {
      planId: plan.value.id,
      cycle: cycle.value,
      // 支付方式一定要上报：服务端要按渠道开关校验，也要把它落进订单（收款流水按它统计）
      payMethod: payMethod.value,
    })
    paidOk.value = true
    resultExpire.value = expirePreview.value
    ElMessage.success(result.tenantPlanSynced ? '支付成功，套餐已生效' : '支付成功，套餐同步稍后重试')
  } catch (e) {
    paidOk.value = false
    errorMessage.value = e instanceof Error ? e.message : '支付失败'
  } finally {
    submitting.value = false
    step.value = 3
  }
}

async function load() {
  const query = router.currentRoute.value.query
  planId.value = String(query.planId ?? '')
  cycle.value = String(query.cycle ?? '1') === '2' ? 2 : 1
  orderNo.value = String(query.orderNo ?? '')
  orderAmount.value = Number(query.amount ?? 0)
  try {
    plans.value = await fetchPlans()
    usage.value = await fetchUsage()
  } catch (e) {
    showErrorMessage(e, '套餐加载失败')
  }
  // 支付配置：决定哪些支付方式能选。**拿不到就按"都能选"处理**——
  // 配置服务抖一下不该让企业结不了账，真关了的那一层由服务端 payOrder 兜住。
  try {
    payConfig.value = await fetchConsolePaymentConfig()
    const usable = payMethods.value.filter((item) => item.enabled)
    if (usable.length && !usable.some((item) => item.id === payMethod.value)) {
      payMethod.value = usable[0].id
      if (payMethod.value === 3) {
        ElMessage.info('平台已关闭在线支付，已为你选择对公转账')
      }
    }
  } catch {
    payConfig.value = null
  }
  // 从「我的订单」的待支付订单进来：直接落在"确认支付"这一步
  if (orderNo.value) {
    orderAmount.value = orderAmount.value || amount.value
    step.value = 1
  }
}

onMounted(load)
</script>

<style scoped>
.checkout-page { width: 100%; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; color: #0f172a; }
.page-head .sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
.btn-icon { margin-right: 4px; }
.steps { margin-bottom: 20px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 18px 10px; }
.checkout-body { display: grid; grid-template-columns: 1fr 320px; gap: 16px; align-items: start; }
.card-title { font-size: 14.5px; font-weight: 600; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.muted { color: #94a3b8; font-size: 12px; }
.mt-8 { margin-top: 8px; }
.mt-12 { margin-top: 12px; }
.empty-wrap { background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 30px; }

/* ① 订单确认 */
.plan-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; }
.plan-name { font-size: 16px; font-weight: 700; color: #0f172a; }
.plan-desc { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.plan-price { font-size: 24px; font-weight: 800; color: #2563eb; }
.plan-price small { font-size: 12px; font-weight: 500; color: #94a3b8; }
.cycle-switch { margin-top: 14px; }
.cycle-save { margin-top: 10px; font-size: 12.5px; color: #dc2626; background: #fef2f2; border: 1px solid #feecec; border-radius: 10px; padding: 8px 12px; }
.switch-note { margin-top: 10px; font-size: 12.5px; color: #475569; background: #eff6ff; border: 1px solid #dbeafe; border-radius: 10px; padding: 8px 12px; }
.agreed { margin-top: 14px; }
.actions { display: flex; gap: 10px; margin-top: 18px; }
.actions.center { justify-content: center; }

/* ② 支付确认 */
.pay-summary { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14px; }
.pay-summary span { font-size: 13px; color: #64748b; }
.pay-summary b { font-size: 28px; font-weight: 800; color: #dc2626; }
.pay-methods { display: grid; gap: 10px; margin-top: 14px; }
.pay-method { display: flex; align-items: center; gap: 12px; border: 1px solid #e6e8ef; border-radius: 12px; padding: 12px 14px; cursor: pointer; transition: border-color .18s, box-shadow .18s; }
.pay-method:hover { border-color: #bfdbfe; }
.pay-method.active { border-color: #2563eb; box-shadow: 0 0 0 3px rgba(37, 99, 235, .08); }
/* 平台关掉的渠道：置灰 + 不可点（提示写在 desc 里，别只靠颜色） */
.pay-method.disabled { cursor: not-allowed; background: #f8fafc; opacity: .72; }
.pay-method.disabled:hover { border-color: #e6e8ef; }
.pay-method.disabled .pm-name { color: #64748b; }
.pay-alert { margin-bottom: 4px; }
.pm-name { font-size: 13.5px; font-weight: 600; color: #0f172a; }
.pm-desc { font-size: 11.5px; color: #94a3b8; margin-top: 2px; }
.pm-check { margin-left: auto; color: #2563eb; }
.pm-blue { color: #2563eb; }
.pm-green { color: #10b981; }
.pm-amber { color: #f59e0b; }

/* ③ 支付页 */
.qr-wrap { display: grid; grid-template-columns: 220px 1fr; gap: 22px; align-items: center; }
.qr-box { width: 200px; height: 200px; border: 1px dashed #cbd5e1; border-radius: 14px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; background: #f8fafc; }
.qr-icon { color: #2563eb; }
.qr-tip { font-size: 11.5px; color: #94a3b8; text-align: center; padding: 0 12px; }
.qr-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.qr-steps { margin: 10px 0 14px; padding-left: 18px; font-size: 12.5px; color: #475569; line-height: 1.9; }
.qr-amount { font-size: 26px; font-weight: 800; color: #dc2626; margin-bottom: 10px; }
.bank-wrap .actions { margin-top: 14px; }

/* ④ 结果页 */
.result-card { width: 100%; }
.result { text-align: center; padding: 18px 10px 6px; }
.result-icon { color: #10b981; }
.result.fail .result-icon { color: #ef4444; }
.result-title { margin-top: 12px; font-size: 20px; font-weight: 700; color: #0f172a; }
.result-sub { margin-top: 6px; font-size: 13px; color: #64748b; }
.result-detail { max-width: 620px; margin: 20px auto 0; text-align: left; }

/* 右侧摘要 */
.side-col :deep(.el-card__body) { padding-top: 6px; }
.sum-row { display: flex; align-items: center; justify-content: space-between; padding: 7px 0; font-size: 12.5px; color: #64748b; }
.sum-row b { color: #0f172a; font-weight: 600; }
.sum-row b.save { color: #dc2626; }
.sum-total { display: flex; align-items: baseline; justify-content: space-between; }
.sum-total span { font-size: 13px; color: #64748b; }
.sum-total b { font-size: 24px; font-weight: 800; color: #dc2626; }

@media (max-width: 1200px) {
  .checkout-body { grid-template-columns: 1fr; }
  .qr-wrap { grid-template-columns: 1fr; justify-items: center; text-align: center; }
}
</style>
