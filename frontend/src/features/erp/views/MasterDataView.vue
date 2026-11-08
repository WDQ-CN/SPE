<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  changeMasterDataStatus,
  createCompany,
  createCustomer,
  createItem,
  createPlant,
  createWarehouse,
  listCompanies,
  listPlants,
  pageCompanies,
  pageCustomers,
  pageItems,
  pagePlants,
  pageWarehouses,
  updateCompany,
  updateCustomer,
  updateItem,
  updatePlant,
  updateWarehouse,
  type CompanyOption,
  type CompanyRecord,
  type CustomerRecord,
  type ItemRecord,
  type MasterDataQuery,
  type PlantOption,
  type PlantRecord,
  type WarehouseRecord,
} from '@/features/erp/api'
import {
  ITEM_TYPE_LABEL,
  MASTER_STATUS_LABEL,
  VALUATION_METHOD_LABEL,
  WAREHOUSE_TYPE_LABEL,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { PageResult } from '@/shared/types/result'

type Kind = 'companies' | 'plants' | 'warehouses' | 'items' | 'customers'
type MasterRow = CompanyRecord | PlantRecord | WarehouseRecord | ItemRecord | CustomerRecord

interface FieldDef {
  key: string
  label: string
  type: 'text' | 'number' | 'select' | 'switch'
  required?: boolean
  max?: number
  min?: number
  precision?: number
  options?: { label: string; value: string }[]
}

interface ColumnDef {
  key: string
  label: string
  width?: number
  format?: (row: MasterRow) => string
}

interface MasterQuery extends MasterDataQuery {
  companyId?: number
  plantId?: number
  itemType?: string
}

interface KindConfig {
  label: string
  resource: Kind
  fields: FieldDef[]
  columns: ColumnDef[]
  defaults: Record<string, unknown>
  fetch: (query: MasterQuery) => Promise<PageResult<MasterRow>>
  create: (form: Record<string, unknown>) => Promise<unknown>
  update: (id: number, form: Record<string, unknown>) => Promise<unknown>
}

const kind = ref<Kind>('companies')
const keyword = ref('')
const status = ref('')
const filterCompanyId = ref<number | undefined>(undefined)
const filterPlantId = ref<number | undefined>(undefined)
const filterItemType = ref('')

const companies = ref<CompanyOption[]>([])
const plants = ref<PlantOption[]>([])

const companyNames = computed(() => new Map(companies.value.map((item) => [item.id, item.name])))
const plantNames = computed(() => new Map(plants.value.map((item) => [item.id, item.name])))

function text(form: Record<string, unknown>, key: string): string {
  return String(form[key] ?? '').trim()
}

function optionalText(form: Record<string, unknown>, key: string): string | null {
  const value = text(form, key)
  return value || null
}

function number(form: Record<string, unknown>, key: string): number | null {
  const value = Number(form[key])
  return Number.isFinite(value) ? value : null
}

function flag(form: Record<string, unknown>, key: string): boolean {
  return Boolean(form[key])
}

const configs: Record<Kind, KindConfig> = {
  companies: {
    label: '公司',
    resource: 'companies',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true, max: 32 },
      { key: 'name', label: '名称', type: 'text', required: true, max: 128 },
      { key: 'baseCurrency', label: '本位币', type: 'text', required: true, max: 3 },
      { key: 'taxNo', label: '税号', type: 'text', max: 64 },
      { key: 'country', label: '国家', type: 'text', required: true, max: 2 },
    ],
    columns: [
      { key: 'code', label: '编码' },
      { key: 'name', label: '名称' },
      { key: 'baseCurrency', label: '本位币' },
      { key: 'country', label: '国家' },
    ],
    defaults: { baseCurrency: 'CNY', country: 'CN' },
    fetch: (query) => pageCompanies(query),
    create: (form) =>
      createCompany({
        code: text(form, 'code'),
        name: text(form, 'name'),
        baseCurrency: text(form, 'baseCurrency').toUpperCase(),
        taxNo: optionalText(form, 'taxNo'),
        country: text(form, 'country').toUpperCase(),
      }),
    update: (id, form) =>
      updateCompany(id, {
        code: text(form, 'code'),
        name: text(form, 'name'),
        baseCurrency: text(form, 'baseCurrency').toUpperCase(),
        taxNo: optionalText(form, 'taxNo'),
        country: text(form, 'country').toUpperCase(),
        version: Number(form.version),
      }),
  },
  plants: {
    label: '工厂',
    resource: 'plants',
    fields: [
      { key: 'companyId', label: '所属公司', type: 'select', required: true },
      { key: 'code', label: '编码', type: 'text', required: true, max: 32 },
      { key: 'name', label: '名称', type: 'text', required: true, max: 128 },
      { key: 'address', label: '地址', type: 'text', max: 255 },
    ],
    columns: [
      { key: 'code', label: '编码' },
      { key: 'name', label: '名称' },
      { key: 'companyId', label: '公司', format: (row) => companyNames.value.get((row as PlantRecord).companyId) ?? '—' },
      { key: 'address', label: '地址' },
    ],
    defaults: {},
    fetch: (query) => pagePlants(query),
    create: (form) =>
      createPlant({
        companyId: Number(form.companyId),
        code: text(form, 'code'),
        name: text(form, 'name'),
        address: optionalText(form, 'address'),
      }),
    update: (id, form) =>
      updatePlant(id, {
        code: text(form, 'code'),
        name: text(form, 'name'),
        address: optionalText(form, 'address'),
        version: Number(form.version),
      }),
  },
  warehouses: {
    label: '仓库',
    resource: 'warehouses',
    fields: [
      { key: 'plantId', label: '所属工厂', type: 'select', required: true },
      { key: 'code', label: '编码', type: 'text', required: true, max: 32 },
      { key: 'name', label: '名称', type: 'text', required: true, max: 128 },
      {
        key: 'warehouseType',
        label: '类型',
        type: 'select',
        required: true,
        options: Object.entries(WAREHOUSE_TYPE_LABEL).map(([value, label]) => ({ value, label })),
      },
    ],
    columns: [
      { key: 'code', label: '编码' },
      { key: 'name', label: '名称' },
      { key: 'plantId', label: '工厂', format: (row) => plantNames.value.get((row as WarehouseRecord).plantId) ?? '—' },
      {
        key: 'warehouseType',
        label: '类型',
        format: (row) => labelOf(WAREHOUSE_TYPE_LABEL, (row as WarehouseRecord).warehouseType),
      },
    ],
    defaults: { warehouseType: 'STANDARD' },
    fetch: (query) => pageWarehouses(query),
    create: (form) =>
      createWarehouse({
        plantId: Number(form.plantId),
        code: text(form, 'code'),
        name: text(form, 'name'),
        warehouseType: text(form, 'warehouseType'),
      }),
    update: (id, form) =>
      updateWarehouse(id, {
        code: text(form, 'code'),
        name: text(form, 'name'),
        warehouseType: text(form, 'warehouseType'),
        version: Number(form.version),
      }),
  },
  items: {
    label: '物料',
    resource: 'items',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true, max: 50 },
      { key: 'name', label: '名称', type: 'text', required: true, max: 200 },
      { key: 'spec', label: '规格', type: 'text', max: 255 },
      {
        key: 'itemType',
        label: '类型',
        type: 'select',
        required: true,
        options: Object.entries(ITEM_TYPE_LABEL).map(([value, label]) => ({ value, label })),
      },
      { key: 'baseUom', label: '基本单位', type: 'text', required: true, max: 16 },
      { key: 'lotControlled', label: '批次管理', type: 'switch' },
      { key: 'serialControlled', label: '序列管理', type: 'switch' },
      {
        key: 'valuationMethod',
        label: '计价方法',
        type: 'select',
        required: true,
        options: Object.entries(VALUATION_METHOD_LABEL).map(([value, label]) => ({ value, label })),
      },
      { key: 'standardCost', label: '标准成本', type: 'number', min: 0, precision: 4 },
    ],
    columns: [
      { key: 'code', label: '编码' },
      { key: 'name', label: '名称' },
      { key: 'itemType', label: '类型', format: (row) => labelOf(ITEM_TYPE_LABEL, (row as ItemRecord).itemType) },
      { key: 'baseUom', label: '单位' },
      {
        key: 'valuationMethod',
        label: '计价',
        format: (row) => labelOf(VALUATION_METHOD_LABEL, (row as ItemRecord).valuationMethod),
      },
      {
        key: 'lotControlled',
        label: '批次',
        format: (row) => ((row as ItemRecord).lotControlled ? '是' : '否'),
      },
    ],
    defaults: {
      itemType: 'RAW',
      baseUom: 'EA',
      lotControlled: false,
      serialControlled: false,
      valuationMethod: 'AVCO',
      standardCost: 0,
    },
    fetch: (query) => pageItems(query),
    create: (form) =>
      createItem({
        code: text(form, 'code'),
        name: text(form, 'name'),
        spec: optionalText(form, 'spec'),
        itemType: text(form, 'itemType'),
        baseUom: text(form, 'baseUom'),
        lotControlled: flag(form, 'lotControlled'),
        serialControlled: flag(form, 'serialControlled'),
        valuationMethod: text(form, 'valuationMethod'),
        standardCost: number(form, 'standardCost'),
      }),
    update: (id, form) =>
      updateItem(id, {
        code: text(form, 'code'),
        name: text(form, 'name'),
        spec: optionalText(form, 'spec'),
        itemType: text(form, 'itemType'),
        baseUom: text(form, 'baseUom'),
        lotControlled: flag(form, 'lotControlled'),
        serialControlled: flag(form, 'serialControlled'),
        valuationMethod: text(form, 'valuationMethod'),
        standardCost: number(form, 'standardCost'),
        version: Number(form.version),
      }),
  },
  customers: {
    label: '客户',
    resource: 'customers',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true, max: 50 },
      { key: 'name', label: '名称', type: 'text', required: true, max: 200 },
      { key: 'taxNo', label: '税号', type: 'text', max: 64 },
      { key: 'address', label: '地址', type: 'text', max: 255 },
      { key: 'contactName', label: '联系人', type: 'text', max: 64 },
      { key: 'contactPhone', label: '联系电话', type: 'text', max: 32 },
      { key: 'currency', label: '币种', type: 'text', required: true, max: 3 },
      { key: 'paymentTerms', label: '账期', type: 'text', max: 32 },
      { key: 'creditLimit', label: '信用额度', type: 'number', min: 0, precision: 2 },
    ],
    columns: [
      { key: 'code', label: '编码' },
      { key: 'name', label: '名称' },
      { key: 'currency', label: '币种' },
      { key: 'creditLimit', label: '信用额度' },
      { key: 'creditUsed', label: '已用额度' },
    ],
    defaults: { currency: 'CNY', creditLimit: 0 },
    fetch: (query) => pageCustomers(query),
    create: (form) =>
      createCustomer({
        code: text(form, 'code'),
        name: text(form, 'name'),
        taxNo: optionalText(form, 'taxNo'),
        address: optionalText(form, 'address'),
        contactName: optionalText(form, 'contactName'),
        contactPhone: optionalText(form, 'contactPhone'),
        currency: text(form, 'currency').toUpperCase(),
        paymentTerms: optionalText(form, 'paymentTerms'),
        creditLimit: number(form, 'creditLimit'),
      }),
    update: (id, form) =>
      updateCustomer(id, {
        code: text(form, 'code'),
        name: text(form, 'name'),
        taxNo: optionalText(form, 'taxNo'),
        address: optionalText(form, 'address'),
        contactName: optionalText(form, 'contactName'),
        contactPhone: optionalText(form, 'contactPhone'),
        currency: text(form, 'currency').toUpperCase(),
        paymentTerms: optionalText(form, 'paymentTerms'),
        creditLimit: number(form, 'creditLimit'),
        version: Number(form.version),
      }),
  },
}

