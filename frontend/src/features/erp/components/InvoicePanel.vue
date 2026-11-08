<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveInvoice,
  createSupplierInvoice,
  getPurchaseOrder,
  getSupplierInvoice,
  listPurchaseOrders,
  listSupplierInvoices,
  listSuppliers,
  rematchInvoice,
  type CompanyOption,
  type InvoiceDetail,
  type InvoiceLine,
  type InvoiceRow,
  type PurchaseOrderDetail,
  type PurchaseOrderRow,
} from '@/features/erp/api'
import {
  INVOICE_MATCH_STATUS_LABEL,
  INVOICE_STATUS_LABEL,
  formatAmount,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const props = defineProps<{ companies: CompanyOption[] }>()

const filters = reactive<{
  companyId?: number
  supplierId?: number
  status: string
  keyword: string
}>({ status: '', keyword: '' })

const suppliers = ref<SupplierSummary[]>([])

const { rows, total, page, size, loading, error, load } = usePagedList<InvoiceRow>(
  (targetPage, pageSize) =>
    listSupplierInvoices({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      supplierId: filters.supplierId,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    }),
)

const statusOptions = Object.entries(INVOICE_STATUS_LABEL)

function invoiceTone(status: string): string {
  return status === 'RECEIVED' ? 'is-warning' : statusTone(status)
}

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
  filters.keyword = ''
  void load(1)
}

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<InvoiceDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getSupplierInvoice(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function rematch(row: InvoiceRow): Promise<void> {
  try {
    await rematchInvoice(row.id, row.version)
    ElMessage.success('已重新匹配')
    await load()
  } catch {
    // http 拦截器已提示
  }
}

async function approve(row: InvoiceRow): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      row.status === 'EXCEPTION' ? '匹配异常，必须填写批准原因' : '审批意见（可留空）',
      '批准发票',
      {
        inputType: 'textarea',
        inputValidator: (input) =>
          row.status === 'EXCEPTION' && !input?.trim() ? '匹配异常必须填写原因' : true,
      },
    )
    await approveInvoice(row.id, value?.trim() || null, row.version)
    ElMessage.success('发票已批准')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getSupplierInvoice(row.id)
    }
  } catch {
    // 取消或接口错误
  }
}

// ---- 创建 ----

interface InvoiceLineForm {
  selected: boolean
  poLineId: number
  itemCode: string
  itemName: string
  remainingQty: number
  qty: number
  unitPrice: number
}

