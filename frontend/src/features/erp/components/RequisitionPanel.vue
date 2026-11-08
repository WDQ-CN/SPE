<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveRequisition,
  convertRequisition,
  createRequisition,
  getRequisition,
  listRequisitions,
  listSuppliers,
  rejectRequisition,
  submitRequisition,
  updateRequisition,
  type CompanyOption,
  type ItemOption,
  type PlantOption,
  type RequisitionDetail,
  type RequisitionRow,
} from '@/features/erp/api'
import {
  REQUISITION_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const props = defineProps<{
  companies: CompanyOption[]
  plants: PlantOption[]
  items: ItemOption[]
}>()

const filters = reactive<{ companyId?: number; status: string; keyword: string }>({
  status: '',
  keyword: '',
})

const suppliers = ref<SupplierSummary[]>([])

const { rows, total, page, size, loading, error, load } = usePagedList<RequisitionRow>(
  (targetPage, pageSize) =>
    listRequisitions({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    }),
)

const statusOptions = Object.entries(REQUISITION_STATUS_LABEL)

onMounted(async () => {
  filters.companyId = props.companies[0]?.id
  try {
    suppliers.value = await listSuppliers()
  } catch {
    suppliers.value = []
  }
  await load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.status = ''
  filters.keyword = ''
  void load(1)
}

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RequisitionDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getRequisition(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// ---- 创建 / 编辑 ----

interface RequisitionLineForm {
  itemId: number | undefined
  qty: number
  uom: string
  estimatedPrice: number
}

const createVisible = ref(false)
const editing = ref<RequisitionDetail | null>(null)
const form = reactive<{
  companyId: number | undefined
  needDate: string
  currency: string
  lines: RequisitionLineForm[]
}>({ companyId: undefined, needDate: '', currency: 'CNY', lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const formTotal = computed(() =>
  form.lines.reduce((sum, line) => sum + line.qty * line.estimatedPrice, 0),
)

function newLine(): RequisitionLineForm {
  return { itemId: undefined, qty: 1, uom: '', estimatedPrice: 0 }
}

function onCompanyChange(): void {
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

function onItemChange(line: RequisitionLineForm): void {
  const item = props.items.find((option) => option.id === line.itemId)
  if (item) {
    line.uom = item.baseUom
  }
}

function openCreate(): void {
  editing.value = null
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.needDate = new Date().toISOString().slice(0, 10)
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

async function openEdit(row: RequisitionRow): Promise<void> {
  const data = await getRequisition(row.id).catch(() => null)
  if (!data) {
    return
  }
  editing.value = data
  form.companyId = data.companyId
  form.needDate = data.needDate
  form.currency = data.currency
  form.lines = data.lines.map((line) => ({
    itemId: line.itemId,
    qty: Number(line.qty),
    uom: line.uom,
    estimatedPrice: Number(line.estimatedPrice),
  }))
  clearError()
  createVisible.value = true
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('请购单至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function validate(): string {
  if (!form.companyId) {
    return '请选择公司'
  }
  if (!form.needDate) {
    return '请选择需求日期'
  }
  for (const [index, line] of form.lines.entries()) {
    if (!line.itemId) {
      return `第 ${index + 1} 行请选择物料`
    }
    if (line.qty <= 0) {
      return `第 ${index + 1} 行数量必须大于 0`
    }
    if (line.estimatedPrice < 0) {
      return `第 ${index + 1} 行预估单价不能为负`
    }
  }
  return ''
}

async function submitForm(): Promise<void> {
  const validation = validate()
  if (validation) {
    ElMessage.warning(validation)
    return
  }
  const current = editing.value
  const ok = await runSave(async () => {
    const payload = {
      needDate: form.needDate,
      currency: form.currency.trim().toUpperCase(),
      lines: form.lines.map((line) => ({
        itemId: line.itemId as number,
        qty: line.qty,
        uom: line.uom.trim() || 'EA',
        estimatedPrice: line.estimatedPrice,
      })),
    }
    if (current) {
      await updateRequisition(current.id, { ...payload, version: current.version })
    } else {
      await createRequisition({ companyId: form.companyId as number, ...payload })
    }
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success(current ? '请购单已保存' : '请购单已创建')
    await load(current ? page.value : 1)
  }
}

// ---- 状态动作 ----

async function act(
  row: RequisitionRow,
  action: 'submit' | 'approve',
): Promise<void> {
  const verb = action === 'submit' ? '提交' : '审批通过'
  const confirmed = await ElMessageBox.confirm(
    `确定要${verb}请购单「${row.docNo}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'submit') {
      await submitRequisition(row.id, row.version)
    } else {
      await approveRequisition(row.id, row.version)
    }
    ElMessage.success(`已${verb}`)
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getRequisition(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

async function reject(row: RequisitionRow): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回意见', '驳回请购单', {
      inputType: 'textarea',
      inputValidator: (input) => (input?.trim() ? true : '驳回意见不能为空'),
    })
    await rejectRequisition(row.id, value.trim(), row.version)
    ElMessage.success('已驳回')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getRequisition(row.id)
    }
  } catch {
    // 取消或接口错误
  }
}

// ---- 转 PO ----

const convertVisible = ref(false)
const convertSaving = ref(false)
const convertError = ref('')
const convertForm = reactive<{
  requisitionId: number
  version: number
  supplierId: number | undefined
  plantId: number | undefined
  paymentTerms: string
}>({ requisitionId: 0, version: 0, supplierId: undefined, plantId: undefined, paymentTerms: '' })

function openConvert(row: RequisitionRow): void {
  convertForm.requisitionId = row.id
  convertForm.version = row.version
  convertForm.supplierId = undefined
  convertForm.plantId = props.plants.find(
    (plant) => plant.companyId === row.companyId,
  )?.id
  convertForm.paymentTerms = ''
  convertError.value = ''
  convertVisible.value = true
}

async function submitConvert(): Promise<void> {
  if (!convertForm.supplierId || !convertForm.plantId) {
    ElMessage.warning('请选择供应商与工厂')
    return
  }
  convertSaving.value = true
  convertError.value = ''
  try {
    const po = await convertRequisition(
      convertForm.requisitionId,
      convertForm.supplierId,
      convertForm.plantId,
      convertForm.paymentTerms.trim() || null,
      convertForm.version,
    )
    convertVisible.value = false
    ElMessage.success(`采购订单 ${po.docNo} 已生成`)
    await load()
  } catch (caught) {
    convertError.value = caught instanceof Error ? caught.message : '转 PO 失败'
  } finally {
    convertSaving.value = false
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
        aria-label="公司"
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
        aria-label="状态"
        @change="search"
      >
        <el-option
          v-for="[value, label] in statusOptions"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-input
        v-model="filters.keyword"
        class="list-toolbar__search"
        placeholder="请购单号或申请人"
        clearable
        aria-label="关键字"
        @keyup.enter="search"
      />
      <el-button
        type="primary"
        @click="search"
      >
        查询
      </el-button>
      <el-button @click="resetSearch">
        重置
      </el-button>
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        新建请购
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
        label="请购单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as RequisitionRow).docNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="requesterName"
        label="申请人"
        min-width="120"
      />
      <el-table-column
        label="需求日"
        width="120"
      >
        <template #default="{ row }">
          {{ (row as RequisitionRow).needDate }}
        </template>
      </el-table-column>
      <el-table-column
        label="金额"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as RequisitionRow).amount, (row as RequisitionRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as RequisitionRow).status)"
          >
            {{ labelOf(REQUISITION_STATUS_LABEL, (row as RequisitionRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as RequisitionRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="260"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as RequisitionRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as RequisitionRow).status === 'DRAFT'"
            link
            @click="openEdit(row as RequisitionRow)"
          >
            编辑
          </el-button>
          <el-button
            v-if="(row as RequisitionRow).status === 'DRAFT'"
            link
            @click="act(row as RequisitionRow, 'submit')"
          >
            提交
          </el-button>
          <el-button
            v-if="(row as RequisitionRow).status === 'SUBMITTED'"
            link
            @click="act(row as RequisitionRow, 'approve')"
          >
            通过
          </el-button>
          <el-button
            v-if="(row as RequisitionRow).status === 'SUBMITTED'"
            link
            @click="reject(row as RequisitionRow)"
          >
            驳回
          </el-button>
          <el-button
            v-if="(row as RequisitionRow).status === 'APPROVED'"
            link
            @click="openConvert(row as RequisitionRow)"
          >
            转 PO
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
    title="请购单详情"
    size="680px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="请购单">
            {{ detail.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(REQUISITION_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="申请人">
            {{ detail.requesterName }}
          </el-descriptions-item>
          <el-descriptions-item label="需求日">
            {{ detail.needDate }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ formatAmount(detail.amount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(detail.createdAt) }}
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
            label="物料"
            min-width="180"
          >
            <template #default="{ row }">
              {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="数量"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }} {{ row.uom }}
            </template>
          </el-table-column>
          <el-table-column
            label="预估单价"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ formatAmount(row.estimatedPrice) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到请购单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    :title="editing ? '编辑请购单' : '新建请购单'"
    width="860px"
  >
    <el-alert
      v-if="saveError"
      class="page-alert"
      type="error"
      :title="saveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="92px">
      <div class="form-grid">
        <el-form-item label="公司">
          <el-select
            v-model="form.companyId"
            placeholder="选择公司"
            :disabled="Boolean(editing)"
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
        <el-form-item label="需求日">
          <el-date-picker
            v-model="form.needDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">请购行</span>
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
          label="物料"
          min-width="200"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.itemId"
              placeholder="选择物料"
              filterable
              @change="onItemChange(row as RequisitionLineForm)"
            >
              <el-option
                v-for="item in props.items"
                :key="item.id"
                :label="`${item.code} · ${item.name}`"
                :value="item.id"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column
          label="数量"
          width="120"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0.000001"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="单位"
          width="80"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.uom"
              maxlength="16"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="预估单价"
          width="130"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.estimatedPrice"
              :min="0"
              :precision="4"
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
        预估合计：<strong>{{ formatAmount(formTotal, form.currency) }}</strong>
      </p>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submitForm"
      >
        保存
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="convertVisible"
    title="请购转采购订单"
    width="520px"
  >
    <el-alert
      v-if="convertError"
      class="page-alert"
      type="error"
      :title="convertError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="供应商">
        <el-select
          v-model="convertForm.supplierId"
          placeholder="选择供应商"
          filterable
        >
          <el-option
            v-for="supplier in suppliers"
            :key="supplier.id"
            :label="`${supplier.code} · ${supplier.name}`"
            :value="supplier.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="工厂">
        <el-select
          v-model="convertForm.plantId"
          placeholder="选择工厂"
        >
          <el-option
            v-for="plant in props.plants.filter(
              (item) =>
                item.companyId ===
                rows.find((row) => row.id === convertForm.requisitionId)?.companyId,
            )"
            :key="plant.id"
            :label="`${plant.code} · ${plant.name}`"
            :value="plant.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="付款条件">
        <el-input
          v-model="convertForm.paymentTerms"
          maxlength="32"
          placeholder="可留空"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="convertVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="convertSaving"
        @click="submitConvert"
      >
        生成 PO
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
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
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
