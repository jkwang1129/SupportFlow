<template>
  <div class="profile-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">个人中心</h2>
        <p class="page-sub">账号资料与安全设置</p>
      </div>
    </div>

    <div class="profile-grid">
      <el-card class="profile-card" shadow="never">
        <template #header>
          <span class="card-title">账号资料</span>
        </template>
        <div class="profile-hero">
          <div class="hero-avatar" :class="avatarClass">
            {{ avatarText }}
            <span class="online-dot"></span>
          </div>
          <div class="hero-info">
            <div class="hero-name">{{ userStore.name || '未设置' }}</div>
            <div class="hero-role">{{ roleText }}</div>
          </div>
        </div>
        <div class="info-list">
          <div class="info-row">
            <span class="k">用户编号</span>
            <span class="v mono">{{ userStore.userNo || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="k">账号类型</span>
            <span class="v">{{ userStore.userType === 1 ? '平台账号' : '企业账号' }}</span>
          </div>
          <div class="info-row">
            <span class="k">归属租户</span>
            <span class="v mono">{{ tenantLabel }}</span>
          </div>
          <div class="info-row">
            <span class="k">登录方式</span>
            <span class="v">手机号 / 邮箱 / 用户编号</span>
          </div>
        </div>
      </el-card>

      <el-card class="profile-card" shadow="never">
        <template #header>
          <span class="card-title">安全建议</span>
        </template>
        <div class="security-list">
          <div class="security-item">
            <div class="sec-icon"><el-icon :size="18"><Lock /></el-icon></div>
            <div class="sec-info">
              <div class="sec-title">账号密码</div>
              <div class="sec-sub">建议定期修改密码，密码需包含字母和数字</div>
            </div>
            <el-button size="small" @click="pwdVisible = true">前往修改</el-button>
          </div>
          <div class="security-item">
            <div class="sec-icon"><el-icon :size="18"><Bell /></el-icon></div>
            <div class="sec-info">
              <div class="sec-title">登录提醒</div>
              <div class="sec-sub">如发现异常登录，请立即修改密码并联系平台运营</div>
            </div>
            <el-tag size="small" type="success">已开启</el-tag>
          </div>
        </div>
        <div class="card-note">
          更多安全设置（绑定手机、二次验证、登录设备管理）将在后续版本开放。
        </div>
      </el-card>
    </div>

    <ChangePasswordDialog v-model="pwdVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useUserStore } from '../../stores/user'
import ChangePasswordDialog from '../../components/ChangePasswordDialog.vue'

const userStore = useUserStore()
const pwdVisible = ref(false)

const roleText = computed(() => {
  if (userStore.userType === 1) return '平台管理员'
  if (userStore.userType === 2) return '企业管理员'
  return '系统用户'
})

const tenantLabel = computed(() => {
  const code = userStore.tenantCode
  if (!code || code === 'PLATFORM') return '平台默认（未开通独立租户）'
  return code
})

const avatarText = computed(() => (userStore.name || '用').slice(0, 1))

const avatarClass = computed(() => {
  const name = userStore.name || ''
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  }
  return `avatar-hue-${hash % 5}`
})
</script>

<style scoped>
.profile-page {
  max-width: 980px;
}

.page-title-row {
  margin-bottom: 18px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  color: #0f172a;
}

.page-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.profile-grid {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 18px;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #1e293b;
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 8px 0 20px;
}

.hero-avatar {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.hero-avatar .online-dot {
  position: absolute;
  right: 2px;
  bottom: 4px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #fff;
}

.hero-name {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.hero-role {
  font-size: 12px;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid #dbeafe;
  padding: 3px 10px;
  border-radius: 999px;
  display: inline-block;
  margin-top: 6px;
}

.info-list {
  border-top: 1px solid #eef2f7;
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 0;
  border-bottom: 1px dashed #eef2f7;
  font-size: 13px;
}

.info-row:last-child {
  border-bottom: none;
}

.info-row .k {
  color: #94a3b8;
}

.info-row .v {
  color: #1e293b;
  font-weight: 500;
  text-align: right;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.security-list {
  display: grid;
  gap: 12px;
}

.security-item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 12px;
  padding: 14px;
}

.sec-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.sec-info {
  flex: 1;
  min-width: 0;
}

.sec-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
}

.sec-sub {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 3px;
  line-height: 1.6;
}

.card-note {
  margin-top: 14px;
  font-size: 12px;
  color: #94a3b8;
  background: #f8fafc;
  border-radius: 10px;
  padding: 10px 12px;
  line-height: 1.7;
}

.avatar-hue-0 { background: linear-gradient(135deg, #2563eb, #38bdf8); }
.avatar-hue-1 { background: linear-gradient(135deg, #1d4ed8, #60a5fa); }
.avatar-hue-2 { background: linear-gradient(135deg, #0284c7, #38bdf8); }
.avatar-hue-3 { background: linear-gradient(135deg, #3b82f6, #93c5fd); }
.avatar-hue-4 { background: linear-gradient(135deg, #1e40af, #3b82f6); }

@media (max-width: 820px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
