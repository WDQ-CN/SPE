<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  consumeReservation,
  createFreeze,
  createReservation,
  expireReservations,
  listFreezes,
  listLocations,
  listReservations,
  releaseFreeze,
  releaseReservation,
  type CompanyOption,
  type InventoryFreezeRow,
  type InventoryReservationRow,
  type ItemOption,
  type LocationOption,
  type WarehouseOption,
} from '@/features/erp/api'
import {
  FREEZE_STATUS_LABEL,
  RESERVATION_STATUS_LABEL,
  formatDateTime,
  formatQty,
  labelOf,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{
  companies: CompanyOption[]
  warehouses: WarehouseOption[]
  items: ItemOption[]
}>()

type Mode = 'reservation' | 'freeze'
type AllocationRow = InventoryReservationRow | InventoryFreezeRow

const mode = ref<Mode>('reservation')
const filters = reactive<{ warehouseId?: number; itemId?: number; status: string }>({ status: '' })

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

const reservationList = usePagedList<InventoryReservationRow>((page, size) =>
  listReservations({
    page,
    size,
    warehouseId: filters.warehouseId,
    itemId: filters.itemId,
    status: filters.status || undefined,
  }),
)

const freezeList = usePagedList<InventoryFreezeRow>((page, size) =>
  listFreezes({
    page,
    size,
    warehouseId: filters.warehouseId,
    itemId: filters.itemId,
    status: filters.status || undefined,
  }),
)

const active = computed(() => (mode.value === 'reservation' ? reservationList : freezeList))
const rows = computed<AllocationRow[]>(() => active.value.rows.value as AllocationRow[])
const currentPage = computed({
  get: () => active.value.page.value,
  set: (value: number) => {
    active.value.page.value = value
  },
})
const currentSize = computed({
  get: () => active.value.size.value,
  set: (value: number) => {
    active.value.size.value = value
  },
})
const currentTotal = computed(() => active.value.total.value)
const currentLoading = computed(() => active.value.loading.value)
const currentError = computed(() => active.value.error.value)
const statusLabel = computed(() =>
  mode.value === 'reservation' ? RESERVATION_STATUS_LABEL : FREEZE_STATUS_LABEL,
)

const statusOptions = computed(() => Object.entries(statusLabel.value))

function activeTone(status: string): string {
  return status === 'ACTIVE' ? 'is-warning' : 'is-off'
}

function reload(): void {
  void active.value.load(1)
}

function loadActive(target: number): void {
  void active.value.load(target)
}

function switchMode(next: Mode): void {
  mode.value = next
  filters.status = ''
  reload()
}

onMounted(() => {
  void reservationList.load(1)
  void freezeList.load(1)
})

// ---- 创建表单 ----

const locations = ref<LocationOption[]>([])
const formVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  warehouseId: number | undefined
  locationId: number | undefined
  itemId: number | undefined
  lotId: string
  qty: number
  sourceType: string
  sourceId: number
  expiresAt: string
  reason: string
}>({
  companyId: undefined,
  warehouseId: undefined,
  locationId: undefined,
  itemId: undefined,
  lotId: '',
  qty: 1,
  sourceType: 'MANUAL',
  sourceId: 0,
  expiresAt: '',
  reason: '',
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

async function loadLocations(warehouseId: number | undefined): Promise<void> {
  form.locationId = undefined
  if (!warehouseId) {
    locations.value = []
    return
  }
  try {
    locations.value = await listLocations(warehouseId)
  } catch {
    locations.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = props.companies[0]?.id
  form.warehouseId = filters.warehouseId ?? props.warehouses[0]?.id
  form.itemId = filters.itemId
  form.lotId = ''
  form.qty = 1
  form.sourceType = 'MANUAL'
  form.sourceId = 0
  form.expiresAt = ''
  form.reason = ''
  await loadLocations(form.warehouseId)
  clearError()
  formVisible.value = true
}

function parseLotId(): number | null {
  const value = Number(form.lotId)
  return form.lotId.trim() === '' || !Number.isFinite(value) || value <= 0 ? null : value
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.warehouseId || !form.itemId) {
    ElMessage.warning('请选择公司、仓库与物料')
    return
  }
  if (form.qty <= 0) {
    ElMessage.warning('数量必须大于 0')
    return
  }
  if (mode.value === 'freeze' && !form.reason.trim()) {
    ElMessage.warning('请填写冻结原因')
    return
  }
  const ok = await runSave(async () => {
    if (mode.value === 'reservation') {
      await createReservation({
        companyId: form.companyId as number,
        warehouseId: form.warehouseId as number,
        locationId: form.locationId ?? null,
        itemId: form.itemId as number,
        lotId: parseLotId(),
        serialId: null,
        qty: form.qty,
        sourceType: form.sourceType.trim() || 'MANUAL',
        sourceId: form.sourceId,
        sourceLineId: null,
        expiresAt: form.expiresAt || null,
      })
    } else {
      await createFreeze({
        companyId: form.companyId as number,
        warehouseId: form.warehouseId as number,
        locationId: form.locationId ?? null,
        itemId: form.itemId as number,
        lotId: parseLotId(),
        serialId: null,
        qty: form.qty,
        reason: form.reason.trim(),
        sourceType: form.sourceType.trim() || 'MANUAL',
        sourceId: form.sourceId,
      })
    }
  })
  if (ok) {
    formVisible.value = false
    ElMessage.success(mode.value === 'reservation' ? '预留已创建' : '冻结已创建')
    reload()
  }
}

// ---- 动作 ----

async function release(row: AllocationRow): Promise<void> {
  const tip = mode.value === 'reservation' ? '释放' : '解冻'
  const confirmed = await ElMessageBox.confirm(`确定${tip}这条记录吗？`, '提示', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (mode.value === 'reservation') {
      await releaseReservation(row.id)
    } else {
      await releaseFreeze(row.id)
    }
    ElMessage.success(`已${tip}`)
    reload()
  } catch {
    // http 拦截器已提示
  }
}

async function consume(row: InventoryReservationRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm('确定将这条预留标记为已消耗吗？', '提示', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await consumeReservation(row.id)
    ElMessage.success('已消耗')
    reload()
  } catch {
    // http 拦截器已提示
  }
}

async function expire(): Promise<void> {
  try {
    const count = await expireReservations()
    ElMessage.success(`已过期释放 ${count} 条预留`)
    reload()
  } catch {
    // http 拦截器已提示
  }
}
</script>

<template>
  <div>
    <div class="operation-switch">
      <el-radio-group
        :model-value="mode"
        @update:model-value="(value) => switchMode(value as Mode)"
      >
        <el-radio-button value="reservation">
          预留
        </el-radio-button>
        <el-radio-button value="freeze">
          冻结
        </el-radio-button>
      </el-radio-group>
      <el-button
        type="primary"
        @click="openCreate"
      >
        {{ mode === 'reservation' ? '新建预留' : '新建冻结' }}
      </el-button>
      <el-button
        v-if="mode === 'reservation'"
        @click="expire"
      >
        过期释放
      </el-button>
    </div>

    <form
      class="list-toolbar"
      @submit.prevent="reload"
    >
      <el-select
        v-model="filters.warehouseId"
        class="list-toolbar__select"
        placeholder="仓库"
        clearable
        aria-label="仓库"
        @change="reload"
      >
        <el-option
          v-for="warehouse in props.warehouses"
          :key="warehouse.id"
          :label="`${warehouse.code} · ${warehouse.name}`"
          :value="warehouse.id"
        />
      </el-select>
      <el-select
        v-model="filters.itemId"
        class="list-toolbar__select"
        placeholder="物料"
        clearable
        filterable
        aria-label="物料"
        @change="reload"
      >
        <el-option
          v-for="item in props.items"
          :key="item.id"
          :label="`${item.code} · ${item.name}`"
          :value="item.id"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        class="list-toolbar__select"
        placeholder="状态"
        clearable
        aria-label="状态"
        @change="reload"
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
        @click="reload"
      >
        查询
      </el-button>
    </form>

    <el-alert
      v-if="currentError"
      class="page-alert"
      type="error"
      :title="currentError"
      :closable="false"
      show-icon
    />
    <el-table
      v-loading="currentLoading"
      :data="rows"
      class="table-list"
    >
      <el-table-column
        label="物料"
        min-width="180"
      >
        <template #default="{ row }">
          {{ itemName((row as AllocationRow).itemId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="仓库 / 库位"
        min-width="170"
      >
        <template #default="{ row }">
          {{ warehouseName((row as AllocationRow).warehouseId) }}
          <span class="mono">
            {{ (row as AllocationRow).locationId ? `#${(row as AllocationRow).locationId}` : '默认库位' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="数量"
        align="right"
        width="110"
      >
        <template #default="{ row }">
          {{ formatQty((row as AllocationRow).qty) }}
        </template>
      </el-table-column>
      <el-table-column
        label="来源"
        min-width="130"
      >
        <template #default="{ row }">
          {{ (row as AllocationRow).sourceType }} #{{ (row as AllocationRow).sourceId }}
        </template>
      </el-table-column>
      <el-table-column
        v-if="mode === 'reservation'"
        label="到期时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as InventoryReservationRow).expiresAt) }}
        </template>
      </el-table-column>
      <el-table-column
        v-else
        label="冻结原因"
        min-width="180"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ (row as InventoryFreezeRow).reason }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="activeTone((row as AllocationRow).status)"
          >
            {{ labelOf(statusLabel, (row as AllocationRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="150"
        align="right"
      >
        <template #default="{ row }">
          <template v-if="(row as AllocationRow).status === 'ACTIVE'">
            <el-button
              v-if="mode === 'reservation'"
              link
              @click="consume(row as InventoryReservationRow)"
            >
              消耗
            </el-button>
            <el-button
              link
              @click="release(row as AllocationRow)"
            >
              {{ mode === 'reservation' ? '释放' : '解冻' }}
            </el-button>
          </template>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="currentPage"
      v-model:size="currentSize"
      :total="currentTotal"
      class="list-pager"
      @change="loadActive"
      @size-change="() => loadActive(1)"
    />
  </div>

  <el-dialog
    v-model="formVisible"
    :title="mode === 'reservation' ? '新建库存预留' : '新建库存冻结'"
    width="560px"
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
      <el-form-item label="仓库">
        <el-select
          v-model="form.warehouseId"
          placeholder="选择仓库"
          @change="loadLocations"
        >
          <el-option
            v-for="warehouse in props.warehouses"
            :key="warehouse.id"
            :label="`${warehouse.code} · ${warehouse.name}`"
            :value="warehouse.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="库位">
        <el-select
          v-model="form.locationId"
          placeholder="默认库位（可空）"
          clearable
        >
          <el-option
            v-for="location in locations"
            :key="location.id"
            :label="`${location.code} · ${location.name}`"
            :value="location.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-select
          v-model="form.itemId"
          placeholder="选择物料"
          filterable
        >
          <el-option
            v-for="item in props.items"
            :key="item.id"
            :label="`${item.code} · ${item.name}`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="批次 ID">
        <el-input
          v-model="form.lotId"
          placeholder="可留空"
        />
      </el-form-item>
      <el-form-item label="数量">
        <el-input-number
          v-model="form.qty"
          :min="0.000001"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
      <el-form-item label="来源类型">
        <el-input
          v-model="form.sourceType"
          maxlength="32"
        />
      </el-form-item>
      <el-form-item label="来源单据">
        <el-input-number
          v-model="form.sourceId"
          :min="0"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
      <el-form-item
        v-if="mode === 'reservation'"
        label="到期时间"
      >
        <el-date-picker
          v-model="form.expiresAt"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="可留空"
          class="line-number"
        />
      </el-form-item>
      <el-form-item
        v-else
        label="冻结原因"
      >
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="2"
          maxlength="255"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.operation-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.line-number {
  width: 100%;
}
</style>
