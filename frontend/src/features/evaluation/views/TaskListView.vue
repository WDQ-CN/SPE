<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在它的白名单里，需显式引入
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import {
  createTask,
  getTask,
  listTasks,
  publishTask,
  updateTask,
  type TaskDraftPayload,
} from '@/features/evaluation/api'
import { listSuppliers } from '@/features/supplier/api'
import { listPublishedVersions, listTemplates } from '@/features/template/api'
import { listUserOptions } from '@/features/user/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import { EVAL_TASK_STATUS_LABEL } from '@/shared/labels'
import type {
  PublishedVersionBrief,
  SupplierSummary,
  TaskSummary,
  UserOption,
} from '@/shared/types/dto'
import type { EvalTaskStatus } from '@/shared/types/enums'

/**
 * 评估任务列表 + 创建向导（01 J1 / 13 §E19）。
 *
 * <p>两处刻意的取舍：
 * 1. **向导里审批链必填、不给默认值**——01 J1 说「默认取系统设置」，但 SM4（默认审批链设置）是机会项、
 *    不在 MVP（07 §52）。给一个「看起来能用但恒空」的默认，比让用户明确选一次更糟（13 §E19 的原话）。
 * 2. **模板版本只列已发布版本**（listPublishedVersions）：草稿版本不能被任务引用（05 ADR-3 的快照口径），
 *    列出来只会让用户选中后被后端拒。
 *
 * <p>下拉的数据源直接复用相邻 feature 的 api（模板/供应商/用户）——各 feature 的 api.ts 是端点的唯一定义处，
 * 在本页重抄一份 `/api/tasks`？不——那样端点会散成两份，改一处漏一处。
 */
const router = useRouter()
const auth = useAuthStore()
// 写端点 MANAGER-only（01 §7）；ADMIN 能进本页看列表，但不该看到点了必 403 的按钮（03 §41.3 的 P2-38）
const canManage = computed(() => auth.roles.includes('MANAGER'))

const keyword = ref('')
const status = ref<EvalTaskStatus | ''>('')
const list = usePagedList<TaskSummary>((page, size) =>
  listTasks({
    page,
    size,
    keyword: keyword.value || undefined,
    status: status.value || undefined,
  }),
)

const dialogVisible = ref(false)
/** 非空表示正在编辑既有草稿（带版本做守卫 UPDATE）；null 表示新建。 */
const editing = ref<{ id: number; version: number } | null>(null)
const submit = useSubmit()
/** 发布是列表级的写操作：闸门与错误回显同样走 useSubmit（03 §38 的 P1-14——原来没有防重复闸门）。 */
const publish = useSubmit()
const publishingId = ref<number | null>(null)
const templates = ref<{ id: number; name: string }[]>([])
const versions = ref<PublishedVersionBrief[]>([])
const suppliers = ref<SupplierSummary[]>([])
const users = ref<UserOption[]>([])
/**
 * 下拉数据源哪一路失败了（03 §38 的 P0-1）。原来一个 403 会把三段一起打断、用户只见一句"无权限"；
 * 改成 `allSettled` 后又不能让失败彻底无声 ⇒ 这里**点名是哪一路**，一行文案由三路共用（第 3 条：单点定义）。
 */
const failedOptions = ref<string[]>([])
const optionsError = computed(() =>
  failedOptions.value.length > 0
    ? `${failedOptions.value.join('、')}选项加载失败，请重试或联系管理员`
    : '',
)
function markOptionFailed(label: string): void {
  if (!failedOptions.value.includes(label)) {
    failedOptions.value.push(label)
  }
}
function clearOptionFailed(label: string): void {
  failedOptions.value = failedOptions.value.filter((item) => item !== label)
}

const form = reactive({
  name: '',
  year: new Date().getFullYear(),
  quarter: 1,
  templateId: null as number | null,
  templateVersionId: null as number | null,
  deadline: '',
  supplierIds: [] as number[],
  approverIds: [] as number[],
})

const supplierLoading = ref(false)
/** 模板切换的迟到响应守卫（03 §41.2 的 P1-17）：慢网下先选 A 再选 B 时，A 的晚到列表不得覆盖 B。 */
let templateChangeSeq = 0

