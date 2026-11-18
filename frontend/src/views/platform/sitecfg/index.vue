<template>
  <div class="sitecfg-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">官网配置</h2>
        <p class="page-sub">
          可视化编辑官网品牌与内容：先「保存草稿」，再「预览官网」确认效果，最后「保存并发布」给访客
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="busy.preview" @click="previewSite">
          <el-icon class="btn-icon"><View /></el-icon>
          预览官网
        </el-button>
        <el-button :loading="busy.reset" @click="resetDraft">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          恢复默认
        </el-button>
        <el-button :loading="busy.save" @click="saveDraft">
          <el-icon class="btn-icon"><Files /></el-icon>
          保存草稿
        </el-button>
        <el-button type="primary" :loading="busy.publish" @click="saveAndPublish">
          <el-icon class="btn-icon"><Promotion /></el-icon>
          保存并发布
        </el-button>
      </div>
    </div>

    <div class="status-strip" v-loading="loading">
      <div class="ss-icon"><el-icon :size="18"><Monitor /></el-icon></div>
      <div class="ss-body">
        <div class="ss-title">官网内容管理</div>
        <div class="ss-sub">{{ statusText }}</div>
      </div>
      <el-tag :type="dirty ? 'warning' : 'success'" effect="light" class="ss-badge">
        {{ dirty ? '有未发布的修改' : '与线上一致' }}
      </el-tag>
    </div>

    <div class="cfg-body">
      <el-card shadow="never" class="cfg-rail">
        <div class="rail-title">页面区块</div>
        <button
          v-for="item in SECTIONS"
          :key="item.key"
          class="rail-item"
          :class="{ active: active === item.key }"
          @click="active = item.key"
        >
          <el-icon :size="14"><component :is="item.icon" /></el-icon>
          <span class="rail-label">{{ item.label }}</span>
          <span v-if="sectionDirty(item.key)" class="rail-dot"></span>
        </button>
        <div class="rail-tip">
          价格不在这里改：套餐价格在「平台设置 → 套餐价格」，官网定价区自动同步。
        </div>
      </el-card>

      <div class="cfg-main">
        <el-card
          v-for="group in activeSection.groups"
          :key="group.title"
          shadow="never"
          class="cfg-card"
        >
          <div class="card-head">
            <div>
              <div class="card-title">{{ group.title }}</div>
              <div v-if="group.sub" class="card-sub">{{ group.sub }}</div>
            </div>
          </div>

          <div v-if="group.fields?.length" class="cfg-fields">
            <div v-for="field in group.fields" :key="field.path" class="field">
              <label class="field-label">{{ field.label }}</label>
              <el-input
                v-if="field.type === 'textarea'"
                :model-value="textOf(field.path)"
                type="textarea"
                :rows="3"
                :placeholder="field.placeholder"
                @update:model-value="(value: string) => setField(field.path, value)"
              />
              <el-input
                v-else
                :model-value="textOf(field.path)"
                :placeholder="field.placeholder"
                @update:model-value="(value: string) => setField(field.path, value)"
              />
            </div>
          </div>

          <div v-for="list in group.lists || []" :key="list.path" class="cfg-list">
            <div class="list-head">
              <div class="list-title">{{ list.label }}</div>
              <el-button link type="primary" @click="addItem(list)">+ 新增一项</el-button>
            </div>

            <div
              v-for="(item, index) in listOf(list.path)"
              :key="itemKey(index, item)"
              class="list-item"
            >
              <div class="li-head">
                <span class="li-title">{{ list.itemLabel }} {{ index + 1 }}</span>
                <div class="li-actions">
                  <el-button link :disabled="index === 0" @click="moveItem(list.path, index, -1)">上移</el-button>
                  <el-button
                    link
                    :disabled="index === listOf(list.path).length - 1"
                    @click="moveItem(list.path, index, 1)"
                  >
                    下移
                  </el-button>
                  <el-button link type="danger" @click="removeItem(list.path, index)">删除</el-button>
                </div>
              </div>
              <div class="li-fields" :class="{ 'one-col': list.fields.length === 1 }">
                <div v-for="field in list.fields" :key="field.path" class="field">
                  <label class="field-label">{{ field.label }}</label>
                  <el-input
                    v-if="field.type === 'textarea'"
                    :model-value="itemText(list.path, index, field.path)"
                    type="textarea"
                    :rows="2"
                    @update:model-value="(value: string) => setItemField(list.path, index, field.path, value)"
                  />
                  <el-input
                    v-else
                    :model-value="itemText(list.path, index, field.path)"
                    @update:model-value="(value: string) => setItemField(list.path, index, field.path, value)"
                  />
                </div>
              </div>
            </div>

            <el-empty v-if="!listOf(list.path).length" description="还没有内容" :image-size="60" />
          </div>
        </el-card>

        <p class="foot-tip">
          预览不会影响访客：它用 ?site= 参数把当前草稿喂给官网，只有你自己这次打开能看到。
          发布后访客再打开官网就是新内容（没改过的区块继续用出厂文案）。
          <br />
          预览地址：<span class="mono">{{ OFFICIAL_BASE }}</span>（官网项目要先起来：项目根目录执行
          <span class="mono">bash website/serve.sh</span>，端口用 VITE_OFFICIAL_BASE 改）
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, type Component } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage } from '../../../utils/message'
import {
  Aim,
  Briefcase,
  CreditCard,
  Document,
  Files,
  Medal,
  Monitor,
  Promotion,
  QuestionFilled,
  Refresh,
  Star,
  View,
} from '@element-plus/icons-vue'
import {
  fetchSiteConfig,
  fetchSitePreviewParam,
  publishSiteConfig,
  resetSiteConfigDraft,
  saveSiteConfigDraft,
} from '../../../api/platform/sitecfg'

