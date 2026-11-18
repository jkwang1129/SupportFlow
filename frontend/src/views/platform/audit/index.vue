<template>
  <div class="audit-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-sub">全平台操作留痕 · 合规可追溯（只追加，不修改不删除）</p>
      </div>
      <div class="head-actions">
        <el-button :loading="exporting" @click="doExport">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出日志
        </el-button>
        <el-button :loading="loading" @click="load(1)">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-select v-model="query.module" placeholder="全部模块" clearable class="f-select" @change="load(1)">
          <el-option
            v-for="item in modules"
            :key="item.module"
            :label="`${item.module}（${item.cnt}）`"
            :value="item.module"
          />
        </el-select>
        <el-select v-model="query.result" placeholder="全部结果" clearable class="f-select-sm" @change="load(1)">
          <el-option label="成功" :value="1" />
          <el-option label="失败" :value="2" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="daterange"
          unlink-panels
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          class="f-range"
          @change="load(1)"
        />
        <el-input
          v-model="query.operator"
          placeholder="操作人（姓名或 ID）"
          clearable
          class="f-input-sm"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-input
          v-model="query.keyword"
          placeholder="操作 / 对象 / 详情关键字"
          clearable
          class="f-input"
          @keyup.enter="load(1)"
          @clear="load(1)"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-table :data="list" v-loading="loading" class="mt-12">
        <el-table-column type="index" label="序号" width="70" :index="indexOf" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ dateTimeText(row.auditTime) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="140">
          <template #default="{ row }">
            <div class="cell-main">{{ row.operatorName || '—' }}</div>
            <div v-if="row.operatorId" class="cell-sub mono">{{ row.operatorId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="模块" width="120">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.module }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" prop="action" />
        <el-table-column label="对象" min-width="170">
          <template #default="{ row }">
            <div class="cell-main">{{ row.targetId || '—' }}</div>
            <div v-if="row.targetType" class="cell-sub">{{ row.targetType }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.result === 1 ? 'success' : 'danger'" effect="light">
              {{ row.result === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ muted: !row.detail }">{{ row.detail || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="130">
          <template #default="{ row }">
            <span class="mono cell-sub">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的操作记录" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="Number(total) || 0"
        :page-sizes="[20, 50, 100]"
        @current-change="loadList"
        @size-change="() => load(1)"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { errorMessage } from '../../../utils/message'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import {
  downloadAuditLogsCsv,
  fetchAuditLogs,
  fetchAuditModules,
  type AuditLogRow,
  type ModuleOption,
} from '../../../api/platform/audit'

const loading = ref(false)
const exporting = ref(false)
const list = ref<AuditLogRow[]>([])
const modules = ref<ModuleOption[]>([])
const total = ref(0)
const range = ref<string[]>([])

const query = reactive({
  module: undefined as string | undefined,
  result: undefined as number | undefined,
  operator: '',
  keyword: '',
  page: 1,
  pageSize: 20,
})

function indexOf(index: number) {
  return (query.page - 1) * query.pageSize + index + 1
}

function dateTimeText(value?: string | null) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

/** 当前筛选条件（列表与导出共用，保证"导出的是什么、看到的就是什么"） */
function currentQuery() {
  return {
    module: query.module,
    result: query.result,
    operator: query.operator || undefined,
    keyword: query.keyword || undefined,
    from: range.value?.[0] || undefined,
    to: range.value?.[1] || undefined,
  }
}

function loadList() {
  void load(query.page || 1)
}

async function load(pageNo: number) {
  query.page = pageNo
  loading.value = true
  try {
    const data = await fetchAuditLogs({
      ...currentQuery(),
      page: pageNo,
      pageSize: query.pageSize,
    })
    list.value = data.list
    total.value = Number(data.total) || 0
  } catch (e) {
    errorMessage(e, '审计日志加载失败')
  } finally {
    loading.value = false
  }
}

async function loadModules() {
  try {
    modules.value = await fetchAuditModules()
  } catch {
    modules.value = []
  }
}

function resetQuery() {
  query.module = undefined
  query.result = undefined
  query.operator = ''
  query.keyword = ''
  range.value = []
  void load(1)
}

async function doExport() {
  exporting.value = true
  try {
    await downloadAuditLogsCsv(currentQuery())
  } catch {
    errorMessage(undefined, '导出失败，请稍后再试')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadModules(), load(1)])
})
</script>

<style scoped>
.audit-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.mt-12 { margin-top: 12px; }

.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.f-select { width: 190px; }
.f-select-sm { width: 120px; }
.f-range { width: 260px; }
.f-input { width: 240px; }
.f-input-sm { width: 180px; }

.pager { margin-top: 14px; justify-content: flex-end; }
.cell-main { font-size: 13px; font-weight: 600; color: #1e293b; }
.cell-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.muted { color: #94a3b8; }
</style>
