<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  closeCapa,
  closeNcr,
  createCapa,
  createNcr,
  disposeNcr,
  getInspectionOrder,
  getInspectionPlan,
  listCapas,
  listCompanies,
  listInspectionOrders,
  listItems,
  listNcrs,
  listSuppliers,
  recordInspectionResults,
  verifyCapa,
  type CapaRow,
  type CompanyOption,
  type InspectionOrderDetail,
  type InspectionOrderRow,
  type ItemOption,
  type NcrRow,
} from '@/features/erp/api'
import {
  CAPA_STATUS_LABEL,
  INSPECTION_STATUS_LABEL,
  NCR_DISPOSITION_LABEL,
  NCR_STATUS_LABEL,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import CertificatePanel from '@/features/erp/components/CertificatePanel.vue'
import InspectionPlanPanel from '@/features/erp/components/InspectionPlanPanel.vue'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const activeTab = ref<'plan' | 'certificate' | 'inspection' | 'ncr' | 'capa'>('inspection')

const companies = ref<CompanyOption[]>([])
const items = ref<ItemOption[]>([])
const suppliers = ref<SupplierSummary[]>([])

const companyId = ref<number | undefined>(undefined)
const inspectionStatus = ref('')
const ncrStatus = ref('')
const capaStatus = ref('')

const itemNames = computed(() => new Map(items.value.map((item) => [item.id, item.name])))

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

const {
  rows: inspectionRows,
  total: inspectionTotal,
  page: inspectionPage,
  size: inspectionSize,
  loading: inspectionLoading,
  error: inspectionError,
  load: loadInspections,
} = usePagedList<InspectionOrderRow>((targetPage, pageSize) =>
  listInspectionOrders({
    page: targetPage,
    size: pageSize,
    companyId: companyId.value,
    status: inspectionStatus.value || undefined,
  }),
)

const {
  rows: ncrRows,
  total: ncrTotal,
  page: ncrPage,
  size: ncrSize,
  loading: ncrLoading,
  error: ncrError,
  load: loadNcrs,
} = usePagedList<NcrRow>((targetPage, pageSize) =>
  listNcrs({
    page: targetPage,
    size: pageSize,
    companyId: companyId.value,
    status: ncrStatus.value || undefined,
  }),
)

const {
  rows: capaRows,
  total: capaTotal,
  page: capaPage,
  size: capaSize,
  loading: capaLoading,
  error: capaError,
  load: loadCapas,
} = usePagedList<CapaRow>((targetPage, pageSize) =>
  listCapas({
    page: targetPage,
    size: pageSize,
    companyId: companyId.value,
    status: capaStatus.value || undefined,
  }),
)

const inspectionStatusOptions = Object.entries(INSPECTION_STATUS_LABEL)
const ncrStatusOptions = Object.entries(NCR_STATUS_LABEL)
const capaStatusOptions = Object.entries(CAPA_STATUS_LABEL)
const dispositionOptions = Object.entries(NCR_DISPOSITION_LABEL)

async function loadOptions(): Promise<void> {
  try {
    const [companyList, itemList, supplierList] = await Promise.all([
      listCompanies(),
      listItems(),
      listSuppliers(),
    ])
    companies.value = companyList
    items.value = itemList
    suppliers.value = supplierList
    if (!companyId.value && companyList.length > 0) {
      companyId.value = companyList[0].id
    }
  } catch {
    // 主数据不可用时仍可查询
  }
}

function reloadAll(): void {
  void loadInspections(1)
  void loadNcrs(1)
  void loadCapas(1)
}

onMounted(async () => {
  await loadOptions()
  reloadAll()
})

// ---- 检验结果 ----

interface ResultLineForm {
  characteristicId: number | null
  name: string
  spec: string
  measuredValue: string
  result: string
  remark: string
}

const resultVisible = ref(false)
const resultLoading = ref(false)
const resultOrder = ref<InspectionOrderDetail | null>(null)
const resultLines = ref<ResultLineForm[]>([])
const resultEditable = computed(() =>
  resultOrder.value ? ['PENDING', 'IN_PROGRESS'].includes(resultOrder.value.status) : false,
)
const { submitting: resultSubmitting, errorMessage: resultError, run: runResult } = useSubmit()

async function openResults(row: InspectionOrderRow): Promise<void> {
  resultLoading.value = true
  resultVisible.value = true
  try {
    const order = await getInspectionOrder(row.id)
    resultOrder.value = order
    if (['PENDING', 'IN_PROGRESS'].includes(order.status)) {
      const plan = await getInspectionPlan(order.planId)
      resultLines.value = plan.characteristics.map((characteristic) => ({
        characteristicId: characteristic.id,
        name: characteristic.name,
        spec: characteristic.spec ?? '',
        measuredValue: '',
        result: 'PASS',
        remark: '',
      }))
    } else {
      resultLines.value = order.results.map((result) => ({
        characteristicId: result.characteristicId,
        name: result.name,
        spec: '',
        measuredValue: result.measuredValue ?? '',
        result: result.result ?? 'PASS',
        remark: result.remark ?? '',
      }))
    }
  } catch {
    resultOrder.value = null
  } finally {
    resultLoading.value = false
  }
}

async function submitResults(): Promise<void> {
  if (!resultOrder.value) {
    return
  }
  if (resultLines.value.some((line) => !line.name.trim())) {
    ElMessage.warning('检验特性名不能为空')
    return
  }
  const orderId = resultOrder.value.id
  const ok = await runResult(async () => {
    await recordInspectionResults(orderId, {
      lines: resultLines.value.map((line) => ({
        characteristicId: line.characteristicId,
        name: line.name,
        measuredValue: line.measuredValue.trim() || null,
        result: line.result,
        remark: line.remark.trim() || null,
      })),
    })
  })
  if (ok) {
    resultVisible.value = false
    ElMessage.success('检验结果已记录')
    await loadInspections()
  }
}

// ---- NCR ----

const ncrVisible = ref(false)
const ncrForm = reactive<{
  companyId: number | undefined
  itemId: number | undefined
  supplierId: number | undefined
  lotNo: string
  qty: number
  defect: string
}>({ companyId: undefined, itemId: undefined, supplierId: undefined, lotNo: '', qty: 0, defect: '' })

const {
  submitting: ncrSaving,
  errorMessage: ncrSaveError,
  run: runNcrSave,
  clearError: clearNcrError,
} = useSubmit()

function openNcrDialog(): void {
  ncrForm.companyId = companyId.value ?? companies.value[0]?.id
  ncrForm.itemId = undefined
  ncrForm.supplierId = undefined
  ncrForm.lotNo = ''
  ncrForm.qty = 0
  ncrForm.defect = ''
  clearNcrError()
  ncrVisible.value = true
}

async function submitNcr(): Promise<void> {
  if (!ncrForm.companyId || !ncrForm.itemId) {
    ElMessage.warning('请选择公司与物料')
    return
  }
  if (!ncrForm.defect.trim()) {
    ElMessage.warning('请填写缺陷描述')
    return
  }
  const ok = await runNcrSave(async () => {
    await createNcr({
      companyId: ncrForm.companyId as number,
      itemId: ncrForm.itemId as number,
      supplierId: ncrForm.supplierId ?? null,
      lotNo: ncrForm.lotNo.trim() || null,
      qty: ncrForm.qty > 0 ? ncrForm.qty : null,
      defect: ncrForm.defect.trim(),
    })
  })
  if (ok) {
    ncrVisible.value = false
    ElMessage.success('NCR 已创建')
    await loadNcrs(1)
  }
}

const disposeVisible = ref(false)
const disposeForm = reactive<{ ncrId: number; ncrNo: string; disposition: string; reason: string }>({
  ncrId: 0,
  ncrNo: '',
  disposition: 'REWORK',
  reason: '',
})

const {
  submitting: disposing,
  errorMessage: disposeError,
  run: runDispose,
  clearError: clearDisposeError,
} = useSubmit()

function openDispose(row: NcrRow): void {
  disposeForm.ncrId = row.id
  disposeForm.ncrNo = row.ncrNo
  disposeForm.disposition = 'REWORK'
  disposeForm.reason = ''
  clearDisposeError()
  disposeVisible.value = true
}

async function submitDispose(): Promise<void> {
  if (!disposeForm.reason.trim()) {
    ElMessage.warning('请填写处置原因')
    return
  }
  const ok = await runDispose(async () => {
    await disposeNcr(disposeForm.ncrId, disposeForm.disposition, disposeForm.reason.trim())
  })
  if (ok) {
    disposeVisible.value = false
    ElMessage.success('NCR 已处置')
    await loadNcrs()
  }
}

async function closeNcrRow(row: NcrRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定关闭 NCR「${row.ncrNo}」吗？`, '关闭', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await closeNcr(row.id)
    ElMessage.success('NCR 已关闭')
    await loadNcrs()
  } catch {
    // http 拦截器已提示
  }
}

// ---- CAPA ----

const capaVisible = ref(false)
const capaForm = reactive<{ ncrId: number | undefined; rootCause: string; actionPlan: string }>({
  ncrId: undefined,
  rootCause: '',
  actionPlan: '',
})
const ncrOptions = ref<NcrRow[]>([])
const {
  submitting: capaSaving,
  errorMessage: capaSaveError,
  run: runCapaSave,
  clearError: clearCapaError,
} = useSubmit()

async function openCapaDialog(ncr?: NcrRow): Promise<void> {
  capaForm.ncrId = ncr?.id
  capaForm.rootCause = ''
  capaForm.actionPlan = ''
  clearCapaError()
  capaVisible.value = true
  try {
    const page = await listNcrs({ page: 1, size: 200 })
    ncrOptions.value = page.list
  } catch {
    ncrOptions.value = []
  }
}

async function submitCapa(): Promise<void> {
  if (!capaForm.ncrId) {
    ElMessage.warning('请选择关联 NCR')
    return
  }
  if (!capaForm.actionPlan.trim()) {
    ElMessage.warning('请填写措施计划')
    return
  }
  const ok = await runCapaSave(async () => {
    await createCapa(
      capaForm.ncrId as number,
      capaForm.rootCause.trim() || null,
      capaForm.actionPlan.trim(),
    )
  })
  if (ok) {
    capaVisible.value = false
    ElMessage.success('CAPA 已创建')
    await loadCapas(1)
  }
}

async function verifyCapaRow(row: CapaRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确认验证通过 CAPA「${row.capaNo}」吗？`, '验证', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await verifyCapa(row.id)
    ElMessage.success('CAPA 已验证')
    await loadCapas()
  } catch {
    // http 拦截器已提示
  }
}

