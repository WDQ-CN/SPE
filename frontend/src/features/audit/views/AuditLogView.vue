<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'

import { listAuditActions, listAuditLogs } from '@/features/audit/api'
import { formatUtcLocal } from '@/shared/approval-timeline'
import { prettyAuditDetail } from '@/shared/audit'
import { usePagedList } from '@/shared/composables/usePagedList'
import { AUDIT_TARGET_TYPE_LABEL } from '@/shared/labels'
import type { AuditLogView } from '@/shared/types/dto'

/**
 * 操作日志（01 SM5；M5 W6）：仅 ADMIN。
 *
 * <p>时间过滤：日期选择器给出本地日期，前端把「当天 00:00」与「次日 00:00」转成 ISO instant 传给后端
 * （服务端折算 UTC 后比较，22 §2 D8）；展示同样转回本地（复用 `formatUtcLocal`）。
 */
const filters = reactive({
  action: '',
  targetType: '',
  operator: '',
  range: [] as string[],
})
const actions = ref<string[]>([])

function instantOf(date: string, endExclusive: boolean): string {
  const [year, month, day] = date.split('-').map(Number)
  const local = new Date(year, month - 1, day + (endExclusive ? 1 : 0), 0, 0, 0, 0)
  return local.toISOString()
}

const list = usePagedList<AuditLogView>((page, size) =>
  listAuditLogs({
    page,
    size,
    action: filters.action || undefined,
    targetType: filters.targetType || undefined,
    operator: filters.operator.trim() || undefined,
    from: filters.range?.[0] ? instantOf(filters.range[0], false) : undefined,
    to: filters.range?.[1] ? instantOf(filters.range[1], true) : undefined,
  }),
)

function search(): void {
  void list.load(1)
}

onMounted(async () => {
  await list.load()
  try {
    actions.value = await listAuditActions()
  } catch {
    // 动作选项加载失败不该把列表打断：提醒用户后继续（列表仍可按其它条件查）
    actions.value = []
    ElMessage.error('审计动作选项加载失败，请稍后重试')
  }
})
</script>

<template>
  <PageHeader
    eyebrow="AUDIT / LOG"
    title="操作日志"
    description="审计留痕 — 时间、操作人、动作与来源 IP 均按 UTC 存储、本地展示"
  />

  <SectionCard title="查询">
    <div class="list-toolbar audit__filters">
      <el-select
        v-model="filters.action"
        placeholder="全部动作"
        clearable
        filterable
        class="audit__action"
      >
        <el-option
          v-for="item in actions"
          :key="item"
          :label="item"
          :value="item"
        />
      </el-select>
      <el-select
        v-model="filters.targetType"
        placeholder="全部目标"
        clearable
        class="audit__target"
      >
        <el-option
          v-for="(label, value) in AUDIT_TARGET_TYPE_LABEL"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-input
        v-model="filters.operator"
        placeholder="操作人姓名（包含匹配）"
        clearable
        class="audit__operator"
        @keyup.enter="search"
      />
      <el-date-picker
        v-model="filters.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="audit__range"
        style="width: 280px; flex: 0 0 280px"
      />
      <el-button
        type="primary"
        @click="search"
      >
        查询
      </el-button>
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
      description="没有符合条件的操作日志"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        type="expand"
        width="40"
      >
        <template #default="{ row }">
          <pre class="audit__detail">{{ prettyAuditDetail(row.detail) }}</pre>
        </template>
      </el-table-column>
      <el-table-column
        label="时间"
        width="170"
      >
        <template #default="{ row }">
          {{ formatUtcLocal(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作人"
        width="130"
      >
        <template #default="{ row }">
          {{ row.operatorName ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="动作"
        width="220"
      >
        <template #default="{ row }">
          <span class="audit__action">{{ row.action }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="目标"
        width="140"
      >
        <template #default="{ row }">
          {{ AUDIT_TARGET_TYPE_LABEL[row.targetType] ?? row.targetType ?? '—' }}
          <span v-if="row.targetId != null">#{{ row.targetId }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="ip"
        label="来源 IP"
        width="140"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.ip }}</span>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[20, 50, 100]"
      class="list-pager"
      @change="list.load"
      @size-change="list.load(1)"
    />
  </SectionCard>
</template>

<style scoped>
.audit__action {
  width: 220px;
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}
.audit__target {
  width: 150px;
}
.audit__operator {
  width: 200px;
}
.audit__detail {
  margin: 0;
  padding: 12px 16px;
  max-height: 320px;
  overflow: auto;
  border-left: 2px solid var(--rule);
  background: var(--paper-deep);
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
  white-space: pre-wrap;
}
</style>
