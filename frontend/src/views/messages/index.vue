<template>
  <div class="mc-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">消息中心</h2>
        <p class="page-sub">
          平台公告、工单提醒、系统与质检通知都收在这里；带「前往处理」的可以直接跳到对应页面，
          没有跳转的就在详情里看全文
        </p>
      </div>
      <div class="head-actions">
        <el-button :disabled="!(summary?.all.unread ?? 0)" @click="markAllRead">
          <el-icon class="btn-icon"><Select /></el-icon>
          全部已读
        </el-button>
        <el-button type="primary" :loading="loading" @click="load()">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Message /></el-icon></div>
        <div>
          <div class="stat-value">{{ summary?.all.total ?? '—' }}</div>
          <div class="stat-label">全部消息</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div>
          <div class="stat-value">{{ summary?.all.unread ?? '—' }}</div>
          <div class="stat-label">未读</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><Promotion /></el-icon></div>
        <div>
          <div class="stat-value">{{ typeStat(5).total }}</div>
          <div class="stat-label">平台公告</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><Tickets /></el-icon></div>
        <div>
          <div class="stat-value">{{ typeStat(2).total }}</div>
          <div class="stat-label">工单提醒</div>
        </div>
      </div>
    </div>

    <div class="mc-body">
      <!-- 左栏：类型 + 未读数，一眼看出"哪儿有没读的" -->
      <el-card shadow="never" class="mc-rail">
        <div class="rail-title">消息类型</div>
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="rail-item"
          :class="{ active: query.notifyType === tab.value }"
          @click="pickType(tab.value)"
        >
          <span class="rail-name">{{ tab.label }}</span>
          <span v-if="tabStat(tab.value).unread" class="rail-unread">{{ tabStat(tab.value).unread }}</span>
          <span v-else class="rail-total">{{ tabStat(tab.value).total }}</span>
        </button>
        <div class="rail-tip">
          红点角标是<strong>未读数</strong>（按人记：同一条公告，同事读过不影响你）；
          右上角铃铛与这里同源，数字不会对不上
        </div>
      </el-card>

      <!-- 右栏：列表 + 分页 -->
      <el-card shadow="never" class="mc-main">
        <div class="mc-toolbar">
          <el-input
            v-model="query.keyword"
            class="w-260"
            clearable
            placeholder="搜索标题或内容"
            @keydown.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-button @click="applyFilter">搜索</el-button>
          <el-switch
            v-model="query.unreadOnly"
            class="unread-switch"
            active-text="只看未读"
            @change="applyFilter"
          />
          <span class="toolbar-tip">共 {{ total }} 条</span>
        </div>

        <div v-loading="loading" class="mc-list">
          <div
            v-for="item in list"
            :key="item.id"
            class="mc-item"
            :class="{ unread: !item.read }"
            @click="openItem(item)"
          >
            <div class="mc-icon" :class="`type-${item.notifyType}`">
              <el-icon :size="16"><component :is="iconOf(item.notifyType)" /></el-icon>
            </div>
            <div class="mc-content">
              <div class="mc-head">
                <span class="mc-title">{{ item.title }}</span>
                <el-tag size="small" :type="tagOf(item.notifyType)" effect="light">
                  {{ item.notifyTypeText }}
                </el-tag>
                <span v-if="!item.read" class="mc-dot"></span>
              </div>
              <div class="mc-text">{{ item.content || '（无正文）' }}</div>
              <div class="mc-meta">
                <span>{{ item.publishTime || '—' }}</span>
                <span v-if="!item.read" class="mc-unread-text">未读</span>
              </div>
            </div>
            <div class="mc-actions" @click.stop>
              <el-button link type="primary" @click="openDetail(item)">详情</el-button>
              <el-button v-if="canDownload(item)" link type="success" @click="downloadExport(item)">
                下载
              </el-button>
              <el-button v-if="routeOf(item)" link type="success" @click="goItem(item)">
                前往处理
              </el-button>
              <el-button v-if="!item.read" link @click="markOne(item)">标为已读</el-button>
            </div>
          </div>

          <el-empty v-if="!loading && !list.length" description="没有符合条件的消息" :image-size="90" />
        </div>

        <el-pagination
          v-model:current-page="query.page"
          class="mc-pager"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-size="query.pageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="loadList"
          @size-change="onSizeChange"
        />

        <p v-if="query.unreadOnly && pageScanLimit" class="scan-tip">
          只看未读时的口径：在最近 {{ pageScanLimit }} 条消息里挑未读（已读按人存，数据库里筛不掉）
        </p>
      </el-card>
    </div>

    <!-- 消息详情：没有跳转的消息在这里看全文 -->
    <el-drawer v-model="detailOpen" title="消息详情" size="480px">
      <div v-if="detail" class="detail-wrap">
        <div class="detail-head">
          <div class="mc-icon big" :class="`type-${detail.notifyType}`">
            <el-icon :size="18"><component :is="iconOf(detail.notifyType)" /></el-icon>
          </div>
          <div>
            <div class="detail-title">{{ detail.title }}</div>
            <div class="detail-meta">
              <el-tag size="small" :type="tagOf(detail.notifyType)" effect="light">
                {{ detail.notifyTypeText }}
              </el-tag>
              <span>{{ detail.publishTime || '—' }}</span>
            </div>
          </div>
        </div>

        <div class="detail-body">{{ detail.content || '（这条消息没有正文）' }}</div>

        <div class="detail-foot">
          <el-button v-if="canDownload(detail)" type="success" :loading="downloading" @click="downloadExport(detail)">
            <el-icon class="btn-icon"><Download /></el-icon>
            下载导出数据
          </el-button>
          <el-button v-if="routeOf(detail)" type="primary" @click="goItem(detail)">
            <el-icon class="btn-icon"><TopRight /></el-icon>
            前往处理
          </el-button>
          <span v-if="!routeOf(detail) && !canDownload(detail)" class="detail-tip">
            这条消息没有需要处理的页面，看完即可
          </span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorMessage } from '../../utils/message'
