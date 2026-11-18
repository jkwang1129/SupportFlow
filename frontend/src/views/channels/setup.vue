<template>
  <div class="setup-page">
    <div class="setup-head">
      <el-button text @click="router.push('/channels')">← 返回渠道列表</el-button>
      <span class="setup-title">{{ stageTitle }}</span>
      <span class="mono" v-if="current">{{ current.name }}</span>
    </div>

    <el-card v-loading="loading" shadow="never" class="setup-card">
      <!-- 创建渠道 -->
      <template v-if="stage === 'create'">
        <h3>创建渠道</h3>
        <div class="mini-type">
          <el-radio-group v-model="selectedType">
            <el-radio-button :value="1" :disabled="activeTypes.includes(1)">官网 / 网站</el-radio-button>
            <el-radio-button :value="2" :disabled="activeTypes.includes(2)">微信公众号</el-radio-button>
            <el-radio-button :value="3" :disabled="activeTypes.includes(3)">微信小程序</el-radio-button>
            <el-radio-button :value="4" :disabled="activeTypes.includes(4)">官方 App</el-radio-button>
            <el-radio-button :value="5" :disabled="activeTypes.includes(5)">400 电话热线</el-radio-button>
            <el-radio-button :value="6" :disabled="activeTypes.includes(6)">邮件工单</el-radio-button>
          </el-radio-group>
        </div>
        <!-- 同一企业同一种渠道只能有一个生效中：已经有生效渠道的类型直接禁用，别让人白填一遍 -->
        <p v-if="activeTypes.length" class="type-hint">
          灰掉的类型已经有「生效中」的渠道：{{ typeNames(activeTypes) }}。
          同一企业同一种渠道只能保留一个生效中——要换渠道，请先在列表里停用旧的，再回来新建。
        </p>
        <el-form label-position="top" class="form">
          <el-form-item label="渠道名称"><el-input v-model="name" placeholder="例如：官网在线客服" maxlength="64" /></el-form-item>
          <el-form-item label="渠道描述"><el-input v-model="desc" type="textarea" :rows="3" maxlength="255" /></el-form-item>
          <el-button type="primary" :loading="submitting" @click="submitCreate">创建渠道</el-button>
        </el-form>
      </template>

      <!-- 获取密钥 -->
      <template v-else-if="stage === 'key'">
        <h3>获取密钥</h3>
        <p class="desc">密钥用于 SDK / 服务端验签，页面仅展示部分内容，点击复制获得完整密钥。</p>
        <!-- 密钥一律打码显示，页面上不回显明文；要拿去用就点「复制完整密钥」（复制到剪贴板，不落到屏幕上） -->
        <el-input :model-value="maskKey(current?.appKey || '')" readonly class="mono">
          <template #append><el-button @click="copyKey">复制完整密钥</el-button></template>
        </el-input>
        <el-button class="action-btn" text type="primary" :loading="rotating" @click="rotateKey">重新生成密钥</el-button>

        <!-- 重新生成后给一个醒目的提示（不显示明文）：新密钥立刻要复制走，否则旧密钥已失效、
             新密钥又没存下来，这条渠道就废了 -->
        <div v-if="newKey" class="new-key">
          <div class="nk-title">新密钥已生成 · 旧密钥立即失效</div>
          <el-button type="primary" size="small" @click="copyNewKey">复制新密钥</el-button>
          <p class="nk-tip">
            为安全起见页面不回显明文，请点上面的按钮复制后立刻保存到你的配置里。
            换了密钥要同步更新：嵌入代码里的 <span class="mono">data-app-id</span>、服务端验签用的密钥。
          </p>
        </div>

        <!-- 联调入口：直接以访客身份打开会话，省得手拼地址 -->
        <div class="test-entry">
          <div class="te-title">在访客端试一下</div>
          <p class="te-desc">
            打开下面的地址就会以访客身份发起一条会话，坐席工作台（在线客服）里立刻能看到。
            访客身份存在浏览器本地：同一个浏览器反复打开认的是同一个客户（会复用没结束的会话），
            想模拟新客户就用"以新访客打开"。这串 appId 是渠道的公开应用标识，正式接入建议由挂件脚本注入。
          </p>
          <el-input :model-value="visitorUrl" readonly class="mono">
            <template #append><el-button @click="copyVisitorUrl">复制测试地址</el-button></template>
          </el-input>
          <div class="te-actions">
            <el-button type="primary" :disabled="!current?.appKey" @click="openVisitorTest(true)">以新访客打开</el-button>
            <el-button :disabled="!current?.appKey" @click="openVisitorTest(false)">以同一访客再打开</el-button>
            <span class="muted">新访客的访客名带随机后缀，工作台里一眼能分出是哪条测试会话</span>
          </div>
        </div>

        <!-- 官网挂件：贴一行 script 就能嵌入 -->
        <div class="embed-entry">
          <div class="te-title">嵌入到自己官网</div>
          <p class="te-desc">
            把下面这一行贴到官网页面里（建议放在 <span class="mono">&lt;/body&gt;</span> 前），右下角就会出现客服入口；
            加 <span class="mono">data-auto-open="1"</span> 可以让访客一进来就展开会话窗口。
          </p>
          <el-input :model-value="embedSnippet" type="textarea" :rows="3" readonly class="mono" />
          <div class="te-actions">
            <el-button @click="copyEmbedSnippet">复制嵌入代码</el-button>
            <span class="muted">data-app-id 是公开标识，访问控制在服务端（域名白名单 + 限流 + 访客令牌）</span>
          </div>
        </div>

        <div class="footer-actions">
          <el-button type="primary" @click="router.push('/channels')">完成接入</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../../stores/user'
