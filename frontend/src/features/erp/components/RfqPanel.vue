<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveAward,
  awardToPo,
  cancelRfq,
  closeRfq,
  createAward,
  createRfq,
  getRfq,
  inviteRfq,
  listRfqAwards,
  listRfqQuotes,
  listRfqs,
  listSuppliers,
  rfqComparison,
  sendRfq,
  type Award,
  type CompanyOption,
  type Comparison,
  type ItemOption,
  type PlantOption,
  type Quote,
  type RfqDetail,
  type RfqRow,
} from '@/features/erp/api'
import {
  AWARD_POLICY_LABEL,
  AWARD_STATUS_LABEL,
  INVITATION_STATUS_LABEL,
  QUOTE_STATUS_LABEL,
  RFQ_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const props = defineProps<{
  companies: CompanyOption[]
  plants: PlantOption[]
  items: ItemOption[]
}>()

const filters = reactive<{ companyId?: number; status: string; keyword: string }>({
  status: '',
  keyword: '',
})

const suppliers = ref<SupplierSummary[]>([])
const supplierNames = computed(
  () => new Map(suppliers.value.map((supplier) => [supplier.id, supplier.name])),
)
const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))

function supplierName(id: number | null): string {
  return id ? supplierNames.value.get(id) ?? `#${id}` : '—'
}

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<RfqRow>(
  (targetPage, pageSize) =>
    listRfqs({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    }),
)

const statusOptions = Object.entries(RFQ_STATUS_LABEL)
const policyOptions = Object.entries(AWARD_POLICY_LABEL)

