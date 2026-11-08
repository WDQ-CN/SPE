<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  creditSalesReturn,
  createSalesReturn,
  getSalesReturn,
  getShipment,
  listSalesReturns,
  listShipments,
  type CompanyOption,
  type CustomerOption,
  type ItemOption,
  type SalesReturnDetail,
  type SalesReturnRow,
  type ShipmentDetail,
  type ShipmentRow,
} from '@/features/erp/api'
import {
  SALES_RETURN_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{
  companies: CompanyOption[]
  customers: CustomerOption[]
  items: ItemOption[]
}>()

const filters = reactive<{ companyId?: number; customerId?: number; status: string }>({ status: '' })

const customerNames = computed(
  () => new Map(props.customers.map((customer) => [customer.id, customer.name])),
)
const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))

function customerName(id: number): string {
  return customerNames.value.get(id) ?? `#${id}`
}

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<SalesReturnRow>(
  (targetPage, pageSize) =>
    listSalesReturns({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      customerId: filters.customerId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(SALES_RETURN_STATUS_LABEL)

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
const detail = ref<SalesReturnDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getSalesReturn(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function credit(row: SalesReturnRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确认对退货单「${row.returnNo}」生成贷项并回库吗？`,
    '贷项',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await creditSalesReturn(row.id)
    ElMessage.success('退货已贷项')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getSalesReturn(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

interface ReturnLineForm {
  selected: boolean
  shipmentLineId: number
  itemId: number
  shippedQty: number
  qty: number
  reason: string
}

const createVisible = ref(false)
const shipments = ref<ShipmentRow[]>([])
const selectedShipment = ref<ShipmentDetail | null>(null)
const form = reactive<{
  companyId: number | undefined
  shipmentId: number | undefined
  reason: string
  lines: ReturnLineForm[]
}>({ companyId: undefined, shipmentId: undefined, reason: '', lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const selectedLines = computed(() => form.lines.filter((line) => line.selected))
const eligibleStatuses = ['SHIPPED', 'DELIVERED']

async function loadShipments(): Promise<void> {
  try {
    const result = await listShipments({ page: 1, size: 100, companyId: form.companyId })
    shipments.value = result.list.filter((shipment) => eligibleStatuses.includes(shipment.status))
  } catch {
    shipments.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.shipmentId = undefined
  form.reason = ''
  form.lines = []
  selectedShipment.value = null
  clearError()
  await loadShipments()
  createVisible.value = true
}

async function onShipmentChange(shipmentId: number | undefined): Promise<void> {
  selectedShipment.value = shipmentId
    ? await getShipment(shipmentId).catch(() => null)
    : null
  form.lines = selectedShipment.value
    ? selectedShipment.value.lines.map((line) => ({
        selected: true,
        shipmentLineId: line.id,
        itemId: line.itemId,
        shippedQty: Number(line.qty),
        qty: Number(line.qty),
        reason: '',
      }))
    : []
}

async function submit(): Promise<void> {
  if (!form.shipmentId) {
    ElMessage.warning('请选择发货单')
    return
  }
  if (selectedLines.value.length === 0) {
    ElMessage.warning('请至少选择一行退货明细')
    return
  }
  for (const line of selectedLines.value) {
    if (line.qty <= 0 || line.qty > line.shippedQty) {
      ElMessage.warning(`物料 ${itemName(line.itemId)} 的退货数量必须在 0 与发货数量之间`)
      return
    }
  }
  const ok = await runSave(async () => {
    await createSalesReturn({
      shipmentId: form.shipmentId as number,
      reason: form.reason.trim() || null,
      lines: selectedLines.value.map((line) => ({
        shipmentLineId: line.shipmentLineId,
        qty: line.qty,
        reason: line.reason.trim() || null,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('退货申请已创建')
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
        新建退货
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
        label="退货单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as SalesReturnRow).returnNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="客户"
        min-width="160"
      >
        <template #default="{ row }">
          {{ customerName((row as SalesReturnRow).customerId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="退货金额"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as SalesReturnRow).totalAmount, (row as SalesReturnRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as SalesReturnRow).status)"
          >
            {{ labelOf(SALES_RETURN_STATUS_LABEL, (row as SalesReturnRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as SalesReturnRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="150"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as SalesReturnRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as SalesReturnRow).status === 'REQUESTED'"
            link
            @click="credit(row as SalesReturnRow)"
          >
            贷项
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
    title="退货单详情"
    size="700px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="退货单">
            {{ detail.returnNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(SALES_RETURN_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ detail.customerName || customerName(detail.customerId) }}
          </el-descriptions-item>
          <el-descriptions-item label="退货金额">
            {{ formatAmount(detail.totalAmount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="原因">
            {{ detail.reason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="贷项时间">
            {{ formatDateTime(detail.creditedAt) }}
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
            min-width="170"
          >
            <template #default="{ row }">
              {{ itemName(row.itemId) }}
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
        description="未找到退货单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建销售退货"
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
    <el-form label-width="92px">
      <div class="form-grid">
        <el-form-item label="公司">
          <el-select
            v-model="form.companyId"
            placeholder="选择公司"
            @change="loadShipments"
          >
            <el-option
              v-for="company in props.companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发货单">
          <el-select
            v-model="form.shipmentId"
            placeholder="选择可退货发货单"
            filterable
            @change="onShipmentChange"
          >
            <el-option
              v-for="shipment in shipments"
              :key="shipment.id"
              :label="`${shipment.shipmentNo} · ${customerName(shipment.customerId)}`"
              :value="shipment.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="退货原因">
          <el-input
            v-model="form.reason"
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
          min-width="170"
        >
          <template #default="{ row }">
            {{ itemName(row.itemId) }}
          </template>
        </el-table-column>
        <el-table-column
          label="发货数量"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatQty(row.shippedQty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="退货数量"
          width="130"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0.000001"
              :max="row.shippedQty"
              :controls="false"
              class="line-number"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="行原因"
          min-width="170"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.reason"
              maxlength="255"
              placeholder="可留空"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="selectedShipment"
        description="该发货单没有可退行"
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
        创建退货
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

.detail-table {
  margin-top: 20px;
}
</style>
