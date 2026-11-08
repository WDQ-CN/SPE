<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  closeWorkOrder,
  createWorkOrder,
  getWorkOrder,
  issueWorkOrder,
  listBoms,
  listCompanies,
  listItems,
  listPlants,
  listWarehouses,
  listWorkOrders,
  receiptWorkOrder,
  releaseWorkOrder,
  type BomOption,
  type CompanyOption,
  type ItemOption,
  type PlantOption,
  type WarehouseOption,
  type WorkOrderDetail,
  type WorkOrderRow,
} from '@/features/erp/api'
import {
  WORK_ORDER_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const router = useRouter()
const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const companies = ref<CompanyOption[]>([])
const plants = ref<PlantOption[]>([])
const items = ref<ItemOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const boms = ref<BomOption[]>([])

const itemNames = computed(
  () => new Map(items.value.map((item) => [item.id, `${item.code} · ${item.name}`])),
)

const { rows, total, page, size, loading, error, load } = usePagedList<WorkOrderRow>(
  (targetPage, pageSize) =>
    listWorkOrders({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(WORK_ORDER_STATUS_LABEL)

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

async function loadOptions(): Promise<void> {
  try {
    const [companyList, plantList, itemList, warehouseList] = await Promise.all([
      listCompanies(),
      listPlants(),
      listItems(),
      listWarehouses(),
    ])
    companies.value = companyList
    plants.value = plantList
    items.value = itemList
    warehouses.value = warehouseList
    if (!filters.companyId && companyList.length > 0) {
      filters.companyId = companyList[0].id
    }
  } catch {
    // 主数据不可用时保留空选项
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

// ---- 新建工单 ----

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  plantId: number | undefined
  itemId: number | undefined
  bomId: number | undefined
  qty: number
}>({ companyId: undefined, plantId: undefined, itemId: undefined, bomId: undefined, qty: 1 })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const formPlants = computed(() =>
  plants.value.filter((plant) => plant.companyId === form.companyId),
)
const formBoms = computed(() =>
  boms.value.filter(
    (bom) =>
      bom.companyId === form.companyId &&
      bom.status === 'ACTIVE' &&
      (!form.itemId || bom.itemId === form.itemId),
  ),
)

function onFormItemChange(): void {
  form.bomId = undefined
}

async function loadBoms(companyId: number): Promise<void> {
  try {
    boms.value = await listBoms(companyId)
  } catch {
    boms.value = []
  }
}

async function openCreate(): Promise<void> {
  form.companyId = filters.companyId ?? companies.value[0]?.id
  form.plantId = undefined
  form.itemId = undefined
  form.bomId = undefined
  form.qty = 1
  boms.value = []
  if (form.companyId) {
    await loadBoms(form.companyId)
  }
  clearError()
  createVisible.value = true
}

async function onCompanyChange(): Promise<void> {
  form.plantId = undefined
  form.bomId = undefined
  if (form.companyId) {
    await loadBoms(form.companyId)
  } else {
    boms.value = []
  }
}

async function submitCreate(): Promise<void> {
  if (!form.companyId || !form.plantId || !form.itemId || !form.bomId) {
    ElMessage.warning('请完整选择公司、工厂、产出物料与 BOM')
    return
  }
  if (form.qty <= 0) {
    ElMessage.warning('计划数量必须大于 0')
    return
  }
  const ok = await runSave(async () => {
    await createWorkOrder({
      companyId: form.companyId as number,
      plantId: form.plantId as number,
      itemId: form.itemId as number,
      bomId: form.bomId as number,
      qty: form.qty,
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('生产工单已创建')
    await load(1)
  }
}

// ---- 状态动作 / 完工入库 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<WorkOrderDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getWorkOrder(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function release(row: WorkOrderRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定下达工单「${row.orderNo}」吗？`, '下达', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await releaseWorkOrder(row.id)
    ElMessage.success('已下达')
    await afterAction(row.id)
  } catch {
    // http 拦截器已提示
  }
}

async function close(row: WorkOrderRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定关闭工单「${row.orderNo}」吗？`, '关闭', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await closeWorkOrder(row.id)
    ElMessage.success('已关闭')
    await afterAction(row.id)
  } catch {
    // http 拦截器已提示
  }
}

const receiptVisible = ref(false)
const receiptForm = reactive<{
  workOrderId: number
  warehouseId: number | undefined
  lotNo: string
  qty: number
}>({ workOrderId: 0, warehouseId: undefined, lotNo: '', qty: 0 })

const { submitting: receipting, run: runReceipt, errorMessage: receiptError, clearError: clearReceiptError } =
  useSubmit()

function openReceipt(row: WorkOrderRow): void {
  receiptForm.workOrderId = row.id
  receiptForm.warehouseId = warehouses.value[0]?.id
  receiptForm.lotNo = ''
  receiptForm.qty = Math.max(row.qty - (row.receivedQty ?? 0), 0)
  clearReceiptError()
  receiptVisible.value = true
}

async function submitReceipt(): Promise<void> {
  if (!receiptForm.warehouseId) {
    ElMessage.warning('请选择完工入库仓库')
    return
  }
  if (receiptForm.qty <= 0) {
    ElMessage.warning('完工数量必须大于 0')
    return
  }
  const ok = await runReceipt(async () => {
    await receiptWorkOrder(receiptForm.workOrderId, {
      warehouseId: receiptForm.warehouseId as number,
      locationId: null,
      lotNo: receiptForm.lotNo.trim() || null,
      qty: receiptForm.qty,
    })
  })
  if (ok) {
    receiptVisible.value = false
    ElMessage.success('完工入库已登记')
    await afterAction(receiptForm.workOrderId)
  }
}

async function afterAction(id: number): Promise<void> {
  await load()
  if (detail.value?.id === id) {
    detail.value = await getWorkOrder(id)
  }
}

// ---- 领料 ----

interface IssueLineForm {
  selected: boolean
  componentId: number
  label: string
  remaining: number
  qty: number
  lotNo: string
}

const issueVisible = ref(false)
const issueLoading = ref(false)
const issueSaving = ref(false)
const issueError = ref('')
const issueOrderId = ref(0)
const issueCompanyId = ref<number | undefined>(undefined)
const issueWarehouseId = ref<number | undefined>(undefined)
const issueLines = ref<IssueLineForm[]>([])

const issueWarehouses = computed(() => {
  const plantIds = new Set(
    plants.value
      .filter((plant) => plant.companyId === issueCompanyId.value)
      .map((plant) => plant.id),
  )
  return warehouses.value.filter(
    (warehouse) => plantIds.has(warehouse.plantId) && warehouse.status === 'ACTIVE',
  )
})

async function openIssue(row: WorkOrderRow): Promise<void> {
  issueVisible.value = true
  issueLoading.value = true
  issueError.value = ''
  issueOrderId.value = row.id
  issueCompanyId.value = row.companyId
  try {
    const data = await getWorkOrder(row.id)
    issueLines.value = data.components.map((component) => {
      const remaining = Math.max(Number(component.qtyRequired) - Number(component.issuedQty), 0)
      return {
        selected: remaining > 0,
        componentId: component.id,
        label: `${component.componentName} (${component.componentCode})`,
        remaining,
        qty: remaining,
        lotNo: '',
      }
    })
    issueWarehouseId.value = issueWarehouses.value[0]?.id
  } catch (caught) {
    issueError.value = caught instanceof Error ? caught.message : '工单组件加载失败'
  } finally {
    issueLoading.value = false
  }
}

async function submitIssue(): Promise<void> {
  const selected = issueLines.value.filter((line) => line.selected)
  if (!issueWarehouseId.value) {
    ElMessage.warning('请选择领料仓库')
    return
  }
  if (selected.length === 0) {
    ElMessage.warning('请至少选择一行领料')
    return
  }
  for (const line of selected) {
    if (line.qty <= 0 || line.qty > line.remaining) {
      ElMessage.warning(`组件 ${line.label} 的领料数量必须在 0 与剩余需求之间`)
      return
    }
  }
  issueSaving.value = true
  issueError.value = ''
  try {
    await issueWorkOrder(issueOrderId.value, {
      warehouseId: issueWarehouseId.value,
      lines: selected.map((line) => ({
        componentId: line.componentId,
        qty: line.qty,
        lotNo: line.lotNo.trim() || null,
        locationId: null,
      })),
    })
    issueVisible.value = false
    ElMessage.success('领料已过账')
    await afterAction(issueOrderId.value)
  } catch (caught) {
    issueError.value = caught instanceof Error ? caught.message : '领料失败'
  } finally {
    issueSaving.value = false
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / MANUFACTURING"
    title="生产工单"
    :description="`共 ${total} 张工单 · 下达后领料，完工入库并结转材料成本`"
  >
    <template #actions>
      <el-button @click="router.push('/manufacturing/boms')">
        BOM 维护
      </el-button>
      <el-button
        type="primary"
        @click="openCreate"
      >
        新建生产工单
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
        label="工单号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as WorkOrderRow).orderNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="产出物料"
        min-width="220"
      >
        <template #default="{ row }">
          {{ itemName((row as WorkOrderRow).itemId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as WorkOrderRow).status)"
          >
            {{ labelOf(WORK_ORDER_STATUS_LABEL, (row as WorkOrderRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="计划 / 完工"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatQty((row as WorkOrderRow).qty) }} / {{ formatQty((row as WorkOrderRow).receivedQty) }}
        </template>
      </el-table-column>
      <el-table-column
        label="材料成本"
        align="right"
        min-width="130"
      >
        <template #default="{ row }">
          {{ formatAmount((row as WorkOrderRow).materialCost) }}
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as WorkOrderRow).createdAt) }}
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
            @click="openDetail((row as WorkOrderRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as WorkOrderRow).status === 'CREATED'"
            link
            @click="release(row as WorkOrderRow)"
          >
            下达
          </el-button>
          <el-button
            v-if="['RELEASED', 'IN_PROGRESS'].includes((row as WorkOrderRow).status)"
            link
            @click="openIssue(row as WorkOrderRow)"
          >
            领料
          </el-button>
          <el-button
            v-if="['RELEASED', 'IN_PROGRESS'].includes((row as WorkOrderRow).status)"
            link
            @click="openReceipt(row as WorkOrderRow)"
          >
            完工入库
          </el-button>
          <el-button
            v-if="(row as WorkOrderRow).status === 'COMPLETED'"
            link
            @click="close(row as WorkOrderRow)"
          >
            关闭
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
    title="工单详情"
    size="760px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="工单号">
            {{ detail.orderNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(WORK_ORDER_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="产出物料">
            {{ detail.itemName }}
          </el-descriptions-item>
          <el-descriptions-item label="计划数量">
            {{ formatQty(detail.qty) }}
          </el-descriptions-item>
          <el-descriptions-item label="已完工">
            {{ formatQty(detail.receivedQty) }}
          </el-descriptions-item>
          <el-descriptions-item label="材料成本">
            {{ formatAmount(detail.materialCost) }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          组件需求
        </h3>
        <el-table
          :data="detail.components"
          class="table-list"
        >
          <el-table-column
            prop="lineNo"
            label="#"
            width="50"
          />
          <el-table-column
            label="组件"
            min-width="180"
          >
            <template #default="{ row }">
              {{ row.componentName }} <span class="mono">{{ row.componentCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="需求"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.qtyRequired) }} {{ row.uom }}
            </template>
          </el-table-column>
          <el-table-column
            label="已领"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.issuedQty) }}
            </template>
          </el-table-column>
        </el-table>

        <h3 class="detail-title">
          领料记录
        </h3>
        <el-table
          :data="detail.issues"
          class="table-list"
          empty-text="暂无领料"
        >
          <el-table-column
            prop="lotNo"
            label="批次"
            min-width="120"
          />
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
            label="单位成本"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.unitCost) }}
            </template>
          </el-table-column>
        </el-table>

        <h3 class="detail-title">
          完工入库
        </h3>
        <el-table
          :data="detail.receipts"
          class="table-list"
          empty-text="暂无完工记录"
        >
          <el-table-column
            prop="receiptNo"
            label="入库单"
            min-width="150"
          />
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
            label="金额"
            align="right"
            width="140"
          >
            <template #default="{ row }">
              {{ formatAmount(row.amount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="时间"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatDateTime(row.createdAt) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到工单"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建生产工单"
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
    <el-form label-width="96px">
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
      <el-form-item label="工厂">
        <el-select
          v-model="form.plantId"
          placeholder="选择工厂"
        >
          <el-option
            v-for="plant in formPlants"
            :key="plant.id"
            :label="`${plant.code} · ${plant.name}`"
            :value="plant.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="产出物料">
        <el-select
          v-model="form.itemId"
          placeholder="选择物料"
          filterable
          @change="onFormItemChange"
        >
          <el-option
            v-for="item in items"
            :key="item.id"
            :label="`${item.code} · ${item.name}`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="BOM">
        <el-select
          v-model="form.bomId"
          placeholder="选择已生效 BOM"
          filterable
        >
          <el-option
            v-for="bom in formBoms"
            :key="bom.id"
            :label="`${bom.bomNo} · v${bom.version}`"
            :value="bom.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="计划数量">
        <el-input-number
          v-model="form.qty"
          :min="0.000001"
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
        @click="submitCreate"
      >
        创建工单
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="receiptVisible"
    title="完工入库"
    width="480px"
  >
    <el-alert
      v-if="receiptError"
      class="page-alert"
      type="error"
      :title="receiptError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="仓库">
        <el-select
          v-model="receiptForm.warehouseId"
          placeholder="选择仓库"
        >
          <el-option
            v-for="warehouse in warehouses"
            :key="warehouse.id"
            :label="`${warehouse.code} · ${warehouse.name}`"
            :value="warehouse.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="批次号">
        <el-input
          v-model="receiptForm.lotNo"
          maxlength="64"
          placeholder="可留空"
        />
      </el-form-item>
      <el-form-item label="完工数量">
        <el-input-number
          v-model="receiptForm.qty"
          :min="0.000001"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="receiptVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="receipting"
        @click="submitReceipt"
      >
        确认入库
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="issueVisible"
    title="工单领料"
    width="760px"
  >
    <div v-loading="issueLoading">
      <el-alert
        v-if="issueError"
        class="page-alert"
        type="error"
        :title="issueError"
        :closable="false"
        show-icon
      />
      <el-form label-width="84px">
        <el-form-item label="领料仓库">
          <el-select
            v-model="issueWarehouseId"
            placeholder="选择仓库"
          >
            <el-option
              v-for="warehouse in issueWarehouses"
              :key="warehouse.id"
              :label="`${warehouse.code} · ${warehouse.name}`"
              :value="warehouse.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table
        :data="issueLines"
        class="table-list"
      >
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox
              v-model="(row as IssueLineForm).selected"
              :disabled="(row as IssueLineForm).remaining <= 0"
            />
          </template>
        </el-table-column>
        <el-table-column
          prop="label"
          label="组件"
          min-width="220"
        />
        <el-table-column
          label="剩余需求"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatQty((row as IssueLineForm).remaining) }}
          </template>
        </el-table-column>
        <el-table-column
          label="领料数量"
          width="130"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="(row as IssueLineForm).qty"
              :min="0.000001"
              :max="(row as IssueLineForm).remaining"
              :controls="false"
              class="line-number"
              :disabled="!(row as IssueLineForm).selected"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="批次号"
          width="140"
        >
          <template #default="{ row }">
            <el-input
              v-model="(row as IssueLineForm).lotNo"
              maxlength="64"
              placeholder="可留空"
              :disabled="!(row as IssueLineForm).selected"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>
    <template #footer>
      <el-button @click="issueVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="issueSaving"
        @click="submitIssue"
      >
        确认领料
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.line-number {
  width: 100%;
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