async function closeCapaRow(row: CapaRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定关闭 CAPA「${row.capaNo}」吗？`, '关闭', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await closeCapa(row.id)
    ElMessage.success('CAPA 已关闭')
    await loadCapas()
  } catch {
    // http 拦截器已提示
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / QUALITY"
    title="质量管理"
    :description="`检验单 ${inspectionTotal} · NCR ${ncrTotal} · CAPA ${capaTotal}`"
  >
    <template #actions>
      <el-select
        v-model="companyId"
        class="header-company"
        placeholder="公司"
        clearable
        aria-label="公司"
        @change="reloadAll"
      >
        <el-option
          v-for="company in companies"
          :key="company.id"
          :label="`${company.code} · ${company.name}`"
          :value="company.id"
        />
      </el-select>
    </template>
  </PageHeader>

  <el-tabs
    v-model="activeTab"
    class="quality-tabs"
  >
    <el-tab-pane
      label="检验计划"
      name="plan"
      lazy
    >
      <InspectionPlanPanel
        :companies="companies"
        :items="items"
        :suppliers="suppliers"
        :company-id="companyId"
      />
    </el-tab-pane>
    <el-tab-pane
      label="供应商证书"
      name="certificate"
      lazy
    >
      <CertificatePanel
        :companies="companies"
        :items="items"
        :suppliers="suppliers"
        :company-id="companyId"
      />
    </el-tab-pane>
    <el-tab-pane
      label="检验单"
      name="inspection"
    >
      <div class="list-toolbar">
        <el-select
          v-model="inspectionStatus"
          class="list-toolbar__select"
          placeholder="状态"
          clearable
          aria-label="检验状态"
          @change="loadInspections(1)"
        >
          <el-option
            v-for="[value, label] in inspectionStatusOptions"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button
          type="primary"
          @click="loadInspections(1)"
        >
          查询
        </el-button>
        <span class="list-toolbar__note">检验不合格将自动生成 NCR</span>
      </div>

      <el-alert
        v-if="inspectionError"
        class="page-alert"
        type="error"
        :title="inspectionError"
        :closable="false"
        show-icon
      />
      <el-table
        v-loading="inspectionLoading"
        :data="inspectionRows"
        class="table-list"
      >
        <el-table-column
          label="检验单号"
          min-width="150"
        >
          <template #default="{ row }">
            <span class="mono">{{ (row as InspectionOrderRow).orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="物料"
          min-width="190"
        >
          <template #default="{ row }">
            {{ itemName((row as InspectionOrderRow).itemId) }}
          </template>
        </el-table-column>
        <el-table-column
          label="批次"
          min-width="120"
        >
          <template #default="{ row }">
            <span class="mono">{{ (row as InspectionOrderRow).lotNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="数量"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ formatQty((row as InspectionOrderRow).qty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="120"
        >
          <template #default="{ row }">
            <span
              class="status-mark"
              :class="statusTone((row as InspectionOrderRow).status)"
            >
              {{ labelOf(INSPECTION_STATUS_LABEL, (row as InspectionOrderRow).status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ formatDateTime((row as InspectionOrderRow).createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          align="right"
        >
          <template #default="{ row }">
            <el-button
              link
              @click="openResults(row as InspectionOrderRow)"
            >
              {{ ['PENDING', 'IN_PROGRESS'].includes((row as InspectionOrderRow).status) ? '录入结果' : '查看结果' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPagination
        v-model:page="inspectionPage"
        v-model:size="inspectionSize"
        :total="inspectionTotal"
        class="list-pager"
        @change="(target: number) => void loadInspections(target)"
        @size-change="() => void loadInspections(1)"
      />
    </el-tab-pane>

    <el-tab-pane
      label="NCR"
      name="ncr"
    >
      <div class="list-toolbar">
        <el-select
          v-model="ncrStatus"
          class="list-toolbar__select"
          placeholder="状态"
          clearable
          aria-label="NCR 状态"
          @change="loadNcrs(1)"
        >
          <el-option
            v-for="[value, label] in ncrStatusOptions"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button
          type="primary"
          @click="loadNcrs(1)"
        >
          查询
        </el-button>
        <el-button
          class="toolbar-create"
          @click="openNcrDialog"
        >
          新建 NCR
        </el-button>
      </div>

      <el-alert
        v-if="ncrError"
        class="page-alert"
        type="error"
        :title="ncrError"
        :closable="false"
        show-icon
      />
      <el-table
        v-loading="ncrLoading"
        :data="ncrRows"
        class="table-list"
      >
        <el-table-column
          label="NCR"
          min-width="150"
        >
          <template #default="{ row }">
            <span class="mono">{{ (row as NcrRow).ncrNo }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="物料"
          min-width="180"
        >
          <template #default="{ row }">
            {{ itemName((row as NcrRow).itemId) }}
          </template>
        </el-table-column>
        <el-table-column
          label="缺陷"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row as NcrRow).defect }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="status-mark"
              :class="statusTone((row as NcrRow).status)"
            >
              {{ labelOf(NCR_STATUS_LABEL, (row as NcrRow).status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="处置"
          width="120"
        >
          <template #default="{ row }">
            {{ labelOf(NCR_DISPOSITION_LABEL, (row as NcrRow).disposition) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="210"
          align="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="(row as NcrRow).status === 'OPEN'"
              link
              @click="openDispose(row as NcrRow)"
            >
              处置
            </el-button>
            <el-button
              v-if="(row as NcrRow).status === 'DISPOSITIONED'"
              link
              @click="closeNcrRow(row as NcrRow)"
            >
              关闭
            </el-button>
            <el-button
              link
              @click="openCapaDialog(row as NcrRow)"
            >
              生成 CAPA
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPagination
        v-model:page="ncrPage"
        v-model:size="ncrSize"
        :total="ncrTotal"
        class="list-pager"
        @change="(target: number) => void loadNcrs(target)"
        @size-change="() => void loadNcrs(1)"
      />
    </el-tab-pane>

    <el-tab-pane
      label="CAPA"
      name="capa"
    >
      <div class="list-toolbar">
        <el-select
          v-model="capaStatus"
          class="list-toolbar__select"
          placeholder="状态"
          clearable
          aria-label="CAPA 状态"
          @change="loadCapas(1)"
        >
          <el-option
            v-for="[value, label] in capaStatusOptions"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button
          type="primary"
          @click="loadCapas(1)"
        >
          查询
        </el-button>
        <el-button
          class="toolbar-create"
          @click="openCapaDialog()"
        >
          新建 CAPA
        </el-button>
      </div>

      <el-alert
        v-if="capaError"
        class="page-alert"
        type="error"
        :title="capaError"
        :closable="false"
        show-icon
      />
      <el-table
        v-loading="capaLoading"
        :data="capaRows"
        class="table-list"
      >
        <el-table-column
          label="CAPA"
          min-width="160"
        >
          <template #default="{ row }">
            <span class="mono">{{ (row as CapaRow).capaNo }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="措施计划"
          min-width="280"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row as CapaRow).actionPlan }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="status-mark"
              :class="statusTone((row as CapaRow).status)"
            >
              {{ labelOf(CAPA_STATUS_LABEL, (row as CapaRow).status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ formatDateTime((row as CapaRow).createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="150"
          align="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="['OPEN', 'IN_PROGRESS'].includes((row as CapaRow).status)"
              link
              @click="verifyCapaRow(row as CapaRow)"
            >
              验证
            </el-button>
            <el-button
              v-if="(row as CapaRow).status === 'VERIFIED'"
              link
              @click="closeCapaRow(row as CapaRow)"
            >
              关闭
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPagination
        v-model:page="capaPage"
        v-model:size="capaSize"
        :total="capaTotal"
        class="list-pager"
        @change="(target: number) => void loadCapas(target)"
        @size-change="() => void loadCapas(1)"
      />
    </el-tab-pane>
  </el-tabs>

  <el-dialog
    v-model="resultVisible"
    :title="resultEditable ? '录入检验结果' : '检验结果'"
    width="820px"
  >
    <div v-loading="resultLoading">
      <el-alert
        v-if="resultError"
        class="page-alert"
        type="error"
        :title="resultError"
        :closable="false"
        show-icon
      />
      <template v-if="resultOrder">
        <p class="dialog-lede">
          {{ resultOrder.orderNo }} · {{ itemName(resultOrder.itemId) }} ·
          {{ labelOf(INSPECTION_STATUS_LABEL, resultOrder.status) }}
        </p>
        <el-table
          :data="resultLines"
          class="table-list"
        >
          <el-table-column
            label="特性"
            min-width="150"
          >
            <template #default="{ row }">
              {{ row.name }}
              <span
                v-if="row.spec"
                class="mono"
              >{{ row.spec }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="实测值"
            min-width="140"
          >
            <template #default="{ row }">
              <el-input
                v-if="resultEditable"
                v-model="row.measuredValue"
                maxlength="100"
              />
              <span v-else>{{ row.measuredValue || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="判定"
            width="120"
          >
            <template #default="{ row }">
              <el-select
                v-if="resultEditable"
                v-model="row.result"
              >
                <el-option
                  label="合格"
                  value="PASS"
                />
                <el-option
                  label="不合格"
                  value="FAIL"
                />
              </el-select>
              <span v-else>{{ row.result === 'FAIL' ? '不合格' : '合格' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="备注"
            min-width="160"
          >
            <template #default="{ row }">
              <el-input
                v-if="resultEditable"
                v-model="row.remark"
                maxlength="255"
              />
              <span v-else>{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="resultLines.length === 0"
          description="检验计划没有特性"
        />
      </template>
      <el-empty
        v-else-if="!resultLoading"
        description="未找到检验单"
      />
    </div>
    <template #footer>
      <el-button @click="resultVisible = false">
        {{ resultEditable ? '取消' : '关闭' }}
      </el-button>
      <el-button
        v-if="resultEditable"
        type="primary"
        :loading="resultSubmitting"
        @click="submitResults"
      >
        提交结果
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="ncrVisible"
    title="新建 NCR"
    width="520px"
  >
    <el-alert
      v-if="ncrSaveError"
      class="page-alert"
      type="error"
      :title="ncrSaveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="公司">
        <el-select
          v-model="ncrForm.companyId"
          placeholder="选择公司"
        >
          <el-option
            v-for="company in companies"
            :key="company.id"
            :label="`${company.code} · ${company.name}`"
            :value="company.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-select
          v-model="ncrForm.itemId"
          placeholder="选择物料"
          filterable
        >
          <el-option
            v-for="item in items"
            :key="item.id"
            :label="`${item.code} · ${item.name}`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="供应商">
        <el-select
          v-model="ncrForm.supplierId"
          placeholder="可留空"
          clearable
          filterable
        >
          <el-option
            v-for="supplier in suppliers"
            :key="supplier.id"
            :label="`${supplier.code} · ${supplier.name}`"
            :value="supplier.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="批次号">
        <el-input
          v-model="ncrForm.lotNo"
          maxlength="64"
          placeholder="可留空"
        />
      </el-form-item>
      <el-form-item label="数量">
        <el-input-number
          v-model="ncrForm.qty"
          :min="0"
          :controls="false"
          class="line-number"
        />
      </el-form-item>
      <el-form-item label="缺陷描述">
        <el-input
          v-model="ncrForm.defect"
          type="textarea"
          :rows="3"
          maxlength="255"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="ncrVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="ncrSaving"
        @click="submitNcr"
      >
        创建
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="disposeVisible"
    title="NCR 处置"
    width="480px"
  >
    <el-alert
      v-if="disposeError"
      class="page-alert"
      type="error"
      :title="disposeError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="NCR">
        {{ disposeForm.ncrNo }}
      </el-form-item>
      <el-form-item label="处置方式">
        <el-select v-model="disposeForm.disposition">
          <el-option
            v-for="[value, label] in dispositionOptions"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="处置原因">
        <el-input
          v-model="disposeForm.reason"
          type="textarea"
          :rows="3"
          maxlength="255"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="disposeVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="disposing"
        @click="submitDispose"
      >
        确认处置
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="capaVisible"
    title="新建 CAPA"
    width="520px"
  >
    <el-alert
      v-if="capaSaveError"
      class="page-alert"
      type="error"
      :title="capaSaveError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="关联 NCR">
        <el-select
          v-model="capaForm.ncrId"
          placeholder="选择 NCR"
          filterable
        >
          <el-option
            v-for="ncr in ncrOptions"
            :key="ncr.id"
            :label="`${ncr.ncrNo} · ${ncr.defect}`"
            :value="ncr.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="根本原因">
        <el-input
          v-model="capaForm.rootCause"
          type="textarea"
          :rows="2"
          maxlength="500"
        />
      </el-form-item>
      <el-form-item label="措施计划">
        <el-input
          v-model="capaForm.actionPlan"
          type="textarea"
          :rows="3"
          maxlength="1000"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="capaVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="capaSaving"
        @click="submitCapa"
      >
        创建
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.header-company {
  width: 240px;
}

.quality-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.toolbar-create {
  margin-left: auto;
}

.dialog-lede {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
}

.line-number {
  width: 100%;
}
</style>
