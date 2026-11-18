<template>
  <div class="qa-rules-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">质检规则配置</h2>
        <p class="page-sub">配置规则引擎的检查项与权重，命中后生成待复核质检任务</p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/modules/qa')">
          <el-icon class="btn-icon"><Back /></el-icon>
          返回质检中心
        </el-button>
        <el-button type="primary" @click="openRule()">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新增规则
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="main-card">
      <el-table :data="rules" v-loading="loadingRules">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column label="规则名称" min-width="180">
          <template #default="{ row }">
            <div class="rule-name-cell">
              <span class="rule-icon" :class="`type-${row.ruleType}`" :title="row.ruleTypeText">
                <el-icon :size="18"><component :is="ruleIcon(row.ruleType)" /></el-icon>
              </span>
              <span class="rule-name">{{ row.ruleName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ row.ruleTypeText }}</template>
        </el-table-column>
        <el-table-column label="规则内容" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.ruleContent || '—' }}
            <span v-if="row.ruleType === 5" class="timeout-tip">（超时 {{ row.timeoutSeconds }} 秒）</span>
          </template>
        </el-table-column>
        <el-table-column label="权重" width="110">
          <template #default="{ row }"><span class="weight-value">{{ row.weight }}%</span></template>
        </el-table-column>
        <el-table-column label="实时质检" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.realtime" size="small" type="warning" effect="light">参与</el-tag>
            <span v-else class="muted">仅批量</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              @change="toggleRule(row, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRule(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRuleInline(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="ruleVisible" :title="editingRule ? '编辑质检规则' : '新建质检规则'" width="560px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="规则名称" required>
          <el-input v-model="ruleForm.ruleName" maxlength="64" placeholder="例如：敏感词与禁语" />
        </el-form-item>
        <div class="form-grid-2">
          <el-form-item label="规则类型" required>
            <el-select v-model="ruleForm.ruleType" class="full-width">
              <el-option v-for="t in ruleTypes" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="权重">
            <el-input-number v-model="ruleForm.weight" :min="0" :max="100" :step="5" class="full-width" />
          </el-form-item>
        </div>
        <el-form-item label="规则内容">
          <el-input
            v-model="ruleForm.ruleContent"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="描述检查要点，例如：排查“妈的、废物”等不礼貌用语"
          />
        </el-form-item>
        <el-divider content-position="left">实时质检（边聊边检）</el-divider>
        <div class="enable-line">
          <span>参与实时质检（坐席说话当场判定并弹预警）</span>
          <el-switch v-model="ruleForm.realtime" />
        </div>
        <el-form-item label="命中词表（英文/中文逗号分隔，命中任意一个就告警）">
          <el-input
            v-model="ruleForm.hitKeywords"
            maxlength="512"
            placeholder="例如：妈的,废物,随便你"
          />
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType === 5" label="响应超时秒数（客户发完消息多久没回算超时）">
          <el-input-number v-model="ruleForm.timeoutSeconds" :min="10" :max="3600" :step="10" />
        </el-form-item>
        <el-form-item label="告警级别">
          <el-radio-group v-model="ruleForm.severity">
            <el-radio-button :value="1">提示</el-radio-button>
            <el-radio-button :value="2">警告</el-radio-button>
            <el-radio-button :value="3">严重</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="实时质检做四类：敏感词、绝对化承诺、情绪安抚未到位，以及响应超时（客户发完消息久没人回，由定时扫描每 25 秒检查一次）；必答项这类要看整段会话的，放在会话结束后批量质检。"
        />
        <div class="enable-line">
          <span>启用该规则</span>
          <el-switch v-model="ruleForm.enabled" />
        </div>
      </el-form>
      <template #footer>
        <el-button v-if="editingRule" type="danger" plain @click="removeRule">删除规则</el-button>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="submitRule">保存规则</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteQaRule,
  fetchQaRules,
  saveQaRule,
  type QaRuleItem,
} from '../../api/customer/qa'

const router = useRouter()
const rules = ref<QaRuleItem[]>([])
const loadingRules = ref(false)
const ruleVisible = ref(false)
const savingRule = ref(false)
const editingRule = ref<QaRuleItem | null>(null)

const ruleForm = reactive({
  ruleName: '',
  ruleType: 1,
  ruleContent: '',
  weight: 25,
  enabled: true,
  realtime: true,
  hitKeywords: '',
  severity: 2,
  timeoutSeconds: 60,
})

