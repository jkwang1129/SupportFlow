<template>
  <div class="channels-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">渠道接入</h2>
        <p class="page-sub">管理六种客户入口：网站 / 公众号 / 小程序 / 官方 App / 400 热线 / 邮件</p>
        <p class="page-hint">
          渠道效果演示里的对话是拟真演示，不会产生真实会话；要用真实渠道体验，点列表里的「测试会话」。
        </p>
      </div>
      <div class="head-actions">
        <!-- 照原型：渠道效果演示（六大渠道接入后客户看到的样子） -->
        <el-button @click="router.push('/channel-demos')">
          <el-icon><VideoPlay /></el-icon>渠道效果演示
        </el-button>
        <!-- 照原型：接入文档（六种渠道的接入方式、开放接口与安全规范） -->
        <el-button @click="router.push('/channels/docs')">
          <el-icon><Document /></el-icon>接入文档
        </el-button>
        <el-button type="primary" @click="router.push('/channels/setup/create')">新增渠道</el-button>
      </div>
    </div>

    <OnboardingGuide @refresh="refreshFromGuide" />

    <el-table v-if="channels.length" :data="channels" v-loading="loading" stripe>
      <el-table-column type="index" label="序号" width="70" />
      <el-table-column label="渠道" width="180">
        <template #default="{ row }">
          <div class="channel-name">
            <span class="channel-icon"><el-icon :size="18"><component :is="iconOf(row.channelType)" /></el-icon></span>
            {{ channelTypeLabel(row.channelType) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="创建人" width="130">
        <template #default="{ row }">{{ row.creatorName || '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 2 ? 'info' : 'success'" effect="light">{{ row.status === 2 ? '已禁用' : '生效中' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channelId" label="渠道 ID" min-width="200">
        <template #default="{ row }"><span class="mono">{{ row.channelId }}</span></template>
      </el-table-column>
      <el-table-column label="技能组" min-width="150">
        <template #default="{ row }">
          <el-tag v-if="groupNameOf(row.skillGroupId)" size="small" effect="light" type="primary">
            {{ groupNameOf(row.skillGroupId) }}
          </el-tag>
          <span v-else class="muted">未指定</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="370" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openBindGroup(row)">绑定技能组</el-button>
          <el-button link type="primary" @click="openVisitorTest(row)">测试会话</el-button>
          <el-button
            v-if="row.channelType >= 4"
            link
            type="primary"
            @click="router.push(`/channels/native/${row.id}`)"
          >
            原生接入配置
          </el-button>
          <el-button
            v-if="row.channelType === 2 || row.channelType === 3"
            link
            type="primary"
            @click="router.push(`/channels/wechat/${row.id}`)"
          >
            微信接入配置
          </el-button>
          <el-button link type="primary" @click="router.push(`/channels/setup/key/${row.id}`)">查看密钥</el-button>
          <el-button v-if="row.status !== 2" link type="danger" @click="toggleStatus(row, false)">禁用</el-button>
          <el-button v-else link type="success" @click="toggleStatus(row, true)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-else-if="!loading" class="empty-wrap">
      <div class="empty-title">接入第一个渠道</div>
      <div class="empty-sub">官网 / 微信 / 小程序任选其一</div>
      <div class="type-grid">
        <div v-for="t in channelTypes" :key="t.type" class="type-card" @click="router.push(`/channels/setup/create?type=${t.type}`)">
          <span class="type-icon" :class="`tone-${t.type}`"><el-icon :size="26"><component :is="t.icon" /></el-icon></span>
          <div class="type-name">{{ t.name }}</div>
          <div class="type-desc">{{ t.desc }}</div>
        </div>
      </div>
      <el-button class="empty-demo" link type="primary" @click="router.push('/channel-demos')">
        先看看渠道接入后客户看到的效果 →
      </el-button>
    </div>

    <!-- 绑定技能组：这个渠道进来的会话优先分给组内在线坐席，组内没人再按排队升级放宽 -->
    <el-dialog v-model="bindVisible" :title="`绑定技能组 · ${bindRow?.name || ''}`" width="460px">
      <div class="bind-tip">
        这个渠道进来的会话会优先分给组内在线坐席；组内没人时按该组的「排队升级」时长放宽给其他在线坐席。选「不指定」表示不限技能组。
      </div>
      <el-select v-model="bindGroupId" placeholder="请选择技能组" style="width: 100%">
        <el-option value="" label="不指定（不限技能组）" />
        <el-option
          v-for="group in groups"
          :key="group.id"
          :value="group.id"
          :label="`${group.name}（${group.members.length} 人）`"
        />
      </el-select>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="binding" @click="submitBindGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../../stores/user'
import OnboardingGuide from '../../components/OnboardingGuide.vue'
import { channelTypeLabel, listChannels, updateChannelStatus, type ChannelResult } from '../../api/customer/channel'
import { openVisitorTestTab } from '../../utils/visitor'
import {
  bindChannelSkillGroup,
  listSkillGroups,
  type SkillGroupItem,
} from '../../api/customer/skillGroup'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const channels = ref<ChannelResult[]>([])
const groups = ref<SkillGroupItem[]>([])
const bindVisible = ref(false)
const binding = ref(false)
const bindRow = ref<ChannelResult | null>(null)
const bindGroupId = ref('')
const channelTypes = [
  { type: 1, name: '官网 / 网站', desc: '在官网右下角嵌入悬浮客服组件', icon: 'Monitor' },
  { type: 2, name: '微信公众号', desc: '菜单跳转 H5 客服页；原生消息回调与身份映射已支持（需公众号后台配服务器地址）', icon: 'ChatDotRound' },
  { type: 3, name: '微信小程序', desc: 'web-view 内嵌客服页；原生客服消息回调与身份映射已支持（需小程序后台配服务器地址）', icon: 'Iphone' },
  { type: 4, name: '官方 App', desc: 'App 内嵌客服 + 离线推送（原生接入已支持）', icon: 'Cellphone' },
  { type: 5, name: '400 电话热线', desc: '呼叫中心对接：语音导航 + 按键分流 + 通话小结建单（需话务线路，回调接口已就绪）', icon: 'PhoneFilled' },
  { type: 6, name: '邮件工单', desc: 'IMAP 收信自动建会话（发件人即客户身份）+ 坐席回复走 SMTP 同主题回信', icon: 'Message' },
]

onMounted(async () => {
  try {
    if (!userStore.userId) await userStore.fetchProfile()
    if (userStore.userType === 2 && userStore.tenantCode === 'PLATFORM') {
      await userStore.refreshToken()
    }
  } catch {
    // ignore
  }
  await load()
})

async function load() {
  loading.value = true
  try {
    const [channelList, groupList] = await Promise.all([
      listChannels(),
      listSkillGroups().catch(() => [] as SkillGroupItem[]),
    ])
    channels.value = channelList
    groups.value = groupList
    if (channels.value.some((c) => c.stage === 3)) {
      localStorage.setItem('supportflow_onboard_done', 'true')
    }
  } finally {
    loading.value = false
  }
}

/** 渠道绑定的技能组名（没绑就返回空） */
function groupNameOf(skillGroupId?: number | string | null) {
  if (skillGroupId === null || skillGroupId === undefined || skillGroupId === '') {
    return ''
  }
  const group = groups.value.find((item) => item.id === String(skillGroupId))
  return group?.name || ''
}

function openBindGroup(row: ChannelResult) {
  bindRow.value = row
  bindGroupId.value = row.skillGroupId ? String(row.skillGroupId) : ''
  bindVisible.value = true
}

async function submitBindGroup() {
  if (!bindRow.value) {
    return
  }
  binding.value = true
  try {
    await bindChannelSkillGroup(bindRow.value.id, bindGroupId.value || null)
    ElMessage.success('渠道绑定已更新')
    bindVisible.value = false
    await load()
  } finally {
    binding.value = false
  }
}

function refreshFromGuide() {
  load()
  ElMessage.success('已刷新渠道列表')
}

function iconOf(type: number) {
  return channelTypes.find((t) => t.type === type)?.icon || 'Monitor'
}

/** 测试会话：每次都以新访客身份打开，方便反复造测试会话 */
function openVisitorTest(row: ChannelResult) {
  if (!openVisitorTestTab(row, { fresh: true })) {
    ElMessage.warning('该渠道还没有可用密钥，先生成密钥再测试')
  }
}

function formatTime(time?: string) {
  return time ? time.replace('T', ' ').slice(0, 19) : '—'
}

async function toggleStatus(row: ChannelResult, enabled: boolean) {
  await ElMessageBox.confirm(
    enabled ? `确认启用渠道「${row.name}」吗？` : `确认禁用渠道「${row.name}」吗？禁用后该渠道将暂停接待客户。`,
    enabled ? '启用渠道' : '禁用渠道',
    { type: 'warning' },
  )
  await updateChannelStatus(row.id, enabled)
  ElMessage.success(enabled ? '渠道已启用' : '渠道已禁用')
  await load()
}
</script>

<style scoped>
.channels-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.head-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.page-hint { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }
.channel-name { display: flex; align-items: center; gap: 8px; }
.channel-icon { width: 28px; height: 28px; border-radius: 8px; background: #eff6ff; color: #2563eb; display: flex; align-items: center; justify-content: center; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.muted { color: #94a3b8; font-size: 13px; }
.bind-tip { font-size: 13px; line-height: 1.7; color: #64748b; margin-bottom: 12px; }
.empty-wrap { text-align: center; padding: 30px 0 10px; }
.empty-title { font-size: 20px; font-weight: 700; color: #0f172a; }
.empty-sub { color: #94a3b8; font-size: 13px; margin: 8px 0 24px; }
.type-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; }
.type-card { border: 1px solid #e6e8ef; border-radius: 16px; padding: 26px 18px; cursor: pointer; transition: all .2s; background: #fff; }
.type-card:hover { border-color: #93c5fd; box-shadow: 0 12px 32px rgba(37,99,235,.12); transform: translateY(-2px); }
.type-icon { width: 52px; height: 52px; border-radius: 14px; display: inline-flex; align-items: center; justify-content: center; color: #fff; }
.tone-1 { background: linear-gradient(135deg,#1d4ed8,#60a5fa); }
.tone-2 { background: linear-gradient(135deg,#0284c7,#38bdf8); }
.tone-3 { background: linear-gradient(135deg,#2563eb,#38bdf8); }
.type-name { font-size: 15px; font-weight: 700; color: #0f172a; margin-top: 14px; }
.type-desc { font-size: 12px; color: #94a3b8; margin-top: 6px; line-height: 1.7; }
.empty-demo { margin-top: 22px; }
@media (max-width: 820px) { .type-grid { grid-template-columns: 1fr; } }
</style>
