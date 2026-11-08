<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  cancelSalesOrder,
  confirmSalesOrder,
  createSalesOrder,
  getSalesOrder,
  listCompanies,
  listCustomers,
  listItems,
  listSalesOrders,
  type CompanyOption,
  type CustomerOption,
  type ItemOption,
  type SalesOrderDetail,
  type SalesOrderRow,
} from '@/features/erp/api'
import {
  SALES_ORDER_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

interface SalesLineForm {
  itemId: number | undefined
  qty: number
  uom: string
  unitPrice: number
  discountRate: number
  taxRate: number
}

const router = useRouter()
const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const companies = ref<CompanyOption[]>([])
const customers = ref<CustomerOption[]>([])
const items = ref<ItemOption[]>([])

const customerNames = computed(
  () => new Map(customers.value.map((customer) => [customer.id, customer.name])),
)

const { rows, total, page, size, loading, error, load } = usePagedList<SalesOrderRow>(
  (targetPage, pageSize) =>
    listSalesOrders({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(SALES_ORDER_STATUS_LABEL)

function customerName(id: number): string {
  return customerNames.value.get(id) ?? `#${id}`
}

async function loadOptions(): Promise<void> {
  try {
    const [companyList, customerList, itemList] = await Promise.all([
      listCompanies(),
      listCustomers(),
      listItems(),
    ])
    companies.value = companyList
    customers.value = customerList
    items.value = itemList
    if (!filters.companyId && companyList.length > 0) {
      filters.companyId = companyList[0].id
    }
  } catch {
    // 主数据不可用时保留空选项，列表仍可查询
  }
}

onMounted(async () => {
  await loadOptions()
  await load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.status = ''
  filters.companyId = companies.value[0]?.id
  void load(1)
}

// ---- 新建订单 ----

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  customerId: number | undefined
  currency: string
  paymentTerms: string
  lines: SalesLineForm[]
}>({
  companyId: undefined,
  customerId: undefined,
  currency: 'CNY',
  paymentTerms: '',
  lines: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const formTotal = computed(() =>
  form.lines.reduce((sum, line) => {
    const net = line.qty * line.unitPrice * (1 - line.discountRate)
    return sum + net * (1 + line.taxRate)
  }, 0),
)

function newLine(): SalesLineForm {
  return { itemId: undefined, qty: 1, uom: '', unitPrice: 0, discountRate: 0, taxRate: 0.13 }
}

function openCreate(): void {
  form.companyId = filters.companyId ?? companies.value[0]?.id
  form.customerId = undefined
  form.currency = 'CNY'
  form.paymentTerms = ''
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

function onCompanyChange(): void {
  const company = companies.value.find((item) => item.id === form.companyId)
  if (company) {
    form.currency = company.baseCurrency
  }
}

function onCustomerChange(): void {
  const customer = customers.value.find((item) => item.id === form.customerId)
  if (customer && customer.currency) {
    form.currency = customer.currency
  }
}

function onItemChange(line: SalesLineForm): void {
  const item = items.value.find((option) => option.id === line.itemId)
  if (item) {
    line.uom = item.baseUom
  }
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('订单至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function validateForm(): string {
  if (!form.companyId) {
    return '请选择公司'
  }
  if (!form.customerId) {
    return '请选择客户'
  }
  if (form.lines.length === 0) {
    return '订单至少需要一行'
  }
  for (const [index, line] of form.lines.entries()) {
    if (!line.itemId) {
      return `第 ${index + 1} 行请选择物料`
    }
    if (line.qty <= 0) {
      return `第 ${index + 1} 行数量必须大于 0`
    }
    if (line.unitPrice < 0) {
      return `第 ${index + 1} 行单价不能为负`
    }
  }
  return ''
}

async function submitCreate(): Promise<void> {
  const validation = validateForm()
  if (validation) {
    ElMessage.warning(validation)
    return
  }
  const ok = await runSave(async () => {
    await createSalesOrder({
      companyId: form.companyId as number,
      customerId: form.customerId as number,
      currency: form.currency.trim().toUpperCase(),
      paymentTerms: form.paymentTerms.trim() || null,
      lines: form.lines.map((line) => ({
        itemId: line.itemId as number,
        qty: line.qty,
        uom: line.uom || 'EA',
        unitPrice: line.unitPrice,
        discountRate: line.discountRate,
        taxRate: line.taxRate,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('销售订单已创建')
    await load(1)
  }
}

// ---- 详情与状态动作 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SalesOrderDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getSalesOrder(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function changeStatus(row: SalesOrderRow, action: 'confirm' | 'cancel'): Promise<void> {
  const verb = action === 'confirm' ? '确认' : '取消'
  const confirmed = await ElMessageBox.confirm(`确定要${verb}订单「${row.orderNo}」吗？`, '提示', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'confirm') {
      await confirmSalesOrder(row.id)
    } else {
      await cancelSalesOrder(row.id)
    }
    ElMessage.success(`已${verb}`)
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getSalesOrder(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / ORDER TO CASH"
    title="销售订单"
    :description="`共 ${total} 张订单 · 确认后进入发货与开票流程`"
  >
    <template #actions>
      <el-button @click="router.push('/sales/operations')">
        销售执行
      </el-button>
      <el-button
        type="primary"
        @click="openCreate"
      >
        新建销售订单
      </el-button>
    </template>
  </PageHeader>

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
        v-for="company in companies"
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
    <el-button
      type="primary"
      @click="search"
    >
      查询
    </el-button>
    <el-button @click="resetSearch">
      重置
    </el-button>
    <span class="list-toolbar__note">金额单位：订单币种</span>
  </form>

  <el-alert
    v-if="error"
    class="page-alert"
    type="error"
    :title="error"
    :closable="false"
    show-icon
  >
    <el-button
      link
      @click="load()"
    >
      重试
    </el-button>
  </el-alert>

  <section>
    <el-table
      v-loading="loading"
      :data="rows"
      :border="false"
      class="table-list"
    >
      <el-table-column
        label="订单号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as SalesOrderRow).orderNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="客户"
        min-width="180"
      >
        <template #default="{ row }">
          {{ customerName((row as SalesOrderRow).customerId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as SalesOrderRow).status)"
          >
            {{ labelOf(SALES_ORDER_STATUS_LABEL, (row as SalesOrderRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="价税合计"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as SalesOrderRow).totalAmount, (row as SalesOrderRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as SalesOrderRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="190"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as SalesOrderRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as SalesOrderRow).status === 'DRAFT'"
            link
            @click="changeStatus(row as SalesOrderRow, 'confirm')"
          >
            确认
          </el-button>
          <el-button
            v-if="['DRAFT', 'CONFIRMED'].includes((row as SalesOrderRow).status)"
            link
            @click="changeStatus(row as SalesOrderRow, 'cancel')"
          >
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      class="list-pager"
      @change="(target: number) => void load(target)"
      @size-change="() => void load(1)"
    />
  </section>

  <el-drawer
    v-model="detailVisible"
    title="销售订单详情"
    size="720px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="订单号">
            {{ detail.orderNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(SALES_ORDER_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ detail.customerName }}
          </el-descriptions-item>
          <el-descriptions-item label="币种">
            {{ detail.currency }}
          </el-descriptions-item>
          <el-descriptions-item label="价税合计">
            {{ formatAmount(detail.totalAmount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="付款条件">
            {{ detail.paymentTerms || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          订单行
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
              {{ formatQty(row.qty) }} {{ row.uom }}
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
            label="已发货"
            align="right"
            width="100"
          >
            <template #default="{ row }">
              {{ formatQty(row.shippedQty) }}
            </template>
          </el-table-column>
          <el-table-column
            label="已开票"
            align="right"
            width="100"
          >
            <template #default="{ row }">
              {{ formatQty(row.invoicedQty) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到订单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建销售订单"
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
    <el-form label-width="84px">
      <div class="form-grid">
        <el-form-item label="公司">
          <el-select
            v-model="form.companyId"
            placeholder="选择公司"
            @change="onCompanyChange"
          >
            <el-option
              v-for="company in companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select
            v-model="form.customerId"
            placeholder="选择客户"
            filterable
            @change="onCustomerChange"
          >
            <el-option
              v-for="customer in customers"
              :key="customer.id"
              :label="`${customer.code} · ${customer.name}`"
              :value="customer.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="付款条件">
          <el-input
            v-model="form.paymentTerms"
            placeholder="如 NET30，可留空"
          />
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">订单行</span>
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
          min-width="210"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.itemId"
              placeholder="选择物料"
              filterable
              @change="onItemChange(row as SalesLineForm)"
            >
              <el-option
                v-for="item in items"
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
          width="90"
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
              v-model="row.unitPrice"
              :min="0"
              :precision="4"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="折扣率"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.discountRate"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="4"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="税率"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.taxRate"
              :min="0"
              :max="1"
              :step="0.01"
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
        预估价税合计：<strong>{{ formatAmount(formTotal, form.currency) }}</strong>
      </p>
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
        创建订单
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
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

.line-number {
  width: 100%;
}

.line-total {
  margin: 16px 0 0;
  text-align: right;
  font-size: 13px;
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
