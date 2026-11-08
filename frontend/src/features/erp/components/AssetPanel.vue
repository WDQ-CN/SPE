<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  capitalizeAsset,
  createAsset,
  depreciateAsset,
  disposeAsset,
  getAsset,
  listAssets,
  type AssetDetail,
  type AssetRow,
  type CompanyOption,
  type PlantOption,
} from '@/features/erp/api'
import {
  ASSET_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[]; plants: PlantOption[] }>()

const filters = reactive<{ companyId?: number; status: string }>({ status: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<AssetRow>(
  (targetPage, pageSize) =>
    listAssets({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      status: filters.status || undefined,
    }),
)

const statusOptions = Object.entries(ASSET_STATUS_LABEL)

onMounted(() => {
  filters.companyId = props.companies[0]?.id
  void load(1)
})

function search(): void {
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AssetDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getAsset(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail(id: number): Promise<void> {
  detail.value = await getAsset(id)
}

async function capitalize(row: AssetRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定资本化资产「${row.name}」吗？`, '资本化', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await capitalizeAsset(row.id)
    ElMessage.success('资产已资本化')
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

async function depreciate(row: AssetRow): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请输入折旧期间（YYYY-MM）', '计提折旧', {
      inputPattern: /^\d{4}-(0[1-9]|1[0-2])$/,
      inputErrorMessage: '期间必须为 YYYY-MM',
    })
    await depreciateAsset(row.id, value)
    ElMessage.success('折旧已计提')
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // 取消或接口错误
  }
}

async function dispose(row: AssetRow): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请输入处置收入', '处置资产', {
      inputPattern: /^\d+(\.\d{1,4})?$/,
      inputErrorMessage: '请输入非负金额',
    })
    await disposeAsset(row.id, Number(value))
    ElMessage.success('资产已处置')
    await load()
    if (detail.value?.id === row.id) {
      await refreshDetail(row.id)
    }
  } catch {
    // 取消或接口错误
  }
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  plantId: number | undefined
  category: string
  name: string
  cost: number
  salvageValue: number
  usefulLifeMonths: number
  depreciationMethod: string
}>({
  companyId: undefined,
  plantId: undefined,
  category: '',
  name: '',
  cost: 0,
  salvageValue: 0,
  usefulLifeMonths: 60,
  depreciationMethod: 'STRAIGHT_LINE',
})

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function openCreate(): void {
  form.companyId = filters.companyId ?? props.companies[0]?.id
  form.plantId = undefined
  form.category = ''
  form.name = ''
  form.cost = 0
  form.salvageValue = 0
  form.usefulLifeMonths = 60
  form.depreciationMethod = 'STRAIGHT_LINE'
  clearError()
  createVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.companyId || !form.category.trim() || !form.name.trim()) {
    ElMessage.warning('请填写公司与资产类别/名称')
    return
  }
  if (form.cost <= 0 || form.usefulLifeMonths <= 0) {
    ElMessage.warning('原值与使用寿命必须大于 0')
    return
  }
  const ok = await runSave(async () => {
    await createAsset({
      companyId: form.companyId as number,
      plantId: form.plantId ?? null,
      category: form.category.trim(),
      name: form.name.trim(),
      sourceType: 'MANUAL',
      sourceId: null,
      cost: form.cost,
      salvageValue: form.salvageValue,
      usefulLifeMonths: form.usefulLifeMonths,
      depreciationMethod: form.depreciationMethod.trim() || 'STRAIGHT_LINE',
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('资产卡片已创建')
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
        v-model="filters.companyId"
        class="list-toolbar__select"
        placeholder="公司"
        clearable
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
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        新建资产
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
        label="资产号"
        min-width="140"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as AssetRow).assetNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="名称"
        min-width="180"
      />
      <el-table-column
        prop="category"
        label="类别"
        width="110"
      />
      <el-table-column
        label="原值"
        align="right"
        width="130"
      >
        <template #default="{ row }">
          {{ formatAmount((row as AssetRow).cost) }}
        </template>
      </el-table-column>
      <el-table-column
        label="累计折旧"
        align="right"
        width="130"
      >
        <template #default="{ row }">
          {{ formatAmount((row as AssetRow).accumulatedDepreciation) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as AssetRow).status)"
          >
            {{ labelOf(ASSET_STATUS_LABEL, (row as AssetRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="250"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as AssetRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as AssetRow).status === 'PLANNED'"
            link
            @click="capitalize(row as AssetRow)"
          >
            资本化
          </el-button>
          <el-button
            v-if="(row as AssetRow).status === 'ACTIVE'"
            link
            @click="depreciate(row as AssetRow)"
          >
            折旧
          </el-button>
          <el-button
            v-if="(row as AssetRow).status === 'ACTIVE'"
            link
            @click="dispose(row as AssetRow)"
          >
            处置
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
    title="资产详情"
    size="640px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="资产号">
            {{ detail.assetNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(ASSET_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="名称">
            {{ detail.name }}
          </el-descriptions-item>
          <el-descriptions-item label="类别">
            {{ detail.category }}
          </el-descriptions-item>
          <el-descriptions-item label="原值">
            {{ formatAmount(detail.cost) }}
          </el-descriptions-item>
          <el-descriptions-item label="净值">
            {{ formatAmount(detail.netBookValue) }}
          </el-descriptions-item>
          <el-descriptions-item label="累计折旧">
            {{ formatAmount(detail.accumulatedDepreciation) }}
          </el-descriptions-item>
          <el-descriptions-item label="最后折旧期间">
            {{ detail.lastDepreciationPeriod || '—' }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.disposedAt"
            label="处置"
            :span="2"
          >
            收入 {{ formatAmount(detail.disposalProceeds) }} · 损益
            {{ formatAmount(detail.disposalGainLoss) }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          折旧记录
        </h3>
        <el-table
          :data="detail.depreciations"
          class="table-list"
          empty-text="尚未计提"
        >
          <el-table-column
            prop="period"
            label="期间"
            width="110"
          />
          <el-table-column
            label="金额"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ formatAmount(row.amount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="累计后"
            align="right"
            width="130"
          >
            <template #default="{ row }">
              {{ formatAmount(row.accumulatedAfter) }}
            </template>
          </el-table-column>
          <el-table-column
            label="过账时间"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatDateTime(row.postedAt) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到资产"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建固定资产"
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
    <el-form label-width="100px">
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
        <el-form-item label="工厂">
          <el-select
            v-model="form.plantId"
            placeholder="可空"
            clearable
          >
            <el-option
              v-for="plant in props.plants.filter((item) => item.companyId === form.companyId)"
              :key="plant.id"
              :label="`${plant.code} · ${plant.name}`"
              :value="plant.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="资产类别">
          <el-input
            v-model="form.category"
            maxlength="50"
          />
        </el-form-item>
        <el-form-item label="资产名称">
          <el-input
            v-model="form.name"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="原值">
          <el-input-number
            v-model="form.cost"
            :min="0.0001"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="残值">
          <el-input-number
            v-model="form.salvageValue"
            :min="0"
            :precision="4"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="使用寿命">
          <el-input-number
            v-model="form.usefulLifeMonths"
            :min="1"
            :controls="false"
            class="line-number"
          />
        </el-form-item>
        <el-form-item label="折旧方法">
          <el-input
            v-model="form.depreciationMethod"
            maxlength="16"
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
        创建资产
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

.detail-title {
  margin: 24px 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