/** 官网项目地址：预览时打开它（可用 VITE_OFFICIAL_BASE 覆盖，运行时也可用 window.SUPPORTFLOW_OFFICIAL_BASE） */
const OFFICIAL_BASE =
  (import.meta.env.VITE_OFFICIAL_BASE as string | undefined) ||
  (window as any).SUPPORTFLOW_OFFICIAL_BASE ||
  'http://localhost:5180'

interface FieldDef {
  /** 相对区块的字段路径；'.' 表示"这一项本身就是文本"（如 showcase.points） */
  path: string
  label: string
  type?: 'text' | 'textarea'
  placeholder?: string
}

interface ListDef {
  path: string
  label: string
  itemLabel: string
  fields: FieldDef[]
}

interface GroupDef {
  title: string
  sub?: string
  fields?: FieldDef[]
  lists?: ListDef[]
}

interface SectionDef {
  key: string
  label: string
  icon: Component
  groups: GroupDef[]
}

/**
 * 编辑器的区块与字段，与官网页面 data-cfg 的契约一一对应。
 *
 * <p>字段路径不是随便定的：官网 site.js 就是按这些路径覆盖文案的，
 * 改这里等于改官网，所以新增字段前先确认官网有这个位置。</p>
 */
const SECTIONS: SectionDef[] = [
  {
    key: 'hero',
    label: '首屏 Hero',
    icon: View,
    groups: [
      {
        title: '首屏文案',
        sub: '访客打开官网的第一屏，直接决定第一印象',
        fields: [
          { path: 'hero.badge', label: '角标文案', placeholder: '例如：大模型多轮对话 · 全量 AI 质检正式上线' },
          { path: 'hero.title1', label: '主标题（前半句）' },
          { path: 'hero.titleGrad', label: '主标题（渐变强调句）' },
          { path: 'hero.sub', label: '副标题', type: 'textarea' },
          { path: 'hero.cta1', label: '主按钮文案' },
          { path: 'hero.cta2', label: '次按钮文案' },
          { path: 'hero.note', label: '按钮下方说明' },
        ],
      },
      {
        title: '首屏数据指标',
        sub: '右侧 4 个数据点，建议保持简洁',
        lists: [
          {
            path: 'hero.stats',
            label: '指标',
            itemLabel: '指标',
            fields: [
              { path: 'v', label: '数值' },
              { path: 'l', label: '标签' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'trust',
    label: '信任与数据',
    icon: Medal,
    groups: [
      {
        title: '信任墙',
        sub: '访客看到"别人也在用"，信任感立起来',
        fields: [
          { path: 'trust.line', label: '标题文案' },
          {
            path: 'trust.brands',
            label: '企业名称（逗号分隔）',
            type: 'textarea',
            placeholder: '云智科技,芯动智能,启辰信息',
          },
        ],
      },
      {
        title: '数据横幅',
        sub: '页面中段的四个大数字',
        lists: [
          {
            path: 'statband',
            label: '数据点',
            itemLabel: '数据点',
            fields: [
              { path: 'v', label: '数字' },
              { path: 's', label: '单位（+ / % / 亿）' },
              { path: 'l', label: '说明' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'features',
    label: '核心功能',
    icon: Aim,
    groups: [
      {
        title: '区块标题',
        fields: [
          { path: 'features.head', label: '标题' },
          { path: 'features.sub', label: '副标题' },
        ],
      },
      {
        title: '功能卡片',
        sub: '每张卡片一个卖点，建议 6 张以内',
        lists: [
          {
            path: 'features.items',
            label: '功能',
            itemLabel: '功能',
            fields: [
              { path: 't', label: '标题' },
              { path: 'd', label: '描述', type: 'textarea' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'showcase',
    label: '产品展示',
    icon: Monitor,
    groups: [
      {
        title: '工作台介绍',
        fields: [
          { path: 'showcase.tag', label: '小标签' },
          { path: 'showcase.title', label: '标题' },
          { path: 'showcase.desc', label: '描述', type: 'textarea' },
        ],
      },
      {
        title: '亮点清单',
        lists: [
          {
            path: 'showcase.points',
            label: '亮点',
            itemLabel: '亮点',
            fields: [{ path: '.', label: '文案' }],
          },
        ],
      },
      {
        title: '产品演示区',
        sub: '页面右侧那个能真的聊两句话的演示组件',
        fields: [
          { path: 'demo.head', label: '标题' },
          { path: 'demo.sub', label: '副标题', type: 'textarea' },
        ],
      },
    ],
  },
  {
    key: 'solutions',
    label: '行业方案',
    icon: Briefcase,
    groups: [
      {
        title: '区块标题',
        fields: [
          { path: 'solutions.head', label: '标题' },
          { path: 'solutions.sub', label: '副标题' },
        ],
      },
      {
        title: '行业卡片',
        lists: [
          {
            path: 'solutions.items',
            label: '行业',
            itemLabel: '行业',
            fields: [
              { path: 't', label: '行业名' },
              { path: 'd', label: '描述', type: 'textarea' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'cases',
    label: '客户案例',
    icon: Star,
    groups: [
      {
        title: '区块标题',
        fields: [
          { path: 'cases.head', label: '标题' },
          { path: 'cases.sub', label: '副标题' },
        ],
      },
      {
        title: '案例卡片',
        sub: '引用真实客户的话更有说服力，别写太满',
        lists: [
          {
            path: 'cases.items',
            label: '案例',
            itemLabel: '案例',
            fields: [
              { path: 'quote', label: '客户原话', type: 'textarea' },
              { path: 'company', label: '企业名称' },
              { path: 'role', label: '身份 · 版本' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'pricing',
    label: '定价方案',
    icon: CreditCard,
    groups: [
      {
        title: '区块标题',
        sub: '套餐名、价格、额度都来自「平台设置 → 套餐价格」，这里只管文案',
        fields: [
          { path: 'pricing.head', label: '标题' },
          { path: 'pricing.sub', label: '副标题' },
          { path: 'pricing.saveNote', label: '年付优惠角标' },
        ],
      },
    ],
  },
  {
    key: 'faq',
    label: '常见问题',
    icon: QuestionFilled,
    groups: [
      {
        title: '区块标题',
        fields: [{ path: 'faq.head', label: '标题' }],
      },
      {
        title: '问答',
        sub: '顺序就是页面上的顺序；一条别太长',
        lists: [
          {
            path: 'faq.items',
            label: '问题',
            itemLabel: '问题',
            fields: [
              { path: 'q', label: '问题' },
              { path: 'a', label: '回答', type: 'textarea' },
            ],
          },
        ],
      },
    ],
  },
  {
    key: 'footer',
    label: '底部 CTA 与页脚',
    icon: Document,
    groups: [
      {
        title: '底部 CTA',
        fields: [
          { path: 'cta.title', label: '标题' },
          { path: 'cta.desc', label: '描述' },
          { path: 'cta.btn1', label: '主按钮文案' },
          { path: 'cta.btn2', label: '次按钮文案' },
        ],
      },
      {
        title: '页脚',
        fields: [
          { path: 'footer.slogan', label: '一句话介绍', type: 'textarea' },
          { path: 'footer.copyright', label: '版权信息' },
          { path: 'footer.icp', label: '备案号' },
        ],
      },
    ],
  },
]

const loading = ref(false)
const touched = ref(false)
const active = ref(SECTIONS[0].key)
const draft = ref<Record<string, any>>({})
/** 当前线上那份：只用来对比"哪个区块被改过"，不参与编辑 */
const published = ref<Record<string, any>>({})
const info = ref<{
  dirty: boolean
  version: number
  draftVersion: number
  publishTime?: string | null
  updateTime?: string | null
  editor?: string | null
} | null>(null)
const busy = reactive({ save: false, publish: false, reset: false, preview: false })

const activeSection = computed(() => SECTIONS.find((item) => item.key === active.value) || SECTIONS[0])
const dirty = computed(() => touched.value || Boolean(info.value?.dirty))

const statusText = computed(() => {
  const current = info.value
  if (!current) {
    return '正在读取配置…'
  }
  const parts: string[] = []
  parts.push(current.version > 0 ? `线上版本 v${current.version}` : '线上还是出厂文案（还没发布过）')
  if (current.version > 0) {
    parts.push(`发布 ${current.publishTime || '—'}`)
  }
  if (current.updateTime) {
    parts.push(`草稿更新 ${current.updateTime}`)
  }
  if (current.editor) {
    parts.push(`最后编辑 ${current.editor}`)
  }
  parts.push(dirty.value ? '有改动待发布' : '草稿与线上一致')
  return parts.join(' · ')
})

// ----------------------------------------------------------------- 路径读写

function getPath(target: any, path: string): any {
  if (path === '.' || !path) {
    return target
  }
  return path.split('.').reduce((acc: any, key: string) => (acc == null ? undefined : acc[key]), target)
}

function setPath(target: any, path: string, value: any): void {
  const keys = path.split('.')
  let cursor = target
  for (const key of keys.slice(0, -1)) {
    if (cursor[key] == null || typeof cursor[key] !== 'object') {
      cursor[key] = {}
    }
    cursor = cursor[key]
  }
  cursor[keys[keys.length - 1]] = value
}

function textOf(path: string): string {
  const value = getPath(draft.value, path)
  return value == null ? '' : String(value)
}

function setField(path: string, value: string): void {
  setPath(draft.value, path, value)
  touched.value = true
}

function listOf(path: string): any[] {
  const value = getPath(draft.value, path)
  return Array.isArray(value) ? value : []
}

/** 列表不存在（或类型不对）时先建出来，否则 push 到临时数组里等于没改 */
function ensureList(path: string): any[] {
  const value = getPath(draft.value, path)
  if (Array.isArray(value)) {
    return value
  }
  setPath(draft.value, path, [])
  return getPath(draft.value, path)
}

function itemText(listPath: string, index: number, path: string): string {
  const item = listOf(listPath)[index]
  const value = getPath(item, path)
  return value == null ? '' : String(value)
}

function setItemField(listPath: string, index: number, path: string, value: string): void {
  const item = listOf(listPath)[index]
  if (item == null) {
    return
  }
  if (path === '.') {
    listOf(listPath)[index] = value
  } else {
    setPath(item, path, value)
  }
  touched.value = true
}

function addItem(list: ListDef): void {
  const rows = ensureList(list.path)
  if (list.fields.length === 1 && list.fields[0].path === '.') {
    rows.push('')
  } else {
    const blank: Record<string, any> = {}
    list.fields.forEach((field) => setPath(blank, field.path, ''))
    rows.push(blank)
  }
  touched.value = true
}

function removeItem(listPath: string, index: number): void {
  ensureList(listPath).splice(index, 1)
  touched.value = true
}

function moveItem(listPath: string, index: number, delta: number): void {
  const rows = ensureList(listPath)
  const target = index + delta
  if (target < 0 || target >= rows.length) {
    return
  }
  const [row] = rows.splice(index, 1)
  rows.splice(target, 0, row)
  touched.value = true
}

/** 左栏小圆点：这个区块跟线上不一样 */
function sectionDirty(key: string): boolean {
  const current = info.value
  if (!current || (!touched.value && !current.dirty)) {
    return false
  }
  return toJson(published.value?.[key]) !== toJson(draft.value?.[key])
}

function toJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? null)
  } catch {
    return 'null'
  }
}

/** 列表项的 key：带上内容片段，改一个字也不会让整块重渲染错位 */
function itemKey(index: number, item: unknown): string {
  const marker = typeof item === 'string' ? item.slice(0, 12) : toJson(item).slice(0, 24)
  return `${index}:${marker}`
}

// ----------------------------------------------------------------- 接口动作

function applyState(state: any): void {
  draft.value = state?.draft ?? {}
  published.value = state?.published ?? {}
  info.value = {
    dirty: Boolean(state?.dirty),
    version: Number(state?.version) || 0,
    draftVersion: Number(state?.draftVersion) || 0,
    publishTime: state?.publishTime ?? null,
    updateTime: state?.updateTime ?? null,
    editor: state?.editor ?? null,
  }
  touched.value = false
}

async function load(): Promise<void> {
  loading.value = true
  try {
    applyState(await fetchSiteConfig())
  } catch (e) {
    errorMessage(e, '官网配置加载失败')
  } finally {
    loading.value = false
  }
}

async function doSave(silent = false): Promise<boolean> {
  busy.save = true
  try {
    applyState(await saveSiteConfigDraft(draft.value))
    if (!silent) {
      ElMessage.success('草稿已保存（线上还没变）')
    }
    return true
  } catch (e) {
    errorMessage(e, '保存失败')
    return false
  } finally {
    busy.save = false
  }
}

async function saveDraft(): Promise<void> {
  await doSave()
}

async function previewSite(): Promise<void> {
  // 先把窗口开出来，避免 await 之后再 window.open 被浏览器拦成弹窗
  const win = window.open('', '_blank')
  busy.preview = true
  try {
    if (touched.value) {
      const saved = await doSave(true)
      if (!saved) {
        win?.close()
        return
      }
    }
    const { param } = await fetchSitePreviewParam()
    const url = `${OFFICIAL_BASE.replace(/\/$/, '')}/index.html?site=${param}`
    // 官网是独立项目：没启动的话打开就是个"访问不了"，先把话说清楚
    if (!(await officialReachable())) {
      win?.close()
      await ElMessageBox.alert(
        `<div style="line-height:1.9">预览要把草稿喂给官网页面，但 <b>${OFFICIAL_BASE}</b> 上没服务在跑。</div>`
        + '<div style="margin-top:8px;line-height:1.9">启动官网：在项目根目录执行 '
        + '<b>bash website/serve.sh</b>（默认端口 5180），然后重新点「预览官网」。</div>',
        '官网服务没启动',
        { confirmButtonText: '知道了', showClose: false, dangerouslyUseHTMLString: true },
      ).catch(() => undefined)
      return
    }
    if (win) {
      win.location.href = url
    } else {
      window.open(url, '_blank')
    }
    ElMessage.success('已打开预览（只有你自己看得到，不影响访客）')
  } catch (e) {
    win?.close()
    errorMessage(e, '预览失败')
  } finally {
    busy.preview = false
  }
}

/**
 * 官网可达性探测。
 *
 * <p>用 {@code no-cors}：跨域时拿不到响应内容（也不需要有），但只要 TCP/HTTP 通了这个
 * Promise 就会 resolve；连不上才会 reject——正好拿来回答"官网起来了没"。</p>
 */
async function officialReachable(): Promise<boolean> {
  try {
    await fetch(`${OFFICIAL_BASE.replace(/\/$/, '')}/index.html`, { mode: 'no-cors', cache: 'no-store' })
    return true
  } catch {
    return false
  }
}

async function saveAndPublish(): Promise<void> {
  // 草稿和线上一致时后端会拒绝发布（"草稿与已发布一致，没有需要发布的内容"），
  // 这种情况先说清楚就够了，不必发一次注定失败的请求再弹红字
  if (!dirty.value) {
    ElMessage.info('当前草稿与线上一致，没有需要发布的内容')
    return
  }
  busy.publish = true
  try {
    if (touched.value) {
      const saved = await doSave(true)
      if (!saved) {
        return
      }
    }
    const state = await publishSiteConfig()
    applyState(state)
    ElMessage.success(`已发布 v${state.version}，访客打开官网即生效`)
  } catch (e) {
    errorMessage(e, '发布失败')
  } finally {
    busy.publish = false
  }
}

async function resetDraft(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '草稿会恢复成出厂文案，但线上版本不变——要生效还要再点「保存并发布」。确定吗？',
      '恢复默认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.reset = true
  try {
    applyState(await resetSiteConfigDraft())
    ElMessage.success('草稿已恢复出厂文案（还没发布）')
  } catch (e) {
    errorMessage(e, '恢复默认失败')
  } finally {
    busy.reset = false
  }
}

onMounted(load)
</script>

<style scoped>
.sitecfg-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; max-width: 760px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; flex-wrap: wrap; }
.btn-icon { margin-right: 4px; }

.status-strip {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
.ss-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef4ff;
  color: #2563eb;
  flex-shrink: 0;
}
.ss-body { flex: 1; min-width: 0; }
.ss-title { font-size: 14px; font-weight: 700; color: #0f172a; }
.ss-sub { margin-top: 3px; font-size: 12.5px; color: #94a3b8; }
.ss-badge { flex-shrink: 0; }

.cfg-body { display: grid; grid-template-columns: 216px minmax(0, 1fr); gap: 16px; align-items: start; }

.cfg-rail :deep(.el-card__body) { padding: 12px; }
.rail-title { font-size: 12px; color: #94a3b8; padding: 4px 8px 10px; }
.rail-item {
  width: 100%;
  display: flex;
  align-items: center;
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
.rail-label { flex: 1; }
.rail-dot { width: 7px; height: 7px; border-radius: 50%; background: #f59e0b; flex-shrink: 0; }
.rail-tip {
  margin-top: 10px;
  padding: 8px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #94a3b8;
  background: #f8fafc;
  border-radius: 8px;
}

.cfg-main { min-width: 0; }
.cfg-card { margin-bottom: 14px; }
.card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.card-sub { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }

.cfg-fields { margin-top: 16px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.field-label { font-size: 12.5px; color: #64748b; }

.cfg-list { margin-top: 18px; }
.list-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.list-title { font-size: 13px; font-weight: 600; color: #334155; }
.list-item {
  border: 1px solid #eef2f7;
  border-radius: 10px;
  padding: 10px 12px 12px;
  margin-bottom: 8px;
  background: #fbfdff;
}
.li-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.li-title { font-size: 12.5px; font-weight: 600; color: #64748b; }
.li-actions { display: flex; align-items: center; gap: 2px; }
.li-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 14px; }
.li-fields.one-col { grid-template-columns: minmax(0, 1fr); }

.foot-tip { margin: 4px 0 0; font-size: 12px; color: #94a3b8; line-height: 1.7; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #64748b; }

@media (max-width: 1100px) {
  .cfg-body { grid-template-columns: minmax(0, 1fr); }
  .cfg-fields { grid-template-columns: minmax(0, 1fr); }
  .li-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