const ruleTypes = [
  { value: 1, label: '敏感词' },
  { value: 2, label: '承诺规范' },
  { value: 3, label: '必答项' },
  { value: 4, label: '情绪识别' },
  { value: 5, label: '响应超时' },
]

onMounted(loadRules)

async function loadRules() {
  loadingRules.value = true
  try {
    rules.value = await fetchQaRules()
  } finally {
    loadingRules.value = false
  }
}

function openRule(rule?: QaRuleItem) {
  editingRule.value = rule || null
  ruleForm.ruleName = rule?.ruleName || ''
  ruleForm.ruleType = rule?.ruleType || 1
  ruleForm.ruleContent = rule?.ruleContent || ''
  ruleForm.weight = rule?.weight ?? 25
  ruleForm.enabled = rule?.enabled ?? true
  ruleForm.realtime = rule?.realtime ?? true
  ruleForm.hitKeywords = rule?.hitKeywords || ''
  ruleForm.severity = rule?.severity ?? 2
  ruleForm.timeoutSeconds = rule?.timeoutSeconds ?? 60
  ruleVisible.value = true
}

async function submitRule() {
  if (!ruleForm.ruleName.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  savingRule.value = true
  try {
    await saveQaRule({
      id: editingRule.value?.id || undefined,
      ruleName: ruleForm.ruleName.trim(),
      ruleType: ruleForm.ruleType,
      ruleContent: ruleForm.ruleContent.trim() || undefined,
      weight: ruleForm.weight,
      enabled: ruleForm.enabled,
      realtime: ruleForm.realtime,
      hitKeywords: ruleForm.hitKeywords.trim() || undefined,
      severity: ruleForm.severity,
      timeoutSeconds: ruleForm.timeoutSeconds,
    })
    ruleVisible.value = false
    await loadRules()
    ElMessage.success(editingRule.value ? '规则已更新' : '规则已创建')
  } finally {
    savingRule.value = false
  }
}

async function removeRule() {
  const target = editingRule.value
  if (!target) return
  await ElMessageBox.confirm(`确认删除规则「${target.ruleName}」吗？删除后不再参与质检。`, '删除规则', { type: 'warning' })
  await deleteQaRule(target.id)
  ruleVisible.value = false
  editingRule.value = null
  await loadRules()
  ElMessage.success('规则已删除')
}

async function removeRuleInline(rule: QaRuleItem) {
  await ElMessageBox.confirm(`确认删除规则「${rule.ruleName}」吗？删除后不再参与质检。`, '删除规则', { type: 'warning' })
  await deleteQaRule(rule.id)
  await loadRules()
  ElMessage.success('规则已删除')
}

async function toggleRule(rule: QaRuleItem, value: boolean | string | number) {
  const enabled = value === true || value === 'true' || value === 1
  try {
    await saveQaRule({
      id: rule.id,
      ruleName: rule.ruleName,
      ruleType: rule.ruleType,
      ruleContent: rule.ruleContent || undefined,
      weight: rule.weight,
      enabled,
      realtime: rule.realtime,
      hitKeywords: rule.hitKeywords || undefined,
      severity: rule.severity,
      timeoutSeconds: rule.timeoutSeconds,
    })
    rule.enabled = enabled
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
  } catch {
    // 错误提示由请求层统一处理
  }
}

function ruleIcon(type: number) {
  return ['', 'Warning', 'Calendar', 'DocumentChecked', 'User'][type] || 'Setting'
}

function formatTime(time?: string) {
  return time ? time.slice(0, 19) : '—'
}
</script>

<style scoped>
.muted { color: #94a3b8; font-size: 13px; }
.timeout-tip { color: #b45309; }
.qa-rules-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; gap: 10px; }
.btn-icon { margin-right: 4px; }
.main-card { width: 100%; }
.rule-name-cell { display: flex; align-items: center; gap: 9px; }
.rule-icon { width: 34px; height: 34px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; background: #f8fafc; border: 1px solid #e2e8f0; color: #475569; }
.type-1 { color: #b91c1c; }
.type-2 { color: #b45309; }
.type-3 { color: #1d4ed8; }
.type-4 { color: #6d28d9; }
.rule-name { font-weight: 700; color: #1e293b; font-size: 13px; }
.weight-value { color: #334155; font-weight: 600; }
.form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.full-width { width: 100%; }
.enable-line { display: flex; align-items: center; justify-content: space-between; border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px 14px; color: #475569; }
</style>
