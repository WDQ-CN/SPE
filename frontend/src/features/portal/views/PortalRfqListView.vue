<script setup lang="ts">
import { onMounted, reactive } from 'vue'

import { listPortalRfqs, type PortalRfqSummary } from '@/features/portal/api'
import {
  QUOTE_STATUS_LABEL,
  RFQ_STATUS_LABEL,
  formatDateTime,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'

const filters = reactive<{ status: string }>({ status: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<PortalRfqSummary>(
  (targetPage, pageSize) =>
    listPortalRfqs(targetPage, pageSize, filters.status || undefined),
)

const statusOptions = Object.entries(RFQ_STATUS_LABEL).filter(([value]) =>
  ['SENT', 'CLOSED', 'AWARDED'].includes(value),
)

onMounted(() => void load(1))

function search(): void {
  void load(1)
}
</script>

<template>
  <PageHeader
    eyebrow="SUPPLIER PORTAL / RFQ"
    title="我的询价"
    :description="`共 ${total} 张 · 仅显示被邀请的 RFQ`"
  />

  <form
    class="list-toolbar"
    @submit.prevent="search"
  >
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
      label="RFQ"
      min-width="150"
    >
      <template #default="{ row }">
        <span class="mono">{{ (row as PortalRfqSummary).rfqNo }}</span>
      </template>
    </el-table-column>
    <el-table-column
      label="标题"
      min-width="220"
    >
      <template #default="{ row }">
        <RouterLink
          class="rfq-link"
          :to="`/portal/rfqs/${(row as PortalRfqSummary).rfqId}`"
        >
          {{ (row as PortalRfqSummary).title }}
        </RouterLink>
      </template>
    </el-table-column>
    <el-table-column
      label="截止时间"
      min-width="170"
    >
      <template #default="{ row }">
        {{ formatDateTime((row as PortalRfqSummary).deadlineAt) }}
      </template>
    </el-table-column>
    <el-table-column
      label="我的报价"
      width="110"
    >
      <template #default="{ row }">
        {{ labelOf(QUOTE_STATUS_LABEL, (row as PortalRfqSummary).myQuoteStatus) }}
      </template>
    </el-table-column>
    <el-table-column
      label="状态"
      width="110"
    >
      <template #default="{ row }">
        <span
          class="status-mark"
          :class="statusTone((row as PortalRfqSummary).status)"
        >
          {{ labelOf(RFQ_STATUS_LABEL, (row as PortalRfqSummary).status) }}
        </span>
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
</template>

<style scoped>
.rfq-link {
  color: var(--ink);
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
}

.rfq-link:hover {
  border-color: var(--ink);
}
</style>
