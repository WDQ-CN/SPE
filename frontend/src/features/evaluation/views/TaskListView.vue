<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在它的白名单里，需显式引入
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import {
  createTask,
  listTasks,
  publishTask,
  type TaskDraftPayload,
} from '@/features/evaluation/api'
import { listSuppliers } from '@/features/supplier/api'
import { listPublishedVersions, listTemplates } from '@/features/template/api'
import { listUsers } from '@/features/user/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import { EVAL_TASK_STATUS_LABEL } from '@/shared/labels'
import type { PublishedVersionBrief, SupplierSummary, TaskSummary, UserSummary } from '@/shared/types/dto'
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
const submit = useSubmit()
const templates = ref<{ id: number; name: string }[]>([])
const versions = ref<PublishedVersionBrief[]>([])
const suppliers = ref<SupplierSummary[]>([])
const users = ref<UserSummary[]>([])

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

async function openDialog(): Promise<void> {
  dialogVisible.value = true
  submit.clearError()
  form.name = ''
  form.templateId = null
  form.templateVersionId = null
  form.deadline = ''
  form.supplierIds = []
  form.approverIds = []
  versions.value = []
  const [templatePage, userPage] = await Promise.all([
    listTemplates({ page: 1, size: 100 }),
    listUsers({ page: 1, size: 100 }),
  ])
  templates.value = templatePage.list.map((item) => ({ id: item.id, name: item.name }))
  users.value = userPage.list
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
  } finally {
    supplierLoading.value = false
  }
}

async function onTemplateChange(templateId: number): Promise<void> {
  form.templateVersionId = null
  versions.value = await listPublishedVersions(templateId)
}

async function onCreate(): Promise<void> {
  const payload: TaskDraftPayload = {
    name: form.name,
    year: form.year,
    quarter: form.quarter,
    templateVersionId: form.templateVersionId ?? 0,
    deadline: form.deadline,
    supplierIds: form.supplierIds,
    approverIds: form.approverIds,
  }
  const ok = await submit.run(async () => {
    await createTask(payload)
  })
  if (ok) {
    ElMessage.success('草稿已创建，可在列表里发布')
    dialogVisible.value = false
    await list.reload()
  }
}

async function onPublish(row: TaskSummary): Promise<void> {
  await ElMessageBox.confirm(
    '发布后将为每个供应商的每个维度生成评分单并自动指派，任务进入「进行中」。发布日期与指派不可撤销。',
    '确认发布',
    { type: 'warning' },
  )
  const result = await publishTask(row.id, row.version)
  ElMessage.success(
    `已发布：${result.supplierCount} 个供应商共 ${result.sheetCount} 张评分单，` +
      `自动指派 ${result.assignedCount} 张、待指派 ${result.unassignedCount} 张`,
  )
  await list.reload()
}

onMounted(() => void list.load())
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>评估任务</span>
        <el-button
          type="primary"
          @click="openDialog"
        >
          新建任务
        </el-button>
      </div>
    </template>

    <div class="filters">
      <el-input
        v-model="keyword"
        placeholder="按名称搜索"
        clearable
        class="filters__keyword"
        @keyup.enter="list.load(1)"
        @clear="list.load(1)"
      />
      <el-select
        v-model="status"
        placeholder="全部状态"
        clearable
        class="filters__status"
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
    </div>

    <el-alert
      v-if="list.error.value"
      :title="list.error.value"
      type="error"
      :closable="false"
      show-icon
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
    >
      <el-table-column
        prop="name"
        label="任务名称"
        min-width="200"
      />
      <el-table-column
        label="周期"
        width="120"
      >
        <template #default="{ row }">
          {{ row.year }} 年 Q{{ row.quarter }}
        </template>
      </el-table-column>
      <el-table-column
        prop="deadline"
        label="截止日期"
        width="130"
      />
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'DRAFT' ? 'info' : 'success'">
            {{ EVAL_TASK_STATUS_LABEL[row.status as keyof typeof EVAL_TASK_STATUS_LABEL] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="180"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push({ name: 'task-detail', params: { id: row.id } })"
          >
            详情
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT'"
            link
            type="primary"
            @click="onPublish(row as TaskSummary)"
          >
            发布
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="list.page.value"
      v-model:page-size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="list.load"
      @size-change="list.load(1)"
    />

    <el-dialog
      v-model="dialogVisible"
      title="新建评估任务"
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
          @click="onCreate"
        >
          保存草稿
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filters {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.filters__keyword {
  width: 240px;
}

.filters__status {
  width: 160px;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.quarter {
  width: 100px;
  margin-left: 8px;
}
</style>
