<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveTimesheet,
  createTimesheet,
  getTimesheet,
  listProjects,
  listTimesheets,
  submitTimesheet,
  type CompanyOption,
  type ProjectRow,
  type TimesheetDetail,
  type TimesheetRow,
} from '@/features/erp/api'
import {
  TIMESHEET_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[] }>()

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })
const activeProjects = ref<ProjectRow[]>([])
const projectNames = computed(
  () => new Map(activeProjects.value.map((project) => [project.id, project.name])),
)

function projectName(id: number): string {
  return projectNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<TimesheetRow>(
  (targetPage, pageSize) =>
    listTimesheets({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(TIMESHEET_STATUS_LABEL)

onMounted(async () => {
  filters.companyId = props.companies[0]?.id
  try {
    const result = await listProjects({ page: 1, size: 100, companyId: filters.companyId })
    activeProjects.value = result.list.filter((project) => project.status === 'ACTIVE')
  } catch {
    activeProjects.value = []
  }
  await load(1)
})

function search(): void {
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TimesheetDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getTimesheet(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function act(row: TimesheetRow, action: 'submit' | 'approve'): Promise<void> {
  const verb = action === 'submit' ? '提交' : '审批通过'
  const confirmed = await ElMessageBox.confirm(
    `确定要${verb}工时单「${row.timesheetNo}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'submit') {
      await submitTimesheet(row.id)
    } else {
      await approveTimesheet(row.id)
    }
    ElMessage.success(`已${verb}`)
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getTimesheet(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

interface TimesheetLineForm {
  projectId: number | undefined
  workDate: string
  hours: number
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  lines: TimesheetLineForm[]
}>({ companyId: undefined, lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const totalHours = computed(() => form.lines.reduce((sum, line) => sum + line.hours, 0))

function newLine(): TimesheetLineForm {
  return {
    projectId: activeProjects.value[0]?.id,
    workDate: new Date().toISOString().slice(0, 10),
    hours: 8,
  }
}

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('工时单至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

async function submitForm(): Promise<void> {
  if (!form.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  if (form.lines.some((line) => !line.projectId || !line.workDate || line.hours <= 0)) {
    ElMessage.warning('请完整填写项目、日期与工时')
    return
  }
  const ok = await runSave(async () => {
    await createTimesheet(
      form.companyId as number,
      form.lines.map((line) => ({
        projectId: line.projectId as number,
        wbsId: null,
        workDate: line.workDate,
        hours: line.hours,
      })),
    )
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('工时单已创建')
    await load(1)
  }
}
</script>

<template>
  <div>
    <form
      class="list-toolbar"
      @submit.prevent="search"
    >
      <el-select
        v-model="filters.companyId"
        class="list-toolbar__select"
        placeholder="公司"
        clearable
        @change="search"
      >
        <el-option
          v-for="company in props.companies"
          :key="company.id"
          :label="`${company.code} · ${company.name}`"
          :value="company.id"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        class="list-toolbar__select"
        placeholder="状态"
        clearable
        @change="search"
      >
        <el-option
          v-for="[value, label] in statusOptions"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button
        type="primary"
        @click="search"
      >
        查询
      </el-button>
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        新建工时单
      </el-button>
    </form>

    <el-alert
      v-if="error"
      class="page-alert"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />
    <el-table
      v-loading="loading"
      :data="rows"
      class="table-list"
    >
      <el-table-column
        label="工时单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as TimesheetRow).timesheetNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as TimesheetRow).status)"
          >
            {{ labelOf(TIMESHEET_STATUS_LABEL, (row as TimesheetRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="工时"
        align="right"
        width="100"
      >
        <template #default="{ row }">
          {{ formatQty((row as TimesheetRow).totalHours) }}
        </template>
      </el-table-column>
      <el-table-column
        label="金额"
        align="right"
        width="130"
      >
        <template #default="{ row }">
          {{ formatAmount((row as TimesheetRow).totalAmount) }}
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as TimesheetRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="170"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as TimesheetRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as TimesheetRow).status === 'DRAFT'"
            link
            @click="act(row as TimesheetRow, 'submit')"
          >
            提交
          </el-button>
          <el-button
            v-if="(row as TimesheetRow).status === 'SUBMITTED'"
            link
            @click="act(row as TimesheetRow, 'approve')"
          >
            通过
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <ListPagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      class="list-pager"
      @change="(target: number) => void load(target)"
      @size-change="() => void load(1)"
    />
  </div>

  <el-drawer
    v-model="detailVisible"
    title="工时单详情"
    size="640px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="工时单">
            {{ detail.timesheetNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(TIMESHEET_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="总工时">
            {{ formatQty(detail.totalHours) }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ formatAmount(detail.totalAmount) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table
          :data="detail.lines"
          class="table-list detail-table"
        >
          <el-table-column
            prop="lineNo"
            label="#"
            width="50"
          />
          <el-table-column
            label="项目"
            min-width="180"
          >
            <template #default="{ row }">
              {{ projectName(row.projectId) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="workDate"
            label="日期"
            width="120"
          />
          <el-table-column
            label="工时"
            align="right"
            width="100"
          >
            <template #default="{ row }">
              {{ formatQty(row.hours) }}
            </template>
          </el-table-column>
          <el-table-column
            label="金额"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.amount) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到工时单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建工时单"
    width="680px"
  >
    <el-alert
      v-if="saveError"
      class="page-alert"
      type="error"
      :title="saveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="公司">
        <el-select
          v-model="form.companyId"
          placeholder="选择公司"
        >
          <el-option
            v-for="company in props.companies"
            :key="company.id"
            :label="`${company.code} · ${company.name}`"
            :value="company.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="line-head">
      <span class="line-head__title">工时行</span>
      <el-button
        link
        @click="addLine"
      >
        添加一行
      </el-button>
    </div>
    <el-table
      :data="form.lines"
      class="table-list"
    >
      <el-table-column
        label="项目"
        min-width="200"
      >
        <template #default="{ row }">
          <el-select
            v-model="row.projectId"
            placeholder="选择进行中项目"
            filterable
          >
            <el-option
              v-for="project in activeProjects"
              :key="project.id"
              :label="`${project.projectNo} · ${project.name}`"
              :value="project.id"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column
        label="日期"
        width="140"
      >
        <template #default="{ row }">
          <el-date-picker
            v-model="row.workDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="工时"
        width="120"
      >
        <template #default="{ row }">
          <el-input-number
            v-model="row.hours"
            :min="0.01"
            :precision="2"
            :controls="false"
            class="line-number"
          />
        </template>
      </el-table-column>
      <el-table-column
        label=""
        width="60"
        align="right"
      >
        <template #default="{ $index }">
          <el-button
            link
            @click="removeLine($index)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="line-total">
      合计工时：<strong>{{ formatQty(totalHours) }}</strong>
    </p>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submitForm"
      >
        保存工时单
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar-create {
  margin-left: auto;
}

.line-number {
  width: 100%;
}

.line-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--rule);
}

.line-head__title {
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.line-total {
  margin: 16px 0 0;
  text-align: right;
  font-size: 13px;
}

.detail-table {
  margin-top: 20px;
}
</style>
