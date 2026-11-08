<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  createGoodsReceipt,
  getGoodsReceipt,
  getPurchaseOrder,
  listGoodsReceipts,
  listLocations,
  listPurchaseOrders,
  listWarehouses,
  reverseGoodsReceipt,
  type CompanyOption,
  type GoodsReceiptDetail,
  type GoodsReceiptRow,
  type LocationOption,
  type PurchaseOrderDetail,
  type PurchaseOrderRow,
  type WarehouseOption,
} from '@/features/erp/api'
import {
  GOODS_RECEIPT_STATUS_LABEL,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[] }>()

const filters = reactive<{ companyId?: number; keyword: string }>({ keyword: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<GoodsReceiptRow>(
  (targetPage, pageSize) =>
    listGoodsReceipts({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      keyword: filters.keyword.trim() || undefined,
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
  filters.keyword = ''
  void load(1)
}

// ---- 详情 / 冲销 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<GoodsReceiptDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getGoodsReceipt(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reverse(): Promise<void> {
  if (!detail.value) {
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入冲销原因', '冲销收货', {
      inputType: 'textarea',
      inputValidator: (input) => (input?.trim() ? true : '冲销原因不能为空'),
    })
    await reverseGoodsReceipt(detail.value.id, value.trim(), detail.value.version)
    ElMessage.success('收货已冲销')
    detail.value = await getGoodsReceipt(detail.value.id)
    await load()
  } catch {
    // 取消或接口错误（错误已提示）
  }
}

// ---- 创建收货 ----

interface ReceiptLineForm {
  selected: boolean
  poLineId: number
  itemCode: string
  itemName: string
  remainingQty: number
  qty: number
  lotNo: string
  locationId: number | undefined
  productionDate: string
  expiryDate: string
}

const createVisible = ref(false)
const orders = ref<PurchaseOrderRow[]>([])
const selectedOrder = ref<PurchaseOrderDetail | null>(null)
const orderWarehouses = ref<WarehouseOption[]>([])
const orderLocations = ref<LocationOption[]>([])
const form = reactive<{
  companyId: number | undefined
  poId: number | undefined
  warehouseId: number | undefined
  lines: ReceiptLineForm[]
}>({ companyId: undefined, poId: undefined, warehouseId: undefined, lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const eligibleStatuses = ['SENT', 'PARTIAL_RECEIVED']

const selectedLines = computed(() => form.lines.filter((line) => line.selected))

function newReceiptLine(line: PurchaseOrderDetail['lines'][number]): ReceiptLineForm | null {
  const remaining = Number(line.qty) - Number(line.receivedQty ?? 0)
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
    lotNo: '',
    locationId: undefined,
    productionDate: '',
    expiryDate: '',
  }
}

async function loadOrders(): Promise<void> {
  try {
    const result = await listPurchaseOrders({
      page: 1,
      size: 100,
      companyId: form.companyId,
    })
    orders.value = result.list.filter((order) => eligibleStatuses.includes(order.status))
  } catch {
    orders.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.poId = undefined
  form.warehouseId = undefined
  form.lines = []
  selectedOrder.value = null
  orderWarehouses.value = []
  orderLocations.value = []
  clearError()
  await loadOrders()
  createVisible.value = true
}

async function onOrderChange(poId: number | undefined): Promise<void> {
  form.warehouseId = undefined
  form.lines = []
  orderWarehouses.value = []
  orderLocations.value = []
  selectedOrder.value = poId ? await getPurchaseOrder(poId).catch(() => null) : null
  if (!selectedOrder.value) {
    return
  }
  form.lines = selectedOrder.value.lines
    .map((line) => newReceiptLine(line))
    .filter((line): line is ReceiptLineForm => line !== null)
  try {
    const plantWarehouses = await listWarehouses(selectedOrder.value.plantId)
    orderWarehouses.value = plantWarehouses.filter((warehouse) => warehouse.status === 'ACTIVE')
    form.warehouseId = orderWarehouses.value[0]?.id
    await loadOrderLocations(form.warehouseId)
  } catch {
    orderWarehouses.value = []
  }
}

async function loadOrderLocations(warehouseId: number | undefined): Promise<void> {
  orderLocations.value = warehouseId ? await listLocations(warehouseId).catch(() => []) : []
}

async function submit(): Promise<void> {
  if (!form.poId || !form.warehouseId) {
    ElMessage.warning('请选择采购订单与收货仓库')
    return
  }
  if (selectedLines.value.length === 0) {
    ElMessage.warning('请至少选择一行收货明细')
    return
  }
  for (const line of selectedLines.value) {
    if (line.qty <= 0 || line.qty > line.remainingQty) {
      ElMessage.warning(`物料 ${line.itemCode} 的收货数量必须在 0 与剩余数量之间`)
      return
    }
  }
  const ok = await runSave(async () => {
    await createGoodsReceipt({
      poId: form.poId as number,
      warehouseId: form.warehouseId as number,
      lines: selectedLines.value.map((line) => ({
        poLineId: line.poLineId,
        qty: line.qty,
        lotNo: line.lotNo.trim() || null,
        productionDate: line.productionDate || null,
        expiryDate: line.expiryDate || null,
        locationId: line.locationId ?? null,
        serials: [],
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('收货已过账')
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
      <el-input
        v-model="filters.keyword"
        class="list-toolbar__search"
        placeholder="收货单号或订单号"
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
        新建收货
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
        label="收货单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as GoodsReceiptRow).docNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="采购订单"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as GoodsReceiptRow).poDocNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="warehouseName"
        label="仓库"
        min-width="150"
      />
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as GoodsReceiptRow).status)"
          >
            {{ labelOf(GOODS_RECEIPT_STATUS_LABEL, (row as GoodsReceiptRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="收货时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as GoodsReceiptRow).receivedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="110"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as GoodsReceiptRow).id)"
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
    title="收货单详情"
    size="720px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="收货单">
            {{ detail.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(GOODS_RECEIPT_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="采购订单">
            {{ detail.poDocNo }}
          </el-descriptions-item>
          <el-descriptions-item label="仓库">
            {{ detail.warehouseName }}
          </el-descriptions-item>
          <el-descriptions-item label="收货人">
            {{ detail.createdByName }}
          </el-descriptions-item>
          <el-descriptions-item label="收货时间">
            {{ formatDateTime(detail.receivedAt) }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.reversedAt"
            label="冲销原因"
            :span="2"
          >
            {{ detail.reversedByName }} · {{ detail.reverseReason }}
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
            width="120"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }} {{ row.uom }}
            </template>
          </el-table-column>
          <el-table-column
            prop="lotNo"
            label="批次"
            min-width="120"
          />
        </el-table>
        <div
          v-if="detail.status === 'POSTED'"
          class="detail-actions"
        >
          <el-button
            type="danger"
            plain
            @click="reverse"
          >
            冲销收货
          </el-button>
        </div>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到收货单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建收货"
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
        <el-form-item label="采购订单">
          <el-select
            v-model="form.poId"
            placeholder="选择可收货订单"
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
        <el-form-item label="收货仓库">
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
        <el-table-column
          width="50"
        >
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column
          label="物料"
          min-width="180"
        >
          <template #default="{ row }">
            {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="剩余"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatQty(row.remainingQty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="收货数量"
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
        description="该订单没有待收货行"
      />
      <p
        v-if="selectedOrder"
        class="section-note"
      >
        订单币种 {{ selectedOrder.currency }} · 工厂 {{ selectedOrder.plantName }} ·
        勾选行才会提交收货
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
        过账收货
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

.detail-actions {
  margin-top: 16px;
  text-align: right;
}
</style>
