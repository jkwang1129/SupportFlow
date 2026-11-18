<template>
  <div class="entry-page">
    <div class="entry-loading">
      <el-icon class="is-loading" :size="30"><Loading /></el-icon>
      <span>正在进入后台…</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../../utils/message'
import { getEnterpriseGuide } from '../../api/enterprise'
import { listChannels } from '../../api/customer/channel'
import { fetchMemberAccess } from '../../api/member'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const userStore = useUserStore()

const ONBOARD_DONE_KEY = 'supportflow_onboard_done'

onMounted(async () => {
  try {
    // 刷新页面后 store 丢失时先恢复会话
    if (!userStore.userId) {
      await userStore.fetchProfile()
    }
    // 平台账号进平台侧首页：数据概览是**企业侧**页面，平台账号没有租户、必然被指标接口拒绝，
    // 之前跳 /dashboard 会显示"经营数据加载失败：仅企业成员可查看数据报表"，让人误以为服务没重启
    if (userStore.userType === 1) {
      // 先确保权限已经拉回来，否则会按"还没加载"的兜底把人送进一个他没权限的页面
      if (!userStore.permsLoaded) {
        try {
          await userStore.fetchProfile()
        } catch {
          // 拿不到权限就按兜底顺序走，反正 AdminLayout 里还会再拉一次
        }
      }
      const candidates = [
        '/admin/reviews',
        '/platform/support',
        '/platform/tenants',
        '/platform/billing',
        '/platform/payments',
        '/platform/users',
        '/platform/roles',
      ]
      // 角色可能只勾了其中几个：挑第一个有权限的；一个都没有就退回个人中心
      router.replace(candidates.find((path) => userStore.hasPerm(path)) ?? '/profile')
      return
    }
    // 普通客服成员不进入企业开通引导，也不调用企业资料接口
    if (userStore.userType === 2) {
      try {
        const access = await fetchMemberAccess()
        if (!access.canManage) {
          router.replace('/dashboard')
          return
        }
      } catch {
        // 老版本后端无成员权限接口时，继续按企业管理员处理
      }
    }
    // 企业账号：已审核通过且已有运行渠道时直接进后台，否则进入开通引导
    const guide = await getEnterpriseGuide()
    if (guide.stage === 'APPROVED') {
      const done = localStorage.getItem(ONBOARD_DONE_KEY) === 'true'
      if (done) {
        router.replace('/dashboard')
        return
      }
      try {
        const channels = await listChannels()
        if (channels.some((c) => c.stage === 3)) {
          localStorage.setItem(ONBOARD_DONE_KEY, 'true')
          router.replace('/dashboard')
          return
        }
      } catch {
        // 渠道服务不可用时按本地标记处理
      }
      router.replace('/guide')
      return
    }
    router.replace('/guide')
  } catch {
    errorMessage(undefined, '暂时无法获取企业开通状态，已为你进入工作台')
    router.replace('/dashboard')
  }
})
</script>

<style scoped>
.entry-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(900px 500px at 12% -10%, rgba(59, 130, 246, 0.2), transparent 60%),
    #f6f7f9;
}

.entry-loading {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #475569;
  font-size: 14px;
  background: #fff;
  padding: 18px 26px;
  border-radius: 14px;
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.08);
}
</style>
