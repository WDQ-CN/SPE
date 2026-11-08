<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  activateProject,
  addProjectWbs,
  approveProject,
  approveProjectBudget,
  closeProject,
  createProject,
  createProjectBudget,
  getProject,
  listProjects,
  type CompanyOption,
  type CustomerOption,
  type ProjectBudget,
  type ProjectDetail,
  type ProjectRow,
} from '@/features/erp/api'
import {
  PROJECT_BUDGET_STATUS_LABEL,
  PROJECT_STATUS_LABEL,
  WBS_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[]; customers: CustomerOption[] }>()

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<ProjectRow>(
  (targetPage, pageSize) =>
    listProjects({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(PROJECT_STATUS_LABEL)

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getProject(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail(id: number): Promise<void> {
  detail.value = await getProject(id)
}

async function act(row: ProjectRow, action: 'approve' | 'activate' | 'close'): Promise<void> {
  const verbs = { approve: '审批', activate: '激活', close: '关闭' }
  const confirmed = await ElMessageBox.confirm(
    `确定要${verbs[action]}项目「${row.name}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'approve') {
      await approveProject(row.id)
    } else if (action === 'activate') {
      await activateProject(row.id)
    } else {
      await closeProject(row.id)
    }
    ElMessage.success(`已${verbs[action]}`)
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  customerId: number | undefined
  name: string
  currency: string
  defaultLaborRate: number | null
  startDate: string
  endDate: string
}>({
  companyId: undefined,
  customerId: undefined,
  name: '',
  currency: 'CNY',
  defaultLaborRate: null,
  startDate: '',
  endDate: '',
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.customerId = undefined
  form.name = ''
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.defaultLaborRate = null
  form.startDate = ''
  form.endDate = ''
  clearError()
  createVisible.value = true
}

function onCompanyChange(): void {
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.name.trim()) {
    ElMessage.warning('请选择公司并填写项目名称')
    return
  }
  if (form.startDate && form.endDate && form.endDate < form.startDate) {
    ElMessage.warning('结束日期不能早于开始日期')
    return
  }
  const ok = await runSave(async () => {
    await createProject({
      companyId: form.companyId as number,
      customerId: form.customerId ?? null,
      name: form.name.trim(),
      currency: form.currency.trim().toUpperCase(),
      defaultLaborRate: form.defaultLaborRate,
      startDate: form.startDate || null,
      endDate: form.endDate || null,
      managerId: null,
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('项目已创建')
    await load(1)
  }
}

// ---- WBS ----

const wbsVisible = ref(false)
const wbsSaving = ref(false)
const wbsError = ref('')
const wbsForm = reactive<{
  projectId: number
  parentId: number | undefined
  code: string
  name: string
  budget: number | null
}>({ projectId: 0, parentId: undefined, code: '', name: '', budget: null })

function openWbs(): void {
  if (!detail.value) {
    return
  }
  wbsForm.projectId = detail.value.id
  wbsForm.parentId = undefined
  wbsForm.code = ''
  wbsForm.name = ''
  wbsForm.budget = null
  wbsError.value = ''
  wbsVisible.value = true
}

async function submitWbs(): Promise<void> {
  if (!wbsForm.code.trim() || !wbsForm.name.trim()) {
    ElMessage.warning('请填写 WBS 编码与名称')
    return
  }
  wbsSaving.value = true
  wbsError.value = ''
  try {
    await addProjectWbs(wbsForm.projectId, {
      parentId: wbsForm.parentId ?? null,
      code: wbsForm.code.trim(),
      name: wbsForm.name.trim(),
      budget: wbsForm.budget,
    })
    wbsVisible.value = false
    ElMessage.success('WBS 已新增')
    await refreshDetail(wbsForm.projectId)
  } catch (caught) {
    wbsError.value = caught instanceof Error ? caught.message : 'WBS 新增失败'
  } finally {
    wbsSaving.value = false
  }
}

// ---- 预算 ----

const budgetVisible = ref(false)
const budgetSaving = ref(false)
const budgetError = ref('')
const budgetForm = reactive<{
  projectId: number
  versionNo: string
  laborAmount: number | null
  purchaseAmount: number | null
  expenseAmount: number | null
  otherAmount: number | null
}>({
  projectId: 0,
  versionNo: 'V1',
  laborAmount: null,
  purchaseAmount: null,
  expenseAmount: null,
  otherAmount: null,
})

function openBudget(): void {
  if (!detail.value) {
    return
  }
  budgetForm.projectId = detail.value.id
  budgetForm.versionNo = `V${detail.value.budgets.length + 1}`
  budgetForm.laborAmount = null
  budgetForm.purchaseAmount = null
  budgetForm.expenseAmount = null
  budgetForm.otherAmount = null
  budgetError.value = ''
  budgetVisible.value = true
}

async function submitBudget(): Promise<void> {
  budgetSaving.value = true
  budgetError.value = ''
  try {
    await createProjectBudget(budgetForm.projectId, {
      versionNo: budgetForm.versionNo.trim() || null,
      laborAmount: budgetForm.laborAmount,
      purchaseAmount: budgetForm.purchaseAmount,
      expenseAmount: budgetForm.expenseAmount,
      otherAmount: budgetForm.otherAmount,
    })
    budgetVisible.value = false
    ElMessage.success('预算版本已创建')
    await refreshDetail(budgetForm.projectId)
  } catch (caught) {
    budgetError.value = caught instanceof Error ? caught.message : '预算创建失败'
  } finally {
    budgetSaving.value = false
  }
}

async function approveBudget(budget: ProjectBudget): Promise<void> {
  if (!detail.value) {
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确定批准预算「${budget.versionNo}」吗？批准后回写项目预算。`,
    '审批',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await approveProjectBudget(budget.id)
    ElMessage.success('预算已批准')
    await refreshDetail(detail.value.id)
  } catch {
    // http 拦截器已提示
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
        新建项目
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
        label="项目号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as ProjectRow).projectNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="名称"
        min-width="200"
      />
      <el-table-column
        label="预算"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as ProjectRow).budgetAmount, (row as ProjectRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as ProjectRow).status)"
          >
            {{ labelOf(PROJECT_STATUS_LABEL, (row as ProjectRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as ProjectRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="280"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as ProjectRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as ProjectRow).status === 'DRAFT'"
            link
            @click="act(row as ProjectRow, 'approve')"
          >
            审批
          </el-button>
          <el-button
            v-if="(row as ProjectRow).status === 'APPROVED'"
            link
            @click="act(row as ProjectRow, 'activate')"
          >
            激活
          </el-button>
          <el-button
            v-if="(row as ProjectRow).status === 'ACTIVE'"
            link
            @click="act(row as ProjectRow, 'close')"
          >
            关闭
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
    title="项目详情"
    size="820px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="项目号">
            {{ detail.projectNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(PROJECT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="名称">
            {{ detail.name }}
          </el-descriptions-item>
          <el-descriptions-item label="预算">
            {{ formatAmount(detail.budgetAmount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="实际成本">
            {{ formatAmount(detail.actualCost, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="人工费率">
            {{ formatAmount(detail.defaultLaborRate) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-head">
          <h3 class="detail-title">
            WBS
          </h3>
          <el-button
            v-if="detail.status !== 'CLOSED'"
            link
            @click="openWbs"
          >
            新增 WBS
          </el-button>
        </div>
        <el-table
          :data="detail.wbs"
          class="table-list"
          empty-text="尚无 WBS"
        >
          <el-table-column
            prop="code"
            label="编码"
            width="120"
          />
          <el-table-column
            prop="name"
            label="名称"
            min-width="180"
          />
          <el-table-column
            label="预算"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.budget) }}
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
          >
            <template #default="{ row }">
              {{ labelOf(WBS_STATUS_LABEL, row.status) }}
            </template>
          </el-table-column>
        </el-table>

        <div class="detail-head">
          <h3 class="detail-title">
            预算版本
          </h3>
          <el-button
            v-if="detail.status !== 'CLOSED'"
            link
            @click="openBudget"
          >
            新增预算
          </el-button>
        </div>
        <el-table
          :data="detail.budgets"
          class="table-list"
          empty-text="尚无预算版本"
        >
          <el-table-column
            prop="versionNo"
            label="版本"
            width="90"
          />
          <el-table-column
            label="总额"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.totalAmount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
          >
            <template #default="{ row }">
              {{ labelOf(PROJECT_BUDGET_STATUS_LABEL, row.status) }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="100"
            align="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'DRAFT'"
                link
                @click="approveBudget(row as ProjectBudget)"
              >
                批准
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到项目"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建项目"
    width="620px"
  >
    <el-alert
      v-if="saveError"
      class="page-alert"
      type="error"
      :title="saveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="96px">
      <div class="form-grid">
        <el-form-item label="公司">
          <el-select
            v-model="form.companyId"
            placeholder="选择公司"
            @change="onCompanyChange"
          >
            <el-option
              v-for="company in props.companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select
            v-model="form.customerId"
            placeholder="可空"
            clearable
            filterable
          >
            <el-option
              v-for="customer in props.customers"
              :key="customer.id"
              :label="`${customer.code} · ${customer.name}`"
              :value="customer.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input
            v-model="form.name"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="人工费率">
          <el-input-number
            v-model="form.defaultLaborRate"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker
            v-model="form.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker
            v-model="form.endDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        创建项目
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="wbsVisible"
    title="新增 WBS"
    width="480px"
  >
    <el-alert
      v-if="wbsError"
      class="page-alert"
      type="error"
      :title="wbsError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="上级 WBS">
        <el-select
          v-model="wbsForm.parentId"
          placeholder="顶层（可空）"
          clearable
        >
          <el-option
            v-for="wbs in detail?.wbs ?? []"
            :key="wbs.id"
            :label="`${wbs.code} · ${wbs.name}`"
            :value="wbs.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="编码">
        <el-input
          v-model="wbsForm.code"
          maxlength="32"
        />
      </el-form-item>
      <el-form-item label="名称">
        <el-input
          v-model="wbsForm.name"
          maxlength="200"
        />
      </el-form-item>
      <el-form-item label="预算">
        <el-input-number
          v-model="wbsForm.budget"
          :min="0"
          :precision="4"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="wbsVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="wbsSaving"
        @click="submitWbs"
      >
        保存
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="budgetVisible"
    title="新增预算版本"
    width="560px"
  >
    <el-alert
      v-if="budgetError"
      class="page-alert"
      type="error"
      :title="budgetError"
      :closable="false"
      show-icon
    />
    <el-form label-width="100px">
      <div class="form-grid">
        <el-form-item label="版本号">
          <el-input
            v-model="budgetForm.versionNo"
            maxlength="16"
          />
        </el-form-item>
        <el-form-item label="人工预算">
          <el-input-number
            v-model="budgetForm.laborAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="采购预算">
          <el-input-number
            v-model="budgetForm.purchaseAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="费用预算">
          <el-input-number
            v-model="budgetForm.expenseAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="其他预算">
          <el-input-number
            v-model="budgetForm.otherAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="budgetVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="budgetSaving"
        @click="submitBudget"
      >
        保存预算
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar-create {
  margin-left: auto;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}

.line-number {
  width: 100%;
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 22px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--rule);
}

.detail-title {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
