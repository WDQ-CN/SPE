<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在它的白名单里，需显式引入
import { ElMessageBox, type UploadFile } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import {
  downloadImportTemplate,
  getSheetScores,
  importScores,
  previewScores,
  saveScores,
  submitSheet,
  type ScoreEntryPayload,
} from '@/features/evaluation/api'
import { checkRawValue, inputHint } from '@/features/evaluation/raw-value'
import { sheetPermissions } from '@/features/evaluation/sheet-status'
import { SHEET_STATUS_LABEL } from '@/shared/labels'
import type { SheetIndicatorEntry, SheetPreview, SheetScoreView } from '@/shared/types/dto'

/**
 * 打分页（01 E2/E4 / 13 §E19）。
 *
 * <p><b>三条从 P2 搬来的教训</b>：
 * 1. **切单先清数据再渲染**（03 §31.7.16 的 P2-1）：`sheet` 置 null 再请求，且写入口由
 *    `shetPermissions(..., loaded, ...)` 统一关闸——窗口期绝不能让"在旧数据上点保存"发生。
 * 2. **录入值的前端预校验与后端同一套边界**（raw-value.ts ↔ 01 §6.6）：前端只是"提前说"，不是唯一防线；
 *    真正拒绝的是后端 ` ScoreWriter` → 2025。
 * 3. **试算实时反映"改过的格子"**：把当前所有已填值传给 `preview`（不落库），
 *    维度分与总分都可能为 null（E6 的不完整口径），所以界面上要显示"评分未齐"而不是 0 分。
 *
 * <p>⚠️ **能不能读到这张单子，本身就证明了它是不是我的**：后端 `readSheet` 对非指派人直接 4002。
 * 所以这里不需要"当前用户 id"参与判定（前端也没有它）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const sheetId = computed(() => Number(route.params.id))
/** 当前这张评分单；null = 还没加载到（切单窗口期） */
const sheet = ref<SheetScoreView | null>(null)
/** 本地录入值：indicatorId → 用户输入（null = 清空） */
const values = ref<Record<number, number | null>>({})
const preview = ref<SheetPreview | null>(null)
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const submitting = ref(false)

const permissions = computed(() =>
  // 能读到就说明是我的（后端 4002 把关），故 isAssignee 传 true
  sheetPermissions(sheet.value?.status ?? 'UNASSIGNED', sheet.value !== null, true, auth.roles),
)

const dimensionPreview = computed(() => {
  const current = preview.value
  const currentSheet = sheet.value
  if (!current || !currentSheet) {
    return null
  }
  return current.taskSupplier.sheets.find((item) => item.dimensionId === currentSheet.dimensionId) ?? null
})

/** 某格的校验结果（用于输入框下方的红字提示） */
function cellError(entry: SheetIndicatorEntry): string {
  const value = values.value[entry.indicatorId] ?? null
  const checked = checkRawValue(entry.type, entry.scoreMode, value)
  return checked.ok ? '' : checked.message
}

function loadValues(data: SheetScoreView): void {
  const next: Record<number, number | null> = {}
  data.indicators.forEach((entry) => {
    next[entry.indicatorId] = entry.rawValue
  })
  values.value = next
}

/** 把当前所有已填值整理成试算/暂存的入参（只含有值的格子） */
function filledEntries(): ScoreEntryPayload[] {
  const entries: ScoreEntryPayload[] = []
  Object.entries(values.value).forEach(([key, value]) => {
    if (value !== null && !Number.isNaN(value) && value !== undefined) {
      entries.push({ indicatorId: Number(key), rawValue: value })
    }
  })
  return entries
}

/**
 * 请求序号（04-096）：切评分单时旧请求可能**后返回**，若直接赋值就会让"屏幕上是 B、values 是 A"，
 * 而暂存接口**不带乐观锁**（`saveScores` 只传 scores），于是点保存会把 A 的值写进 B——静默写错分。
 * 与 `usePagedList` 的"并发只认最后一次"同一形态：只认最新那次的结果，过期的直接丢弃。
 */
let loadSeq = 0
let previewSeq = 0

async function runPreview(): Promise<void> {
  const seq = ++previewSeq
  const entries = filledEntries()
  const data = await previewScores(sheetId.value, entries)
  if (seq !== previewSeq) {
    return // 过期试算：新的一次已经发出，别用旧分值覆盖
  }
  preview.value = data
}

let previewTimer: number | undefined
function schedulePreview(): void {
  if (!permissions.value.canEdit) {
    return
  }
  window.clearTimeout(previewTimer)
  // 防抖：每敲一格都发一次请求既吵又慢（每次请求都会带上整张表的候选值）
  previewTimer = window.setTimeout(() => void runPreview(), 350)
}

