<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { listResultTasks } from '@/features/evaluation/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { EVAL_TASK_STATUS_LABEL } from '@/shared/labels'
import type { ResultTaskSummary } from '@/shared/types/dto'
import type { EvalTaskStatus } from '@/shared/types/enums'

/**
 * 结果报表入口（01 R2）：先选任务，再看该任务的供应商结果。
 *
 * <p>结果数据是**任务级**的（`GET /tasks/{id}/results`），所以这一页用分页任务列表做入口；
 * 具体结果在 `/results/:id`。数据源用 `/results/tasks` 而不是 `/tasks`：后者只对 ADMIN/MANAGER 开放，
 * 而结果报表对 VIEWER 也可读（01 §7）——复用会让 VIEWER 在入口页拿到 403（e2e 实测）。
 */
const router = useRouter()
const keyword = ref('')
const status = ref<EvalTaskStatus | ''>('')

const list = usePagedList<ResultTaskSummary>((page, size) =>
  listResultTasks({
    page,
    size,
    keyword: keyword.value || undefined,
    status: status.value || undefined,
  }),
)

onMounted(() => void list.load())
</script>

<template>
  <PageHeader
    eyebrow="RESULTS"
    title="结果报表"
  />

  <SectionCard title="选择评估任务">
    <div class="filters">
      <el-input
        v-model="keyword"
        placeholder="按任务名称搜索"
        clearable
        class="filters__keyword"
        @keyup.enter="list.load(1)"
        @clear="list.load(1)"
      />
      <el-select
        v-model="status"
        placeholder="全部状态"
        clearable
        class="filters__status"
        @change="list.load(1)"
      >
        <el-option
          v-for="(label, value) in EVAL_TASK_STATUS_LABEL"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button
        type="primary"
        @click="list.load(1)"
      >
        查询
      </el-button>
    </div>

    <el-alert
      v-if="list.error.value"
      :title="list.error.value"
      type="error"
      :closable="false"
      show-icon
    />
    <el-empty
      v-else-if="!list.loading.value && list.rows.value.length === 0"
      description="没有符合条件的评估任务"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
    >
      <el-table-column
        prop="taskName"
        label="任务名称"
        min-width="200"
      />
      <el-table-column
        label="周期"
        width="120"
      >
        <template #default="{ row }">
          {{ row.year }} 年 Q{{ row.quarter }}
        </template>
      </el-table-column>
      <el-table-column
        prop="deadline"
        label="截止日期"
        width="130"
      />
      <el-table-column
        label="已出结果"
        width="100"
      >
        <template #default="{ row }">
          {{ row.resultCount }} 个供应商
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'info'">
            {{ EVAL_TASK_STATUS_LABEL[row.status as keyof typeof EVAL_TASK_STATUS_LABEL] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="120"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push({ name: 'task-results', params: { id: row.taskId } })"
          >
            查看结果
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      @change="list.load"
      @size-change="list.load(1)"
    />
  </SectionCard>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.filters__keyword {
  max-width: 260px;
}
.filters__status {
  width: 160px;
}
</style>
