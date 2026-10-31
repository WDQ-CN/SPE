<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { listMyApprovals } from '@/features/evaluation/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { TASK_SUPPLIER_STATUS_LABEL } from '@/shared/labels'
import type { ApprovalTodoView } from '@/shared/types/dto'

/**
 * 我的审批待办（01 P4 / 15 §E8）：只列**当前节点是我**且状态在审批中的评估单。
 *
 * <p>过滤在后端 SQL（`approval_node.approver_id = 我`），前端不做可见性判断。
 */
const router = useRouter()

const list = usePagedList<ApprovalTodoView>((page, size) => listMyApprovals(page, size))

function tagType(status: ApprovalTodoView['status']): 'warning' | 'success' {
  return status === 'APPROVING' ? 'warning' : 'success'
}

onMounted(() => void list.load())
</script>

<template>
  <PageHeader
    eyebrow="APPROVALS"
    title="我的审批"
  />

  <SectionCard title="待我审批">
    <template #actions>
      <el-button
        link
        type="primary"
        @click="list.load()"
      >
        刷新
      </el-button>
    </template>

    <el-alert
      v-if="list.error.value"
      :title="list.error.value"
      type="error"
      :closable="false"
      show-icon
    />
    <el-empty
      v-else-if="!list.loading.value && list.rows.value.length === 0"
      description="暂无待办审批"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
    >
      <el-table-column
        prop="taskName"
        label="评估任务"
        min-width="180"
      />
      <el-table-column
        label="周期"
        width="110"
      >
        <template #default="{ row }">
          {{ row.year }} 年 Q{{ row.quarter }}
        </template>
      </el-table-column>
      <el-table-column
        prop="supplierName"
        label="供应商"
        min-width="150"
      />
      <el-table-column
        prop="deadline"
        label="截止日期"
        width="130"
      />
      <el-table-column
        label="轮次 / 节点"
        width="120"
      >
        <template #default="{ row }">
          第 {{ row.round }} 轮 · 第 {{ row.currentNode }} 级
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)">
            {{ TASK_SUPPLIER_STATUS_LABEL[row.status as keyof typeof TASK_SUPPLIER_STATUS_LABEL] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="110"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push({ name: 'approval-detail', params: { id: row.taskSupplierId } })"
          >
            去审批
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
