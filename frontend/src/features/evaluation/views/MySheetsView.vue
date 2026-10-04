<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import { listMySheets } from '@/features/evaluation/api'
import { mySheetsEmptyHint } from '@/features/evaluation/sheet-status'
import { SHEET_STATUS_LABEL } from '@/shared/labels'
import type { MySheetView } from '@/shared/types/dto'

/**
 * 我的待办（01 E1 / 13 §E13）。
 *
 * <p>列表由后端按 `assignee_id = 我` 过滤（前端不做也不该做可见性判断）——前端只负责展示与跳转。
 */
const auth = useAuthStore()
const router = useRouter()

const rows = ref<MySheetView[]>([])
const loading = ref(false)
const error = ref('')
const emptyHint = computed(() => mySheetsEmptyHint(auth.roles))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    rows.value = await listMySheets()
  } catch (caught) {
    // 失败即清空：留着上一次的数据配上错误提示，会让人以为看到的是当前待办（usePagedList 的同一条取舍）
    rows.value = []
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>我的待办</span>
        <el-button
          link
          type="primary"
          @click="load"
        >
          刷新
        </el-button>
      </div>
    </template>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
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
    >
      <el-table-column
        prop="taskName"
        label="评估任务"
        min-width="180"
      />
      <el-table-column
        prop="supplierName"
        label="供应商"
        min-width="140"
      />
      <el-table-column
        prop="dimensionName"
        label="评估维度"
        width="120"
      />
      <el-table-column
        prop="deadline"
        label="截止日期"
        width="130"
      />
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUBMITTED' ? 'success' : 'warning'">
            {{ SHEET_STATUS_LABEL[row.status as keyof typeof SHEET_STATUS_LABEL] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="100"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push({ name: 'sheet-scoring', params: { id: row.sheetId } })"
          >
            去打分
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
