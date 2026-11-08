<script setup lang="ts">
import { onMounted, reactive } from 'vue'

import { listPortalAuctions, type PortalAuctionSummary } from '@/features/portal/api'
import { AUCTION_STATUS_LABEL, formatDateTime, labelOf, statusTone } from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'

const filters = reactive<{ status: string }>({ status: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<PortalAuctionSummary>(
  (targetPage, pageSize) =>
    listPortalAuctions(targetPage, pageSize, filters.status || undefined),
)

const statusOptions = Object.entries(AUCTION_STATUS_LABEL).filter(([value]) =>
  ['LIVE', 'SCHEDULED', 'CLOSED', 'AWARDED'].includes(value),
)

onMounted(() => void load(1))

function search(): void {
  void load(1)
}
</script>

<template>
  <PageHeader
    eyebrow="SUPPLIER PORTAL / AUCTIONS"
    title="我的竞价"
    :description="`共 ${total} 场 · 仅显示被邀请的竞价`"
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
      label="竞价号"
      min-width="150"
    >
      <template #default="{ row }">
        <span class="mono">{{ (row as PortalAuctionSummary).auctionNo }}</span>
      </template>
    </el-table-column>
    <el-table-column
      label="标题"
      min-width="220"
    >
      <template #default="{ row }">
        <RouterLink
          class="auction-link"
          :to="`/portal/auctions/${(row as PortalAuctionSummary).id}`"
        >
          {{ (row as PortalAuctionSummary).title }}
        </RouterLink>
      </template>
    </el-table-column>
    <el-table-column
      label="结束时间"
      min-width="170"
    >
      <template #default="{ row }">
        {{ formatDateTime((row as PortalAuctionSummary).endAt) }}
      </template>
    </el-table-column>
    <el-table-column
      label="我的出价"
      align="right"
      width="100"
    >
      <template #default="{ row }">
        {{ (row as PortalAuctionSummary).myBidCount }}
      </template>
    </el-table-column>
    <el-table-column
      label="密封"
      width="80"
    >
      <template #default="{ row }">
        {{ (row as PortalAuctionSummary).sealed ? '是' : '否' }}
      </template>
    </el-table-column>
    <el-table-column
      label="状态"
      width="110"
    >
      <template #default="{ row }">
        <span
          class="status-mark"
          :class="statusTone((row as PortalAuctionSummary).status)"
        >
          {{ labelOf(AUCTION_STATUS_LABEL, (row as PortalAuctionSummary).status) }}
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
.auction-link {
  color: var(--ink);
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
}

.auction-link:hover {
  border-color: var(--ink);
}
</style>
