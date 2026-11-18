<template>
  <div class="wechat-page" v-loading="loading">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">{{ channelName }} · 微信原生接入配置</h2>
        <p class="page-sub">
          服务器配置（收消息）· 网页授权（认微信身份）· 模板消息（物流 / 工单 / 优惠券）
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/channels')">返回渠道接入</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </div>

    <el-card shadow="never" class="mb-16">
      <template #header><span class="card-title">填到微信后台的两个地址</span></template>
      <div v-if="detail?.configured" class="hook-list">
        <div class="hook-row">
          <el-tag size="small" effect="plain">服务器地址(URL)</el-tag>
          <span class="mono hook-url">{{ detail?.callbackUrl }}</span>
          <el-button link type="primary" size="small" @click="copy(detail?.callbackUrl || '')">复制</el-button>
        </div>
        <div class="hook-row">
          <el-tag size="small" effect="plain">网页授权回调</el-tag>
          <span class="mono hook-url">{{ detail?.oauthUrl }}</span>
          <el-button link type="primary" size="small" @click="copy(detail?.oauthUrl || '')">复制</el-button>
        </div>
        <div class="hook-tip">
          公众号：设置与开发 → 服务器配置（Token / EncodingAESKey 与下面一致，推荐安全模式）；
          小程序：开发设置 → 消息推送（数据格式选 JSON，URL 用「服务器地址」这条，把小程序的 AppID / Secret 填进来）。
          另外记得把服务器出口 IP 加进<b>IP 白名单</b>，否则调微信接口会报 <span class="mono">40164</span>。
        </div>
      </div>
      <div v-else class="muted">保存配置后这里会显示回调地址</div>
    </el-card>

    <el-card shadow="never" class="mb-16">
      <template #header><span class="card-title">渠道配置</span></template>
      <el-form label-position="top">
        <el-form-item label="平台类型" required>
          <el-radio-group v-model="form.platform">
            <el-radio-button :value="1">公众号</el-radio-button>
            <el-radio-button :value="2">小程序</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="AppID" required>
          <el-input v-model="form.appId" placeholder="wx 开头的 18 位字符串" />
        </el-form-item>
        <el-form-item>
          <template #label>
            AppSecret
            <el-tag v-if="detail?.appSecretConfigured" size="small" type="success" effect="plain">已配置</el-tag>
          </template>
          <el-input v-model="form.appSecret" type="password" show-password
                    :placeholder="detail?.appSecretConfigured ? '留空表示不修改' : '公众号 / 小程序后台 → 基本配置'" />
        </el-form-item>
        <el-form-item>
          <template #label>
            Token（服务器配置里的 Token）
            <el-tag v-if="detail?.tokenConfigured" size="small" type="success" effect="plain">已配置</el-tag>
          </template>
          <el-input v-model="form.token" type="password" show-password
                    :placeholder="detail?.tokenConfigured ? '留空表示不修改' : '自己起一个 3~32 位字母数字'" />
        </el-form-item>
        <el-form-item>
          <template #label>
            EncodingAESKey（安全模式用，43 位）
            <el-tag v-if="detail?.aesKeyConfigured" size="small" type="success" effect="plain">已配置</el-tag>
          </template>
          <el-input v-model="form.aesKey" type="password" show-password
                    :placeholder="detail?.aesKeyConfigured ? '留空表示不修改' : '后台随机生成，消息加解密选安全模式时必填'" />
        </el-form-item>
        <el-form-item label="关注 / 进入客服会话时的欢迎语">
          <el-input v-model="form.welcomeText" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }"
                    placeholder="您好，欢迎关注！有什么可以帮您？直接在这里发消息就行。" />
        </el-form-item>
        <el-form-item label="模板消息 ID（物流提醒 / 工单进展 / 优惠券到账）">
          <div class="template-grid">
            <el-input v-model="form.templateLogistics" placeholder="物流提醒模板 ID" />
            <el-input v-model="form.templateTicket" placeholder="工单进展模板 ID" />
            <el-input v-model="form.templateCoupon" placeholder="优惠券到账模板 ID" />
          </div>
        </el-form-item>
        <!-- 小程序订阅消息：与公众号模板消息不是一套模板，模板 ID 要在小程序后台「订阅消息」里申请 -->
        <el-form-item v-if="form.platform === 2" label="订阅消息模板 ID（小程序专用）">
          <div class="template-grid">
            <el-input v-model="form.subscribeTicket" placeholder="工单进展订阅模板 ID" />
            <el-input v-model="form.subscribeService" placeholder="客服接入订阅模板 ID" />
          </div>
          <div class="muted mt-6">
            小程序订阅消息是一次性订阅：客户点一次授权、平台才能发一次。模板要在小程序后台
            「订阅消息」里选（字段选 工单号 / 工单标题 / 当前状态 / 时间，或 客服 / 时间 / 服务说明），
            与上面的公众号模板 ID 不通用。
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">联调</span></template>
      <div class="sim-actions">
        <el-button :loading="testing" @click="testTemplate">发一条测试模板消息</el-button>
        <el-button v-if="form.platform === 2" :loading="testingSubscribe" @click="testSubscribe">
          发一条测试订阅消息
        </el-button>
        <span class="muted">
          模板消息会发给最近一位关注者；公众号 / 小程序本身的消息联调，直接在微信里给公众号发句话即可
        </span>
      </div>
      <!-- 小程序订阅消息：发送记录（"到底发了没、为什么没发"必须看得见） -->
      <el-table
        v-if="subscribeLogs.length"
        :data="subscribeLogs"
        size="small"
        class="mt-12"
      >
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="sceneText" label="场景" width="100" />
        <el-table-column prop="bizNo" label="业务号" width="200" show-overflow-tooltip />
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : (row.status === 2 ? 'danger' : 'info')"
                    effect="light">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errcode">errcode={{ row.errcode }} · </span>{{ row.message }}
          </template>
        </el-table-column>
      </el-table>
      <el-alert
        v-if="testResult"
        class="mt-12"
        type="info"
        show-icon
        :closable="false"
        :title="testResult"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getWeChatConfig, saveWeChatConfig, type WeChatConfigDetail } from '../../api/customer/wechatChannel'