import {
  BellFilled,
  Download,
  Message,
  Promotion,
  Refresh,
  Select,
  Tickets,
  TopRight,
} from '@element-plus/icons-vue'
import {
  fetchNotificationPage,
  fetchNotificationSummary,
  isExportDownloadNotice,
  markAllNotificationsRead,
  markNotificationRead,
  notificationRoute,
  type NotificationItem,
  type NotificationSummary,
} from '../../api/customer/notification'
import { downloadApprovedCustomersCsv } from '../../api/customer/customer'

const router = useRouter()

const loading = ref(false)
const list = ref<NotificationItem[]>([])
const total = ref(0)
const summary = ref<NotificationSummary | null>(null)
const pageScanLimit = ref(0)

const query = reactive({
  notifyType: 0,
  keyword: '',
  unreadOnly: false,
  page: 1,
  pageSize: 10,
})

/** 左栏页签：0 = 全部（不带类型条件） */
const tabs = [
  { key: 'all', label: '全部消息', value: 0 },
  { key: 'announce', label: '平台公告', value: 5 },
  { key: 'ticket', label: '工单提醒', value: 2 },
  { key: 'system', label: '系统通知', value: 1 },
  { key: 'review', label: '审核通知', value: 3 },
  { key: 'qa', label: '质检通知', value: 4 },
  { key: 'bill', label: '账单通知', value: 6 },
]

const detailOpen = ref(false)
const detail = ref<NotificationItem | null>(null)
/** 导出下载中：消息是"点一下就下载"，连点两次只会发一次请求 */
const downloading = ref(false)

function typeStat(type: number) {
  if (type === 0) {
    return { text: '全部消息', total: summary.value?.all.total ?? 0, unread: summary.value?.all.unread ?? 0 }
  }
  return summary.value?.byType[String(type)] ?? { text: '', total: 0, unread: 0 }
}

function tabStat(value: number) {
  return typeStat(value)
}

function iconOf(notifyType?: number) {
  if (notifyType === 2) return 'Tickets'
  if (notifyType === 5) return 'Promotion'
  if (notifyType === 4) return 'DataAnalysis'
  if (notifyType === 3) return 'CircleCheck'
  return 'Bell'
}

function tagOf(notifyType?: number) {
  if (notifyType === 2) return 'warning'
  if (notifyType === 5) return 'success'
  if (notifyType === 4) return 'primary'
  return 'info'
}

function routeOf(item: NotificationItem) {
  return notificationRoute(item.linkView)
}

function pickType(value: number) {
  query.notifyType = value
  applyFilter()
}

function applyFilter() {
  query.page = 1
  void load()
}

function onSizeChange() {
  query.page = 1
  void loadList()
}

async function load() {
  await Promise.all([loadSummary(), loadList()])
}

async function loadSummary() {
  try {
    summary.value = await fetchNotificationSummary()
  } catch {
    // 统计拿不到不影响列表：左栏角标空着比整页报错强
    summary.value = null
  }
}

async function loadList() {
  loading.value = true
  try {
    const result = await fetchNotificationPage({
      notifyType: query.notifyType || undefined,
      keyword: query.keyword,
      unreadOnly: query.unreadOnly,
      page: query.page,
      pageSize: query.pageSize,
    })
    list.value = result.list ?? []
    total.value = Number(result.total) || 0
    pageScanLimit.value = Number(result.scanLimit) || 0
  } catch (e) {
    errorMessage(e, '消息加载失败')
  } finally {
    loading.value = false
  }
}

/** 点一行：标记已读；有跳转就直接去，没有跳转就打开详情 */
async function openItem(item: NotificationItem) {
  await markOne(item)
  // 导出审批通过的那条消息：点一下就把文件下载下来（不是跳页面）
  if (isExportDownloadNotice(item.linkView)) {
    await downloadExport(item)
    return
  }
  const route = routeOf(item)
  if (route) {
    void router.push(route)
    return
  }
  openDetail(item)
}

function canDownload(item: NotificationItem | null): boolean {
  return !!item && isExportDownloadNotice(item.linkView)
}

/**
 * 下载审批通过的导出数据。
 *
 * <p>范围由服务端从审批单里读，前端不传条件；一次审批只对应一次导出，
 * 所以第二次点会如实提示"这张审批单已经导出过了"。</p>
 */
