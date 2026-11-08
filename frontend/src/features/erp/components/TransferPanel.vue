<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  cancelTransfer,
  createTransfer,
  getTransfer,
  listLocations,
  listTransfers,
  receiveTransfer,
  shipTransfer,
  type CompanyOption,
  type InventoryTransferDetail,
  type InventoryTransferRow,
  type ItemOption,
  type LocationOption,
  type WarehouseOption,
} from '@/features/erp/api'
import {
  TRANSFER_STATUS_LABEL,
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
  warehouses: WarehouseOption[]
  items: ItemOption[]
}>()

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))
const warehouseNames = computed(
  () => new Map(props.warehouses.map((warehouse) => [warehouse.id, warehouse.name])),
)

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

function warehouseName(id: number): string {
  return warehouseNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<InventoryTransferRow>(
  (targetPage, pageSize) =>
    listTransfers({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(TRANSFER_STATUS_LABEL)

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

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<InventoryTransferDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getTransfer(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function act(row: InventoryTransferRow, action: 'ship' | 'receive' | 'cancel'): Promise<void> {
  const verbs = { ship: '发运', receive: '接收', cancel: '取消' }
  const confirmed = await ElMessageBox.confirm(
    `确定要${verbs[action]}调拨单「${row.transferNo}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'ship') {
      await shipTransfer(row.id)
    } else if (action === 'receive') {
      await receiveTransfer(row.id)
    } else {
      await cancelTransfer(row.id)
    }
    ElMessage.success(`已${verbs[action]}`)
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getTransfer(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

interface TransferLineForm {
  itemId: number | undefined
  lotId: string
  qty: number
  uom: string
  unitCost: number | null
}

const createVisible = ref(false)
const fromLocations = ref<LocationOption[]>([])
const toLocations = ref<LocationOption[]>([])
const form = reactive<{
  companyId: number | undefined
  fromWarehouseId: number | undefined
  fromLocationId: number | undefined
  toWarehouseId: number | undefined
  toLocationId: number | undefined
  reason: string
  lines: TransferLineForm[]
}>({
  companyId: undefined,
  fromWarehouseId: undefined,
  fromLocationId: undefined,
  toWarehouseId: undefined,
  toLocationId: undefined,
  reason: '',
  lines: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function newLine(): TransferLineForm {
  return { itemId: undefined, lotId: '', qty: 1, uom: '', unitCost: null }
}

async function loadFromLocations(warehouseId: number | undefined): Promise<void> {
  form.fromLocationId = undefined
  fromLocations.value = warehouseId ? await listLocations(warehouseId).catch(() => []) : []
}

async function loadToLocations(warehouseId: number | undefined): Promise<void> {
  form.toLocationId = undefined
  toLocations.value = warehouseId ? await listLocations(warehouseId).catch(() => []) : []
}

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.fromWarehouseId = props.warehouses[0]?.id
  form.fromLocationId = undefined
  form.toWarehouseId = props.warehouses[1]?.id ?? props.warehouses[0]?.id
  form.toLocationId = undefined
  form.reason = ''
  form.lines = [newLine()]
  fromLocations.value = []
  toLocations.value = []
  void loadFromLocations(form.fromWarehouseId)
  void loadToLocations(form.toWarehouseId)
  clearError()
  createVisible.value = true
}

function onItemChange(line: TransferLineForm): void {
  const item = props.items.find((option) => option.id === line.itemId)
  if (item) {
    line.uom = item.baseUom
  }
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('调拨至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function parseLotId(value: string): number | null {
  const parsed = Number(value)
  return value.trim() === '' || !Number.isFinite(parsed) || parsed <= 0 ? null : parsed
}

function validate(): string {
  if (!form.companyId || !form.fromWarehouseId || !form.toWarehouseId) {
    return '请选择公司、源仓库与目标仓库'
  }
  if (
    form.fromWarehouseId === form.toWarehouseId &&
    (form.fromLocationId ?? 0) === (form.toLocationId ?? 0)
  ) {
    return '源库位与目标库位不能相同'
  }
  for (const [index, line] of form.lines.entries()) {
    if (!line.itemId) {
      return `第 ${index + 1} 行请选择物料`
    }
    if (line.qty <= 0) {
      return `第 ${index + 1} 行数量必须大于 0`
    }
    if (!line.uom.trim()) {
      return `第 ${index + 1} 行请填写单位`
    }
  }
  return ''
}

async function submit(): Promise<void> {
  const validation = validate()
  if (validation) {
    ElMessage.warning(validation)
    return
  }
  const ok = await runSave(async () => {
    await createTransfer({
      companyId: form.companyId as number,
      fromWarehouseId: form.fromWarehouseId as number,
      fromLocationId: form.fromLocationId ?? null,
      toWarehouseId: form.toWarehouseId as number,
      toLocationId: form.toLocationId ?? null,
      reason: form.reason.trim() || null,
      lines: form.lines.map((line) => ({
        itemId: line.itemId as number,
        lotId: parseLotId(line.lotId),
        qty: line.qty,
        uom: line.uom.trim() || 'EA',
        unitCost: line.unitCost,
        serialIds: [],
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('调拨单已创建')
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
        新建调拨
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
        label="调拨单"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InventoryTransferRow).transferNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="源 → 目标"
        min-width="260"
      >
        <template #default="{ row }">
          {{ warehouseName((row as InventoryTransferRow).fromWarehouseId) }}
          →
          {{ warehouseName((row as InventoryTransferRow).toWarehouseId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as InventoryTransferRow).status)"
          >
            {{ labelOf(TRANSFER_STATUS_LABEL, (row as InventoryTransferRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as InventoryTransferRow).createdAt) }}
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
            @click="openDetail((row as InventoryTransferRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as InventoryTransferRow).status === 'DRAFT'"
            link
            @click="act(row as InventoryTransferRow, 'ship')"
          >
            发运
          </el-button>
          <el-button
            v-if="(row as InventoryTransferRow).status === 'IN_TRANSIT'"
            link
            @click="act(row as InventoryTransferRow, 'receive')"
          >
            接收
          </el-button>
          <el-button
            v-if="['DRAFT', 'IN_TRANSIT'].includes((row as InventoryTransferRow).status)"
            link
            @click="act(row as InventoryTransferRow, 'cancel')"
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
      class="list-pager"
      @change="(target: number) => void load(target)"
      @size-change="() => void load(1)"
    />
  </div>

  <el-drawer
    v-model="detailVisible"
    title="调拨单详情"
    size="680px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="调拨单">
            {{ detail.transferNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(TRANSFER_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="源仓库">
            {{ warehouseName(detail.fromWarehouseId) }}
          </el-descriptions-item>
          <el-descriptions-item label="目标仓库">
            {{ warehouseName(detail.toWarehouseId) }}
          </el-descriptions-item>
          <el-descriptions-item label="发运时间">
            {{ formatDateTime(detail.shippedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="接收时间">
            {{ formatDateTime(detail.receivedAt) }}
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
              {{ formatQty(row.qty) }} {{ row.uom }}
            </template>
          </el-table-column>
          <el-table-column
            label="单位成本"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ formatAmount(row.unitCost) }}
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
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到调拨单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建库存调拨"
    width="920px"
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
          >
            <el-option
              v-for="company in props.companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input
            v-model="form.reason"
            maxlength="255"
            placeholder="可留空"
          />
        </el-form-item>
        <el-form-item label="源仓库">
          <el-select
            v-model="form.fromWarehouseId"
            placeholder="选择源仓库"
            @change="loadFromLocations"
          >
            <el-option
              v-for="warehouse in props.warehouses"
              :key="warehouse.id"
              :label="`${warehouse.code} · ${warehouse.name}`"
              :value="warehouse.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源库位">
          <el-select
            v-model="form.fromLocationId"
            placeholder="默认库位（可空）"
            clearable
          >
            <el-option
              v-for="location in fromLocations"
              :key="location.id"
              :label="`${location.code} · ${location.name}`"
              :value="location.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标仓库">
          <el-select
            v-model="form.toWarehouseId"
            placeholder="选择目标仓库"
            @change="loadToLocations"
          >
            <el-option
              v-for="warehouse in props.warehouses"
              :key="warehouse.id"
              :label="`${warehouse.code} · ${warehouse.name}`"
              :value="warehouse.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标库位">
          <el-select
            v-model="form.toLocationId"
            placeholder="默认库位（可空）"
            clearable
          >
            <el-option
              v-for="location in toLocations"
              :key="location.id"
              :label="`${location.code} · ${location.name}`"
              :value="location.id"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">调拨行</span>
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
          min-width="190"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.itemId"
              placeholder="选择物料"
              filterable
              @change="onItemChange(row as TransferLineForm)"
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
          label="批次 ID"
          width="110"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.lotId"
              placeholder="可空"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="数量"
          width="110"
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
          label="单位成本"
          width="120"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitCost"
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
        创建调拨
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

.detail-table {
  margin-top: 20px;
}
</style>