async function openDialog(): Promise<void> {
  dialogVisible.value = true
  editing.value = null
  submit.clearError()
  form.name = ''
  form.templateId = null
  form.templateVersionId = null
  form.deadline = ''
  form.supplierIds = []
  form.approverIds = []
  versions.value = []
  failedOptions.value = []
  await loadDialogOptions()
}

/** 编辑草稿：复用同一向导，按详情回填；提交走 `PUT /tasks/{id}`（带 version 守卫）。 */
async function openEdit(row: TaskSummary): Promise<void> {
  dialogVisible.value = true
  editing.value = { id: row.id, version: row.version }
  submit.clearError()
  failedOptions.value = []
  versions.value = []
  await loadDialogOptions()
  try {
    const detail = await getTask(row.id)
    form.name = detail.name
    form.year = detail.year
    form.quarter = detail.quarter
    form.deadline = detail.deadline
    form.templateId = detail.templateId
    form.templateVersionId = detail.templateVersionId
    form.supplierIds = detail.suppliers.map((item) => item.id)
    form.approverIds = detail.approvers.map((item) => item.userId)
    // 远程搜索只回前 50 家；已选中的必须留在选项里，否则 v-model 会显示裸 id
    for (const supplier of detail.suppliers) {
      if (!suppliers.value.some((item) => item.id === supplier.id)) {
        suppliers.value.push({
          id: supplier.id,
          code: supplier.code ?? '',
          name: supplier.name ?? '',
          category: null,
          status: 'ENABLED',
          version: 0,
        })
      }
    }
    for (const approver of detail.approvers) {
      if (!users.value.some((item) => item.id === approver.userId)) {
        // 下拉只展示姓名；role/department 仅用于满足 UserOption 形状
        users.value.push({
          id: approver.userId,
          name: approver.name ?? '',
          role: 'MANAGER',
          department: 'OTHER',
        })
      }
    }
    try {
      versions.value = await listPublishedVersions(detail.templateId)
    } catch {
      markOptionFailed('模板版本')
    }
  } catch {
    ElMessage.error('草稿加载失败，请重试')
    dialogVisible.value = false
    editing.value = null
  }
}

async function loadDialogOptions(): Promise<void> {
  // allSettled 而不是 all：一个分支失败（03 §38 的 P0-1 就是这个场景）不能把另外两路一起打断——
  // 但也不能只靠 allSettled 就完事：失败必须**在界面上说是哪一路失败了**，否则 P0 只是变成静默 P2
  const [templateResult, userResult] = await Promise.allSettled([
    listTemplates({ page: 1, size: 100 }),
    // 审批人限 MANAGER/ADMIN（01 §3 经 2026-10-25 用户裁决收窄，21 §3.W1-1）：
    // 不传 roles 会把评估人/查看者放进候选，选中后任务会卡死在无人能审的节点（03 §41.1）
    listUserOptions(['MANAGER', 'ADMIN']),
  ])
  if (templateResult.status === 'fulfilled') {
    templates.value = templateResult.value.list.map((item) => ({ id: item.id, name: item.name }))
  } else {
    templates.value = []
    markOptionFailed('评估模板')
  }
  if (userResult.status === 'fulfilled') {
    users.value = userResult.value
  } else {
    users.value = []
    markOptionFailed('审批链')
  }
  // 供应商走**远程搜索**（见 searchSuppliers 的注释），不再一次拉 100 家
  await searchSuppliers('')
}

/**
 * 供应商远程搜索（04-096）：需求规模是 **500 家**，而单页上限 `size` 只有 100——
 * 原先 `listSuppliers({ page: 1, size: 100 })` 会让第 101 家起**静默无法被选中**（无任何提示）。
 * 改为按关键字远程查询（后端 `GET /api/suppliers` 本就支持 `keyword` 包含匹配）。
 *
 * <p>合并策略：**已选中的必须留在选项里**，否则再次打开对话框时 `v-model` 里的 id 会显示成裸数字
 * （远程搜索的经典坑）；新查到的结果覆盖同名项。
 */