async function downloadExport(item: NotificationItem) {
  if (!canDownload(item) || downloading.value) {
    return
  }
  downloading.value = true
  try {
    await downloadApprovedCustomersCsv(`客户列表-${new Date().toISOString().slice(0, 10)}.csv`)
    ElMessage.success('导出文件已开始下载')
  } catch (e) {
    errorMessage(e, '下载失败，请联系平台运营')
  } finally {
    downloading.value = false
  }
}

function openDetail(item: NotificationItem) {
  detail.value = item
  detailOpen.value = true
}

async function goItem(item: NotificationItem) {
  await markOne(item)
  const route = routeOf(item)
  if (route) {
    void router.push(route)
  }
}

async function markOne(item: NotificationItem) {
  if (item.read) {
    return
  }
  try {
    await markNotificationRead(item.id)
    item.read = true
    await loadSummary()
  } catch {
    // 标记失败不改本地状态：下次进来还是未读，不"假装看过了"
  }
}

async function markAllRead() {
  try {
    const result = await markAllNotificationsRead(query.notifyType || undefined)
    ElMessage.success(`已把 ${result.count} 条标为已读`)
    await load()
  } catch (e) {
    errorMessage(e, '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.mc-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; max-width: 760px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.stat-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.orange { background: #fff7ed; color: #ea580c; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.mc-body { display: grid; grid-template-columns: 232px minmax(0, 1fr); gap: 16px; align-items: start; }

.mc-rail :deep(.el-card__body) { padding: 12px; }
.rail-title { font-size: 12px; color: #94a3b8; padding: 4px 8px 10px; }
.rail-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 10px;
  margin-bottom: 2px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: #334155;
  text-align: left;
}
.rail-item:hover { background: #f5f8fc; }
.rail-item.active { background: #eef4ff; color: #1d4ed8; font-weight: 600; }
.rail-name { flex: 1; }
.rail-unread {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
}
.rail-total { font-size: 12px; color: #cbd5e1; }
.rail-tip { margin-top: 10px; padding: 8px; font-size: 11.5px; line-height: 1.6; color: #94a3b8; background: #f8fafc; border-radius: 8px; }

.mc-main :deep(.el-card__body) { padding: 14px 16px; }
.mc-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.w-260 { width: 260px; }
.unread-switch { margin-left: 4px; }
.toolbar-tip { margin-left: auto; font-size: 12.5px; color: #94a3b8; }

.mc-list { min-height: 240px; }
.mc-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 12px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color .15s, box-shadow .15s;
}
.mc-item:hover { border-color: #d7e3f5; box-shadow: 0 2px 10px rgba(15, 23, 42, .05); }
.mc-item.unread { background: #fbfdff; border-color: #e2ecfb; }

.mc-icon { width: 34px; height: 34px; border-radius: 9px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: #eef2f7; color: #64748b; }
.mc-icon.big { width: 40px; height: 40px; border-radius: 10px; }
.mc-icon.type-1 { background: #eff6ff; color: #2563eb; }
.mc-icon.type-2 { background: #fff7ed; color: #ea580c; }
.mc-icon.type-3 { background: #e0f2fe; color: #0284c7; }
.mc-icon.type-4 { background: #dbeafe; color: #1d4ed8; }
.mc-icon.type-5 { background: #ecfdf5; color: #059669; }
.mc-icon.type-6 { background: #f5f3ff; color: #7c3aed; }

.mc-content { flex: 1; min-width: 0; }
.mc-head { display: flex; align-items: center; gap: 8px; }
.mc-title { font-size: 14px; font-weight: 600; color: #0f172a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mc-dot { width: 7px; height: 7px; border-radius: 50%; background: #ef4444; flex-shrink: 0; }
.mc-text {
  margin-top: 4px;
  font-size: 12.5px;
  color: #64748b;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.mc-meta { margin-top: 6px; display: flex; align-items: center; gap: 10px; font-size: 11.5px; color: #94a3b8; }
.mc-unread-text { color: #ef4444; font-weight: 600; }
.mc-actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }

.mc-pager { margin-top: 14px; justify-content: flex-end; }
.scan-tip { margin: 8px 0 0; font-size: 12px; color: #94a3b8; }

.detail-wrap { display: flex; flex-direction: column; height: 100%; }
.detail-head { display: flex; gap: 12px; align-items: flex-start; }
.detail-title { font-size: 16px; font-weight: 700; color: #0f172a; line-height: 1.5; }
.detail-meta { margin-top: 8px; display: flex; align-items: center; gap: 10px; font-size: 12.5px; color: #94a3b8; }
.detail-body {
  margin-top: 16px;
  padding: 14px;
  border-radius: 10px;
  background: #f8fafc;
  font-size: 13.5px;
  color: #334155;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  flex: 1;
  overflow-y: auto;
}
.detail-foot { margin-top: 16px; display: flex; align-items: center; gap: 10px; }
.detail-tip { font-size: 12.5px; color: #94a3b8; }

@media (max-width: 1100px) {
  .mc-body { grid-template-columns: minmax(0, 1fr); }
  .stat-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
