<template>
  <div v-if="userStore.userType === 2 && canManage && !hidden && !allDone" class="ob-card">
    <div class="ob-head">
      <div>
        <div class="ob-title">🎉 系统初始化 · 完成以下 4 步即可开始接待客户</div>
        <div class="ob-sub">已完成 {{ doneCount }}/{{ steps.length }} 步</div>
      </div>
      <div class="ob-right">
        <el-progress
          type="circle"
          :percentage="Math.round((doneCount / steps.length) * 100)"
          :width="38"
          :stroke-width="4"
        />
        <button class="ob-close" aria-label="关闭" title="关闭后不再提示" @click="closeGuide">
          <el-icon :size="16"><Close /></el-icon>
        </button>
      </div>
    </div>
    <div class="ob-list">
      <div
        v-for="(step, i) in steps"
        :key="step.id"
        class="ob-step"
        :class="{ done: step.done, current: i === currentIndex && !step.done }"
      >
        <span class="ob-no">
          <el-icon v-if="step.done"><Check /></el-icon>
          <template v-else>{{ i + 1 }}</template>
        </span>
        <div class="ob-info">
          <div class="ob-name">
            {{ step.label }}
            <el-tag
              size="small"
              :type="step.done ? 'success' : step.id === 'channel' ? 'primary' : 'info'"
              effect="light"
            >{{ step.done ? '已完成' : step.id === 'channel' ? '当前步骤' : '待完成' }}</el-tag>
          </div>
          <div class="ob-desc">{{ step.desc }}</div>
        </div>
        <el-button v-if="!step.done" size="small" :type="i === currentIndex ? 'primary' : 'default'" @click="go(step)">
          {{ step.id === 'channel' ? '去接入' : '去配置' }}
        </el-button>
        <el-button v-else size="small" text @click="go(step)">查看</el-button>
      </div>
    </div>
    <div class="ob-foot">
      <span class="ob-tip">渠道步骤在渠道上线后自动完成，其余步骤完成配置后可手动标记。</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listChannels } from '../api/customer/channel'
import { fetchMemberAccess } from '../api/member'
import { useUserStore } from '../stores/user'
import { getTenantCode } from '../utils/auth'

const router = useRouter()
const userStore = useUserStore()
const emit = defineEmits<{ (e: 'refresh'): void }>()
/** 关闭状态按"租户"记：同一个企业关一次就一直关着，换个企业/账号才重新提示 */
const HIDE_KEY = 'supportflow_onboard_guide_hidden'
const DONE_PREFIX = 'supportflow_ob_done_'

const hideKey = () => `${HIDE_KEY}_${getTenantCode() || 'default'}`

/**
 * 初始化引导的关闭状态。
 *
 * <p>以前只在内存里改 `hidden`，没落盘：切个菜单、刷新一下、或者重新登录，组件重新挂载，
 * 引导就又冒出来了——所以这里一开始就读本地记录，点关闭时再写回去。</p>
 */
const hidden = ref(readHidden())

function readHidden(): boolean {
  // 兼容老版本遗留的全局标记：以前关过的人不该再被弹一次
  return localStorage.getItem(hideKey()) === 'true' || localStorage.getItem(HIDE_KEY) === 'true'
}

function closeGuide() {
  hidden.value = true
  localStorage.setItem(hideKey(), 'true')
}

const canManage = ref(false)

const steps = ref([
  { id: 'channel', label: '接入第一个渠道', desc: '官网 / 微信 / 小程序任选其一', path: '/channels', done: false },
  { id: 'bot', label: '配置智能机器人', desc: '开启意图识别与多轮对话', path: '/modules/bot', done: false },
  { id: 'member', label: '邀请客服成员', desc: '创建坐席账号与技能组', path: '/members', done: false },
  { id: 'qa', label: '发起首次质检', desc: 'AI 全量初检今日会话', path: '/modules/qa', done: false },
])

const doneCount = computed(() => steps.value.filter((s) => s.done).length)
const allDone = computed(() => doneCount.value === steps.value.length)
const currentIndex = computed(() => steps.value.findIndex((s) => !s.done))

onMounted(async () => {
  try {
    if (userStore.userType === 2) {
      const access = await fetchMemberAccess()
      canManage.value = access.canManage
    }
  } catch {
    // 老版本后端无权限接口时兼容为企业管理员展示
    canManage.value = userStore.userType === 2
  }
  if (!canManage.value) {
    return
  }
  steps.value.forEach((s) => {
    s.done = localStorage.getItem(DONE_PREFIX + s.id) === 'true'
  })
  try {
    const channels = await listChannels()
    const running = channels.some((c) => c.stage === 3)
    if (running) {
      steps.value[0].done = true
      localStorage.setItem(DONE_PREFIX + 'channel', 'true')
    }
  } catch {
    // 渠道状态不可用时按本地记录展示
  }
})

function go(step: { id: string; path: string; done: boolean }) {
  if (step.id !== 'channel' && !step.done) {
    localStorage.setItem(DONE_PREFIX + step.id, 'true')
    step.done = true
  }
  if (router.currentRoute.value.path === step.path) {
    emit('refresh')
    return
  }
  router.push(step.path)
}
</script>

<style scoped>
.ob-card {
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
  border: 1px solid #dbeafe;
  border-radius: 16px;
  padding: 12px 16px;
  margin-bottom: 14px;
}

.ob-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.ob-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.ob-close {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s;
}

.ob-close:hover {
  background: #eff6ff;
  color: #2563eb;
}

.ob-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.ob-sub {
  font-size: 11.5px;
  color: #64748b;
  margin-top: 2px;
}

.ob-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  margin-top: 8px;
  gap: 8px;
}

.ob-step {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border: 1px solid #e6e8ef;
  border-radius: 12px;
  padding: 6px 12px;
}

.ob-step.done {
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.ob-step.current {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08);
}

.ob-no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.ob-step.done .ob-no {
  background: #bbf7d0;
  color: #16a34a;
}

.ob-step.current .ob-no {
  background: #2563eb;
  color: #fff;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.14);
}

.ob-info {
  flex: 1;
  min-width: 0;
}

.ob-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ob-desc {
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: 2px;
}

.ob-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 8px;
}

.ob-tip {
  color: #94a3b8;
  font-size: 11.5px;
}

@media (max-width: 900px) {
  .ob-list {
    grid-template-columns: 1fr;
  }
}
</style>
