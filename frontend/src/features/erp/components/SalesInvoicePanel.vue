<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createSalesInvoice,
  getSalesInvoice,
  getSalesOrder,
  listSalesInvoices,
  listSalesOrders,
  type CompanyOption,
  type CustomerOption,
  type SalesInvoiceDetail,
  type SalesInvoiceRow,
  type SalesOrderDetail,
  type SalesOrderRow,
} from '@/features/erp/api'
import {
  SALES_INVOICE_STATUS_LABEL,
  formatAmount,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[]; customers: CustomerOption[] }>()

const filters = reactive<{ companyId?: number; customerId?: number; status: string }>({ status: '' })

const customerNames = computed(
  () => new Map(props.customers.map((customer) => [customer.id, customer.name])),
)

function customerName(id: number): string {
  return customerNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<SalesInvoiceRow>(
  (targetPage, pageSize) =>
    listSalesInvoices({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      customerId: filters.customerId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(SALES_INVOICE_STATUS_LABEL)

function invoiceTone(status: string): string {
  return status === 'ISSUED' || status === 'PARTIAL' ? 'is-warning' : statusTone(status)
}

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.customerId = undefined
  filters.status = ''
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SalesInvoiceDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getSalesInvoice(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// ---- 创建 ----

interface InvoiceLineForm {
  selected: boolean
  orderLineId: number
  itemCode: string
  itemName: string
  remainingQty: number
  qty: number
}

const createVisible = ref(false)
const orders = ref<SalesOrderRow[]>([])
const selectedOrder = ref<SalesOrderDetail | null>(null)
const form = reactive<{
  companyId: number | undefined
  orderId: number | undefined
  dueDate: string
  remark: string
  lines: InvoiceLineForm[]
}>({ companyId: undefined, orderId: undefined, dueDate: '', remark: '', lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const selectedLines = computed(() => form.lines.filter((line) => line.selected))
const eligibleStatuses = ['SHIPPED', 'PARTIAL_SHIPPED']

function newLine(line: SalesOrderDetail['lines'][number]): InvoiceLineForm | null {
  const remaining = Number(line.qty) - Number(line.invoicedQty ?? 0)
  if (remaining <= 0) {
    return null
  }
  return {
    selected: true,
    orderLineId: line.id,
    itemCode: line.itemCode,
    itemName: line.itemName,
    remainingQty: remaining,
    qty: remaining,
  }
}

async function loadOrders(): Promise<void> {
  try {
    const result = await listSalesOrders({ page: 1, size: 100, companyId: form.companyId })
    orders.value = result.list.filter((order) => eligibleStatuses.includes(order.status))
  } catch {
    orders.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.orderId = undefined
  form.dueDate = ''
  form.remark = ''
  form.lines = []
  selectedOrder.value = null
  clearError()
  await loadOrders()
  createVisible.value = true
}

async function onOrderChange(orderId: number | undefined): Promise<void> {
  selectedOrder.value = orderId ? await getSalesOrder(orderId).catch(() => null) : null
  form.lines = selectedOrder.value
    ? selectedOrder.value.lines
        .map((line) => newLine(line))
        .filter((line): line is InvoiceLineForm => line !== null)
    : []
}

async function submit(): Promise<void> {
  if (!form.orderId) {
    ElMessage.warning('请选择销售订单')
    return
  }
  if (!form.dueDate) {
    ElMessage.warning('请选择到期日')
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
  }
  const ok = await runSave(async () => {
    await createSalesInvoice({
      orderId: form.orderId as number,
      dueDate: form.dueDate,
      remark: form.remark.trim() || null,
      lines: selectedLines.value.map((line) => ({
        orderLineId: line.orderLineId,
        qty: line.qty,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('销售发票已开具')
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
        v-model="filters.customerId"
        class="list-toolbar__select"
        placeholder="客户"
        clearable
        filterable
        aria-label="客户"
        @change="search"
      >
        <el-option
          v-for="customer in props.customers"
          :key="customer.id"
          :label="`${customer.code} · ${customer.name}`"
          :value="customer.id"
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
        开具发票
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
        label="发票号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as SalesInvoiceRow).invoiceNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="客户"
        min-width="160"
      >
        <template #default="{ row }">
          {{ customerName((row as SalesInvoiceRow).customerId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="价税合计"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as SalesInvoiceRow).totalAmount, (row as SalesInvoiceRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="已收"
        align="right"
        min-width="120"
      >
        <template #default="{ row }">
          {{ formatAmount((row as SalesInvoiceRow).paidAmount) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="invoiceTone((row as SalesInvoiceRow).status)"
          >
            {{ labelOf(SALES_INVOICE_STATUS_LABEL, (row as SalesInvoiceRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="到期日"
        width="120"
      >
        <template #default="{ row }">
          {{ (row as SalesInvoiceRow).dueDate }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="90"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as SalesInvoiceRow).id)"
          >
            详情
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
    title="销售发票详情"
    size="720px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="发票号">
            {{ detail.invoiceNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(SALES_INVOICE_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ detail.customerName || customerName(detail.customerId) }}
          </el-descriptions-item>
          <el-descriptions-item label="币种">
            {{ detail.currency }}
          </el-descriptions-item>
          <el-descriptions-item label="价税合计">
            {{ formatAmount(detail.totalAmount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="已收">
            {{ formatAmount(detail.paidAmount) }}
          </el-descriptions-item>
          <el-descriptions-item label="到期日">
            {{ detail.dueDate }}
          </el-descriptions-item>
          <el-descriptions-item label="备注">
            {{ detail.remark || '—' }}
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
            label="价税合计"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(Number(row.netAmount) + Number(row.taxAmount)) }}
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
    title="开具销售发票"
    width="820px"
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
        <el-form-item label="销售订单">
          <el-select
            v-model="form.orderId"
            placeholder="选择已发货订单"
            filterable
            @change="onOrderChange"
          >
            <el-option
              v-for="order in orders"
              :key="order.id"
              :label="`${order.orderNo} · ${customerName(order.customerId)}`"
              :value="order.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="到期日">
          <el-date-picker
            v-model="form.dueDate"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            maxlength="255"
            placeholder="可留空"
          />
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
          width="140"
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
        开具发票
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

.detail-table {
  margin-top: 20px;
}
</style>
