<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'

import {
  createSupplierPrice,
  listSupplierPrices,
  listSuppliers,
  type CompanyOption,
  type ItemOption,
  type SupplierPriceRow,
} from '@/features/erp/api'
import {
  PRICE_SOURCE_LABEL,
  PRICE_STATUS_LABEL,
  formatAmount,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { SupplierSummary } from '@/shared/types/dto'

const props = defineProps<{ companies: CompanyOption[]; items: ItemOption[] }>()

const suppliers = ref<SupplierSummary[]>([])
const rows = ref<SupplierPriceRow[]>([])
const loading = ref(false)
const error = ref('')
const filters = reactive<{ supplierId?: number; itemId?: number; date: string }>({
  supplierId: undefined,
  itemId: undefined,
  date: '',
})

onMounted(async () => {
  try {
    suppliers.value = await listSuppliers()
    filters.supplierId = suppliers.value[0]?.id
    if (filters.supplierId) {
      await search()
    }
  } catch {
    suppliers.value = []
  }
})

async function search(): Promise<void> {
  if (!filters.supplierId) {
    error.value = '请选择供应商'
    rows.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    rows.value = await listSupplierPrices({
      supplierId: filters.supplierId,
      itemId: filters.itemId,
      date: filters.date || undefined,
    })
  } catch (caught) {
    rows.value = []
    error.value = caught instanceof Error ? caught.message : '价格查询失败'
  } finally {
    loading.value = false
  }
}

function resetSearch(): void {
  filters.itemId = undefined
  filters.date = ''
  void search()
}

// ---- 创建 ----

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  supplierId: number | undefined
  itemId: number | undefined
  uom: string
  minQty: number | null
  price: number
  taxIncluded: boolean
  currency: string
  validFrom: string
  validTo: string
}>({
  companyId: undefined,
  supplierId: undefined,
  itemId: undefined,
  uom: '',
  minQty: null,
  price: 0,
  taxIncluded: true,
  currency: 'CNY',
  validFrom: new Date().toISOString().slice(0, 10),
  validTo: '',
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function onItemChange(): void {
  const item = props.items.find((option) => option.id === form.itemId)
  if (item) {
    form.uom = item.baseUom
  }
}

function onCompanyChange(): void {
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
}

function openCreate(): void {
  form.companyId = props.companies[0]?.id
  form.supplierId = filters.supplierId
  form.itemId = filters.itemId
  form.uom = ''
  form.minQty = null
  form.price = 0
  form.taxIncluded = true
  form.currency =
    props.companies.find((company) => company.id === form.companyId)?.baseCurrency ?? 'CNY'
  form.validFrom = new Date().toISOString().slice(0, 10)
  form.validTo = ''
  if (form.itemId) {
    onItemChange()
  }
  clearError()
  createVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.supplierId || !form.itemId) {
    ElMessage.warning('请选择公司、供应商与物料')
    return
  }
  if (!form.validFrom) {
    ElMessage.warning('请选择生效日')
    return
  }
  if (form.price < 0) {
    ElMessage.warning('价格不能为负')
    return
  }
  if (form.validTo && form.validTo < form.validFrom) {
    ElMessage.warning('失效日不能早于生效日')
    return
  }
  const ok = await runSave(async () => {
    await createSupplierPrice({
      companyId: form.companyId as number,
      supplierId: form.supplierId as number,
      itemId: form.itemId as number,
      uom: form.uom.trim() || null,
      minQty: form.minQty,
      price: form.price,
      taxIncluded: form.taxIncluded,
      currency: form.currency.trim().toUpperCase(),
      validFrom: form.validFrom,
      validTo: form.validTo || null,
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('供应商价格已维护')
    await search()
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
        v-model="filters.supplierId"
        class="list-toolbar__select"
        placeholder="供应商（必选）"
        filterable
        aria-label="供应商"
        @change="search"
      >
        <el-option
          v-for="supplier in suppliers"
          :key="supplier.id"
          :label="`${supplier.code} · ${supplier.name}`"
          :value="supplier.id"
        />
      </el-select>
      <el-select
        v-model="filters.itemId"
        class="list-toolbar__select"
        placeholder="物料"
        clearable
        filterable
        aria-label="物料"
        @change="search"
      >
        <el-option
          v-for="item in props.items"
          :key="item.id"
          :label="`${item.code} · ${item.name}`"
          :value="item.id"
        />
      </el-select>
      <el-date-picker
        v-model="filters.date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="生效日（默认今天）"
        class="date-filter"
        @change="search"
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
        维护价格
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
      empty-text="该供应商在所选日期没有有效价格"
    >
      <el-table-column
        label="物料"
        min-width="180"
      >
        <template #default="{ row }">
          {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="单价"
        align="right"
        width="130"
      >
        <template #default="{ row }">
          {{ formatAmount(row.price, row.currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="含税"
        width="80"
      >
        <template #default="{ row }">
          {{ row.taxIncluded ? '是' : '否' }}
        </template>
      </el-table-column>
      <el-table-column
        label="最小量"
        align="right"
        width="100"
      >
        <template #default="{ row }">
          {{ formatQty(row.minQty) }}
        </template>
      </el-table-column>
      <el-table-column
        label="有效期"
        min-width="190"
      >
        <template #default="{ row }">
          {{ row.validFrom }} ~ {{ row.validTo || '长期' }}
        </template>
      </el-table-column>
      <el-table-column
        label="来源"
        width="110"
      >
        <template #default="{ row }">
          {{ labelOf(PRICE_SOURCE_LABEL, row.sourceType) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone(row.status)"
          >
            {{ labelOf(PRICE_STATUS_LABEL, row.status) }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <el-dialog
    v-model="createVisible"
    title="维护供应商价格"
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
        <el-form-item label="供应商">
          <el-select
            v-model="form.supplierId"
            placeholder="选择供应商"
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
        <el-form-item label="物料">
          <el-select
            v-model="form.itemId"
            placeholder="选择物料"
            filterable
            @change="onItemChange"
          >
            <el-option
              v-for="item in props.items"
              :key="item.id"
              :label="`${item.code} · ${item.name}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="单位">
          <el-input
            v-model="form.uom"
            maxlength="16"
            placeholder="默认物料单位"
          />
        </el-form-item>
        <el-form-item label="最小量">
          <el-input-number
            v-model="form.minQty"
            :min="0"
            :precision="6"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="单价">
          <el-input-number
            v-model="form.price"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="含税">
          <el-switch v-model="form.taxIncluded" />
        </el-form-item>
        <el-form-item label="币种">
          <el-input
            v-model="form.currency"
            maxlength="3"
          />
        </el-form-item>
        <el-form-item label="生效日">
          <el-date-picker
            v-model="form.validFrom"
            type="date"
            value-format="YYYY-MM-DD"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="失效日">
          <el-date-picker
            v-model="form.validTo"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="长期"
            class="line-number"
          />
        </el-form-item>
      </div>
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
        保存价格
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar-create {
  margin-left: auto;
}

.date-filter {
  width: 190px;
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