import { request } from '../../api/request'
import { listChannels } from '../../api/customer/channel'

const route = useRoute()
const router = useRouter()
const channelId = String(route.params.channelId || '')

const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const detail = ref<WeChatConfigDetail | null>(null)
const channelName = ref('微信公众号')
const testResult = ref('')
/** 小程序订阅消息：测试发送中 + 最近发送记录 */
const testingSubscribe = ref(false)
const subscribeLogs = ref<Array<{
  id: string
  sceneText: string
  bizNo?: string | null
  status: number
  statusText: string
  errcode?: number | null
  message?: string | null
  createTime?: string | null
}>>([])

/** 拉最近发送记录（页面加载与测试发送后都要刷新，否则"发了没"只能靠猜） */
async function fetchSubscribeLogs(id: string) {
  return await request<typeof subscribeLogs.value>({
    url: `/customer/channels/${id}/wechat/subscribe-logs`,
    method: 'get',
    params: { limit: 5 },
    silent: true,
  }).catch(() => [])
}

const form = reactive({
  platform: 1,
  appId: '',
  appSecret: '',
  token: '',
  aesKey: '',
  welcomeText: '',
  templateLogistics: '',
  templateTicket: '',
  templateCoupon: '',
  subscribeTicket: '',
  subscribeService: '',
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const channels = await listChannels().catch(() => [])
    const channel = channels.find((item) => String(item.id) === channelId)
    if (channel) {
      channelName.value = channel.name
      // 渠道类型 3（小程序）默认选中「小程序」
      form.platform = channel.channelType === 3 ? 2 : 1
    }
    const data = await getWeChatConfig(channelId)
    detail.value = data
    form.appId = data.appId || ''
    form.welcomeText = data.welcomeText || ''
    form.templateLogistics = data.templateLogistics || ''
    form.templateTicket = data.templateTicket || ''
    form.templateCoupon = data.templateCoupon || ''
    form.subscribeTicket = data.subscribeTicket || ''
    form.subscribeService = data.subscribeService || ''
    if (data.platform) {
      form.platform = data.platform
    }
    form.appSecret = ''
    form.token = ''
    form.aesKey = ''
    subscribeLogs.value = await fetchSubscribeLogs(channelId)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.appId.trim()) {
    ElMessage.warning('AppID 必填')
    return
  }
  saving.value = true
  try {
    await saveWeChatConfig(channelId, { ...form })
    ElMessage.success('微信渠道配置已保存')
    await load()
  } finally {
    saving.value = false
  }
}

async function testTemplate() {
  testing.value = true
  try {
    const result = await request<{ sent: boolean; openid: string; kind: string }>({
      url: `/customer/channels/${channelId}/wechat/test-template`,
      method: 'post',
      data: { kind: 'TICKET' },
    })
    testResult.value = result.sent
      ? `已发送（模板：工单进展 → ${result.openid}）`
      : '没有发送成功：请检查是否已配模板 ID，以及是否已有客户关注 / 进过会话'
  } finally {
    testing.value = false
  }
}

/** 小程序订阅消息：发一条测试 + 把最近发送记录拉回来（失败原因要当场看到） */
async function testSubscribe() {
  testingSubscribe.value = true
  try {
    const result = await request<{ sent: boolean; message: string }>({
      url: `/customer/channels/${channelId}/wechat/test-subscribe`,
      method: 'post',
      data: { scene: 1 },
    })
    testResult.value = result.message
    subscribeLogs.value = await fetchSubscribeLogs(channelId)
  } finally {
    testingSubscribe.value = false
  }
}

async function copy(text: string) {
  if (!text) return
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}
</script>

<style scoped>
.wechat-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; gap: 10px; }
.card-title { font-size: 14.5px; font-weight: 600; color: #0f172a; }
.mb-16 { margin-bottom: 16px; }
.mt-12 { margin-top: 12px; }
.hook-list { display: grid; gap: 10px; }
.hook-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hook-url { font-size: 13px; color: #0f172a; word-break: break-all; }
.hook-tip { font-size: 12px; line-height: 1.9; color: #64748b; }
.template-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; width: 100%; }
.sim-actions { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.muted { color: #94a3b8; font-size: 12.5px; }
@media (max-width: 900px) { .template-grid { grid-template-columns: 1fr; } }
</style>
