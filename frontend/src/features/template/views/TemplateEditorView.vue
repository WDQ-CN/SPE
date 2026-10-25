<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在白名单里，需显式引入并带样式
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import { useAuthStore } from '@/features/auth/store'
import {
  addDimension,
  addIndicator,
  deleteDimension,
  deleteIndicator,
  getVersionTree,
  publishVersion,
  updateDimension,
  updateIndicator,
} from '@/features/template/api'
import { interpretWriteResult } from '@/features/template/effective-version'
import { checkTemplateWeights, formatWeight } from '@/features/template/weight'
import { useSubmit } from '@/shared/composables/useSubmit'
import {
  DEPARTMENT_OPTIONS,
  INDICATOR_TYPE_LABEL,
  INDICATOR_TYPE_OPTIONS,
  SCORE_MODE_LABEL,
  SCORE_MODE_OPTIONS,
  TEMPLATE_VERSION_STATUS_LABEL,
} from '@/shared/labels'
import type { DimensionView, IndicatorView, TemplateVersionTree, WriteResult } from '@/shared/types/dto'
import type { IndicatorType, ScoreMode } from '@/shared/types/enums'

/**
 * 模板版本编辑器（01 T1~T4 / 12 §3-W4）。
 *
 * <p>三件事必须做对，否则体验与数据都会出问题：
 *
 * 1. **权重实时提示，但只拦发布、不拦保存**（12 §E4）。草稿允许合计 ≠ 100——想把 40 改成 30
 *    的用户必须先经过 70 这个中间态，如果连保存都拦，他就改不动了。所以提示常驻、发布按钮按
 *    {@link report} 的 `publishable` 禁用。
 * 2. **改动已发布版本会自动复制草稿**（12 §E5/E18）。写端点回的是 `effectiveVersionId`，与本页
 *    当前版本不同就说明发生了复制——此时行 id 全变了，只能**重新拉整棵树**，并且要 `replace` 到
 *    新版本（用 `replace` 而不是 `push`：用户没有"回到刚才那个已发布版本继续改"的意图）。
 * 3. **MANAGER 只读**（01 §7）：隐藏写入口。真正的判定在后端（403 + 4001 + `ACCESS_DENIED`）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const canWrite = computed(() => auth.roles.includes('ADMIN'))

const tree = ref<TemplateVersionTree | null>(null)
const loading = ref(false)
const loadError = ref('')

// 页面级写操作与对话框共用一套提交状态：同一时刻只应有一个写请求在飞
const { submitting, errorMessage, run, clearError } = useSubmit()

const dimensions = computed<DimensionView[]>(() => tree.value?.dimensions ?? [])

/**
 * 权重报告：维度合计 + 每个维度内的指标合计（12 §E4）。
 *
 * 传 `dimensions` 而不是后端快照：编辑器看到的就是它将保存的内容，与"发布时会校验什么"同源。
 */
const report = computed(() =>
  checkTemplateWeights(
    dimensions.value.map((dimension) => ({
      name: dimension.name,
      weight: dimension.weight,
      indicators: dimension.indicators.map((indicator) => ({
        weight: indicator.weight,
        code: indicator.code,
        scoreMode: indicator.scoreMode,
        targetValue: indicator.targetValue,
      })),
    })),
  ),
)

const isDraft = computed(() => tree.value?.status === 'DRAFT')
const canPublish = computed(() => canWrite.value && isDraft.value && report.value.publishable)

