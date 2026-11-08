<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approvePayment,
  createPayment,
  getPayment,
  listPayments,
  listSupplierInvoices,
  payPayment,
  type CompanyOption,
  type InvoiceRow,
  type PaymentDetail,
  type PaymentRow,
} from '@/features/erp/api'
import {
  PAYMENT_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[] }>()

const filters = reactive<{
  companyId?: number
  invoiceId?: number
  status: string
  keyword: string
}>({ status: '', keyword: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<PaymentRow>(
  (targetPage, pageSize) =>
    listPayments({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      invoiceId: filters.invoiceId,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    }),
)

const statusOptions = Object.entries(PAYMENT_STATUS_LABEL)

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.invoiceId = undefined
  filters.status = ''
  filters.keyword = ''
  void load(1)
}

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PaymentDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getPayment(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function approve(row: PaymentRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定批准付款申请「${row.docNo}」吗？`, '审批', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    const current = await getPayment(row.id)
    await approvePayment(row.id, current.version)
    ElMessage.success('付款申请已批准')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getPayment(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

async function pay(row: PaymentRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定登记付款「${row.docNo}」吗？`, '付款', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    const current = await getPayment(row.id)
    await payPayment(row.id, current.version)
    ElMessage.success('付款已登记')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getPayment(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

const createVisible = ref(false)
const invoiceOptions = ref<InvoiceRow[]>([])
const form = reactive<{ invoiceId: number | undefined; amount: number }>({
  invoiceId: undefined,
  amount: 0,
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const selectedInvoice = computed(() =>
  invoiceOptions.value.find((invoice) => invoice.id === form.invoiceId),
)
const outstanding = computed(() => {
  const invoice = selectedInvoice.value
  return invoice ? Number(invoice.totalAmount) - Number(invoice.paidAmount) : 0
})

async function openCreate(): Promise<void> {
  form.invoiceId = undefined
  form.amount = 0
  clearError()
  createVisible.value = true
  try {
    const result = await listSupplierInvoices({ page: 1, size: 200, companyId: filters.companyId })
    invoiceOptions.value = result.list.filter((invoice) =>
      ['APPROVED', 'PARTIALLY_PAID'].includes(invoice.status),
    )
  } catch {
    invoiceOptions.value = []
  }
}

function onInvoiceChange(invoiceId: number | undefined): void {
  const invoice = invoiceOptions.value.find((option) => option.id === invoiceId)
  form.amount = invoice ? Math.max(Number(invoice.totalAmount) - Number(invoice.paidAmount), 0) : 0
}

async function submit(): Promise<void> {
  if (!form.invoiceId) {
    ElMessage.warning('请选择已批准发票')
    return
  }
  if (form.amount <= 0) {
    ElMessage.warning('付款金额必须大于 0')
    return
  }
  if (form.amount > outstanding.value) {
    ElMessage.warning('付款金额不能超过发票未付余额')
    return
  }
  const ok = await runSave(async () => {
    await createPayment(form.invoiceId as number, form.amount)
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('付款申请已创建')
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
        placeholder="付款单号或发票号"
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
        新建付款
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
        label="付款单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as PaymentRow).docNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="supplierName"
        label="供应商"
        min-width="160"
      />
      <el-table-column
        label="发票"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as PaymentRow).invoiceDocNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="金额"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as PaymentRow).amount, (row as PaymentRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as PaymentRow).status)"
          >
            {{ labelOf(PAYMENT_STATUS_LABEL, (row as PaymentRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as PaymentRow).createdAt) }}
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
            @click="openDetail((row as PaymentRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as PaymentRow).status === 'REQUESTED'"
            link
            @click="approve(row as PaymentRow)"
          >
            批准
          </el-button>
          <el-button
            v-if="(row as PaymentRow).status === 'APPROVED'"
            link
            @click="pay(row as PaymentRow)"
          >
            登记付款
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
    title="付款详情"
    size="620px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="付款单">
            {{ detail.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(PAYMENT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.supplierName }}
          </el-descriptions-item>
          <el-descriptions-item label="发票">
            {{ detail.invoiceDocNo }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ formatAmount(detail.amount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="申请时间">
            {{ formatDateTime(detail.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="申请人">
            {{ detail.requestedByName }}
          </el-descriptions-item>
          <el-descriptions-item label="审批人">
            {{ detail.approvedByName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="付款时间">
            {{ formatDateTime(detail.paidAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到付款单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建付款申请"
    width="520px"
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
      <el-form-item label="发票">
        <el-select
          v-model="form.invoiceId"
          placeholder="选择已批准发票"
          filterable
          @change="onInvoiceChange"
        >
          <el-option
            v-for="invoice in invoiceOptions"
            :key="invoice.id"
            :label="`${invoice.docNo} · ${invoice.supplierName} · ${formatAmount(invoice.totalAmount, invoice.currency)}`"
            :value="invoice.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="未付余额">
        <span>{{ formatAmount(outstanding, selectedInvoice?.currency) }}</span>
      </el-form-item>
      <el-form-item label="付款金额">
        <el-input-number
          v-model="form.amount"
          :min="0.0001"
          :precision="4"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
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
        提交申请
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
</style>