async function load(): Promise<void> {
  const seq = ++loadSeq
  // 先清再取：窗口期内 sheet 为 null ⇒ 写入口关闭（canEdit 依赖 loaded）
  sheet.value = null
  values.value = {}
  preview.value = null
  loading.value = true
  error.value = ''
  try {
    const data = await getSheetScores(sheetId.value)
    if (seq !== loadSeq) {
      return // 期间已经切到另一张单子：这次的响应过期，丢弃（否则 values 与路由会错位）
    }
    sheet.value = data
    loadValues(data)
    await runPreview()
  } catch (caught) {
    if (seq !== loadSeq) {
      return
    }
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

async function onSave(): Promise<void> {
  const invalid = (sheet.value?.indicators ?? []).filter((entry) => cellError(entry) !== '')
  if (invalid.length > 0) {
    ElMessage.error('有录入值不合法，请先修正再保存')
    return
  }
  saving.value = true
  try {
    const written = await saveScores(sheetId.value, filledEntries())
    ElMessage.success(`已保存 ${written} 个指标`)
    await load()
  } finally {
    saving.value = false
  }
}

async function onSubmit(): Promise<void> {
  const current = sheet.value
  if (!current) {
    return
  }
  await ElMessageBox.confirm(
    '提交后本维度不可再修改（如需修改需由采购经理驳回）。确认提交？',
    '确认提交',
    { type: 'warning' },
  )
  submitting.value = true
  try {
    const result = await submitSheet(current.sheetId, current.version)
    ElMessage.success(
      result.taskSupplierStatus === 'PENDING_APPROVAL'
        ? '已提交，全部维度完成，评估单进入待审批'
        : `已提交，本维度 ${result.submittedIndicators} 个指标`,
    )
    await router.push({ name: 'my-sheets' })
  } finally {
    submitting.value = false
  }
}

async function onDownloadTemplate(): Promise<void> {
  const current = sheet.value
  if (!current) {
    return
  }
  const blob = await downloadImportTemplate(current.taskId)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `task-${current.taskId}-import-template.xlsx`
  link.click()
  URL.revokeObjectURL(url)
}

async function onImport(uploadFile: UploadFile): Promise<void> {
  const current = sheet.value
  const file = uploadFile.raw
  if (!current || !file) {
    return
  }
  const report = await importScores(current.taskId, file)
  ElMessage.success(`导入完成：写入 ${report.writtenCells} 格，跳过 ${report.skippedCells} 格`)
  await load()
}

onMounted(load)
// 从待办切到另一张评分单时不重建组件（同一个路由不同参数），必须显式重载
watch(() => route.params.id, () => void load())
</script>

<template>
  <div v-loading="loading">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <template v-else-if="sheet">
      <el-card class="block">
        <template #header>
          <div class="card-header">
            <span>评分：{{ sheet.dimensionName }}</span>
            <span class="actions">
              <el-tag>{{ SHEET_STATUS_LABEL[sheet.status] }}</el-tag>
              <el-button
                link
                type="primary"
                @click="onDownloadTemplate"
              >
                下载导入模板
              </el-button>
              <el-upload
                :auto-upload="false"
                :show-file-list="false"
                accept=".xlsx"
                @change="onImport"
              >
                <el-button
                  link
                  type="primary"
                >Excel 导入</el-button>
              </el-upload>
            </span>
          </div>
        </template>

        <el-alert
          v-if="permissions.readOnlyHint"
          :title="permissions.readOnlyHint"
          type="info"
          :closable="false"
          show-icon
        />

        <el-table :data="sheet.indicators">
          <el-table-column
            prop="code"
            label="编码"
            width="170"
          />
          <el-table-column
            prop="name"
            label="指标"
            min-width="150"
          />
          <el-table-column
            label="权重"
            width="80"
          >
            <template #default="{ row }">
              {{ row.weight }}%
            </template>
          </el-table-column>
          <el-table-column
            label="录入值"
            width="240"
          >
            <template #default="{ row }">
              <el-input-number
                v-model="values[row.indicatorId]"
                :disabled="!permissions.canEdit"
                :controls="false"
                :precision="row.type === 'QUAL' ? 0 : 2"
                :step="row.type === 'QUAL' ? 1 : 0.01"
                class="input"
                @change="schedulePreview"
              />
              <div class="hint">
                {{ inputHint(row.type, row.scoreMode) }}
              </div>
              <div
                v-if="cellError(row as SheetIndicatorEntry)"
                class="error"
              >
                {{ cellError(row as SheetIndicatorEntry) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column
            prop="standard"
            label="评分标准"
            min-width="200"
          />
          <el-table-column
            label="换算分"
            width="100"
          >
            <template #default="{ row }">
              <span v-if="row.scoreValue !== null">{{ row.scoreValue }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="block">
        <template #header>
          试算（不落库）
        </template>
        <el-descriptions :column="3">
          <el-descriptions-item label="本维度得分">
            <span v-if="dimensionPreview?.complete">{{ dimensionPreview.score }}（{{ dimensionPreview.grade }}）</span>
            <el-tag
              v-else
              type="info"
              size="small"
            >
              评分未齐
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总分">
            <span v-if="preview?.taskSupplier.complete">
              {{ preview?.taskSupplier.totalScore }}（{{ preview?.taskSupplier.totalGrade }}）
            </span>
            <el-tag
              v-else
              type="info"
              size="small"
            >
              评分未齐
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="维度进度">
            {{ preview?.taskSupplier.sheets.filter((item) => item.complete).length ?? 0 }} /
            {{ preview?.taskSupplier.sheets.length ?? 0 }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <div class="footer">
        <el-button
          :disabled="!permissions.canEdit"
          :loading="saving"
          @click="onSave"
        >
          保存
        </el-button>
        <el-button
          type="primary"
          :disabled="!permissions.canSubmit"
          :loading="submitting"
          @click="onSubmit"
        >
          提交
        </el-button>
      </div>
    </template>
    <el-empty
      v-else-if="!loading"
      description="评分单不可见或不存在"
    />
  </div>
</template>

<style scoped>
.block {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.input {
  width: 100%;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.error {
  font-size: 12px;
  color: var(--el-color-danger);
}

.footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
