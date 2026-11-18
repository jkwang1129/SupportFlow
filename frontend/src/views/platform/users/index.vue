<template>
  <div class="users-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">平台账号中心 · 平台管理员与企业用户统一管理，登录账号均来源于此</p>
      </div>
      <div class="head-actions">
        <el-button :loading="exporting" @click="doExport">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出账号
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建用户
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-radio-group v-model="query.userType" @change="load(1)">
          <el-radio-button :value="undefined">全部账号</el-radio-button>
          <el-radio-button :value="1">平台账号</el-radio-button>
          <el-radio-button :value="2">企业账号</el-radio-button>
        </el-radio-group>
        <el-select v-model="query.status" placeholder="状态" clearable class="f-select" @change="load(1)">
          <el-option label="正常" :value="1" />
          <el-option label="停用" :value="2" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="姓名 / 用户编号 / 手机号 / 邮箱 / 租户编码"
          clearable
          class="f-input"
          @keyup.enter="load(1)"
          @clear="load(1)"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <span class="f-tip">禁用账号将无法登录后台，重新启用即可恢复</span>
      </div>

      <el-table :data="list" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="indexOf" />
        <el-table-column label="账号" min-width="200">
          <template #default="{ row }">
            <div class="cell-main">{{ row.name }}</div>
            <div class="cell-sub mono">{{ row.user_no }}</div>
          </template>
        </el-table-column>
        <el-table-column label="联系方式" min-width="210">
          <template #default="{ row }">
            <div class="cell-main mono">{{ row.phone || '—' }}</div>
            <div class="cell-sub">{{ row.email || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="账号类型" width="110">
          <template #default="{ row }">
            <el-tag :type="row.user_type === 1 ? 'warning' : 'info'" effect="light">
              {{ row.user_type === 1 ? '平台账号' : '企业账号' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="所属租户" width="180">
          <template #default="{ row }">
            <div class="cell-main">{{ tenantText(row.tenant_code) }}</div>
            <div class="cell-sub mono">{{ row.tenant_code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <span v-if="row.role_names">{{ row.role_names }}</span>
            <el-tag v-else type="danger" effect="plain" size="small">未分配角色</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="light">
              {{ row.status === 1 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="170">
          <template #default="{ row }">{{ dateTimeText(row.last_login_time) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openRoles(row)">角色</el-button>
            <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的账号" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="Number(total) || 0"
        :page-sizes="[10, 20, 50]"
        @current-change="loadList"
        @size-change="() => load(1)"
      />
    </el-card>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="formOpen" :title="form.userId ? '编辑账号' : '新建用户'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="账号类型" required>
          <el-radio-group v-model="form.userType" :disabled="Boolean(form.userId)">
            <el-radio-button :value="1">平台账号</el-radio-button>
            <el-radio-button :value="2">企业账号</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="手机号与邮箱至少填一个" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="手机号与邮箱至少填一个" />
        </el-form-item>
        <el-form-item v-if="form.userType === 2" label="所属租户" required>
          <el-input v-model="form.tenantCode" placeholder="例如 T202609050000002" />
        </el-form-item>
        <el-form-item v-if="!form.userId" label="初始密码">
          <el-input v-model="form.password" placeholder="留空默认 SupportFlow@123456" />
        </el-form-item>
        <el-form-item v-if="!form.userId" label="分配角色">
          <el-select v-model="form.roleIds" multiple placeholder="可留空，之后再分配" class="w-full">
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">
          {{ form.userId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 角色分配 -->
    <el-dialog v-model="rolesOpen" title="分配角色" width="460px">
      <p class="tip">
        {{ rolesTarget?.name }} · {{ rolesTarget?.user_type === 1 ? '平台账号（平台角色）' : '企业账号（该租户的企业角色）' }}
      </p>
      <el-select v-model="roleIds" multiple class="w-full">
        <el-option v-for="role in roleOptions" :key="role.id" :label="role.roleName" :value="role.id" />
      </el-select>
      <p class="tip">
        保存是「全量覆盖」：清空后保存表示这个账号不再拥有任何角色。
        平台账号若没有任何角色，会按超管处理（能看到全部菜单）。
      </p>
      <template #footer>
        <el-button @click="rolesOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSaveRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetOpen" title="重置密码" width="440px">
      <p class="tip">给「{{ resetTarget?.name }}」设置新密码，至少 6 位。</p>
      <el-input v-model="newPassword" placeholder="新密码" show-password />
      <template #footer>
        <el-button @click="resetOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doReset">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import { Download, Plus, Search } from '@element-plus/icons-vue'
import {
  createPlatformUser,
  downloadPlatformUsersCsv,
  fetchAssignableRoles,
  fetchPlatformUsers,
  resetPlatformUserPassword,
  setPlatformUserRoles,
  updatePlatformUser,
  updatePlatformUserStatus,
  type AssignableRole,
  type PlatformUserRow,
} from '../../../api/platform/account'
import { fetchTenantNames } from '../../../api/platform/tenant'

const loading = ref(false)
const submitting = ref(false)
const exporting = ref(false)
const list = ref<PlatformUserRow[]>([])
const total = ref(0)
const roleOptions = ref<AssignableRole[]>([])
/** 租户编码 → 企业名称（跨库，靠 tenant-service 的租户目录） */
const tenantNames = ref<Record<string, string>>({})

const query = reactive({
  userType: undefined as number | undefined,
  status: undefined as number | undefined,
  keyword: '',
  page: 1,
  pageSize: 10,
})

const formOpen = ref(false)
const form = reactive({
  userId: '',
  userType: 2,
  name: '',
  phone: '',
  email: '',
  tenantCode: '',
  password: '',
  roleIds: [] as string[],
})

const rolesOpen = ref(false)
const rolesTarget = ref<PlatformUserRow | null>(null)
const roleIds = ref<string[]>([])

const resetOpen = ref(false)
const resetTarget = ref<PlatformUserRow | null>(null)
const newPassword = ref('')

function indexOf(index: number) {
  return (query.page - 1) * query.pageSize + index + 1
}

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function tenantText(tenantCode?: string | null) {
  if (!tenantCode) return '—'
  return tenantNames.value[tenantCode] ?? (tenantCode === 'PLATFORM' ? '平台' : tenantCode)
}

function loadList() {
  void load(query.page || 1)
}

async function load(pageNo: number) {
  query.page = pageNo
  loading.value = true
  try {
    const data = await fetchPlatformUsers({
      userType: query.userType,
      status: query.status,
      keyword: query.keyword || undefined,
      page: pageNo,
      pageSize: query.pageSize,
    })
    list.value = data.list
    total.value = Number(data.total) || 0
  } catch (e) {
    errorMessage(e, '账号列表加载失败')
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.userType = undefined
  query.status = undefined
  query.keyword = ''
  void load(1)
}

/** 角色下拉随"账号类型 + 租户"变：平台账号给平台角色，企业账号给该租户的企业角色 */
async function loadRoles(tenantCode?: string) {
  try {
    roleOptions.value = await fetchAssignableRoles(tenantCode)
  } catch {
    roleOptions.value = []
  }
}

function openCreate() {
  Object.assign(form, {
    userId: '', userType: 2, name: '', phone: '', email: '',
    tenantCode: '', password: '', roleIds: [],
  })
  void loadRoles('PLATFORM')
  formOpen.value = true
}

function openEdit(row: PlatformUserRow) {
  Object.assign(form, {
    userId: row.user_id,
    userType: row.user_type,
    name: row.name,
    phone: row.phone ?? '',
    email: row.email ?? '',
    tenantCode: row.tenant_code,
    password: '',
    roleIds: [],
  })
  formOpen.value = true
}

async function doSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (!form.phone.trim() && !form.email.trim()) {
    ElMessage.warning('手机号与邮箱至少填一个')
    return
  }
  if (form.userType === 2 && !form.userId && !form.tenantCode.trim()) {
    ElMessage.warning('企业账号必须填写所属租户编码')
    return
  }
  submitting.value = true
  try {
    if (form.userId) {
      await updatePlatformUser(form.userId, {
        name: form.name.trim(),
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
      })
      ElMessage.success('已保存')
    } else {
      const result = await createPlatformUser({
        userType: form.userType,
        name: form.name.trim(),
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
        tenantCode: form.userType === 2 ? form.tenantCode.trim() : undefined,
        password: form.password.trim() || undefined,
        roleIds: form.roleIds,
      })
      ElMessageBox.alert(
        `账号已创建：${result.userNo}，初始密码 ${result.initialPassword}。请转告本人并提醒尽快修改。`,
        '创建成功',
        { type: 'success', confirmButtonText: '知道了' },
      )
    }
    formOpen.value = false
    load(1)
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    submitting.value = false
  }
}

async function openRoles(row: PlatformUserRow) {
  rolesTarget.value = row
  roleIds.value = (row.role_ids ?? '').split(',').filter(Boolean)
  await loadRoles(row.user_type === 1 ? 'PLATFORM' : row.tenant_code)
  rolesOpen.value = true
}

async function doSaveRoles() {
  if (!rolesTarget.value) return
  submitting.value = true
  try {
    await setPlatformUserRoles(rolesTarget.value.user_id, roleIds.value)
    ElMessage.success('角色已更新')
    rolesOpen.value = false
    load(query.page)
  } catch (e) {
    errorMessage(e, '保存失败')
  } finally {
    submitting.value = false
  }
}

function openReset(row: PlatformUserRow) {
  resetTarget.value = row
  newPassword.value = ''
  resetOpen.value = true
}

async function doReset() {
  if (!resetTarget.value) return
  if (newPassword.value.trim().length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  submitting.value = true
  try {
    await resetPlatformUserPassword(resetTarget.value.user_id, newPassword.value.trim())
    ElMessage.success('密码已重置')
    resetOpen.value = false
  } catch (e) {
    errorMessage(e, '重置失败')
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: PlatformUserRow) {
  const next = row.status === 1 ? 2 : 1
  try {
    await ElMessageBox.confirm(
      next === 2
        ? `停用后「${row.name}」将无法登录后台，确定吗？`
        : `确定恢复「${row.name}」的登录权限吗？`,
      next === 2 ? '停用账号' : '启用账号',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await updatePlatformUserStatus(row.user_id, next)
    ElMessage.success(next === 2 ? '已停用' : '已启用')
    load(query.page)
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

async function doExport() {
  exporting.value = true
  try {
    await downloadPlatformUsersCsv({
      userType: query.userType,
      status: query.status,
      keyword: query.keyword || undefined,
    })
  } catch {
    errorMessage(undefined, '导出失败，请稍后再试')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  const [names] = await Promise.allSettled([fetchTenantNames()])
  if (names.status === 'fulfilled') {
    tenantNames.value = Object.fromEntries(
      names.value.filter((item) => item.companyName)
        .map((item) => [item.tenantCode, String(item.companyName)]),
    )
  }
  void load(1)
})
</script>

<style scoped>
.users-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.mt-12 { margin-top: 12px; }

.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.f-select { width: 120px; }
.f-input { width: 300px; }
.f-tip { font-size: 12px; color: #94a3b8; }

.pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.tip { margin: 0 0 10px; font-size: 12px; color: #94a3b8; line-height: 1.6; }
.w-full { width: 100%; }
</style>
