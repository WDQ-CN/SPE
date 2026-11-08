<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  activateInspectionPlan,
  createInspectionPlan,
  getInspectionPlan,
  pageInspectionPlans,
  type CompanyOption,
  type InspectionPlanDetail,
  type InspectionPlanRow,
  type ItemOption,
} from '@/features/erp/api'
import {
  INSPECTION_PLAN_STATUS_LABEL,
  INSPECTION_TYPE_LABEL,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const props = defineProps<{
  companies: CompanyOption[]
  items: ItemOption[]
  suppliers: SupplierSummary[]
  companyId?: number
}>()

const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))
const supplierNames = computed(
  () => new Map(props.suppliers.map((supplier) => [supplier.id, supplier.name])),
)

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<InspectionPlanRow>(
  (targetPage, pageSize) =>
    props.companyId
      ? pageInspectionPlans(targetPage, pageSize, props.companyId)
      : Promise.resolve({ list: [], total: 0, page: targetPage, size: pageSize }),
)

watch(
  () => props.companyId,
  () => void load(1),
)

onMounted(() => {
  if (props.companyId) {
    void load(1)
  }
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<InspectionPlanDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getInspectionPlan(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function activate(row: InspectionPlanRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定生效检验计划「${row.planNo}」吗？`,
    '生效',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await activateInspectionPlan(row.id)
    ElMessage.success('检验计划已生效')
    await load()
  } catch {
    // http 拦截器已提示
  }
}

interface CharacteristicForm {
  name: string
  spec: string
  critical: boolean
  method: string
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  itemId: number | undefined
  supplierId: number | undefined
  inspectionType: string
  version: string
  samplingPlan: string
  aql: number | null
  characteristics: CharacteristicForm[]
}>({
  companyId: undefined,
  itemId: undefined,
  supplierId: undefined,
  inspectionType: 'IQC',
  version: 'V1',
  samplingPlan: '',
  aql: null,
  characteristics: [],
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()
const typeOptions = Object.entries(INSPECTION_TYPE_LABEL)

function newCharacteristic(): CharacteristicForm {
  return { name: '', spec: '', critical: false, method: '' }
}

function openCreate(): void {
  form.companyId = props.companyId ?? props.companies[0]?.id
  form.itemId = undefined
  form.supplierId = undefined
  form.inspectionType = 'IQC'
  form.version = 'V1'
  form.samplingPlan = ''
  form.aql = null
  form.characteristics = [newCharacteristic()]
  clearError()
  createVisible.value = true
}

function addCharacteristic(): void {
  form.characteristics.push(newCharacteristic())
}

function removeCharacteristic(index: number): void {
  if (form.characteristics.length <= 1) {
    ElMessage.warning('检验计划至少保留一个特性')
    return
  }
  form.characteristics.splice(index, 1)
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.itemId) {
    ElMessage.warning('请选择公司与物料')
    return
  }
  if (form.characteristics.some((item) => !item.name.trim())) {
    ElMessage.warning('检验特性名不能为空')
    return
  }
  const ok = await runSave(async () => {
    await createInspectionPlan({
      companyId: form.companyId as number,
      itemId: form.itemId as number,
      supplierId: form.supplierId ?? null,
      inspectionType: form.inspectionType,
      version: form.version.trim() || 'V1',
      samplingPlan: form.samplingPlan.trim() || null,
      aql: form.aql,
      characteristics: form.characteristics.map((item) => ({
        name: item.name.trim(),
        spec: item.spec.trim() || null,
        critical: item.critical,
        method: item.method.trim() || null,
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('检验计划已创建')
    await load(1)
  }
}
</script>

<template>
  <div>
    <div class="list-toolbar">
      <span class="list-toolbar__note">公司由页面顶部筛选决定</span>
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        新建检验计划
      </el-button>
    </div>

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
        label="计划号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InspectionPlanRow).planNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="物料"
        min-width="190"
      >
        <template #default="{ row }">
          {{ itemName((row as InspectionPlanRow).itemId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="类型"
        width="110"
      >
        <template #default="{ row }">
          {{ labelOf(INSPECTION_TYPE_LABEL, (row as InspectionPlanRow).inspectionType) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="version"
        label="版本"
        width="90"
      />
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as InspectionPlanRow).status)"
          >
            {{ labelOf(INSPECTION_PLAN_STATUS_LABEL, (row as InspectionPlanRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as InspectionPlanRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="140"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as InspectionPlanRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as InspectionPlanRow).status === 'DRAFT'"
            link
            @click="activate(row as InspectionPlanRow)"
          >
            生效
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
    title="检验计划详情"
    size="640px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="计划号">
            {{ detail.planNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(INSPECTION_PLAN_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="物料">
            {{ detail.itemName }} <span class="mono">{{ detail.itemCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ labelOf(INSPECTION_TYPE_LABEL, detail.inspectionType) }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.supplierId ? supplierNames.get(detail.supplierId) ?? '—' : '通用' }}
          </el-descriptions-item>
          <el-descriptions-item label="AQL">
            {{ detail.aql ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table
          :data="detail.characteristics"
          class="table-list detail-table"
        >
          <el-table-column
            prop="lineNo"
            label="#"
            width="50"
          />
          <el-table-column
            prop="name"
            label="特性"
            min-width="160"
          />
          <el-table-column
            prop="spec"
            label="规格"
            min-width="150"
          />
          <el-table-column
            label="关键"
            width="80"
          >
            <template #default="{ row }">
              {{ row.critical ? '是' : '否' }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到检验计划"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建检验计划"
    width="820px"
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
        <el-form-item label="供应商">
          <el-select
            v-model="form.supplierId"
            placeholder="通用（可空）"
            clearable
            filterable
          >
            <el-option
              v-for="supplier in props.suppliers"
              :key="supplier.id"
              :label="`${supplier.code} · ${supplier.name}`"
              :value="supplier.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="检验类型">
          <el-select v-model="form.inspectionType">
            <el-option
              v-for="[value, label] in typeOptions"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input
            v-model="form.version"
            maxlength="16"
          />
        </el-form-item>
        <el-form-item label="AQL">
          <el-input-number
            v-model="form.aql"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="抽样方案">
          <el-input
            v-model="form.samplingPlan"
            maxlength="64"
            placeholder="可留空"
          />
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">检验特性</span>
        <el-button
          link
          @click="addCharacteristic"
        >
          添加特性
        </el-button>
      </div>
      <el-table
        :data="form.characteristics"
        class="table-list"
      >
        <el-table-column
          label="特性名"
          min-width="160"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.name"
              maxlength="100"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="规格"
          min-width="150"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.spec"
              maxlength="255"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="关键"
          width="80"
        >
          <template #default="{ row }">
            <el-switch v-model="row.critical" />
          </template>
        </el-table-column>
        <el-table-column
          label="方法"
          min-width="150"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.method"
              maxlength="64"
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
              @click="removeCharacteristic($index)"
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
        创建计划
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

.line-number {
  width: 100%;
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

.detail-table {
  margin-top: 20px;
}
</style>
