<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  activateBom,
  createBom,
  getBom,
  pageBoms,
  type BomDetail,
  type BomRow,
  type CompanyOption,
  type ItemOption,
} from '@/features/erp/api'
import { BOM_STATUS_LABEL, formatDateTime, formatQty, labelOf, statusTone } from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const props = defineProps<{ companies: CompanyOption[]; items: ItemOption[] }>()

const companyId = ref<number | undefined>(props.companies[0]?.id)
const itemNames = computed(() => new Map(props.items.map((item) => [item.id, item.name])))

function itemName(id: number): string {
  return itemNames.value.get(id) ?? `#${id}`
}

const { rows, total, page, size, loading, error, load } = usePagedList<BomRow>(
  (targetPage, pageSize) =>
    pageBoms({
      page: targetPage,
      size: pageSize,
      companyId: companyId.value as number,
    }),
)

onMounted(() => {
  if (companyId.value) {
    void load(1)
  }
})

function onCompanyChange(): void {
  void load(1)
}

// ---- 详情 ----

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<BomDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getBom(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function activate(row: BomRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定生效 BOM「${row.bomNo}」吗？同物料旧版本会停用。`,
    '生效',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await activateBom(row.id)
    ElMessage.success('BOM 已生效')
    await load()
    if (detail.value?.id === row.id) {
      detail.value = await getBom(row.id)
    }
  } catch {
    // http 拦截器已提示
  }
}

// ---- 创建 ----

interface BomLineForm {
  componentItemId: number | undefined
  qty: number
  uom: string
}

const createVisible = ref(false)
const form = reactive<{
  companyId: number | undefined
  itemId: number | undefined
  version: string
  lines: BomLineForm[]
}>({ companyId: undefined, itemId: undefined, version: 'V1', lines: [] })

const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

function newLine(): BomLineForm {
  return { componentItemId: undefined, qty: 1, uom: 'EA' }
}

function onComponentChange(line: BomLineForm): void {
  const item = props.items.find((option) => option.id === line.componentItemId)
  if (item) {
    line.uom = item.baseUom
  }
}

function openCreate(): void {
  form.companyId = companyId.value
  form.itemId = undefined
  form.version = 'V1'
  form.lines = [newLine()]
  clearError()
  createVisible.value = true
}

function addLine(): void {
  form.lines.push(newLine())
}

function removeLine(index: number): void {
  if (form.lines.length <= 1) {
    ElMessage.warning('BOM 至少保留一行')
    return
  }
  form.lines.splice(index, 1)
}

function validate(): string {
  if (!form.companyId || !form.itemId) {
    return '请选择公司与产出物料'
  }
  if (!form.version.trim()) {
    return '请填写版本'
  }
  for (const [index, line] of form.lines.entries()) {
    if (!line.componentItemId) {
      return `第 ${index + 1} 行请选择组件物料`
    }
    if (line.qty <= 0) {
      return `第 ${index + 1} 行用量必须大于 0`
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
    await createBom({
      companyId: form.companyId as number,
      itemId: form.itemId as number,
      version: form.version.trim(),
      lines: form.lines.map((line) => ({
        componentItemId: line.componentItemId as number,
        qty: line.qty,
        uom: line.uom.trim() || 'EA',
      })),
    })
  })
  if (ok) {
    createVisible.value = false
    ElMessage.success('BOM 已创建')
    await load(1)
  }
}
</script>

<template>
  <div>
    <form
      class="list-toolbar"
      @submit.prevent="load(1)"
    >
      <el-select
        v-model="companyId"
        class="list-toolbar__select"
        placeholder="公司"
        aria-label="公司"
        @change="onCompanyChange"
      >
        <el-option
          v-for="company in props.companies"
          :key="company.id"
          :label="`${company.code} · ${company.name}`"
          :value="company.id"
        />
      </el-select>
      <el-button
        type="primary"
        @click="load(1)"
      >
        查询
      </el-button>
      <el-button
        class="toolbar-create"
        type="primary"
        @click="openCreate"
      >
        新建 BOM
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
        label="BOM"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as BomRow).bomNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="产出物料"
        min-width="190"
      >
        <template #default="{ row }">
          {{ itemName((row as BomRow).itemId) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="version"
        label="版本"
        width="100"
      />
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as BomRow).status)"
          >
            {{ labelOf(BOM_STATUS_LABEL, (row as BomRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as BomRow).createdAt) }}
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
            @click="openDetail((row as BomRow).id)"
          >
            详情
          </el-button>
          <el-button
            v-if="(row as BomRow).status === 'DRAFT'"
            link
            @click="activate(row as BomRow)"
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
    title="BOM 详情"
    size="620px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="BOM">
            {{ detail.bomNo }}
          </el-descriptions-item>
          <el-descriptions-item label="版本">
            {{ detail.version }}
          </el-descriptions-item>
          <el-descriptions-item label="产出物料">
            {{ detail.itemName }} <span class="mono">{{ detail.itemCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(BOM_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table
          :data="detail.lines"
          class="table-list detail-table"
        >
          <el-table-column
            prop="lineNo"
            label="#"
            width="50"
          />
          <el-table-column
            label="组件"
            min-width="190"
          >
            <template #default="{ row }">
              {{ row.componentName }} <span class="mono">{{ row.componentCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="用量"
            align="right"
            width="120"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }} {{ row.uom }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到 BOM"
      />
    </div>
  </el-drawer>

  <el-dialog
    v-model="createVisible"
    title="新建 BOM"
    width="780px"
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
          >
            <el-option
              v-for="company in props.companies"
              :key="company.id"
              :label="`${company.code} · ${company.name}`"
              :value="company.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产出物料">
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
        <el-form-item label="版本">
          <el-input
            v-model="form.version"
            maxlength="16"
          />
        </el-form-item>
      </div>

      <div class="line-head">
        <span class="line-head__title">组件行</span>
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
          label="组件物料"
          min-width="220"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.componentItemId"
              placeholder="选择组件"
              filterable
              @change="onComponentChange(row as BomLineForm)"
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
          label="用量"
          width="130"
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
          width="90"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.uom"
              maxlength="16"
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
        @click="submit"
      >
        创建 BOM
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