const createVisible = ref(false)
const orders = ref<PurchaseOrderRow[]>([])
const selectedOrder = ref<PurchaseOrderDetail | null>(null)
const form = reactive<{
  companyId: number | undefined
  poId: number | undefined
  supplierInvoiceNo: string
  invoiceDate: string
  dueDate: string
  currency: string
  taxAmount: number
  lines: InvoiceLineForm[]
}>({
  companyId: undefined,
  poId: undefined,
  supplierInvoiceNo: '',
  invoiceDate: '',
  dueDate: '',
  currency: 'CNY',
  taxAmount: 0,
  lines: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const eligibleStatuses = ['SENT', 'PARTIAL_RECEIVED', 'RECEIVED']
const selectedLines = computed(() => form.lines.filter((line) => line.selected))
const netTotal = computed(() =>
  selectedLines.value.reduce((sum, line) => sum + line.qty * line.unitPrice, 0),
)

function newInvoiceLine(line: PurchaseOrderDetail['lines'][number]): InvoiceLineForm | null {
  const remaining = Number(line.qty) - Number(line.invoicedQty ?? 0)
  if (remaining <= 0) {
    return null
  }
  return {
    selected: true,
    poLineId: line.id,
    itemCode: line.itemCode,
    itemName: line.itemName,
    remainingQty: remaining,
    qty: remaining,
    unitPrice: Number(line.price),
  }
}

async function loadOrders(): Promise<void> {
  try {
    const result = await listPurchaseOrders({ page: 1, size: 100, companyId: form.companyId })
    orders.value = result.list.filter((order) => eligibleStatuses.includes(order.status))
  } catch {
    orders.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.poId = undefined
  form.supplierInvoiceNo = ''
  form.invoiceDate = new Date().toISOString().slice(0, 10)
  form.dueDate = ''
  form.currency = 'CNY'
  form.taxAmount = 0
  form.lines = []
  selectedOrder.value = null
  clearError()
  await loadOrders()
  createVisible.value = true
}

async function onOrderChange(poId: number | undefined): Promise<void> {
  form.lines = []
  selectedOrder.value = poId ? await getPurchaseOrder(poId).catch(() => null) : null
  if (!selectedOrder.value) {
    return
  }
  form.currency = selectedOrder.value.currency
  form.lines = selectedOrder.value.lines
    .map((line) => newInvoiceLine(line))
    .filter((line): line is InvoiceLineForm => line !== null)
}

async function submit(): Promise<void> {
  if (!form.poId) {
    ElMessage.warning('请选择采购订单')
    return
  }
  if (!form.supplierInvoiceNo.trim()) {
    ElMessage.warning('请填写供应商发票号')
    return
  }
  if (!form.invoiceDate) {
    ElMessage.warning('请选择发票日期')
    return
  }
  if (selectedLines.value.length === 0) {
    ElMessage.warning('请至少选择一行开票明细')
    return
  }
  for (const line of selectedLines.value) {
    if (line.qty <= 0 || line.qty > line.remainingQty) {
      ElMessage.warning(`物料 ${line.itemCode} 的开票数量必须在 0 与剩余数量之间`)
      return
    }
    if (line.unitPrice < 0) {
      ElMessage.warning(`物料 ${line.itemCode} 的单价不能为负`)
      return
    }
  }
  const ok = await runSave(async () => {
    await createSupplierInvoice({
      poId: form.poId as number,
      supplierInvoiceNo: form.supplierInvoiceNo.trim(),
      invoiceDate: form.invoiceDate,
      dueDate: form.dueDate || null,
      currency: form.currency.trim().toUpperCase(),
      taxAmount: form.taxAmount,
      lines: selectedLines.value.map((line) => ({
        poLineId: line.poLineId,
        qty: line.qty,
        unitPrice: line.unitPrice,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('供应商发票已录入')
    await load(1)
  }
}

function invoiceLineAmount(line: InvoiceLine): number {
  return Number(line.amount ?? line.qty * line.unitPrice)
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
      <el-input
        v-model="filters.keyword"
        class="list-toolbar__search"
        placeholder="发票号或订单号"
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
        录入发票
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
        label="发票"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InvoiceRow).docNo }}</span>
          <div class="mono">
            {{ (row as InvoiceRow).supplierInvoiceNo }}
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="supplierName"
        label="供应商"
        min-width="160"
      />
      <el-table-column
        label="采购订单"
        min-width="140"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InvoiceRow).poDocNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="价税合计"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as InvoiceRow).totalAmount, (row as InvoiceRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="已付"
        align="right"
        min-width="120"
      >
        <template #default="{ row }">
          {{ formatAmount((row as InvoiceRow).paidAmount) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="invoiceTone((row as InvoiceRow).status)"
          >
            {{ labelOf(INVOICE_STATUS_LABEL, (row as InvoiceRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="210"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as InvoiceRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="['RECEIVED', 'MATCHED', 'EXCEPTION'].includes((row as InvoiceRow).status)"
            link
            @click="rematch(row as InvoiceRow)"
          >
            重新匹配
          </el-button>
          <el-button
            v-if="['MATCHED', 'EXCEPTION'].includes((row as InvoiceRow).status)"
            link
            @click="approve(row as InvoiceRow)"
          >
            批准
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
    title="供应商发票详情"
    size="760px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="发票号">
            {{ detail.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商发票号">
            {{ detail.supplierInvoiceNo }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.supplierName }}
          </el-descriptions-item>
          <el-descriptions-item label="采购订单">
            {{ detail.poDocNo }}
          </el-descriptions-item>
          <el-descriptions-item label="价税合计">
            {{ formatAmount(detail.totalAmount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(INVOICE_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="发票日期">
            {{ detail.invoiceDate }}
          </el-descriptions-item>
          <el-descriptions-item label="到期日">
            {{ detail.dueDate || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.match">
          <h3 class="detail-title">
            三单匹配
          </h3>
          <el-descriptions
            :column="4"
            border
          >
            <el-descriptions-item label="结论">
              {{ labelOf(INVOICE_MATCH_STATUS_LABEL, detail.match.status) }}
            </el-descriptions-item>
            <el-descriptions-item label="价格差异">
              {{ formatAmount(detail.match.priceVariance) }}
            </el-descriptions-item>
            <el-descriptions-item label="数量差异">
              {{ formatQty(detail.match.qtyVariance) }}
            </el-descriptions-item>
            <el-descriptions-item label="金额差异">
              {{ formatAmount(detail.match.amountVariance) }}
            </el-descriptions-item>
          </el-descriptions>
          <p
            v-if="detail.match.detail"
            class="section-note"
          >
            {{ detail.match.detail }}
          </p>
        </template>

        <h3 class="detail-title">
          发票行
        </h3>
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
            label="数量"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }}
            </template>
          </el-table-column>
          <el-table-column
            label="单价"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatAmount(row.unitPrice) }}
            </template>
          </el-table-column>
          <el-table-column
            label="金额"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(invoiceLineAmount(row as InvoiceLine)) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到发票"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="录入供应商发票"
    width="900px"
  >
    <el-alert
      v-if="saveError"
      class="page-alert"
      type="error"
      :title="saveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="110px">
      <div class="form-grid">
        <el-form-item label="公司">
          <el-select
            v-model="form.companyId"
            placeholder="选择公司"
            @change="loadOrders"
          >
            <el-option
              v-for="company in props.companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="采购订单">
          <el-select
            v-model="form.poId"
            placeholder="选择可开票订单"
            filterable
            @change="onOrderChange"
          >
            <el-option
              v-for="order in orders"
              :key="order.id"
              :label="`${order.docNo} · ${order.supplierName}`"
              :value="order.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商发票号">
          <el-input
            v-model="form.supplierInvoiceNo"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="发票日期">
          <el-date-picker
            v-model="form.invoiceDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="到期日">
          <el-date-picker
            v-model="form.dueDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可留空"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="税额">
          <el-input-number
            v-model="form.taxAmount"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="不含税合计">
          <span>{{ formatAmount(netTotal, form.currency) }}</span>
        </el-form-item>
      </div>

      <el-table
        v-if="form.lines.length > 0"
        :data="form.lines"
        class="table-list"
      >
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column
          label="物料"
          min-width="190"
        >
          <template #default="{ row }">
            {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="剩余可开"
          align="right"
          width="120"
        >
          <template #default="{ row }">
            {{ formatQty(row.remainingQty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="开票数量"
          width="130"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0.000001"
              :max="row.remainingQty"
              :controls="false"
              class="line-number"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="单价"
          width="130"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitPrice"
              :min="0"
              :precision="4"
              :controls="false"
              class="line-number"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="selectedOrder"
        description="该订单没有待开票行"
      />
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
        录入发票
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

.detail-title {
  margin: 24px 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
