<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'

import {
  createCertificate,
  pageCertificates,
  type CertificateRow,
  type CompanyOption,
  type ItemOption,
} from '@/features/erp/api'
import {
  CERT_EFFECTIVE_STATUS_LABEL,
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

const supplierId = ref<number | undefined>(undefined)
const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))
const supplierNames = computed(
  () => new Map(props.suppliers.map((supplier) => [supplier.id, supplier.name])),
)

function itemName(id: number | null): string {
  return id ? itemNames.value.get(id) ?? `#${id}` : '通用'
}

const { rows, total, page, size, loading, error, load } = usePagedList<CertificateRow>(
  (targetPage, pageSize) =>
    pageCertificates(targetPage, pageSize, props.companyId, supplierId.value),
)

watch(
  () => props.companyId,
  () => void load(1),
)

onMounted(() => {
  void load(1)
})

function search(): void {
  void load(1)
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  supplierId: number | undefined
  itemId: number | undefined
  certType: string
  certNo: string
  validFrom: string
  validTo: string
}>({
  companyId: undefined,
  supplierId: undefined,
  itemId: undefined,
  certType: '',
  certNo: '',
  validFrom: '',
  validTo: '',
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function openCreate(): void {
  form.companyId = props.companyId ?? props.companies[0]?.id
  form.supplierId = supplierId.value
  form.itemId = undefined
  form.certType = ''
  form.certNo = ''
  form.validFrom = new Date().toISOString().slice(0, 10)
  form.validTo = ''
  clearError()
  createVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.supplierId) {
    ElMessage.warning('请选择公司与供应商')
    return
  }
  if (!form.certType.trim() || !form.certNo.trim()) {
    ElMessage.warning('请填写证书类型与编号')
    return
  }
  if (!form.validFrom || !form.validTo) {
    ElMessage.warning('请选择证书有效期')
    return
  }
  if (form.validTo < form.validFrom) {
    ElMessage.warning('失效日不能早于生效日')
    return
  }
  const ok = await runSave(async () => {
    await createCertificate({
      companyId: form.companyId as number,
      supplierId: form.supplierId as number,
      itemId: form.itemId ?? null,
      certType: form.certType.trim().toUpperCase(),
      certNo: form.certNo.trim(),
      validFrom: form.validFrom,
      validTo: form.validTo,
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('供应商证书已登记')
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
        v-model="supplierId"
        class="list-toolbar__select"
        placeholder="供应商"
        clearable
        filterable
        aria-label="供应商"
        @change="search"
      >
        <el-option
          v-for="supplier in props.suppliers"
          :key="supplier.id"
          :label="`${supplier.code} · ${supplier.name}`"
          :value="supplier.id"
        />
      </el-select>
      <el-button
        type="primary"
        @click="search"
      >
        查询
      </el-button>
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        登记证书
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
        prop="certType"
        label="类型"
        width="100"
      />
      <el-table-column
        label="证书编号"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as CertificateRow).certNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="供应商"
        min-width="160"
      >
        <template #default="{ row }">
          {{ supplierNames.get((row as CertificateRow).supplierId) ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="物料"
        min-width="150"
      >
        <template #default="{ row }">
          {{ itemName((row as CertificateRow).itemId) }}
        </template>
      </el-table-column>
      <el-table-column
        label="有效期"
        min-width="190"
      >
        <template #default="{ row }">
          {{ (row as CertificateRow).validFrom }} ~ {{ (row as CertificateRow).validTo }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as CertificateRow).effectiveStatus)"
          >
            {{ labelOf(CERT_EFFECTIVE_STATUS_LABEL, (row as CertificateRow).effectiveStatus) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="剩余天数"
        align="right"
        width="100"
      >
        <template #default="{ row }">
          {{ (row as CertificateRow).daysUntilExpiry }}
        </template>
      </el-table-column>
      <el-table-column
        label="登记时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as CertificateRow).validFrom) }}
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
    title="登记供应商证书"
    width="540px"
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
      <el-form-item label="供应商">
        <el-select
          v-model="form.supplierId"
          placeholder="选择供应商"
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
      <el-form-item label="物料">
        <el-select
          v-model="form.itemId"
          placeholder="通用（可空）"
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
      <el-form-item label="证书类型">
        <el-input
          v-model="form.certType"
          maxlength="32"
          placeholder="如 ISO9001 / UL"
        />
      </el-form-item>
      <el-form-item label="证书编号">
        <el-input
          v-model="form.certNo"
          maxlength="64"
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
        @click="submit"
      >
        保存
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
</style>