const config = computed(() => configs[kind.value])
const kindOptions = (Object.keys(configs) as Kind[]).map((value) => ({
  value,
  label: configs[value].label,
}))

const list = usePagedList<MasterRow>((page, size) =>
  config.value.fetch({
    page,
    size,
    keyword: keyword.value.trim() || undefined,
    status: status.value || undefined,
    companyId: kind.value === 'plants' ? filterCompanyId.value : undefined,
    plantId: kind.value === 'warehouses' ? filterPlantId.value : undefined,
    itemType: kind.value === 'items' ? filterItemType.value || undefined : undefined,
  }),
)

onMounted(async () => {
  try {
    const [companyList, plantList] = await Promise.all([listCompanies(), listPlants()])
    companies.value = companyList
    plants.value = plantList
  } catch {
    companies.value = []
    plants.value = []
  }
  await list.load(1)
})

function switchKind(next: Kind): void {
  kind.value = next
  keyword.value = ''
  status.value = ''
  filterCompanyId.value = undefined
  filterPlantId.value = undefined
  filterItemType.value = ''
  void list.load(1)
}

function search(): void {
  void list.load(1)
}

function resetSearch(): void {
  keyword.value = ''
  status.value = ''
  filterCompanyId.value = undefined
  filterPlantId.value = undefined
  filterItemType.value = ''
  void list.load(1)
}