async function searchSuppliers(keyword: string): Promise<void> {
  supplierLoading.value = true
  try {
    const page = await listSuppliers({
      page: 1,
      size: 50,
      keyword: keyword.trim() || undefined,
    })
    const merged = new Map<number, SupplierSummary>()
    suppliers.value
      .filter((item) => form.supplierIds.includes(item.id))
      .forEach((item) => merged.set(item.id, item))
    page.list.forEach((item) => merged.set(item.id, item))
    suppliers.value = [...merged.values()]
    clearOptionFailed('供应商')
  } catch {
    // 原来这里是**未接住的 rejection**：远程搜索一失败就往上抛，界面上什么都不会出现
    suppliers.value = suppliers.value.filter((item) => form.supplierIds.includes(item.id))
    markOptionFailed('供应商')
  } finally {
    supplierLoading.value = false
  }
}

async function onTemplateChange(templateId: number): Promise<void> {
  const seq = ++templateChangeSeq
  form.templateVersionId = null
  try {
    const published = await listPublishedVersions(templateId)
    if (seq === templateChangeSeq) {
      versions.value = published
    }
  } catch {
    if (seq === templateChangeSeq) {
      versions.value = []
      markOptionFailed('模板版本')
    }
  }
}

async function onSaveDraft(): Promise<void> {
  const payload: TaskDraftPayload = {
    name: form.name,
    year: form.year,
    quarter: form.quarter,
    templateId: form.templateId ?? 0,
    templateVersionId: form.templateVersionId ?? 0,
    deadline: form.deadline,
    supplierIds: form.supplierIds,
    approverIds: form.approverIds,
  }
  const current = editing.value
  const ok = await submit.run(async () => {
    if (current) {
      await updateTask(current.id, { ...payload, version: current.version })
    } else {
      await createTask(payload)
    }
  })
  if (ok) {
    ElMessage.success(current ? '草稿已保存' : '草稿已创建，可在列表里发布')
    dialogVisible.value = false
    editing.value = null
    await list.reload()
  }
}

async function onPublish(row: TaskSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '发布后将为每个供应商的每个维度生成评分单并自动指派，任务进入「进行中」。发布日期与指派不可撤销。',
      '确认发布',
      { type: 'warning' },
    )
  } catch {
    // 用户点「取消」不是失败：原先这里是**未接住的 rejection**（03 §38 P1-10 的同族形态）
    return
  }
  publishingId.value = row.id
  // run() 自带防重复闸门：确认框关闭到响应返回之间再点一次，不会再拿**旧 version** 打第二遍
  // ——那会落 3001，而拦截器对 3xxx 会 refreshCurrentView()，连对话框一起重挂（03 §38 的 P1-14）
  await publish.run(async () => {
    const result = await publishTask(row.id, row.version)
    ElMessage.success(
      `已发布：${result.supplierCount} 个供应商共 ${result.sheetCount} 张评分单，` +
        `自动指派 ${result.assignedCount} 张、待指派 ${result.unassignedCount} 张`,
    )
    await list.reload()
  })
  publishingId.value = null
}

function taskStatusClass(status: EvalTaskStatus): string {
  if (status === 'IN_PROGRESS') {
    return 'is-warning'
  }
  return status === 'COMPLETED' ? 'is-success' : 'is-off'
}

onMounted(() => void list.load())
</script>

