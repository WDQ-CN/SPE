<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createCustomerReceipt,
  getCustomerReceipt,
  listCustomerReceipts,
  listSalesInvoices,
  type CompanyOption,
  type CustomerOption,
  type ReceiptDetail,
  type ReceiptRow,
  type SalesInvoiceRow,
} from '@/features/erp/api'
import {
  RECEIPT_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[]; customers: CustomerOption[] }>()

const filters = reactive<{ companyId?: number; customerId?: number }>({})

const customerNames = computed(
  () => new Map(props.customers.map((customer) => [customer.id, customer.name])),
)

function customerName(id: number): string {
  return customerNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<ReceiptRow>(
  (targetPage, pageSize) =>
    listCustomerReceipts({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      customerId: filters.customerId,
    }),
)

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
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ReceiptDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getCustomerReceipt(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// ---- 创建 ----

interface AllocationForm {
  selected: boolean
  invoiceId: number
  invoiceNo: string
  outstanding: number
  amount: number
}

const createVisible = ref(false)
const invoiceLoading = ref(false)
const form = reactive<{
  customerId: number | undefined
  method: string
  allocations: AllocationForm[]
}>({ customerId: undefined, method: '', allocations: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const selectedAllocations = computed(() => form.allocations.filter((line) => line.selected))
const totalAmount = computed(() =>
  selectedAllocations.value.reduce((sum, line) => sum + Number(line.amount || 0), 0),
)
const currency = computed(() => {
  const customer = form.customerId
    ? props.customers.find((item) => item.id === form.customerId)
    : undefined
  return customer?.currency || 'CNY'
})

async function loadInvoices(customerId: number | undefined): Promise<void> {
  form.allocations = []
  if (!customerId) {
    return
  }
  invoiceLoading.value = true
  try {
    const result = await listSalesInvoices({ page: 1, size: 200, customerId })
    form.allocations = result.list
      .filter((invoice) => ['ISSUED', 'PARTIAL'].includes(invoice.status))
      .map((invoice: SalesInvoiceRow) => {
        const outstanding = Number(invoice.totalAmount) - Number(invoice.paidAmount)
        return {
          selected: true,
          invoiceId: invoice.id,
          invoiceNo: invoice.invoiceNo,
          outstanding,
          amount: outstanding,
        }
      })
  } catch {
    form.allocations = []
  } finally {
    invoiceLoading.value = false
  }
}

function openCreate(): void {
  form.customerId = filters.customerId
  form.method = ''
  form.allocations = []
  clearError()
  createVisible.value = true
  if (form.customerId) {
    void loadInvoices(form.customerId)
  }
}

async function submit(): Promise<void> {
  if (!form.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  if (selectedAllocations.value.length === 0) {
    ElMessage.warning('请至少选择一张待核销发票')
    return
  }
  for (const line of selectedAllocations.value) {
    if (line.amount <= 0 || line.amount > line.outstanding) {
      ElMessage.warning(`发票 ${line.invoiceNo} 的核销金额必须在 0 与未收金额之间`)
      return
    }
  }
  const ok = await runSave(async () => {
    await createCustomerReceipt({
      customerId: form.customerId as number,
      method: form.method.trim() || null,
      allocations: selectedAllocations.value.map((line) => ({
        invoiceId: line.invoiceId,
        amount: line.amount,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('收款已登记')
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
        登记收款
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
        label="收款单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as ReceiptRow).receiptNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="客户"
        min-width="160"
      >
        <template #default="{ row }">
          {{ customerName((row as ReceiptRow).customerId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="金额"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as ReceiptRow).amount, (row as ReceiptRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="方式"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as ReceiptRow).method || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as ReceiptRow).status)"
          >
            {{ labelOf(RECEIPT_STATUS_LABEL, (row as ReceiptRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="收款时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as ReceiptRow).receivedAt) }}
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
            @click="openDetail((row as ReceiptRow).id)"
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
    title="收款详情"
    size="620px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="收款单">
            {{ detail.receiptNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(RECEIPT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ detail.customerName || customerName(detail.customerId) }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ formatAmount(detail.amount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="方式">
            {{ detail.method || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="收款时间">
            {{ formatDateTime(detail.receivedAt) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table
          :data="detail.allocations"
          class="table-list detail-table"
        >
          <el-table-column
            label="发票"
            min-width="180"
          >
            <template #default="{ row }">
              <span class="mono">{{ row.invoiceNo || `#${row.invoiceId}` }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="核销金额"
            align="right"
            width="140"
          >
            <template #default="{ row }">
              {{ formatAmount(row.amount) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到收款单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="登记客户收款"
    width="760px"
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
        <el-form-item label="客户">
          <el-select
            v-model="form.customerId"
            placeholder="选择客户"
            filterable
            @change="loadInvoices"
          >
            <el-option
              v-for="customer in props.customers"
              :key="customer.id"
              :label="`${customer.code} · ${customer.name}`"
              :value="customer.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="收款方式">
          <el-input
            v-model="form.method"
            maxlength="32"
            placeholder="如 BANK / CASH，可留空"
          />
        </el-form-item>
      </div>

      <el-table
        v-loading="invoiceLoading"
        :data="form.allocations"
        class="table-list"
        empty-text="该客户没有待核销发票"
      >
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column
          label="发票"
          min-width="170"
        >
          <template #default="{ row }">
            <span class="mono">{{ row.invoiceNo }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="未收金额"
          align="right"
          width="140"
        >
          <template #default="{ row }">
            {{ formatAmount(row.outstanding) }}
          </template>
        </el-table-column>
        <el-table-column
          label="本次核销"
          width="150"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.amount"
              :min="0.0001"
              :max="row.outstanding"
              :precision="4"
              :controls="false"
              class="line-number"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
      </el-table>
      <p class="receipt-total">
        本次收款合计：<strong>{{ formatAmount(totalAmount, currency) }}</strong>
      </p>
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
        登记收款
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

.receipt-total {
  margin: 16px 0 0;
  text-align: right;
  font-size: 13px;
}
</style>