const publishHint = computed(() => {
  if (!tree.value) {
    return ''
  }
  if (!isDraft.value) {
    return '该版本已发布、不可修改；在这里做的任何改动都会自动复制出一份新草稿。'
  }
  // ⚠️ 空版本要**单独一条**（03 §30 的 P2-2）：一个维度都没有时 `report.dimensions.valid` 必然为 false
  // （合计 0.00），原来会落进下面那条通用文案，用户看到的是「维度权重合计 0.00，还差 100.00」——
  // 那是"权重没配齐"的口气，而真实情况是他**一个维度都还没建**，先配权重是配不出来的。
  // 文案分叉只发生在这里；`weight.ts` 的判定语义一个字没改。
  // 位置在"已发布"之后：已发布版本即使没有维度，也该先说"不可修改"，而不是催他加维度。
  if (dimensions.value.length === 0) {
    return '这个版本还没有维度，发布前请至少添加一个维度并在其中配好指标。'
  }
  if (report.value.emptyDimensions.length > 0) {
    return `维度「${report.value.emptyDimensions.join('、')}」下还没有指标，发布前请至少添加一个。`
  }
  if (!report.value.dimensions.valid) {
    return report.value.dimensions.message
  }
  if (report.value.indicatorIssues.length > 0) {
    return report.value.indicatorIssues.map((issue) => issue.check.message).join('；')
  }
  if (report.value.invalidTargets.length > 0) {
    return `以下"目标达成率"指标缺目标值（或不为正数）：${report.value.invalidTargets.join('、')}`
  }
  if (report.value.duplicateCodes.length > 0) {
    // 后端发布校验的第五类（2014，唯一键 uk_indicator_version_code）：
    // 不在这儿说出来，就会出现"闸门禁了按钮却不告诉用户为什么"（03 §38 的 P2-18）
    return `指标编码在版本内重复：${report.value.duplicateCodes.join('、')}（编码需在整个版本内唯一，跨维度也算）`
  }
  if (!canWrite.value) {
    // P2-8：只读角色不该看到"可以发布"——文案与权限矛盾会误导（按钮本来就不渲染）
    return '当前角色为只读：可以查看权重口径，但不能修改或发布。'
  }
  return '权重合计正确，可以发布。'
})

/** 请求序号：只有最后一次 `loadTree` 的结果会被采用（见函数内注释）。 */
let treeRequestSeq = 0

async function loadTree(): Promise<void> {
  const versionId = Number(route.params.versionId)
  // ⚠️ 请求序号：`loadTree` 可能被并发触发（快速点两次"重试"、或路由切换与重试叠加），
  // 而 `usePagedList` 早就为列表做过同一件事。这里更要紧——乱序后 `tree.value` 可能是**另一个版本**
  // 的树，而 `addDimension` 用的是 `tree.value.versionId`，那就等于**往非预期版本写**
  // （03 §30 的 P2-10；审查者没能构造出高可达序列，但修法只要三行，不值得赌）。
  const seq = ++treeRequestSeq
  if (!Number.isFinite(versionId)) {
    // ⚠️ 这里必须**同时清掉 tree**：只设 loadError 的话，错误提示会与"上一个版本"的维度树同时渲染，
    // 而写按钮仍然可用 → 用户能在"URL 与内容不一致"的状态下往旧版本写（03 §30 的 P2-6）。
    tree.value = null
    loadError.value = '版本参数不合法'
    return
  }
  // ⚠️ **换了版本就必须在 await 之前把旧树清掉**（03 §30 的 P2-1）：路由组件被复用（`/templates/:versionId`
  // 的 `name` 不变 ⇒ 不重新挂载）时，`watch` 只调 `loadTree`，而 `tree.value` 仍指向**上一个版本**——
  // 请求在飞的窗口期内页面渲染的是「上个版本的树 + 新版本的 URL」，写入口（加指标/改维度/删维度、
  // 发布 vN）全都还在，点下去就是**拿旧版本的 id 往旧版本写**（`runWrite` 用的是 `tree.value.versionId`）。
  // 上面那条"参数不合法"分支早就这么做了，这里只是把同一条判据补到正常路径上。
  // 判据是"手里那棵树的 versionId 与目标不同"——写完重取、点重试这些**同一版本**的重载不清树，避免自己闪自己。
  if (tree.value !== null && tree.value.versionId !== versionId) {
    tree.value = null
  }
  loading.value = true
  loadError.value = ''
  try {
    const loaded = await getVersionTree(versionId)
    if (seq !== treeRequestSeq) {
      return
    }
    tree.value = loaded
  } catch (caught) {
    if (seq !== treeRequestSeq) {
      return
    }
    tree.value = null
    loadError.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    if (seq === treeRequestSeq) {
      loading.value = false
    }
  }
}

// immediate: 首次进入要加载；之后每次 route 参数变化（自动复制后 replace）都要重新拉树
watch(() => route.params.versionId, () => void loadTree(), { immediate: true })

/**
 * 统一处理写响应：原地更新就重载当前树，"复制了草稿"就跳到新版本。
 *
 * 判定逻辑在 {@link interpretWriteResult}（纯函数、有单测）——分支写错的后果是后续每次改删都被
 * 3001 拒，那种现象很难联想到"这里判断错了"。
 */
