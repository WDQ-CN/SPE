<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { listCorrectiveActions } from '@/features/action/api'
import { actionStatusClass, verifySummary } from '@/features/action/action-status'
import { useAuthStore } from '@/features/auth/store'
import { listSuppliers } from '@/features/supplier/api'
import { listUserOptions } from '@/features/user/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { formatScore } from '@/shared/format'
import {
  CORRECTIVE_ACTION_STATUS_LABEL,
  CORRECTIVE_ACTION_STATUS_OPTIONS,
  VERIFY_OUTCOME_LABEL,
} from '@/shared/labels'
import type { CorrectiveActionSummary, SupplierSummary, UserOption } from '@/shared/types/dto'
import type { CorrectiveActionStatus } from '@/shared/types/enums'

/**
 * 整改任务列表（W4；29 §3.6）。
 *
 * <p>状态/超期对所有角色开放；供应商与负责人筛选只给 ADMIN/MANAGER——这两个目录面对
 * EVALUATOR 不开放，且 EVALUATOR 的列表本来就由后端强制为本人。供应商用远程搜索，
 * 避免一次只拉 100 家后第 101 家“静默无法筛选”。
 */
const auth = useAuthStore()
const filters = reactive<{
  status: CorrectiveActionStatus | ''
  overdue: '' | 'overdue'
  supplierId: number | undefined
  ownerId: number | undefined
}>({ status: '', overdue: '', supplierId: undefined, ownerId: undefined })

const list = usePagedList<CorrectiveActionSummary>((page, size) =>
  listCorrectiveActions({
    page,
    size,
    status: filters.status,
    overdue: filters.overdue === 'overdue' ? true : undefined,
    supplierId: positiveId(filters.supplierId),
    ownerId: positiveId(filters.ownerId),
  }),
)

const isEvaluator = computed(() => auth.roles.includes('EVALUATOR'))
const canFilterByDirectory = computed(() =>
  auth.roles.some((role) => role === 'ADMIN' || role === 'MANAGER'),
)
const supplierOptions = ref<SupplierSummary[]>([])
const ownerOptions = ref<UserOption[]>([])
const supplierLoading = ref(false)
const supplierError = ref('')
const ownerError = ref('')
const optionError = computed(() => supplierError.value || ownerError.value)
const description = computed(
  () => `共 ${list.total.value} 条整改任务 · 超期由服务端按截止日期判定`,
)

function positiveId(value: number | undefined): number | undefined {
  return typeof value === 'number' && value > 0 ? value : undefined
}

function onFilterChange(): void {
  void list.load(1)
}

async function searchSuppliers(keyword: string): Promise<void> {
  supplierLoading.value = true
  try {
    const page = await listSuppliers({
      page: 1,
      size: 50,
      keyword: keyword.trim() || undefined,
    })
    const merged = new Map<number, SupplierSummary>()
    const selected = supplierOptions.value.find(
      (supplier) => supplier.id === filters.supplierId,
    )
    if (selected) {
      // 远程搜索后仍保留当前选中项，否则清空/重开下拉时只显示裸 id
      merged.set(selected.id, selected)
    }
    page.list.forEach((supplier) => merged.set(supplier.id, supplier))
    supplierOptions.value = [...merged.values()]
    supplierError.value = ''
  } catch {
    supplierError.value = '供应商筛选选项加载失败'
  } finally {
    supplierLoading.value = false
  }
}

async function loadFilterOptions(): Promise<void> {
  try {
    ownerOptions.value = await listUserOptions(['EVALUATOR', 'MANAGER'])
    ownerError.value = ''
  } catch {
    ownerError.value = '负责人筛选选项加载失败'
  }
  await searchSuppliers('')
}

function statusLabel(status: CorrectiveActionStatus): string {
  return CORRECTIVE_ACTION_STATUS_LABEL[status] ?? status
}

function verifyText(row: CorrectiveActionSummary): string {
  return verifySummary(row)
}

function outcomeText(row: CorrectiveActionSummary): string {
  return row.verifyOutcome ? VERIFY_OUTCOME_LABEL[row.verifyOutcome] : ''
}

function showOutcome(row: CorrectiveActionSummary): boolean {
  return row.verifyOutcome != null && row.verifyOutcome !== 'PENDING'
}

onMounted(() => {
  void list.load(1)
  if (canFilterByDirectory.value) {
    void loadFilterOptions()
  }
})
</script>