<template>
  <PageHeader
    eyebrow="EVALUATION / TASKS"
    title="评估任务"
    :description="`共 ${list.total.value} 个任务 · 发布后按维度自动生成评分单`"
  >
    <template #actions>
      <el-button
        v-if="canManage"
        type="primary"
        @click="openDialog"
      >
        新建任务
      </el-button>
    </template>
  </PageHeader>

  <SectionCard title="任务列表">
    <el-alert
      v-if="publish.errorMessage.value"
      class="page-alert"
      :title="publish.errorMessage.value"
      type="error"
      :closable="false"
      show-icon
    />

    <div class="list-toolbar">
      <el-input
        v-model="keyword"
        placeholder="按名称搜索"
        clearable
        class="list-toolbar__search"
        @keyup.enter="list.load(1)"
        @clear="list.load(1)"
      />
      <el-select
        v-model="status"
        placeholder="全部状态"
        clearable
        class="list-toolbar__select"
        @change="list.load(1)"
      >
        <el-option
          v-for="(label, value) in EVAL_TASK_STATUS_LABEL"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button
        type="primary"
        @click="list.load(1)"
      >
        查询
      </el-button>
      <span class="list-toolbar__note">草稿可编辑与发布；发布后评分单指派不可撤销。</span>
    </div>

    <el-alert
      v-if="list.error.value"
      class="page-alert"
      :title="list.error.value"
      type="error"
      :closable="false"
      show-icon
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        label="任务名称"
        min-width="240"
      >
        <template #default="{ row }">
          <span class="task-name">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="周期"
        width="120"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.year }} Q{{ row.quarter }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="截止日期"
        width="130"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.deadline }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="taskStatusClass(row.status)"
          >
            {{ EVAL_TASK_STATUS_LABEL[row.status as keyof typeof EVAL_TASK_STATUS_LABEL] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="220"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="router.push({ name: 'task-detail', params: { id: row.id } })"
          >
            详情
          </el-button>
          <el-button
            v-if="canManage && row.status === 'DRAFT'"
            link
            @click="openEdit(row as TaskSummary)"
          >
            编辑
          </el-button>
          <el-button
            v-if="canManage && row.status === 'DRAFT'"
            link
            :loading="publish.submitting.value && publishingId === row.id"
            :disabled="publish.submitting.value"
            @click="onPublish(row as TaskSummary)"
          >
            发布
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      class="list-pager"
      @change="list.load"
      @size-change="list.load(1)"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑草稿' : '新建评估任务'"
      width="620px"
    >
      <el-form label-width="110px">
        <el-form-item label="任务名称">
          <el-input
            v-model="form.name"
            maxlength="100"
            placeholder="如：2026 年 Q1 供应商绩效评估"
          />
        </el-form-item>
        <el-form-item label="评估周期">
          <el-input-number
            v-model="form.year"
            :min="2000"
            :max="2999"
            controls-position="right"
          />
          <span class="hint">年</span>
          <el-select
            v-model="form.quarter"
            class="quarter"
          >
            <el-option
              v-for="q in [1, 2, 3, 4]"
              :key="q"
              :label="`Q${q}`"
              :value="q"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="评估模板">
          <el-select
            v-model="form.templateId"
            placeholder="选择模板"
            @change="onTemplateChange"
          >
            <el-option
              v-for="item in templates"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模板版本">
          <el-select
            v-model="form.templateVersionId"
            placeholder="选择已发布版本"
          >
            <el-option
              v-for="item in versions"
              :key="item.versionId"
              :label="`v${item.versionNo}`"
              :value="item.versionId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker
            v-model="form.deadline"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择截止日期"
          />
        </el-form-item>
        <el-form-item label="参评供应商">
          <el-select
            v-model="form.supplierIds"
            multiple
            filterable
            remote
            reserve-keyword
            :remote-method="searchSuppliers"
            :loading="supplierLoading"
            placeholder="输入编码或名称搜索（500 家规模下不再一次取全）"
          >
            <el-option
              v-for="item in suppliers"
              :key="item.id"
              :label="`${item.code} ${item.name}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="审批链">
          <el-select
            v-model="form.approverIds"
            multiple
            filterable
            placeholder="逐级指定审批人（至少一级）"
          >
            <el-option
              v-for="item in users"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div class="hint">
            顺序即审批顺序（第 1 个为第 1 级）。默认审批链设置不在本期范围，故这里必须显式选择。
          </div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="optionsError"
        :title="optionsError"
        type="error"
        :closable="false"
        show-icon
      />

      <el-alert
        v-if="submit.errorMessage.value"
        :title="submit.errorMessage.value"
        type="error"
        :closable="false"
        show-icon
      />

      <template #footer>
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submit.submitting.value"
          @click="onSaveDraft"
        >
          保存草稿
        </el-button>
      </template>
    </el-dialog>
  </SectionCard>
</template>

<style scoped>
.task-name {
  color: var(--ink);
  font-weight: 500;
}

.hint {
  margin-left: 8px;
  color: var(--muted);
  font-size: 10.5px;
}

.quarter {
  width: 100px;
  margin-left: 8px;
}

</style>