async function runWrite(action: () => Promise<WriteResult>): Promise<boolean> {
  const current = tree.value
  if (!current) {
    return false
  }
  return run(async () => {
    const result = await action()
    const outcome = interpretWriteResult(result, current.versionId, current.versionNo)
    if (outcome.copied) {
      ElMessage.info(outcome.message)
      // replace 而不是 push：用户不打算回到那个不可修改的已发布版本
      await router.replace({
        name: 'template-editor',
        params: { versionId: outcome.effectiveVersionId },
      })
      // route 变化由上面的 watch 触发 loadTree
    } else {
      await loadTree()
    }
  })
}

// ---- 维度对话框 ----
const dimensionDialog = ref(false)
const editingDimension = ref<DimensionView | null>(null)
const dimensionFormRef = ref<FormInstance>()
const dimensionForm = reactive({
  name: '',
  weight: 0,
  sortNo: 0,
  suggestedDept: '',
  isKey: false,
})

// 与后端 Create/UpdateDimensionRequest 对齐：名称 ≤100、权重 0~100 两位小数、建议部门 ≤50
const dimensionRules: FormRules = {
  name: [
    { required: true, message: '请输入维度名称', trigger: 'blur' },
    { max: 100, message: '维度名称长度不得超过 100', trigger: 'blur' },
  ],
  weight: [
    { required: true, message: '请输入权重', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const weight = Number(value)
        if (Number.isNaN(weight) || weight < 0 || weight > 100) {
          callback(new Error('权重须在 0~100 之间'))
          return
        }
        // 与后端 @Digits(integer = 3, fraction = 2) 对齐：超过两位小数入库会被拒
        if (Math.round(weight * 100) !== weight * 100) {
          callback(new Error('权重最多保留两位小数'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  suggestedDept: [{ max: 50, message: '建议评估部门长度不得超过 50', trigger: 'blur' }],
}

function openDimensionDialog(dimension: DimensionView | null): void {
  editingDimension.value = dimension
  Object.assign(dimensionForm, {
    name: dimension?.name ?? '',
    weight: dimension?.weight ?? 0,
    sortNo: dimension?.sortNo ?? dimensions.value.length + 1,
    suggestedDept: dimension?.suggestedDept ?? '',
    isKey: dimension?.isKey ?? false,
  })
  clearError()
  dimensionDialog.value = true
  void nextTick(() => dimensionFormRef.value?.clearValidate())
}

async function submitDimension(): Promise<void> {
  const valid = await dimensionFormRef.value?.validate().catch(() => false)
  if (!valid || !tree.value) {
    return
  }
  const payload = {
    name: dimensionForm.name.trim(),
    weight: Number(dimensionForm.weight),
    sortNo: Number(dimensionForm.sortNo),
    // 留空发 null 而不是空串：库里「未填写」是 NULL，空串是另一个值（与供应商分类同一处理）
    suggestedDept: dimensionForm.suggestedDept.trim() || null,
    isKey: dimensionForm.isKey,
  }
  const target = editingDimension.value
  const versionId = tree.value.versionId
  const ok = await runWrite(() =>
    target
      ? updateDimension(target.id, { ...payload, version: target.version })
      : addDimension(versionId, payload),
  )
  if (ok) {
    dimensionDialog.value = false
    ElMessage.success(target ? '维度已保存' : '维度已新增')
  }
}

async function removeDimension(dimension: DimensionView): Promise<void> {
  if (!tree.value) {
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确定删除维度「${dimension.name}」吗？它下面的 ${dimension.indicators.length} 个指标会一并删除。`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  const ok = await runWrite(() => deleteDimension(dimension.id, dimension.version))
  if (ok) {
    ElMessage.success('维度已删除')
  }
}

// ---- 指标对话框 ----
const indicatorDialog = ref(false)
const editingIndicator = ref<IndicatorView | null>(null)
const indicatorDimensionId = ref(0)
const indicatorFormRef = ref<FormInstance>()
const indicatorForm = reactive({
  name: '',
  code: '',
  weight: 0,
  type: 'QUANT' as IndicatorType,
  scoreMode: 'RATIO' as ScoreMode,
  unit: '',
  targetValue: null as number | null,
  standard: '',
  sortNo: 0,
})

const isQuant = computed(() => indicatorForm.type === 'QUANT')
const isTarget = computed(() => isQuant.value && indicatorForm.scoreMode === 'TARGET')

// 与后端 Create/UpdateIndicatorRequest 对齐：名称 ≤100、编码 ≤50、权重同上、单位 ≤20、标准 ≤500
const indicatorRules: FormRules = {
  name: [
    { required: true, message: '请输入指标名称', trigger: 'blur' },
    { max: 100, message: '指标名称长度不得超过 100', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入指标编码', trigger: 'blur' },
    { max: 50, message: '指标编码长度不得超过 50', trigger: 'blur' },
  ],
  weight: dimensionRules.weight,
  unit: [{ max: 20, message: '单位长度不得超过 20', trigger: 'blur' }],
  standard: [{ max: 500, message: '评分标准长度不得超过 500', trigger: 'blur' }],
  targetValue: [
    {
      validator: (_rule, value, callback) => {
        // 目标值只对 TARGET 有意义；12 §E2：其余情况下它应当是 NULL，而不是残留一个旧值
        if (!isTarget.value || value === null || value === '') {
          callback()
          return
        }
        if (Number(value) <= 0) {
          callback(new Error('目标值须大于 0'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function openIndicatorDialog(dimension: DimensionView, indicator: IndicatorView | null): void {
  editingIndicator.value = indicator
  indicatorDimensionId.value = dimension.id
  Object.assign(indicatorForm, {
    name: indicator?.name ?? '',
    code: indicator?.code ?? '',
    weight: indicator?.weight ?? 0,
    type: indicator?.type ?? 'QUANT',
    scoreMode: indicator?.scoreMode ?? 'RATIO',
    unit: indicator?.unit ?? '',
    targetValue: indicator?.targetValue ?? null,
    standard: indicator?.standard ?? '',
    sortNo: indicator?.sortNo ?? dimension.indicators.length + 1,
  })
  clearError()
  indicatorDialog.value = true
  void nextTick(() => indicatorFormRef.value?.clearValidate())
}

async function submitIndicator(): Promise<void> {
  const valid = await indicatorFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload = {
    name: indicatorForm.name.trim(),
    code: indicatorForm.code.trim(),
    weight: Number(indicatorForm.weight),
    type: indicatorForm.type,
    // 12 §E2：定性指标没有计分方式（策略由 type 决定）；定量才带上目标值
    scoreMode: isQuant.value ? indicatorForm.scoreMode : null,
    unit: indicatorForm.unit.trim() || null,
    targetValue: isTarget.value && indicatorForm.targetValue !== null
      ? Number(indicatorForm.targetValue)
      : null,
    standard: indicatorForm.standard.trim() || null,
    sortNo: Number(indicatorForm.sortNo),
  }
  const target = editingIndicator.value
  const dimensionId = indicatorDimensionId.value
  const ok = await runWrite(() =>
    target
      ? updateIndicator(target.id, { ...payload, version: target.version })
      : addIndicator(dimensionId, payload),
  )
  if (ok) {
    indicatorDialog.value = false
    ElMessage.success(target ? '指标已保存' : '指标已新增')
  }
}

async function removeIndicator(indicator: IndicatorView): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定删除指标「${indicator.name}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  const ok = await runWrite(() => deleteIndicator(indicator.id, indicator.version))
  if (ok) {
    ElMessage.success('指标已删除')
  }
}

// ---- 发布 ----
async function publish(): Promise<void> {
  const current = tree.value
  if (!current) {
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确定发布 v${current.versionNo} 吗？发布后该版本不可再修改，后续改动会自动复制出新草稿。`,
    '发布确认',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  const ok = await run(async () => {
    await publishVersion(current.versionId, current.version)
    await loadTree()
  })
  if (ok) {
    ElMessage.success(`v${current.versionNo} 已发布`)
  }
}

function statusTagType(): 'success' | 'warning' | 'info' {
  if (!tree.value) {
    return 'info'
  }
  if (tree.value.status === 'PUBLISHED') {
    return 'success'
  }
  return tree.value.status === 'DRAFT' ? 'warning' : 'info'
}
</script>

<template>
  <PageHeader
    eyebrow="TEMPLATE EDITOR"
    title="模板编辑器"
  />

  <!-- 刻意不走 SectionCard（03 §35 登记在案的例外）：这里的头部是"标题 + 状态 tag + 副标题 + 按钮组"
       的复合结构，SectionCard 的 title + actions 两个槽装不下它的四层信息。 -->
  <el-card
    v-loading="loading"
    class="page-block"
    style="animation-delay: 0.1s"
  >
    <div class="editor__header">
      <div>
        <h2 class="editor__title">
          {{ tree?.templateName ?? '模板编辑器' }}
          <el-tag
            v-if="tree"
            :type="statusTagType()"
            class="editor__status"
          >
            {{ TEMPLATE_VERSION_STATUS_LABEL[tree.status] }}
          </el-tag>
        </h2>
        <p
          v-if="tree"
          class="editor__subtitle"
        >
          版本 v{{ tree.versionNo }}（版本 id {{ tree.versionId }}）· 共 {{ dimensions.length }} 个维度
        </p>
      </div>
      <div>
        <el-button @click="router.push({ name: 'templates' })">
          返回列表
        </el-button>
        <el-button
          v-if="canWrite && tree"
          type="primary"
          :disabled="!canPublish"
          :loading="submitting"
          @click="publish"
        >
          发布 v{{ tree.versionNo }}
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      class="editor__error"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
    >
      <el-button
        link
        type="primary"
        @click="loadTree"
      >
        重试
      </el-button>
    </el-alert>

    <el-alert
      v-else-if="tree"
      class="editor__weight"
      :type="tree.status !== 'DRAFT' ? 'info' : report.publishable ? 'success' : 'warning'"
      :closable="false"
      show-icon
      :title="publishHint"
    />

    <el-alert
      v-if="errorMessage"
      class="editor__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorMessage"
    />

    <div
      v-for="dimension in dimensions"
      :key="dimension.id"
      class="editor__dimension"
    >
      <div class="editor__dimension-head">
        <div>
          <strong>{{ dimension.name }}</strong>
          <el-tag
            v-if="dimension.isKey"
            type="danger"
            size="small"
            class="editor__key-tag"
          >
            关键
          </el-tag>
          <span class="editor__muted">
            权重 {{ formatWeight(dimension.weight) }}
            <template v-if="dimension.suggestedDept">
              · 建议部门 {{ dimension.suggestedDept }}
            </template>
          </span>
        </div>
        <div v-if="canWrite">
          <el-button
            link
            type="primary"
            @click="openIndicatorDialog(dimension, null)"
          >
            加指标
          </el-button>
          <el-button
            link
            type="primary"
            @click="openDimensionDialog(dimension)"
          >
            改维度
          </el-button>
          <el-button
            link
            type="danger"
            @click="removeDimension(dimension)"
          >
            删维度
          </el-button>
        </div>
      </div>

      <el-table
        :data="dimension.indicators"
        size="small"
        border
      >
        <el-table-column
          prop="code"
          label="编码"
          min-width="150"
        />
        <el-table-column
          prop="name"
          label="指标名称"
          min-width="180"
        />
        <el-table-column
          label="权重"
          width="90"
        >
          <template #default="{ row }">
            {{ formatWeight((row as IndicatorView).weight) }}
          </template>
        </el-table-column>
        <el-table-column
          label="类型"
          width="90"
        >
          <template #default="{ row }">
            {{ INDICATOR_TYPE_LABEL[(row as IndicatorView).type] }}
          </template>
        </el-table-column>
        <el-table-column
          label="计分方式"
          width="120"
        >
          <template #default="{ row }">
            <span v-if="(row as IndicatorView).scoreMode">
              {{ SCORE_MODE_LABEL[(row as IndicatorView).scoreMode!] }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column
          label="目标值"
          width="110"
        >
          <template #default="{ row }">
            <span v-if="(row as IndicatorView).targetValue !== null">
              {{ (row as IndicatorView).targetValue }}{{ (row as IndicatorView).unit ?? '' }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canWrite"
          label="操作"
          width="140"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openIndicatorDialog(dimension, row as IndicatorView)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              @click="removeIndicator(row as IndicatorView)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div
      v-if="tree && dimensions.length === 0"
      class="editor__empty"
    >
      <p>这个版本还没有维度。至少添加一个维度并配好权重后才能发布。</p>
      <el-button
        v-if="canWrite"
        type="primary"
        @click="openDimensionDialog(null)"
      >
        添加第一个维度
      </el-button>
    </div>

    <el-button
      v-if="canWrite && dimensions.length > 0"
      class="editor__add-dimension"
      @click="openDimensionDialog(null)"
    >
      添加维度
    </el-button>

    <!-- 维度对话框 -->
    <el-dialog
      v-model="dimensionDialog"
      :title="editingDimension ? '编辑维度' : '新增维度'"
      width="460px"
    >
      <el-form
        ref="dimensionFormRef"
        :model="dimensionForm"
        :rules="dimensionRules"
        label-width="110px"
      >
        <el-form-item
          label="维度名称"
          prop="name"
        >
          <el-input
            v-model="dimensionForm.name"
            maxlength="100"
            placeholder="如：质量"
          />
        </el-form-item>
        <el-form-item
          label="权重"
          prop="weight"
        >
          <el-input-number
            v-model="dimensionForm.weight"
            :min="0"
            :max="100"
            :precision="2"
            :step="1"
          />
          <span class="editor__muted">（全部维度合计须为 100）</span>
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number
            v-model="dimensionForm.sortNo"
            :min="0"
          />
        </el-form-item>
        <el-form-item label="建议评估部门">
          <el-select
            v-model="dimensionForm.suggestedDept"
            clearable
            placeholder="可留空"
          >
            <el-option
              v-for="option in DEPARTMENT_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关键维度">
          <el-switch v-model="dimensionForm.isKey" />
          <span class="editor__muted">（供预警规则使用）</span>
        </el-form-item>
      </el-form>
      <p
        v-if="errorMessage"
        class="editor__error-text"
      >
        {{ errorMessage }}
      </p>
      <template #footer>
        <el-button @click="dimensionDialog = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="submitDimension"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 指标对话框 -->
    <el-dialog
      v-model="indicatorDialog"
      :title="editingIndicator ? '编辑指标' : '新增指标'"
      width="560px"
    >
      <el-form
        ref="indicatorFormRef"
        :model="indicatorForm"
        :rules="indicatorRules"
        label-width="110px"
      >
        <el-form-item
          label="指标名称"
          prop="name"
        >
          <el-input
            v-model="indicatorForm.name"
            maxlength="100"
          />
        </el-form-item>
        <el-form-item
          label="指标编码"
          prop="code"
        >
          <el-input
            v-model="indicatorForm.code"
            maxlength="50"
            placeholder="如：IQC_PASS_RATE（版本内唯一，大小写不敏感）"
          />
        </el-form-item>
        <el-form-item
          label="权重"
          prop="weight"
        >
          <el-input-number
            v-model="indicatorForm.weight"
            :min="0"
            :max="100"
            :precision="2"
            :step="1"
          />
          <span class="editor__muted">（同一维度内合计须为 100）</span>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="indicatorForm.type">
            <el-option
              v-for="option in INDICATOR_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计分方式">
          <el-select
            v-model="indicatorForm.scoreMode"
            :disabled="!isQuant"
          >
            <el-option
              v-for="option in SCORE_MODE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <span class="editor__muted">（定性指标不需要）</span>
        </el-form-item>
        <el-form-item
          label="目标值"
          prop="targetValue"
        >
          <el-input-number
            v-model="indicatorForm.targetValue"
            :disabled="!isTarget"
            :precision="4"
            :step="1"
          />
          <span class="editor__muted">（仅"目标达成率"需要，须大于 0）</span>
        </el-form-item>
        <el-form-item label="单位">
          <el-input
            v-model="indicatorForm.unit"
            maxlength="20"
            placeholder="如：% 或 天"
          />
        </el-form-item>
        <el-form-item label="评分标准">
          <el-input
            v-model="indicatorForm.standard"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="定性指标的档位文案；直接打分指标可写换算表"
          />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number
            v-model="indicatorForm.sortNo"
            :min="0"
          />
        </el-form-item>
      </el-form>
      <p
        v-if="errorMessage"
        class="editor__error-text"
      >
        {{ errorMessage }}
      </p>
      <template #footer>
        <el-button @click="indicatorDialog = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="submitIndicator"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.editor__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}

.editor__title {
  margin: 0;
}

.editor__status {
  margin-left: 8px;
  vertical-align: middle;
}

.editor__subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.editor__weight,
.editor__error {
  margin-bottom: 12px;
}

.editor__dimension {
  margin-bottom: 20px;
}

.editor__dimension-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.editor__key-tag {
  margin: 0 6px;
}

.editor__muted {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.editor__empty {
  padding: 24px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

/* ⚠️ 2xxx（如"编码重复 2014"、"维度不存在 2015"）由 useSubmit 只写进 errorMessage，
   而 http 拦截器对 param 段刻意不弹全局提示——所以对话框里**必须**渲染它，否则用户在
   对话框内提交失败时看不到任何原因（页面级那个 alert 在模态遮罩之下）。见 03 §30 的 P1-1。 */
.editor__error-text {
  margin: 0 0 4px;
  color: var(--el-color-danger);
  font-size: 13px;
}

.editor__add-dimension {
  margin-top: 8px;
}
</style>
