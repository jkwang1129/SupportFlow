<template>
  <div class="roles-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-sub">平台角色统一管理 · 权限按模块控制，与租户侧「成员管理」相互独立</p>
      </div>
      <div class="head-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新增角色
        </el-button>
      </div>
    </div>

    <div class="grid-12">
      <el-card shadow="never" class="col-4">
        <template #header>
          <div class="card-head">
            <span>平台角色（{{ roles.length }}）</span>
            <span class="muted">点一行看它的权限</span>
          </div>
        </template>
        <div v-loading="loading" class="role-list">
          <div
            v-for="role in roles"
            :key="role.id"
            class="role-item"
            :class="{ active: current?.id === role.id }"
            @click="select(role)"
          >
            <div class="role-main">
              <span class="role-name">{{ role.roleName }}</span>
              <el-tag v-if="role.isFixed" type="info" effect="plain" size="small">内置</el-tag>
              <el-tag v-else-if="role.isEnabled === false" type="danger" effect="plain" size="small">已停用</el-tag>
            </div>
            <div class="role-sub mono">{{ role.roleCode }}</div>
            <div class="role-sub">{{ role.permCount }} 个菜单权限 · {{ role.userCount }} 个账号</div>
          </div>
          <el-empty v-if="!roles.length" description="还没有平台角色" :image-size="70" />
        </div>
      </el-card>

      <el-card shadow="never" class="col-8">
        <template #header>
          <div class="card-head">
            <span>{{ current ? `「${current.roleName}」的模块权限` : '角色与模块权限' }}</span>
            <span v-if="current?.isFixed" class="muted">内置角色权限固定，不可修改</span>
            <span v-else class="muted">勾选 = 该角色的账号登录后能看到这些菜单</span>
          </div>
        </template>

        <el-empty v-if="!current" description="从左侧选一个角色" :image-size="80" />

        <template v-else>
          <el-form label-width="110px" class="role-form">
            <el-form-item label="角色名称">
              <el-input v-model="form.roleName" :disabled="current.isFixed" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="form.remark" :disabled="current.isFixed" placeholder="这个角色负责什么" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="form.isEnabled" :disabled="current.isFixed" />
            </el-form-item>
          </el-form>

          <el-divider content-position="left">菜单权限</el-divider>
          <el-checkbox-group v-model="checkedPermIds" :disabled="current.isFixed" class="perm-group">
            <el-checkbox v-for="perm in permissions" :key="perm.id" :value="perm.id" class="perm-item">
              <span class="perm-name">{{ perm.permName }}</span>
              <span class="perm-code mono">{{ perm.permCode }}</span>
            </el-checkbox>
          </el-checkbox-group>

          <div class="role-actions">
            <el-button type="primary" :loading="submitting" :disabled="current.isFixed" @click="save">
              保存角色与权限
            </el-button>
            <el-button :disabled="current.isFixed" :loading="submitting" @click="doRemove">删除角色</el-button>
            <el-button link type="primary" @click="loadRoles">刷新</el-button>
          </div>
        </template>
      </el-card>
    </div>

    <!-- 新增角色 -->
    <el-dialog v-model="createOpen" title="新增平台角色" width="520px">
      <el-form label-width="100px">
        <el-form-item label="角色编码" required>
          <el-input v-model="createForm.roleCode" placeholder="大写字母与下划线，例如 PLATFORM_CS" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="createForm.roleName" placeholder="例如 平台客服成功" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" placeholder="这个角色负责什么" />
        </el-form-item>
        <el-form-item label="菜单权限">
          <el-checkbox-group v-model="createForm.permIds" class="perm-group">
            <el-checkbox v-for="perm in permissions" :key="perm.id" :value="perm.id" class="perm-item">
              <span class="perm-name">{{ perm.permName }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import { Plus } from '@element-plus/icons-vue'
import {
  createPlatformRole,
  fetchPermissions,
  fetchPlatformRoles,
  removePlatformRole,
  saveRolePermissions,
  updatePlatformRole,
  type PermissionItem,
  type PlatformRoleRow,
} from '../../../api/platform/account'

const loading = ref(false)
const submitting = ref(false)
const roles = ref<PlatformRoleRow[]>([])
const permissions = ref<PermissionItem[]>([])
const current = ref<PlatformRoleRow | null>(null)
const checkedPermIds = ref<string[]>([])

const form = reactive({ roleName: '', remark: '', isEnabled: true })
const createOpen = ref(false)
const createForm = reactive({ roleCode: '', roleName: '', remark: '', permIds: [] as string[] })

async function loadRoles() {
  loading.value = true
  try {
    roles.value = await fetchPlatformRoles()
    if (current.value) {
      const same = roles.value.find((role) => role.id === current.value?.id)
      if (same) select(same)
      else current.value = null
    }
  } catch (e) {
    errorMessage(e, '角色列表加载失败')
  } finally {
    loading.value = false
  }
}

function select(role: PlatformRoleRow) {
  current.value = role
  checkedPermIds.value = [...role.permIds]
  form.roleName = role.roleName
  form.remark = role.remark ?? ''
  form.isEnabled = role.isEnabled !== false
}

function openCreate() {
  Object.assign(createForm, { roleCode: '', roleName: '', remark: '', permIds: [] })
  createOpen.value = true
}

async function doCreate() {
  if (!createForm.roleCode.trim() || !createForm.roleName.trim()) {
    ElMessage.warning('角色编码与名称都要填')
    return
  }
  submitting.value = true
  try {
    await createPlatformRole({
      roleCode: createForm.roleCode.trim(),
      roleName: createForm.roleName.trim(),
      remark: createForm.remark.trim() || undefined,
      permIds: createForm.permIds,
    })
    ElMessage.success('角色已创建')
    createOpen.value = false
    await loadRoles()
  } catch (e) {
    errorMessage(e, '创建失败')
  } finally {
    submitting.value = false
  }
}

async function save() {
  if (!current.value) return
  submitting.value = true
  try {
    await updatePlatformRole(current.value.id, {
      roleName: form.roleName.trim(),
      remark: form.remark.trim() || undefined,
      isEnabled: form.isEnabled,
    })
    const result = await saveRolePermissions(current.value.id, checkedPermIds.value)
    ElMessage.success(`已保存，该角色拥有 ${result.saved} 个菜单权限`)
    await loadRoles()
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    submitting.value = false
  }
}

async function doRemove() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除角色「${current.value.roleName}」吗？还有账号在使用时不能删除。`,
      '删除角色',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await removePlatformRole(current.value.id)
    ElMessage.success('已删除')
    current.value = null
    await loadRoles()
  } catch (e) {
    errorMessage(e, '删除失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    permissions.value = await fetchPermissions()
  } catch {
    permissions.value = []
  }
  await loadRoles()
  if (!current.value && roles.value.length) {
    select(roles.value[0])
  }
})
</script>

<style scoped>
.roles-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }

.grid-12 { display: grid; grid-template-columns: repeat(12, 1fr); gap: 16px; }
.col-4 { grid-column: span 4; }
.col-8 { grid-column: span 8; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.muted { font-size: 12px; color: #94a3b8; }

.role-list { display: flex; flex-direction: column; gap: 8px; }
.role-item { border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px 12px; cursor: pointer; transition: all 0.18s ease; }
.role-item:hover { border-color: #c7d2fe; background: #f8faff; }
.role-item.active { border-color: #2563eb; background: #eef4ff; box-shadow: 0 2px 8px rgba(37, 99, 235, 0.12); }
.role-main { display: flex; align-items: center; gap: 8px; }
.role-name { font-size: 13.5px; font-weight: 700; color: #0f172a; }
.role-sub { font-size: 12px; color: #94a3b8; margin-top: 3px; }

.role-form { max-width: 520px; }
.perm-group { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 6px 18px; }
.perm-item { margin-right: 0; }
.perm-name { font-size: 13px; color: #1e293b; margin-right: 6px; }
.perm-code { font-size: 11.5px; color: #94a3b8; }
.role-actions { margin-top: 18px; display: flex; align-items: center; gap: 10px; }
</style>