import {
  channelTypeLabel,
  createChannel,
  listChannels,
  rotateChannelKey,
  type ChannelResult,
} from '../../api/customer/channel'
import { openVisitorTestTab, visitorTestUrl } from '../../utils/visitor'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const stage = computed(() => String(route.params.stage || 'create'))
const channelId = computed(() => String(route.params.channelId || ''))
const loading = ref(false)
const submitting = ref(false)
const rotating = ref(false)
const current = ref<ChannelResult | null>(null)
const selectedType = ref(Number(route.query.type || 1))
/** 已经「生效中」的渠道类型：同一种渠道只允许一个生效中，这些类型在创建页直接禁用 */
const activeTypes = ref<number[]>([])
const name = ref('')
const desc = ref('')
/** 刚刚重新生成出来的新密钥：只留在内存里供"复制"，页面上不回显明文 */
const newKey = ref('')

const stageTitle = computed(() => ({
  create: '创建渠道',
  key: '获取密钥',
}[stage.value] || '渠道接入'))

onMounted(async () => {
  try {
    if (!userStore.userId) await userStore.fetchProfile()
    if (userStore.userType === 2 && userStore.tenantCode === 'PLATFORM') {
      await userStore.refreshToken()
    }
  } catch { /* ignore */ }
  await init()
})

async function init() {
  loading.value = true
  try {
    const channels = await listChannels()
    activeTypes.value = channels
      .filter((item) => item.status !== 2)
      .map((item) => item.channelType)
    if (stage.value === 'create') {
      // 从列表带 type 进来、但这个类型已经有生效渠道时，自动切到第一个还能建的类型
      if (activeTypes.value.includes(selectedType.value)) {
        const free = [1, 2, 3, 4, 5, 6].find((type) => !activeTypes.value.includes(type))
        if (free) selectedType.value = free
      }
      return
    }
    current.value = channels.find((c) => c.id === channelId.value) || null
    if (!current.value) {
      ElMessage.warning('渠道不存在')
      router.replace('/channels')
      return
    }
    if (current.value.stage === 3) {
      localStorage.setItem('supportflow_onboard_done', 'true')
    }
    newKey.value = ''
  } finally {
    loading.value = false
  }
}

async function submitCreate() {
  if (!name.value.trim()) return ElMessage.warning('请填写渠道名称')
  // 前端禁用只是提前拦一道，真正的闸门在后端（并发时也只有服务端挡得住）
  if (activeTypes.value.includes(selectedType.value)) {
    return ElMessage.warning('这种渠道已经有一个生效中的了：请先停用旧的，或直接使用现有渠道')
  }
  submitting.value = true
  try {
    const created = await createChannel({ channelType: selectedType.value, name: name.value.trim(), desc: desc.value.trim() || undefined })
    ElMessage.success('渠道已创建')
    router.push(`/channels/setup/key/${created.id}`)
  } finally {
    submitting.value = false
  }
}

async function copyKey() {
  if (!current.value?.appKey) return
  await navigator.clipboard.writeText(current.value.appKey)
  ElMessage.success('完整密钥已复制')
}