<template>
  <PageHeader
    eyebrow="CORRECTIVE ACTIONS / FOLLOW-UP"
    title="整改任务"
    :description="description"
  />

  <SectionCard title="整改列表">
    <div class="list-toolbar">
      <el-select
        v-if="canFilterByDirectory"
        v-model="filters.supplierId"
        filterable
        remote
        clearable
        :remote-method="searchSuppliers"
        :loading="supplierLoading"
        placeholder="全部供应商"
        style="width: 200px"
        @change="onFilterChange"
      >
        <el-option
          v-for="supplier in supplierOptions"
          :key="supplier.id"
          :label="`${supplier.name} (${supplier.code})`"
          :value="supplier.id"
        />
      </el-select>
      <el-select
        v-if="canFilterByDirectory"
        v-model="filters.ownerId"
        filterable
        clearable
        placeholder="全部负责人"
        style="width: 150px"
        @change="onFilterChange"
      >
        <el-option
          v-for="owner in ownerOptions"
          :key="owner.id"
          :label="owner.name"
          :value="owner.id"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        clearable
        placeholder="全部状态"
        style="width: 160px"
        @change="onFilterChange"
      >
        <el-option
          v-for="item in CORRECTIVE_ACTION_STATUS_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-radio-group
        v-model="filters.overdue"
        @change="onFilterChange"
      >
        <el-radio-button value="">
          全部期限
        </el-radio-button>
        <el-radio-button value="overdue">
          仅看超期
        </el-radio-button>
      </el-radio-group>
      <span
        v-if="isEvaluator"
        class="list-toolbar__note"
      >评估人仅显示本人负责的整改。</span>
      <span
        v-if="optionError"
        class="list-toolbar__note list-toolbar__note--error"
      >{{ optionError }}</span>
    </div>

    <el-alert
      v-if="list.error.value"
      class="page-alert"
      type="error"
      :title="list.error.value"
      :closable="false"
      show-icon
    />
    <el-empty
      v-else-if="!list.loading.value && list.rows.value.length === 0"
      description="没有符合条件的整改任务"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        label="供应商"
        min-width="170"
      >
        <template #default="{ row }">
          <span class="action-supplier">{{ row.supplierName }}</span>
          <span class="mono action-code">{{ row.supplierCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="来源预警"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="action-alert">{{ row.alertTitle }}</span>
          <span class="action-cell-sub">预警 #{{ row.alertId }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="基线周期"
        width="105"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.baselineYear }} Q{{ row.baselineQuarter }}</span>
          <span
            v-if="row.dimensionName"
            class="action-cell-sub"
          >{{ row.dimensionName }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="基线 / 目标"
        width="120"
      >
        <template #default="{ row }">
          <span class="mono">{{ formatScore(row.baselineScore) }} → {{ formatScore(row.targetScore) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="负责人"
        width="100"
      >
        <template #default="{ row }">
          {{ row.ownerName ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="截止日期"
        width="130"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.dueDate }}</span>
          <el-tag
            v-if="row.overdue"
            type="danger"
            size="small"
            class="action-overdue"
          >
            超期
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="actionStatusClass(row.status)"
          >{{ statusLabel(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="下期验证"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ verifyText(row as CorrectiveActionSummary) }}</span>
          <span
            v-if="showOutcome(row as CorrectiveActionSummary)"
            class="action-cell-sub"
          >{{ outcomeText(row as CorrectiveActionSummary) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="84"
      >
        <template #default="{ row }">
          <RouterLink
            :to="`/actions/${row.id}`"
            class="action-detail-link"
          >
            详情
          </RouterLink>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      class="list-pager"
      @change="list.load"
      @size-change="list.load(1)"
    />
  </SectionCard>
</template>

<style scoped>
.action-supplier {
  color: var(--ink);
  font-weight: 500;
}
.action-code {
  display: block;
  margin-top: 1px;
  font-size: 9px;
  line-height: 13px;
}
.action-alert {
  display: block;
  color: var(--ink);
  font-size: 12px;
  line-height: 17px;
}
.action-cell-sub {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 10px;
  line-height: 14px;
}
.action-overdue {
  margin-left: 6px;
}
.action-detail-link {
  color: var(--ink);
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.list-toolbar__note--error {
  color: var(--danger);
}
</style>