function fieldOptions(field: FieldDef): { label: string; value: string | number }[] {
  if (field.key === 'companyId') {
    return companies.value.map((company) => ({
      label: `${company.code} · ${company.name}`,
      value: company.id,
    }))
  }
  if (field.key === 'plantId') {
    return plants.value.map((plant) => ({
      label: `${plant.code} · ${plant.name}`,
      value: plant.id,
    }))
  }
  return field.options ?? []
}

function cell(row: MasterRow, column: ColumnDef): string {
  if (column.format) {
    return column.format(row)
  }
  const value = (row as unknown as Record<string, unknown>)[column.key]
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

function statusLabel(row: MasterRow): string {
  return labelOf(MASTER_STATUS_LABEL, row.status)
}

// ---- 表单 ----

const dialogVisible = ref(false)
const editing = ref<MasterRow | null>(null)
const form = reactive<Record<string, unknown>>({})
const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function fillForm(row: MasterRow | null): void {
  for (const key of Object.keys(form)) {
    delete form[key]
  }
  const defaults = config.value.defaults
  Object.assign(form, defaults)
  if (row) {
    Object.assign(form, row)
  } else {
    for (const field of config.value.fields) {
      if (form[field.key] === undefined) {
        form[field.key] = field.type === 'switch' ? false : ''
      }
    }
    form.companyId = companies.value[0]?.id
    form.plantId = plants.value[0]?.id
    form.version = 0
  }
  if (row && kind.value === 'plants') {
    form.companyId = (row as PlantRecord).companyId
  }
  if (row && kind.value === 'warehouses') {
    form.plantId = (row as WarehouseRecord).plantId
  }
  clearError()
}

function openCreate(): void {
  editing.value = null
  fillForm(null)
  dialogVisible.value = true
}

function openEdit(row: MasterRow): void {
  editing.value = row
  fillForm(row)
  dialogVisible.value = true
}

function validate(): string {
  for (const field of config.value.fields) {
    if (field.required && !String(form[field.key] ?? '').trim()) {
      return `请填写${field.label}`
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
  const row = editing.value
  const ok = await runSave(async () => {
    if (row) {
      await config.value.update(row.id, form)
    } else {
      await config.value.create(form)
    }
  })
  if (ok) {
    dialogVisible.value = false
    ElMessage.success(row ? '已保存' : '已新增')
    await list.load(row ? list.page.value : 1)
  }
}

async function toggleStatus(row: MasterRow): Promise<void> {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  const confirmed = await ElMessageBox.confirm(
    `确定要${next === 'ACTIVE' ? '启用' : '停用'}「${row.name}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await changeMasterDataStatus(config.value.resource, row.id, next, row.version)
    ElMessage.success(`已${next === 'ACTIVE' ? '启用' : '停用'}`)
    await list.load()
  } catch {
    // http 拦截器已提示
  }
}

function setValue(key: string, value: unknown): void {
  form[key] = value
}

function numberValue(key: string): number | undefined {
  const value = Number(form[key])
  return Number.isFinite(value) ? value : undefined
}

function selectValue(key: string): string | number | undefined {
  const value = form[key]
  return typeof value === 'string' || typeof value === 'number' ? value : undefined
}
</script>

<template>
  <PageHeader
    eyebrow="MASTER DATA / GOVERNANCE"
    title="主数据"
    :description="`${config.label} · 共 ${list.total.value} 条 · 编码唯一，停用不影响历史单据`"
  >
    <template #actions>
      <el-button
        type="primary"
        @click="openCreate"
      >
        新增{{ config.label }}
      </el-button>
    </template>
  </PageHeader>

  <div class="kind-switch">
    <el-radio-group
      :model-value="kind"
      @update:model-value="(value) => switchKind(value as Kind)"
    >
      <el-radio-button
        v-for="option in kindOptions"
        :key="option.value"
        :value="option.value"
      >
        {{ option.label }}
      </el-radio-button>
    </el-radio-group>
  </div>

  <form
    class="list-toolbar"
    @submit.prevent="search"
  >
    <el-input
      v-model="keyword"
      class="list-toolbar__search"
      placeholder="编码或名称"
      clearable
      aria-label="关键字"
      @keyup.enter="search"
    />
    <el-select
      v-if="kind === 'plants'"
      v-model="filterCompanyId"
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
      v-if="kind === 'warehouses'"
      v-model="filterPlantId"
      class="list-toolbar__select"
      placeholder="工厂"
      clearable
      aria-label="工厂"
      @change="search"
    >
      <el-option
        v-for="plant in plants"
        :key="plant.id"
        :label="`${plant.code} · ${plant.name}`"
        :value="plant.id"
      />
    </el-select>
    <el-select
      v-if="kind === 'items'"
      v-model="filterItemType"
      class="list-toolbar__select"
      placeholder="物料类型"
      clearable
      aria-label="物料类型"
      @change="search"
    >
      <el-option
        v-for="[value, label] in Object.entries(ITEM_TYPE_LABEL)"
        :key="value"
        :label="label"
        :value="value"
      />
    </el-select>
    <el-select
      v-model="status"
      class="list-toolbar__select"
      placeholder="状态"
      clearable
      aria-label="状态"
      @change="search"
    >
      <el-option
        v-for="[value, label] in Object.entries(MASTER_STATUS_LABEL)"
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
    v-if="list.error.value"
    class="page-alert"
    type="error"
    :title="list.error.value"
    :closable="false"
    show-icon
  >
    <el-button
      link
      @click="list.load()"
    >
      重试
    </el-button>
  </el-alert>

  <section>
    <el-table
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        v-for="column in config.columns"
        :key="column.key"
        :label="column.label"
        :min-width="column.width ?? 130"
      >
        <template #default="{ row }">
          {{ cell(row as MasterRow, column) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as MasterRow).status)"
          >
            {{ statusLabel(row as MasterRow) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="150"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openEdit(row as MasterRow)"
          >
            编辑
          </el-button>
          <el-button
            link
            @click="toggleStatus(row as MasterRow)"
          >
            {{ (row as MasterRow).status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      :page="list.page.value"
      :size="list.size.value"
      :total="list.total.value"
      class="list-pager"
      @update:page="(value: number) => (list.page.value = value)"
      @update:size="(value: number) => (list.size.value = value)"
      @change="(target: number) => void list.load(target)"
      @size-change="() => void list.load(1)"
    />
  </section>

  <el-dialog
    v-model="dialogVisible"
    :title="editing ? `编辑${config.label}` : `新增${config.label}`"
    width="620px"
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
        <el-form-item
          v-for="field in config.fields"
          :key="field.key"
          :label="field.label"
        >
          <el-input
            v-if="field.type === 'text'"
            :model-value="String(form[field.key] ?? '')"
            :maxlength="field.max"
            @update:model-value="(value) => setValue(field.key, value)"
          />
          <el-input-number
            v-else-if="field.type === 'number'"
            :model-value="numberValue(field.key)"
            :min="field.min"
            :precision="field.precision"
            :controls="false"
            class="line-number"
            @update:model-value="(value) => setValue(field.key, value)"
          />
          <el-select
            v-else-if="field.type === 'select'"
            :model-value="selectValue(field.key)"
            :disabled="Boolean(editing) && ['companyId', 'plantId'].includes(field.key)"
            placeholder="请选择"
            @update:model-value="(value) => setValue(field.key, value)"
          >
            <el-option
              v-for="option in fieldOptions(field)"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-switch
            v-else-if="field.type === 'switch'"
            :model-value="Boolean(form[field.key])"
            @update:model-value="(value) => setValue(field.key, value)"
          />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.kind-switch {
  margin-bottom: 16px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}

.line-number {
  width: 100%;
}
</style>