async function rotateKey() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('重新生成后旧密钥立即失效，是否继续？', '重新生成密钥', { type: 'warning' })
  } catch {
    // 点「取消」也会走 reject：这不是错误，直接返回，别让它冒成未捕获异常
    return
  }
  const channelId = current.value.id
  rotating.value = true
  try {
    const rotated = await rotateChannelKey(channelId)
    // 正常情况后端会把新密钥放在返回里；万一没带（老版本服务端），回列表补一次——
    // 否则输入框会空着，看起来就像"点了没反应"
    const fresh = rotated?.appKey
      ? rotated
      : (await listChannels()).find((item) => item.id === channelId) || rotated
    current.value = fresh
    newKey.value = fresh?.appKey || ''
    if (newKey.value) {
      ElMessage.success('新密钥已生成，旧密钥已失效')
    } else {
      ElMessage.warning('密钥已重新生成，但没取到新密钥：刷新页面后点「复制完整密钥」获取')
    }
  } finally {
    rotating.value = false
  }
}

async function copyNewKey() {
  if (!newKey.value) return
  await navigator.clipboard.writeText(newKey.value)
  ElMessage.success('新密钥已复制')
}

function maskKey(key: string) {
  if (key.length <= 10) return key.replace(/./g, '*')
  return `${key.slice(0, 6)}${'*'.repeat(18)}${key.slice(-4)}`
}

/** 把渠道类型码翻成人话（提示里要说清楚是哪几种已经生效了） */
function typeNames(types: number[]) {
  return types.map((type) => channelTypeLabel(type)).join('、')
}

/** 访客测试地址（复制给手机/别的浏览器时用同一个访客） */
const visitorUrl = computed(() => {
  return current.value ? visitorTestUrl(current.value) : ''
})

/** 官网嵌入代码：客户网站贴这一行就能出现客服挂件 */
const embedSnippet = computed(
  () =>
    '<script src="' +
    window.location.origin +
    '/widget.js" data-app-id="' +
    (current.value?.appKey || '') +
    '" async></' +
    'script>',
)

function openVisitorTest(fresh: boolean) {
  if (!current.value || !openVisitorTestTab(current.value, { fresh })) {
    ElMessage.warning('该渠道还没有可用密钥，先重新生成一个')
  }
}

async function copyVisitorUrl() {
  await navigator.clipboard.writeText(visitorUrl.value)
  ElMessage.success('测试地址已复制，可以贴到手机或其他浏览器打开')
}

async function copyEmbedSnippet() {
  await navigator.clipboard.writeText(embedSnippet.value)
  ElMessage.success('嵌入代码已复制，贴到官网页面即可')
}
</script>

<style scoped>
.setup-page { width: 100%; min-width: 0; }
.setup-head { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.setup-title { font-size: 20px; font-weight: 700; color: #0f172a; }
.setup-card { padding-bottom: 16px; }
h3 { margin: 0 0 16px; font-size: 17px; }
.desc { color: #64748b; font-size: 13px; margin-bottom: 14px; }
.form { width: 100%; }
.mini-type { margin-bottom: 16px; }
.action-btn { padding-left: 0; }

/* 新生成密钥的提示块：绿底 + 边框，别让人漏掉（漏掉就等于旧密钥已失效、新密钥还没存） */
.new-key {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid #bbf7d0;
  background: #f0fdf4;
  border-radius: 10px;
}
.nk-title { font-size: 13px; font-weight: 600; color: #15803d; margin-bottom: 8px; }
.nk-tip { margin: 8px 0 0; font-size: 12px; line-height: 1.7; color: #166534; }

/* 创建页的类型提示：哪些类型已经有生效中的渠道了 */
.type-hint {
  margin: 10px 0 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
  font-size: 12.5px;
  line-height: 1.7;
}
.footer-actions { margin-top: 22px; }
.check-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.muted { color: #94a3b8; }
.result { display: grid; gap: 10px; margin-top: 18px; }
.default-tag { margin-left: 8px; color: #2563eb; background: #eff6ff; border-radius: 999px; padding: 1px 6px; font-size: 11px; }
.success-alert { margin-top: 18px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.test-entry { margin-top: 22px; padding: 16px 18px; border: 1px solid #dbeafe; border-radius: 12px; background: #f8fbff; }
.embed-entry { margin-top: 16px; padding: 16px 18px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fbfdff; }
.te-title { font-size: 15px; font-weight: 600; color: #0f172a; margin-bottom: 8px; }
.te-desc { font-size: 13px; line-height: 1.7; color: #64748b; margin: 0 0 12px; }
.te-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
</style>
