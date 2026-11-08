<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createShipment,
  getSalesOrder,
  getShipment,
  listLocations,
  listSalesOrders,
  listShipments,
  type CompanyOption,
  type CustomerOption,
  type ItemOption,
  type LocationOption,
  type PlantOption,
  type SalesOrderDetail,
  type SalesOrderRow,
  type ShipmentDetail,
  type ShipmentRow,
  type WarehouseOption,
} from '@/features/erp/api'
import {
  SHIPMENT_STATUS_LABEL,
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
  warehouses: WarehouseOption[]
  plants: PlantOption[]
}>()

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const customerNames = computed(
  () => new Map(props.customers.map((customer) => [customer.id, customer.name])),
)
const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))
const warehouseNames = computed(
  () => new Map(props.warehouses.map((warehouse) => [warehouse.id, warehouse.name])),
)

function customerName(id: number): string {
  return customerNames.value.get(id) ?? `#${id}`
}

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

function warehouseName(id: number): string {
  return warehouseNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<ShipmentRow>(
  (targetPage, pageSize) =>
    listShipments({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(SHIPMENT_STATUS_LABEL)

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.status = ''
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ShipmentDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getShipment(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// ---- 创建 ----

interface ShipmentLineForm {
  selected: boolean
  orderLineId: number
  itemCode: string
  itemName: string
  remainingQty: number
  qty: number
  lotNo: string
  locationId: number | undefined
}

const createVisible = ref(false)
const orders = ref<SalesOrderRow[]>([])
const selectedOrder = ref<SalesOrderDetail | null>(null)
const orderWarehouses = ref<WarehouseOption[]>([])
const orderLocations = ref<LocationOption[]>([])
const form = reactive<{
  companyId: number | undefined
  orderId: number | undefined
  warehouseId: number | undefined
  lines: ShipmentLineForm[]
}>({ companyId: undefined, orderId: undefined, warehouseId: undefined, lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const selectedLines = computed(() => form.lines.filter((line) => line.selected))
const eligibleStatuses = ['CONFIRMED', 'PARTIAL_SHIPPED']

function newLine(line: SalesOrderDetail['lines'][number]): ShipmentLineForm | null {
  const remaining = Number(line.qty) - Number(line.shippedQty ?? 0)
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
    lotNo: '',
    locationId: undefined,
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
  form.warehouseId = undefined
  form.lines = []
  selectedOrder.value = null
  orderWarehouses.value = []
  orderLocations.value = []
  clearError()
  await loadOrders()
  createVisible.value = true
}

async function onOrderChange(orderId: number | undefined): Promise<void> {
  form.warehouseId = undefined
  form.lines = []
  selectedOrder.value = orderId ? await getSalesOrder(orderId).catch(() => null) : null
  if (!selectedOrder.value) {
    return
  }
  form.lines = selectedOrder.value.lines
    .map((line) => newLine(line))
    .filter((line): line is ShipmentLineForm => line !== null)
  const companyPlantIds = new Set(
    props.plants
      .filter((plant) => plant.companyId === selectedOrder.value?.companyId)
      .map((plant) => plant.id),
  )
  orderWarehouses.value = props.warehouses.filter(
    (warehouse) => companyPlantIds.has(warehouse.plantId) && warehouse.status === 'ACTIVE',
  )
  form.warehouseId = orderWarehouses.value[0]?.id
  await loadOrderLocations(form.warehouseId)
}

async function loadOrderLocations(warehouseId: number | undefined): Promise<void> {
  orderLocations.value = warehouseId ? await listLocations(warehouseId).catch(() => []) : []
}

async function submit(): Promise<void> {
  if (!form.orderId || !form.warehouseId) {
    ElMessage.warning('请选择销售订单与发货仓库')
    return
  }
  if (selectedLines.value.length === 0) {
    ElMessage.warning('请至少选择一行发货明细')
    return
  }
  for (const line of selectedLines.value) {
    if (line.qty <= 0 || line.qty > line.remainingQty) {
      ElMessage.warning(`物料 ${line.itemCode} 的发货数量必须在 0 与剩余数量之间`)
      return
    }
  }
  const ok = await runSave(async () => {
    await createShipment({
      orderId: form.orderId as number,
      warehouseId: form.warehouseId as number,
      lines: selectedLines.value.map((line) => ({
        orderLineId: line.orderLineId,
        qty: line.qty,
        lotNo: line.lotNo.trim() || null,
        locationId: line.locationId ?? null,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('销售发货已生成')
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
        新建发货
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
        label="发货单"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as ShipmentRow).shipmentNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="客户"
        min-width="160"
      >
        <template #default="{ row }">
          {{ customerName((row as ShipmentRow).customerId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="仓库"
        min-width="160"
      >
        <template #default="{ row }">
          {{ warehouseName((row as ShipmentRow).warehouseId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as ShipmentRow).status)"
          >
            {{ labelOf(SHIPMENT_STATUS_LABEL, (row as ShipmentRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="发货时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as ShipmentRow).shippedAt) }}
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
            @click="openDetail((row as ShipmentRow).id)"
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
    title="发货单详情"
    size="640px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="发货单">
            {{ detail.shipmentNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(SHIPMENT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ customerName(detail.customerId) }}
          </el-descriptions-item>
          <el-descriptions-item label="仓库">
            {{ warehouseName(detail.warehouseId) }}
          </el-descriptions-item>
          <el-descriptions-item label="发货时间">
            {{ formatDateTime(detail.shippedAt) }}
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
              {{ itemName(row.itemId) }}
            </template>
          </el-table-column>
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
            prop="lotNo"
            label="批次"
            min-width="120"
          />
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到发货单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建销售发货"
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
            placeholder="选择可发货订单"
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
        <el-form-item label="发货仓库">
          <el-select
            v-model="form.warehouseId"
            placeholder="选择仓库"
            @change="loadOrderLocations"
          >
            <el-option
              v-for="warehouse in orderWarehouses"
              :key="warehouse.id"
              :label="`${warehouse.code} · ${warehouse.name}`"
              :value="warehouse.id"
            />
          </el-select>
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
          label="剩余可发"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatQty(row.remainingQty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="发货数量"
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
          label="批次号"
          width="130"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.lotNo"
              maxlength="64"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="库位"
          width="150"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.locationId"
              clearable
              placeholder="默认"
              :disabled="!row.selected"
            >
              <el-option
                v-for="location in orderLocations"
                :key="location.id"
                :label="location.code"
                :value="location.id"
              />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="selectedOrder"
        description="该订单没有待发货行"
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
        确认发货
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
