<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  activateContract,
  createContract,
  getContract,
  listContractConsumption,
  listContracts,
  listSuppliers,
  releaseContractOrder,
  terminateContract,
  type CompanyOption,
  type ContractConsumption,
  type ContractDetail,
  type ContractRow,
  type ItemOption,
  type PlantOption,
} from '@/features/erp/api'
import {
  CONTRACT_STATUS_LABEL,
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

const filters = reactive<{ companyId?: number; supplierId?: number; status: string }>({ status: '' })

const suppliers = ref<SupplierSummary[]>([])
const supplierNames = computed(
  () => new Map(suppliers.value.map((supplier) => [supplier.id, supplier.name])),
)

function supplierName(id: number | null): string {
  return id ? supplierNames.value.get(id) ?? `#${id}` : '—'
}

const { rows, total, page, size, loading, error, load } = usePagedList<ContractRow>(
  (targetPage, pageSize) =>
    listContracts({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      supplierId: filters.supplierId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(CONTRACT_STATUS_LABEL)

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
  filters.supplierId = undefined
  filters.status = ''
  void load(1)
}

// ---- 详情 / 状态动作 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ContractDetail | null>(null)
const consumption = ref<ContractConsumption[]>([])

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getContract(id)
    consumption.value = await listContractConsumption(id).catch(() => [])
  } catch {
    detail.value = null
    consumption.value = []
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail(id: number): Promise<void> {
  detail.value = await getContract(id)
  consumption.value = await listContractConsumption(id).catch(() => [])
}

async function activate(row: ContractRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定生效合同「${row.contractNo}」吗？生效后会写入供应商价格库。`,
    '生效',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await activateContract(row.id)
    ElMessage.success('合同已生效')
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

async function terminate(row: ContractRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定终止合同「${row.contractNo}」吗？关联供应商价格将失效。`,
    '终止',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await terminateContract(row.id)
    ElMessage.success('合同已终止')
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

interface ContractLineForm {
  itemId: number | undefined
  uom: string
  price: number
  minQty: number | null
  maxQty: number | null
  taxIncluded: boolean
  validFrom: string
  validTo: string
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  supplierId: number | undefined
  title: string
  currency: string
  validFrom: string
  validTo: string
  paymentTerms: string
  totalLimitAmount: number | null
  totalLimitQty: number | null
  lines: ContractLineForm[]
}>({
  companyId: undefined,
  supplierId: undefined,
  title: '',
  currency: 'CNY',
  validFrom: '',
  validTo: '',
  paymentTerms: '',
  totalLimitAmount: null,
  totalLimitQty: null,
  lines: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function newLine(): ContractLineForm {
  return {
    itemId: undefined,
    uom: '',
    price: 0,
    minQty: null,
    maxQty: null,
    taxIncluded: true,
    validFrom: '',
    validTo: '',
  }
}

function onCompanyChange(): void {
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

function onItemChange(line: ContractLineForm): void {
  const item = props.items.find((option) => option.id === line.itemId)
  if (item) {
    line.uom = item.baseUom
  }
}

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.supplierId = filters.supplierId
  form.title = ''
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.validFrom = ''
  form.validTo = ''
  form.paymentTerms = ''
  form.totalLimitAmount = null
  form.totalLimitQty = null
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('合同至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function validateCreate(): string {
  if (!form.companyId || !form.supplierId) {
    return '请选择公司与供应商'
  }
  if (!form.title.trim()) {
    return '请填写合同标题'
  }
  if (!form.validFrom || !form.validTo) {
    return '请选择合同有效期'
  }
  if (form.validTo < form.validFrom) {
    return '失效日不能早于生效日'
  }
  for (const [index, line] of form.lines.entries()) {
    if (!line.itemId) {
      return `第 ${index + 1} 行请选择物料`
    }
    if (line.price < 0) {
      return `第 ${index + 1} 行单价不能为负`
    }
    if (line.maxQty !== null && line.minQty !== null && line.maxQty < line.minQty) {
      return `第 ${index + 1} 行最大数量不能小于最小数量`
    }
  }
  return ''
}

async function submitCreate(): Promise<void> {
  const validation = validateCreate()
  if (validation) {
    ElMessage.warning(validation)
    return
  }
  const ok = await runSave(async () => {
    await createContract({
      companyId: form.companyId as number,
      supplierId: form.supplierId as number,
      title: form.title.trim(),
      currency: form.currency.trim().toUpperCase(),
      validFrom: form.validFrom,
      validTo: form.validTo,
      paymentTerms: form.paymentTerms.trim() || null,
      totalLimitAmount: form.totalLimitAmount,
      totalLimitQty: form.totalLimitQty,
      lines: form.lines.map((line) => ({
        itemId: line.itemId as number,
        uom: line.uom.trim() || null,
        price: line.price,
        minQty: line.minQty,
        maxQty: line.maxQty,
        taxIncluded: line.taxIncluded,
        validFrom: line.validFrom || null,
        validTo: line.validTo || null,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('合同已创建')
    await load(1)
  }
}

// ---- 释放采购订单 ----

interface ReleaseLineForm {
  selected: boolean
  contractLineId: number
  label: string
  remaining: number | null
  qty: number
}

const releaseVisible = ref(false)
const releaseSaving = ref(false)
const releaseError = ref('')
const releaseForm = reactive<{
  contractId: number
  plantId: number | undefined
  lines: ReleaseLineForm[]
}>({ contractId: 0, plantId: undefined, lines: [] })

function openRelease(): void {
  if (!detail.value) {
    return
  }
  releaseForm.contractId = detail.value.id
  releaseForm.plantId = props.plants.find(
    (plant) => plant.companyId === detail.value?.companyId,
  )?.id
  releaseForm.lines = detail.value.lines.map((line) => {
    const remaining =
      line.maxQty === null ? null : Math.max(Number(line.maxQty) - Number(line.consumedQty), 0)
    return {
      selected: remaining === null || remaining > 0,
      contractLineId: line.id,
      label: `第 ${line.lineNo} 行 · ${line.itemName} · ${formatAmount(line.price)}`,
      remaining,
      qty: remaining === null ? 1 : remaining,
    }
  })
  releaseError.value = ''
  releaseVisible.value = true
}

const releaseSelected = computed(() => releaseForm.lines.filter((line) => line.selected))

async function submitRelease(): Promise<void> {
  if (!releaseForm.plantId) {
    ElMessage.warning('请选择工厂')
    return
  }
  if (releaseSelected.value.length === 0) {
    ElMessage.warning('请至少选择一行')
    return
  }
  for (const line of releaseSelected.value) {
    if (line.qty <= 0) {
      ElMessage.warning('释放数量必须大于 0')
      return
    }
    if (line.remaining !== null && line.qty > line.remaining) {
      ElMessage.warning('释放数量不能超过合同剩余量')
      return
    }
  }
  releaseSaving.value = true
  releaseError.value = ''
  try {
    const po = await releaseContractOrder(
      releaseForm.contractId,
      releaseForm.plantId,
      releaseSelected.value.map((line) => ({
        contractLineId: line.contractLineId,
        qty: line.qty,
      })),
    )
    releaseVisible.value = false
    ElMessage.success(`采购订单 ${po.docNo} 已生成`)
    await load()
    await refreshDetail(releaseForm.contractId)
  } catch (caught) {
    releaseError.value = caught instanceof Error ? caught.message : '释放订单失败'
  } finally {
    releaseSaving.value = false
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
        v-model="filters.supplierId"
        class="list-toolbar__select"
        placeholder="供应商"
        clearable
        filterable
        aria-label="供应商"
        @change="search"
      >
        <el-option
          v-for="supplier in suppliers"
          :key="supplier.id"
          :label="`${supplier.code} · ${supplier.name}`"
          :value="supplier.id"
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
        新建合同
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
        label="合同号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as ContractRow).contractNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="title"
        label="标题"
        min-width="200"
      />
      <el-table-column
        label="供应商"
        min-width="150"
      >
        <template #default="{ row }">
          {{ supplierName((row as ContractRow).supplierId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="有效期"
        min-width="190"
      >
        <template #default="{ row }">
          {{ (row as ContractRow).validFrom }} ~ {{ (row as ContractRow).validTo }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as ContractRow).status)"
          >
            {{ labelOf(CONTRACT_STATUS_LABEL, (row as ContractRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="180"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as ContractRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as ContractRow).status === 'DRAFT'"
            link
            @click="activate(row as ContractRow)"
          >
            生效
          </el-button>
          <el-button
            v-if="(row as ContractRow).status === 'ACTIVE'"
            link
            @click="terminate(row as ContractRow)"
          >
            终止
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
    title="合同详情"
    size="760px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="合同号">
            {{ detail.contractNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(CONTRACT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="标题">
            {{ detail.title }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.supplierName || supplierName(detail.supplierId) }}
          </el-descriptions-item>
          <el-descriptions-item label="有效期">
            {{ detail.validFrom }} ~ {{ detail.validTo }}
          </el-descriptions-item>
          <el-descriptions-item label="已消耗">
            {{ formatQty(detail.consumedQty) }} / {{ formatAmount(detail.consumedAmount) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-head">
          <h3 class="detail-title">
            合同行
          </h3>
          <el-button
            v-if="detail.status === 'ACTIVE'"
            link
            @click="openRelease"
          >
            释放采购订单
          </el-button>
        </div>
        <el-table
          :data="detail.lines"
          class="table-list"
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
            label="单价"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatAmount(row.price) }}
            </template>
          </el-table-column>
          <el-table-column
            label="已消耗"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.consumedQty) }}
            </template>
          </el-table-column>
          <el-table-column
            label="上限"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ row.maxQty == null ? '—' : formatQty(row.maxQty) }}
            </template>
          </el-table-column>
        </el-table>

        <h3 class="detail-title">
          释放记录
        </h3>
        <el-table
          :data="consumption"
          class="table-list"
          empty-text="尚未释放"
        >
          <el-table-column
            prop="poId"
            label="PO id"
            width="100"
          />
          <el-table-column
            label="数量"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }}
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
          <el-table-column
            label="时间"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatDateTime(row.consumedAt) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到合同"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建采购合同"
    width="940px"
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
        <el-form-item label="供应商">
          <el-select
            v-model="form.supplierId"
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
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="生效日">
          <el-date-picker
            v-model="form.validFrom"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="失效日">
          <el-date-picker
            v-model="form.validTo"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="付款条件">
          <el-input
            v-model="form.paymentTerms"
            maxlength="32"
            placeholder="可留空"
          />
        </el-form-item>
        <el-form-item label="金额上限">
          <el-input-number
            v-model="form.totalLimitAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="数量上限">
          <el-input-number
            v-model="form.totalLimitQty"
            :min="0"
            :precision="6"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">合同行</span>
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
          min-width="170"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.itemId"
              placeholder="选择物料"
              filterable
              @change="onItemChange(row as ContractLineForm)"
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
          label="单价"
          width="120"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.price"
              :min="0"
              :precision="4"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="最小量"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.minQty"
              :min="0"
              :precision="6"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="最大量"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.maxQty"
              :min="0"
              :precision="6"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="含税"
          width="70"
        >
          <template #default="{ row }">
            <el-switch v-model="row.taxIncluded" />
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
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submitCreate"
      >
        创建合同
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="releaseVisible"
    title="释放采购订单"
    width="640px"
  >
    <el-alert
      v-if="releaseError"
      class="page-alert"
      type="error"
      :title="releaseError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="工厂">
        <el-select
          v-model="releaseForm.plantId"
          placeholder="选择工厂"
        >
          <el-option
            v-for="plant in props.plants.filter(
              (item) => item.companyId === detail?.companyId,
            )"
            :key="plant.id"
            :label="`${plant.code} · ${plant.name}`"
            :value="plant.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table
      :data="releaseForm.lines"
      class="table-list"
    >
      <el-table-column width="50">
        <template #default="{ row }">
          <el-checkbox v-model="row.selected" />
        </template>
      </el-table-column>
      <el-table-column
        prop="label"
        label="合同行"
        min-width="260"
      />
      <el-table-column
        label="剩余"
        align="right"
        width="100"
      >
        <template #default="{ row }">
          {{ row.remaining === null ? '不限' : formatQty(row.remaining) }}
        </template>
      </el-table-column>
      <el-table-column
        label="释放数量"
        width="140"
      >
        <template #default="{ row }">
          <el-input-number
            v-model="row.qty"
            :min="0.000001"
            :max="row.remaining ?? Number.MAX_SAFE_INTEGER"
            :controls="false"
            class="line-number"
            :disabled="!row.selected"
          />
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="releaseVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="releaseSaving"
        @click="submitRelease"
      >
        生成采购订单
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

.line-head,
.detail-head {
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

.detail-title {
  margin: 24px 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.detail-head .detail-title {
  margin: 0;
}
</style>
