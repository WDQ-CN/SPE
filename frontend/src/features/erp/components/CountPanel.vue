<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveCount,
  cancelCount,
  createCount,
  getCount,
  listCounts,
  listLocations,
  postCount,
  recordCounts,
  submitCount,
  type CompanyOption,
  type InventoryCountDetail,
  type InventoryCountRow,
  type ItemOption,
  type LocationOption,
  type WarehouseOption,
} from '@/features/erp/api'
import {
  COUNT_STATUS_LABEL,
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

const filters = reactive<{ companyId?: number; warehouseId?: number; status: string }>({ status: '' })

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

const { rows, total, page, size, loading, error, load } = usePagedList<InventoryCountRow>(
  (targetPage, pageSize) =>
    listCounts({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      warehouseId: filters.warehouseId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(COUNT_STATUS_LABEL)

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.warehouseId = undefined
  filters.status = ''
  void load(1)
}

async function act(
  row: InventoryCountRow,
  action: 'submit' | 'approve' | 'post' | 'cancel',
): Promise<void> {
  const verbs = { submit: '提交', approve: '审批', post: '过账', cancel: '取消' }
  const confirmed = await ElMessageBox.confirm(
    `确定要${verbs[action]}盘点单「${row.countNo}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'submit') {
      await submitCount(row.id)
    } else if (action === 'approve') {
      await approveCount(row.id)
    } else if (action === 'post') {
      await postCount(row.id)
    } else {
      await cancelCount(row.id)
    }
    ElMessage.success(`已${verbs[action]}`)
    await load()
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

const createVisible = ref(false)
const createLocations = ref<LocationOption[]>([])
const createForm = reactive<{
  companyId: number | undefined
  warehouseId: number | undefined
  itemId: number | undefined
  lotId: string
  locationId: number | undefined
  reason: string
}>({
  companyId: undefined,
  warehouseId: undefined,
  itemId: undefined,
  lotId: '',
  locationId: undefined,
  reason: '',
})

const {
  submitting: creating,
  errorMessage: createError,
  run: runCreate,
  clearError: clearCreateError,
} = useSubmit()

async function loadCreateLocations(warehouseId: number | undefined): Promise<void> {
  createForm.locationId = undefined
  createLocations.value = warehouseId
    ? await listLocations(warehouseId).catch(() => [])
    : []
}

function openCreate(): void {
  createForm.companyId = filters.companyId ?? props.companies[0]?.id
  createForm.warehouseId = filters.warehouseId ?? props.warehouses[0]?.id
  createForm.itemId = undefined
  createForm.lotId = ''
  createForm.reason = ''
  createLocations.value = []
  void loadCreateLocations(createForm.warehouseId)
  clearCreateError()
  createVisible.value = true
}

function parseOptionalId(value: string): number | null {
  const parsed = Number(value)
  return value.trim() === '' || !Number.isFinite(parsed) || parsed <= 0 ? null : parsed
}

async function submitCreate(): Promise<void> {
  if (!createForm.companyId || !createForm.warehouseId) {
    ElMessage.warning('请选择公司与仓库')
    return
  }
  const ok = await runCreate(async () => {
    const created = await createCount({
      companyId: createForm.companyId as number,
      warehouseId: createForm.warehouseId as number,
      itemId: createForm.itemId ?? null,
      lotId: parseOptionalId(createForm.lotId),
      locationId: createForm.locationId ?? null,
      reason: createForm.reason.trim() || null,
    })
    createVisible.value = false
    ElMessage.success('盘点单已创建')
    await openRecord(created.id)
    await load(1)
  })
  if (!ok) {
    // 错误已回显在对话框
  }
}

// ---- 实盘录入 ----

interface CountLineForm {
  lineId: number
  itemId: number
  snapshotQty: number
  countedQty: number
  reason: string
  locationId: number
}

const recordVisible = ref(false)
const recordLoading = ref(false)
const recordDetail = ref<InventoryCountDetail | null>(null)
const recordLines = ref<CountLineForm[]>([])
const recordEditable = computed(
  () =>
    recordDetail.value !== null &&
    ['DRAFT', 'COUNTING'].includes(recordDetail.value.status),
)
const {
  submitting: recording,
  errorMessage: recordError,
  run: runRecord,
  clearError: clearRecordError,
} = useSubmit()

async function openRecord(id: number): Promise<void> {
  recordLoading.value = true
  recordVisible.value = true
  clearRecordError()
  try {
    const detail = await getCount(id)
    recordDetail.value = detail
    recordLines.value = detail.lines.map((line) => ({
      lineId: line.id,
      itemId: line.itemId,
      locationId: line.locationId,
      snapshotQty: line.snapshotQty,
      countedQty: line.countedQty ?? line.snapshotQty,
      reason: line.reason ?? '',
    }))
  } catch {
    recordDetail.value = null
    recordLines.value = []
  } finally {
    recordLoading.value = false
  }
}

function varianceOf(line: CountLineForm): number {
  return Number((line.countedQty - line.snapshotQty).toFixed(6))
}

async function saveRecord(): Promise<void> {
  if (!recordDetail.value) {
    return
  }
  const ok = await runRecord(async () => {
    await recordCounts(
      recordDetail.value!.id,
      recordLines.value.map((line) => ({
        lineId: line.lineId,
        countedQty: line.countedQty,
        reason: line.reason.trim() || null,
      })),
    )
  })
  if (ok) {
    ElMessage.success('实盘数据已保存')
    recordDetail.value = await getCount(recordDetail.value.id)
    await load()
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
        v-model="filters.warehouseId"
        class="list-toolbar__select"
        placeholder="仓库"
        clearable
        aria-label="仓库"
        @change="search"
      >
        <el-option
          v-for="warehouse in props.warehouses"
          :key="warehouse.id"
          :label="`${warehouse.code} · ${warehouse.name}`"
          :value="warehouse.id"
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
        新建盘点
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
        label="盘点单"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InventoryCountRow).countNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="仓库"
        min-width="160"
      >
        <template #default="{ row }">
          {{ warehouseName((row as InventoryCountRow).warehouseId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as InventoryCountRow).status)"
          >
            {{ labelOf(COUNT_STATUS_LABEL, (row as InventoryCountRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="快照时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as InventoryCountRow).snapshotAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as InventoryCountRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="230"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openRecord((row as InventoryCountRow).id)"
          >
            {{ ['DRAFT', 'COUNTING'].includes((row as InventoryCountRow).status) ? '实盘' : '查看' }}
          </el-button>
          <el-button
            v-if="['DRAFT', 'COUNTING'].includes((row as InventoryCountRow).status)"
            link
            @click="act(row as InventoryCountRow, 'submit')"
          >
            提交
          </el-button>
          <el-button
            v-if="(row as InventoryCountRow).status === 'REVIEW'"
            link
            @click="act(row as InventoryCountRow, 'approve')"
          >
            审批
          </el-button>
          <el-button
            v-if="(row as InventoryCountRow).status === 'APPROVED'"
            link
            @click="act(row as InventoryCountRow, 'post')"
          >
            过账
          </el-button>
          <el-button
            v-if="!['POSTED', 'CANCELED'].includes((row as InventoryCountRow).status)"
            link
            @click="act(row as InventoryCountRow, 'cancel')"
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

  <el-dialog
    v-model="createVisible"
    title="新建库存盘点"
    width="520px"
  >
    <el-alert
      v-if="createError"
      class="page-alert"
      type="error"
      :title="createError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="公司">
        <el-select
          v-model="createForm.companyId"
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
          v-model="createForm.warehouseId"
          placeholder="选择仓库"
          @change="loadCreateLocations"
        >
          <el-option
            v-for="warehouse in props.warehouses"
            :key="warehouse.id"
            :label="`${warehouse.code} · ${warehouse.name}`"
            :value="warehouse.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-select
          v-model="createForm.itemId"
          placeholder="全部物料（可空）"
          clearable
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
          v-model="createForm.lotId"
          placeholder="可留空"
        />
      </el-form-item>
      <el-form-item label="库位">
        <el-select
          v-model="createForm.locationId"
          placeholder="全部库位（可空）"
          clearable
        >
          <el-option
            v-for="location in createLocations"
            :key="location.id"
            :label="`${location.code} · ${location.name}`"
            :value="location.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="原因">
        <el-input
          v-model="createForm.reason"
          maxlength="255"
          placeholder="可留空"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="creating"
        @click="submitCreate"
      >
        创建盘点
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="recordVisible"
    :title="recordEditable ? '录入实盘' : '盘点详情'"
    width="900px"
  >
    <div v-loading="recordLoading">
      <el-alert
        v-if="recordError"
        class="page-alert"
        type="error"
        :title="recordError"
        :closable="false"
        show-icon
      />
      <template v-if="recordDetail">
        <p class="dialog-lede">
          {{ recordDetail.countNo }} · {{ warehouseName(recordDetail.warehouseId) }} ·
          {{ labelOf(COUNT_STATUS_LABEL, recordDetail.status) }}
        </p>
        <el-table
          :data="recordLines"
          class="table-list"
        >
          <el-table-column
            label="物料"
            min-width="160"
          >
            <template #default="{ row }">
              {{ itemName(row.itemId) }}
            </template>
          </el-table-column>
          <el-table-column
            label="快照数量"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.snapshotQty) }}
            </template>
          </el-table-column>
          <el-table-column
            label="实盘数量"
            width="140"
          >
            <template #default="{ row }">
              <el-input-number
                v-if="recordEditable"
                v-model="row.countedQty"
                :min="0"
                :controls="false"
                class="line-number"
              />
              <span v-else>{{ formatQty(row.countedQty) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="差异"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(varianceOf(row as CountLineForm)) }}
            </template>
          </el-table-column>
          <el-table-column
            label="差异原因"
            min-width="170"
          >
            <template #default="{ row }">
              <el-input
                v-if="recordEditable"
                v-model="row.reason"
                maxlength="255"
                placeholder="有差异时必填"
              />
              <span v-else>{{ row.reason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!recordLoading"
        description="未找到盘点单"
      />
    </div>
    <template #footer>
      <el-button @click="recordVisible = false">
        关闭
      </el-button>
      <el-button
        v-if="recordEditable"
        type="primary"
        :loading="recording"
        @click="saveRecord"
      >
        保存实盘
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

.dialog-lede {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
}
</style>
