<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approveAuctionResult,
  auctionResultToPo,
  awardAuction,
  closeAuction,
  createAuction,
  getAuction,
  listAuctionResults,
  listAuctions,
  listSuppliers,
  startAuction,
  type AuctionDetail,
  type AuctionResult,
  type AuctionRow,
  type CompanyOption,
  type ItemOption,
  type PlantOption,
} from '@/features/erp/api'
import {
  AUCTION_RESULT_STATUS_LABEL,
  AUCTION_STATUS_LABEL,
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

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })
const suppliers = ref<SupplierSummary[]>([])
const supplierNames = computed(
  () => new Map(suppliers.value.map((supplier) => [supplier.id, supplier.name])),
)
function supplierName(id: number): string {
  return supplierNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<AuctionRow>(
  (targetPage, pageSize) =>
    listAuctions({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(AUCTION_STATUS_LABEL)

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
  void load(1)
}

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AuctionDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getAuction(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function act(row: AuctionRow, action: 'start' | 'close'): Promise<void> {
  const verb = action === 'start' ? '启动' : '结束'
  const confirmed = await ElMessageBox.confirm(
    `确定要${verb}竞价「${row.title}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    if (action === 'start') {
      await startAuction(row.id, row.version)
    } else {
      await closeAuction(row.id, row.version)
    }
    ElMessage.success(`已${verb}`)
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getAuction(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

interface LotForm {
  itemId: number | undefined
  qty: number
  uom: string
  startPrice: number
  reservePrice: number | null
  minDecrement: number
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  title: string
  currency: string
  startAt: string
  endAt: string
  sealed: boolean
  antiSnipingSeconds: number
  maxExtensions: number
  supplierIds: number[]
  lots: LotForm[]
}>({
  companyId: undefined,
  title: '',
  currency: 'CNY',
  startAt: '',
  endAt: '',
  sealed: false,
  antiSnipingSeconds: 60,
  maxExtensions: 3,
  supplierIds: [],
  lots: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function newLot(): LotForm {
  return { itemId: undefined, qty: 1, uom: '', startPrice: 0, reservePrice: null, minDecrement: 0.01 }
}

function onCompanyChange(): void {
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

function onItemChange(lot: LotForm): void {
  const item = props.items.find((option) => option.id === lot.itemId)
  if (item) {
    lot.uom = item.baseUom
  }
}

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.title = ''
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.startAt = ''
  form.endAt = ''
  form.sealed = false
  form.antiSnipingSeconds = 60
  form.maxExtensions = 3
  form.supplierIds = []
  form.lots = [newLot()]
  clearError()
  createVisible.value = true
}

function addLot(): void {
  form.lots.push(newLot())
}

function removeLot(index: number): void {
  if (form.lots.length <= 1) {
    ElMessage.warning('竞价至少保留一个批次')
    return
  }
  form.lots.splice(index, 1)
}

function validate(): string {
  if (!form.companyId) {
    return '请选择公司'
  }
  if (!form.title.trim()) {
    return '请填写标题'
  }
  if (!form.startAt || !form.endAt) {
    return '请选择开始与结束时间'
  }
  if (form.endAt <= form.startAt) {
    return '结束时间必须晚于开始时间'
  }
  if (form.supplierIds.length === 0) {
    return '请至少邀请一家供应商'
  }
  for (const [index, lot] of form.lots.entries()) {
    if (!lot.itemId) {
      return `第 ${index + 1} 个批次请选择物料`
    }
    if (lot.qty <= 0 || lot.startPrice <= 0 || lot.minDecrement <= 0) {
      return `第 ${index + 1} 个批次的数量、起拍价、最小降幅必须大于 0`
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
    await createAuction({
      title: form.title.trim(),
      companyId: form.companyId as number,
      currency: form.currency.trim().toUpperCase(),
      startAt: form.startAt,
      endAt: form.endAt,
      sealed: form.sealed,
      antiSnipingSeconds: form.antiSnipingSeconds,
      maxExtensions: form.maxExtensions,
      lots: form.lots.map((lot) => ({
        itemId: lot.itemId as number,
        qty: lot.qty,
        uom: lot.uom.trim() || 'EA',
        startPrice: lot.startPrice,
        reservePrice: lot.reservePrice,
        minDecrement: lot.minDecrement,
      })),
      supplierIds: form.supplierIds,
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('竞价已创建')
    await load(1)
  }
}

// ---- 结果 ----

const resultsVisible = ref(false)
const resultsLoading = ref(false)
const results = ref<AuctionResult[]>([])
const resultsAuction = ref<AuctionDetail | null>(null)
const resultsCompanyId = ref<number | undefined>(undefined)

const canAward = computed(
  () =>
    resultsAuction.value !== null &&
    ['CLOSED'].includes(resultsAuction.value.status) &&
    results.value.length === 0,
)

async function openResults(row: AuctionRow): Promise<void> {
  resultsVisible.value = true
  resultsCompanyId.value = row.companyId
  resultsLoading.value = true
  try {
    resultsAuction.value = await getAuction(row.id)
    results.value = await listAuctionResults(row.id)
  } catch {
    resultsAuction.value = null
    results.value = []
  } finally {
    resultsLoading.value = false
  }
}

async function generateResults(): Promise<void> {
  if (!resultsAuction.value) {
    return
  }
  const confirmed = await ElMessageBox.confirm('确定生成竞价结果吗？', '生成结果', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    results.value = await awardAuction(resultsAuction.value.id)
    ElMessage.success('竞价结果已生成')
    await load()
  } catch {
    // http 拦截器已提示
  }
}

async function approveResult(result: AuctionResult): Promise<void> {
  const confirmed = await ElMessageBox.confirm('确定批准该竞价结果吗？', '审批', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await approveAuctionResult(result.id)
    ElMessage.success('竞价结果已批准')
    if (resultsAuction.value) {
      results.value = await listAuctionResults(resultsAuction.value.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

const toPoVisible = ref(false)
const toPoResult = ref<AuctionResult | null>(null)
const toPoPlantId = ref<number | undefined>(undefined)
const toPoLoading = ref(false)

function openToPo(result: AuctionResult): void {
  toPoResult.value = result
  toPoPlantId.value = props.plants.find(
    (plant) => plant.companyId === resultsCompanyId.value,
  )?.id
  toPoVisible.value = true
}

async function submitToPo(): Promise<void> {
  if (!toPoResult.value || !toPoPlantId.value) {
    ElMessage.warning('请选择工厂')
    return
  }
  toPoLoading.value = true
  try {
    const po = await auctionResultToPo(toPoResult.value.id, toPoPlantId.value)
    toPoVisible.value = false
    ElMessage.success(`采购订单 ${po.docNo} 已生成`)
    if (resultsAuction.value) {
      results.value = await listAuctionResults(resultsAuction.value.id)
    }
    await load()
  } catch {
    // http 拦截器已提示
  } finally {
    toPoLoading.value = false
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
        新建竞价
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
        label="竞价号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as AuctionRow).auctionNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="title"
        label="标题"
        min-width="200"
      />
      <el-table-column
        label="起止"
        min-width="220"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as AuctionRow).startAt) }} ~
          {{ formatDateTime((row as AuctionRow).endAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="密封"
        width="80"
      >
        <template #default="{ row }">
          {{ (row as AuctionRow).sealed ? '是' : '否' }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as AuctionRow).status)"
          >
            {{ labelOf(AUCTION_STATUS_LABEL, (row as AuctionRow).status) }}
          </span>
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
            @click="openDetail((row as AuctionRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as AuctionRow).status === 'DRAFT'"
            link
            @click="act(row as AuctionRow, 'start')"
          >
            启动
          </el-button>
          <el-button
            v-if="(row as AuctionRow).status === 'LIVE'"
            link
            @click="act(row as AuctionRow, 'close')"
          >
            结束
          </el-button>
          <el-button
            v-if="['CLOSED', 'AWARDED'].includes((row as AuctionRow).status)"
            link
            @click="openResults(row as AuctionRow)"
          >
            结果
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
    title="竞价详情"
    size="720px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="竞价号">
            {{ detail.auctionNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(AUCTION_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="标题">
            {{ detail.title }}
          </el-descriptions-item>
          <el-descriptions-item label="币种">
            {{ detail.currency }}
          </el-descriptions-item>
          <el-descriptions-item label="开始">
            {{ formatDateTime(detail.startAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束">
            {{ formatDateTime(detail.endAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="防狙击">
            {{ detail.antiSnipingSeconds }} 秒 / 最多 {{ detail.maxExtensions }} 次
          </el-descriptions-item>
          <el-descriptions-item label="密封">
            {{ detail.sealed ? '是' : '否' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          批次
        </h3>
        <el-table
          :data="detail.lots"
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
            label="起拍价"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatAmount(row.startPrice) }}
            </template>
          </el-table-column>
          <el-table-column
            label="当前最低"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ row.currentLowestAmount == null ? '—' : formatAmount(row.currentLowestAmount) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="bidCount"
            label="出价数"
            align="right"
            width="90"
          />
        </el-table>

        <h3 class="detail-title">
          邀请供应商
        </h3>
        <p class="section-note">
          {{ detail.supplierIds.map((id) => supplierName(id)).join('、') || '—' }}
        </p>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到竞价"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建竞价"
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
    <el-form label-width="100px">
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
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="密封">
          <el-switch v-model="form.sealed" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker
            v-model="form.startAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker
            v-model="form.endAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="防狙击秒数">
          <el-input-number
            v-model="form.antiSnipingSeconds"
            :min="0"
            :max="600"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="最大延期">
          <el-input-number
            v-model="form.maxExtensions"
            :min="0"
            :max="10"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
      </div>
      <el-form-item label="邀请供应商">
        <el-select
          v-model="form.supplierIds"
          multiple
          filterable
          placeholder="至少一家"
          class="full-width"
        >
          <el-option
            v-for="supplier in suppliers"
            :key="supplier.id"
            :label="`${supplier.code} · ${supplier.name}`"
            :value="supplier.id"
          />
        </el-select>
      </el-form-item>

      <div class="line-head">
        <span class="line-head__title">竞价批次</span>
        <el-button
          link
          @click="addLot"
        >
          添加批次
        </el-button>
      </div>
      <el-table
        :data="form.lots"
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
              @change="onItemChange(row as LotForm)"
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
          label="起拍价"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.startPrice"
              :min="0.0001"
              :precision="4"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="保留价"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.reservePrice"
              :min="0"
              :precision="4"
              :controls="false"
              class="line-number"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="最小降幅"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.minDecrement"
              :min="0.0001"
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
              @click="removeLot($index)"
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
        创建竞价
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="resultsVisible"
    title="竞价结果"
    width="720px"
  >
    <div v-loading="resultsLoading">
      <div class="result-head">
        <span>
          {{ resultsAuction?.auctionNo }} ·
          {{ labelOf(AUCTION_STATUS_LABEL, resultsAuction?.status ?? '') }}
        </span>
        <el-button
          v-if="canAward"
          type="primary"
          link
          @click="generateResults"
        >
          生成结果
        </el-button>
      </div>
      <el-table
        :data="results"
        class="table-list"
        empty-text="尚无竞价结果"
      >
        <el-table-column
          label="供应商"
          min-width="160"
        >
          <template #default="{ row }">
            {{ row.supplierName }}
          </template>
        </el-table-column>
        <el-table-column
          label="成交金额"
          align="right"
          width="140"
        >
          <template #default="{ row }">
            {{ formatAmount(row.amount) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="status-mark"
              :class="statusTone(row.status)"
            >
              {{ labelOf(AUCTION_RESULT_STATUS_LABEL, row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="140"
          align="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              @click="approveResult(row as AuctionResult)"
            >
              批准
            </el-button>
            <el-button
              v-if="row.status === 'APPROVED'"
              link
              @click="openToPo(row as AuctionResult)"
            >
              转 PO
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>

  <el-dialog
    v-model="toPoVisible"
    title="竞价结果转采购订单"
    width="440px"
  >
    <el-form label-width="84px">
      <el-form-item label="工厂">
        <el-select
          v-model="toPoPlantId"
          placeholder="选择工厂"
        >
          <el-option
            v-for="plant in props.plants.filter(
              (item) => item.companyId === resultsCompanyId,
            )"
            :key="plant.id"
            :label="`${plant.code} · ${plant.name}`"
            :value="plant.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="toPoVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="toPoLoading"
        @click="submitToPo"
      >
        生成采购订单
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
.result-head {
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
</style>
