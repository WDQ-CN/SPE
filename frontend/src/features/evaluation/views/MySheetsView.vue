<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import { listMySheets } from '@/features/evaluation/api'
import { mySheetsEmptyHint } from '@/features/evaluation/sheet-status'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { SHEET_STATUS_LABEL } from '@/shared/labels'
import type { MySheetView } from '@/shared/types/dto'

/**
 * 我的待办（01 E1 / 13 §E13）。
 *
 * <p>列表由后端按 `assignee_id = 我` 过滤（前端不做也不该做可见性判断）——前端只负责展示与跳转。
 */
const auth = useAuthStore()
const router = useRouter()

const emptyHint = computed(() => mySheetsEmptyHint(auth.roles))
const {
  data: rows,
  loading,
  error,
  run: load,
} = useAsyncData(listMySheets, [] as MySheetView[])

onMounted(load)
</script>

<template>
  <PageHeader
    eyebrow="MY TODO / SHEETS"
    title="我的待办"
    :description="`指派给我的评分单 · 共 ${rows.length} 张`"
  >
    <template #actions>
      <el-button
        link
        @click="load"
      >
        刷新
      </el-button>
    </template>
  </PageHeader>

  <SectionCard title="待办清单">
    <el-alert
      v-if="error"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />
    <el-empty
      v-else-if="!loading && rows.length === 0"
      :description="emptyHint"
    />
    <el-table
      v-else
      v-loading="loading"
      :data="rows"
      class="table-list"
    >
      <el-table-column
        prop="taskName"
        label="评估任务"
        min-width="200"
      >
        <template #default="{ row }">
          <span class="sheet-task">{{ row.taskName }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="供应商"
        min-width="170"
      >
        <template #default="{ row }">
          <span>{{ row.supplierName }}</span>
          <span class="mono sheet-supplier-code">{{ row.supplierCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="dimensionName"
        label="评估维度"
        width="120"
      />
      <el-table-column
        label="截止日期"
        width="130"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.deadline }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="row.status === 'SUBMITTED' ? 'is-success' : 'is-warning'"
          >
            {{ SHEET_STATUS_LABEL[row.status as keyof typeof SHEET_STATUS_LABEL] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="160"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="router.push({ name: 'sheet-scoring', params: { id: row.sheetId } })"
          >
            去打分
          </el-button>
          <el-button
            link
            @click="router.push({ name: 'report', params: { id: row.taskSupplierId } })"
          >
            报告
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </SectionCard>
</template>

<style scoped>
.sheet-task {
  color: var(--ink);
  font-weight: 500;
}
.sheet-supplier-code {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  line-height: 13px;
}
</style>