onMounted(async () => {
  filters.companyId = props.companies[0]?.id
  try {
    suppliers.value = await listSuppliers()
  } catch {
    suppliers.value = []
  }
  await load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  filters.companyId = props.companies[0]?.id
  filters.status = ''
  filters.keyword = ''
  void load(1)
}

// ---- 详情 / 状态动作 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RfqDetail | null>(null)
const awards = ref<Award[]>([])

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getRfq(id)
    awards.value = await listRfqAwards(id).catch(() => [])
  } catch {
    detail.value = null
    awards.value = []
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail(id: number): Promise<void> {
  detail.value = await getRfq(id)
  awards.value = await listRfqAwards(id).catch(() => [])
}

async function act(row: RfqRow, action: 'send' | 'close' | 'cancel'): Promise<void> {
  const verbs = { send: '发送', close: '截止', cancel: '取消' }
  const confirmed = await ElMessageBox.confirm(`确定要${verbs[action]}「${row.rfqNo}」吗？`, '提示', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'send') {
      await sendRfq(row.id)
    } else if (action === 'close') {
      await closeRfq(row.id)
    } else {
      await cancelRfq(row.id)
    }
    ElMessage.success(`已${verbs[action]}`)
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 邀请 ----

const inviteVisible = ref(false)
const inviteForm = reactive<{ rfqId: number; rfqNo: string; supplierIds: number[] }>({
  rfqId: 0,
  rfqNo: '',
  supplierIds: [],
})
const { submitting: inviting, errorMessage: inviteError, run: runInvite, clearError: clearInviteError } =
  useSubmit()

function openInvite(row: RfqRow): void {
  inviteForm.rfqId = row.id
  inviteForm.rfqNo = row.rfqNo
  inviteForm.supplierIds = []
  clearInviteError()
  inviteVisible.value = true
}

async function submitInvite(): Promise<void> {
  if (inviteForm.supplierIds.length === 0) {
    ElMessage.warning('请至少选择一家供应商')
    return
  }
  const id = inviteForm.rfqId
  const ok = await runInvite(async () => {
    await inviteRfq(id, inviteForm.supplierIds)
  })
  if (ok) {
    inviteVisible.value = false
    ElMessage.success('邀请已发送')
    await load()
    if (detail.value?.id === id) {
      await refreshDetail(id)
    }
  }
}

// ---- 创建 RFQ ----

interface RfqLineForm {
  itemId: number | undefined
  qty: number
  uom: string
  needDate: string
  targetPrice: number | null
  spec: string
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  plantId: number | undefined
  title: string
  currency: string
  deadlineAt: string
  sealed: boolean
  awardPolicy: string
  supplierIds: number[]
  lines: RfqLineForm[]
}>({
  companyId: undefined,
  plantId: undefined,
  title: '',
  currency: 'CNY',
  deadlineAt: '',
  sealed: false,
  awardPolicy: 'WHOLE',
  supplierIds: [],
  lines: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const formPlants = computed(() =>
  props.plants.filter((plant) => plant.companyId === form.companyId),
)

function newLine(): RfqLineForm {
  return { itemId: undefined, qty: 1, uom: '', needDate: '', targetPrice: null, spec: '' }
}

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.plantId = props.plants.find((plant) => plant.companyId === form.companyId)?.id
  form.title = ''
  form.currency = props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.deadlineAt = ''
  form.sealed = false
  form.awardPolicy = 'WHOLE'
  form.supplierIds = []
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

function onCompanyChange(): void {
  form.plantId = props.plants.find((plant) => plant.companyId === form.companyId)?.id
  form.currency = props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

function onItemChange(line: RfqLineForm): void {
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
    ElMessage.warning('RFQ 至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function validateCreate(): string {
  if (!form.companyId || !form.plantId) {
    return '请选择公司与工厂'
  }
  if (!form.title.trim()) {
    return '请填写标题'
  }
  if (!form.deadlineAt) {
    return '请选择截止时间'
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

async function submitCreate(): Promise<void> {
  const validation = validateCreate()
  if (validation) {
    ElMessage.warning(validation)
    return
  }
  let created: RfqDetail | null = null
  const ok = await runSave(async () => {
    created = await createRfq({
      companyId: form.companyId as number,
      plantId: form.plantId as number,
      title: form.title.trim(),
      currency: form.currency.trim().toUpperCase(),
      deadlineAt: form.deadlineAt,
      sealed: form.sealed,
      sourcePrId: null,
      awardPolicy: form.awardPolicy,
      lines: form.lines.map((line) => ({
        itemId: line.itemId as number,
        qty: line.qty,
        uom: line.uom.trim() || 'EA',
        needDate: line.needDate || null,
        targetPrice: line.targetPrice,
        spec: line.spec.trim() || null,
      })),
    })
  })
  if (!ok) {
    return
  }
  createVisible.value = false
  ElMessage.success('RFQ 已创建')
  await load(1)
  if (form.supplierIds.length > 0 && created) {
    try {
      await inviteRfq((created as RfqDetail).id, form.supplierIds)
      ElMessage.success('供应商邀请已发送')
    } catch {
      ElMessage.warning('RFQ 已创建，但邀请发送失败，请在详情中重新邀请')
    }
  }
}

// ---- 比价 / 报价 ----

const comparisonVisible = ref(false)
const comparisonLoading = ref(false)
const comparison = ref<Comparison | null>(null)
const comparisonRfq = ref<RfqRow | null>(null)

const comparisonRows = computed(() => {
  const rfq = comparison.value
  if (!rfq) {
    return []
  }
  return rfq.lines.flatMap((line, index) =>
    line.cells.map((cell) => ({
      lineNo: index + 1,
      itemName: itemName(line.itemId),
      qty: line.qty,
      supplierName: cell.supplierName,
      unitPrice: cell.unitPrice,
      taxIncludedPrice: cell.taxIncludedPrice,
      leadTimeDays: cell.leadTimeDays,
      totalCost: cell.totalCost,
      best: cell.best,
    })),
  )
})

async function openComparison(row: RfqRow): Promise<void> {
  comparisonRfq.value = row
  comparisonVisible.value = true
  comparisonLoading.value = true
  try {
    comparison.value = await rfqComparison(row.id)
  } catch {
    comparison.value = null
  } finally {
    comparisonLoading.value = false
  }
}

const quotesVisible = ref(false)
const quotesLoading = ref(false)
const quotes = ref<Quote[]>([])

async function openQuotes(row: RfqRow): Promise<void> {
  quotesVisible.value = true
  quotesLoading.value = true
  try {
    quotes.value = await listRfqQuotes(row.id)
  } catch {
    quotes.value = []
  } finally {
    quotesLoading.value = false
  }
}

// ---- 定标 ----

const awardVisible = ref(false)
const awardSaving = ref(false)
const awardError = ref('')
const awardForm = reactive<{
  rfqId: number
  supplierId: number | undefined
  reason: string
  lines: {
    selected: boolean
    rfqLineId: number
    quoteLineId: number
    qty: number
    maxQty: number
    label: string
  }[]
}>({ rfqId: 0, supplierId: undefined, reason: '', lines: [] })

const awardSupplierOptions = computed(() => {
  const data = comparison.value
  if (!data) {
    return []
  }
  const names = new Map<number, string>()
  for (const line of data.lines) {
    for (const cell of line.cells) {
      names.set(cell.supplierId, cell.supplierName)
    }
  }
  return [...names.entries()].map(([id, name]) => ({ id, name }))
})

const awardSelectedLines = computed(() => awardForm.lines.filter((line) => line.selected))

async function openAward(rfqId: number): Promise<void> {
  awardForm.rfqId = rfqId
  awardForm.supplierId = undefined
  awardForm.reason = ''
  awardForm.lines = []
  awardError.value = ''
  awardVisible.value = true
  try {
    comparison.value = await rfqComparison(rfqId)
  } catch {
    comparison.value = null
    awardError.value = '无法读取比价结果'
  }
}

function onAwardSupplierChange(supplierId: number | undefined): void {
  const data = comparison.value
  if (!data || !supplierId) {
    awardForm.lines = []
    return
  }
  awardForm.lines = data.lines.flatMap((line, index) => {
    const cell = line.cells.find((candidate) => candidate.supplierId === supplierId)
    return cell
      ? [
          {
            selected: true,
            rfqLineId: line.rfqLineId,
            quoteLineId: cell.quoteLineId,
            qty: line.qty,
            maxQty: line.qty,
            label: `第 ${index + 1} 行 · ${itemName(line.itemId)}`,
          },
        ]
      : []
  })
}

async function submitAward(): Promise<void> {
  if (awardSaving.value) {
    return
  }
  if (!awardForm.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }
  if (awardSelectedLines.value.length === 0) {
    ElMessage.warning('请至少选择一行定标')
    return
  }
  for (const line of awardSelectedLines.value) {
    if (line.qty <= 0) {
      ElMessage.warning('定标数量必须大于 0')
      return
    }
    if (line.qty > line.maxQty) {
      ElMessage.warning(`定标数量不能超过 RFQ 行数量（${line.label}）`)
      return
    }
  }
  awardSaving.value = true
  awardError.value = ''
  try {
    await createAward(awardForm.rfqId, {
      supplierId: awardForm.supplierId,
      reason: awardForm.reason.trim() || null,
      lines: awardSelectedLines.value.map((line) => ({
        rfqLineId: line.rfqLineId,
        quoteLineId: line.quoteLineId,
        awardedQty: line.qty,
      })),
    })
    awardVisible.value = false
    ElMessage.success('定标已创建')
    await load()
    if (detail.value?.id === awardForm.rfqId) {
      await refreshDetail(awardForm.rfqId)
    }
  } catch (caught) {
    awardError.value = caught instanceof Error ? caught.message : '定标创建失败'
  } finally {
    awardSaving.value = false
  }
}

async function approveAwardRow(award: Award): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定批准定标「${award.awardNo}」吗？`, '审批', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await approveAward(award.id)
    ElMessage.success('定标已批准')
    await refreshDetail(award.rfqId)
  } catch {
    // http 拦截器已提示
  }
}

async function awardToPoRow(award: Award): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定将定标「${award.awardNo}」转采购订单吗？`,
    '转 PO',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await awardToPo(award.id)
    ElMessage.success('采购订单已生成')
    await refreshDetail(award.rfqId)
    await load()
  } catch {
    // http 拦截器已提示
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
        placeholder="RFQ 号或标题"
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
        新建 RFQ
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
        label="RFQ"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as RfqRow).rfqNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="title"
        label="标题"
        min-width="200"
      />
      <el-table-column
        label="截止时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as RfqRow).deadlineAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="定标策略"
        width="110"
      >
        <template #default="{ row }">
          {{ labelOf(AWARD_POLICY_LABEL, (row as RfqRow).awardPolicy) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as RfqRow).status)"
          >
            {{ labelOf(RFQ_STATUS_LABEL, (row as RfqRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="300"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as RfqRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as RfqRow).status === 'DRAFT'"
            link
            @click="openInvite(row as RfqRow)"
          >
            邀请
          </el-button>
          <el-button
            v-if="(row as RfqRow).status === 'DRAFT'"
            link
            @click="act(row as RfqRow, 'send')"
          >
            发送
          </el-button>
          <el-button
            v-if="(row as RfqRow).status === 'SENT'"
            link
            @click="act(row as RfqRow, 'close')"
          >
            截止
          </el-button>
          <el-button
            v-if="['CLOSED', 'EVALUATING', 'AWARDED'].includes((row as RfqRow).status)"
            link
            @click="openComparison(row as RfqRow)"
          >
            比价
          </el-button>
          <el-button
            v-if="['CLOSED', 'EVALUATING', 'AWARDED'].includes((row as RfqRow).status)"
            link
            @click="openQuotes(row as RfqRow)"
          >
            报价
          </el-button>
          <el-button
            v-if="!['CANCELED', 'AWARDED'].includes((row as RfqRow).status)"
            link
            @click="act(row as RfqRow, 'cancel')"
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
    title="RFQ 详情"
    size="760px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="RFQ">
            {{ detail.rfqNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(RFQ_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="标题">
            {{ detail.title }}
          </el-descriptions-item>
          <el-descriptions-item label="定标策略">
            {{ labelOf(AWARD_POLICY_LABEL, detail.awardPolicy) }}
          </el-descriptions-item>
          <el-descriptions-item label="截止时间">
            {{ formatDateTime(detail.deadlineAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="密封报价">
            {{ detail.sealed ? '是' : '否' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          询价行
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
            label="目标价"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ row.targetPrice == null ? '—' : formatAmount(row.targetPrice) }}
            </template>
          </el-table-column>
        </el-table>

        <h3 class="detail-title">
          邀请供应商
        </h3>
        <el-table
          :data="detail.invitations"
          class="table-list"
          empty-text="尚未邀请"
        >
          <el-table-column
            label="供应商"
            min-width="180"
          >
            <template #default="{ row }">
              {{ row.supplierName }} <span class="mono">{{ row.supplierCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
          >
            <template #default="{ row }">
              {{ labelOf(INVITATION_STATUS_LABEL, row.status) }}
            </template>
          </el-table-column>
        </el-table>

        <div class="detail-head">
          <h3 class="detail-title">
            定标
          </h3>
          <el-button
            v-if="['CLOSED', 'EVALUATING', 'AWARDED'].includes(detail.status)"
            link
            @click="openAward(detail.id)"
          >
            新建定标
          </el-button>
        </div>
        <el-table
          :data="awards"
          class="table-list"
          empty-text="尚未定标"
        >
          <el-table-column
            label="定标单"
            min-width="150"
          >
            <template #default="{ row }">
              <span class="mono">{{ row.awardNo }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="供应商"
            min-width="150"
          >
            <template #default="{ row }">
              {{ supplierName(row.supplierId) }}
            </template>
          </el-table-column>
          <el-table-column
            label="金额"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.awardAmount, row.currency) }}
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
          >
            <template #default="{ row }">
              {{ labelOf(AWARD_STATUS_LABEL, row.status) }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="150"
            align="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'DRAFT'"
                link
                @click="approveAwardRow(row as Award)"
              >
                批准
              </el-button>
              <el-button
                v-if="row.status === 'APPROVED'"
                link
                @click="awardToPoRow(row as Award)"
              >
                转 PO
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到 RFQ"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="inviteVisible"
    title="邀请供应商"
    width="480px"
  >
    <el-alert
      v-if="inviteError"
      class="page-alert"
      type="error"
      :title="inviteError"
      :closable="false"
      show-icon
    />
    <p class="dialog-lede">
      {{ inviteForm.rfqNo }} · 仅可在草稿状态邀请
    </p>
    <el-select
      v-model="inviteForm.supplierIds"
      multiple
      filterable
      placeholder="选择供应商"
      class="full-width"
    >
      <el-option
        v-for="supplier in suppliers"
        :key="supplier.id"
        :label="`${supplier.code} · ${supplier.name}`"
        :value="supplier.id"
      />
    </el-select>
    <template #footer>
      <el-button @click="inviteVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="inviting"
        @click="submitInvite"
      >
        发送邀请
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="comparisonVisible"
    :title="`比价 · ${comparisonRfq?.rfqNo ?? ''}`"
    width="900px"
  >
    <div v-loading="comparisonLoading">
      <el-alert
        v-if="comparison && !comparison.sealedVisible"
        class="page-alert"
        type="warning"
        title="密封报价尚未解密，暂不可查看比价"
        :closable="false"
        show-icon
      />
      <el-table
        v-else
        :data="comparisonRows"
        class="table-list"
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
            {{ row.itemName }}
          </template>
        </el-table-column>
        <el-table-column
          prop="supplierName"
          label="供应商"
          min-width="150"
        />
        <el-table-column
          label="未税单价"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatAmount(row.unitPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          label="含税单价"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatAmount(row.taxIncludedPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          label="交期"
          align="right"
          width="80"
        >
          <template #default="{ row }">
            {{ row.leadTimeDays ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          label="总成本"
          align="right"
          width="130"
        >
          <template #default="{ row }">
            {{ formatAmount(row.totalCost) }}
          </template>
        </el-table-column>
        <el-table-column
          label="最优"
          width="80"
        >
          <template #default="{ row }">
            <span
              class="status-mark"
              :class="row.best ? 'is-success' : 'is-off'"
            >
              {{ row.best ? '最优' : '—' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="comparison && comparisonRows.length === 0"
        description="暂无有效报价"
      />
    </div>
  </el-dialog>

  <el-dialog
    v-model="quotesVisible"
    title="供应商报价"
    width="820px"
  >
    <div v-loading="quotesLoading">
      <el-collapse v-if="quotes.length > 0">
        <el-collapse-item
          v-for="quote in quotes"
          :key="quote.id"
          :name="quote.id"
        >
          <template #title>
            {{ quote.supplierName }} · {{ quote.quoteNo }} ·
            {{ labelOf(QUOTE_STATUS_LABEL, quote.status) }}
          </template>
          <el-table :data="quote.lines">
            <el-table-column
              label="RFQ 行"
              width="90"
            >
              <template #default="{ row }">
                #{{ row.rfqLineId }}
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
              label="税率"
              align="right"
              width="90"
            >
              <template #default="{ row }">
                {{ (Number(row.taxRate) * 100).toFixed(2) }}%
              </template>
            </el-table-column>
            <el-table-column
              label="交期"
              align="right"
              width="90"
            >
              <template #default="{ row }">
                {{ row.leadTimeDays ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column
              label="可供量"
              align="right"
              width="110"
            >
              <template #default="{ row }">
                {{ row.qtyAvailable == null ? '—' : formatQty(row.qtyAvailable) }}
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <el-empty
        v-else-if="!quotesLoading"
        description="暂无报价"
      />
    </div>
  </el-dialog>

  <el-dialog
    v-model="createVisible"
    title="新建 RFQ"
    width="940px"
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
            @change="onCompanyChange"
          >
            <el-option
              v-for="company in props.companies"
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
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker
            v-model="form.deadlineAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="密封报价">
          <el-switch v-model="form.sealed" />
        </el-form-item>
        <el-form-item label="定标策略">
          <el-select v-model="form.awardPolicy">
            <el-option
              v-for="[value, label] in policyOptions"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="邀请供应商">
          <el-select
            v-model="form.supplierIds"
            multiple
            filterable
            placeholder="可稍后邀请"
          >
            <el-option
              v-for="supplier in suppliers"
              :key="supplier.id"
              :label="`${supplier.code} · ${supplier.name}`"
              :value="supplier.id"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">询价行</span>
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
          min-width="180"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.itemId"
              placeholder="选择物料"
              filterable
              @change="onItemChange(row as RfqLineForm)"
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
          label="需求日"
          width="130"
        >
          <template #default="{ row }">
            <el-date-picker
              v-model="row.needDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="目标价"
          width="120"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.targetPrice"
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
        @click="submitCreate"
      >
        创建 RFQ
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="awardVisible"
    title="新建定标"
    width="720px"
  >
    <el-alert
      v-if="awardError"
      class="page-alert"
      type="error"
      :title="awardError"
      :closable="false"
      show-icon
    />
    <el-form label-width="92px">
      <el-form-item label="供应商">
        <el-select
          v-model="awardForm.supplierId"
          placeholder="从有效报价中选择"
          filterable
          @change="onAwardSupplierChange"
        >
          <el-option
            v-for="option in awardSupplierOptions"
            :key="option.id"
            :label="option.name"
            :value="option.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="定标理由">
        <el-input
          v-model="awardForm.reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          placeholder="非最低价时必须说明"
        />
      </el-form-item>
      <el-table
        :data="awardForm.lines"
        class="table-list"
      >
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column
          prop="label"
          label="RFQ 行"
          min-width="220"
        />
        <el-table-column
          label="定标数量"
          width="150"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0.000001"
              :max="row.maxQty"
              :controls="false"
              class="line-number"
              :disabled="!row.selected"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-form>
    <template #footer>
      <el-button @click="awardVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="awardSaving"
        @click="submitAward"
      >
        创建定标
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

.line-number,
.full-width {
  width: 100%;
}

.line-head,
.detail-head {
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

.detail-title {
  margin: 24px 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.detail-head .detail-title {
  margin: 0;
}

.dialog-lede {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
}
</style>
