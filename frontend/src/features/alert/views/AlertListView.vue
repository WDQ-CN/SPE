<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { handleAlert, listAlerts } from '@/features/alert/api'
import { useAuthStore } from '@/features/auth/store'
import { formatUtcLocal } from '@/shared/approval-timeline'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import { ALERT_STATUS_LABEL, ALERT_TYPE_LABEL } from '@/shared/labels'
import type { AlertView } from '@/shared/types/dto'

/**
 * 预警列表与处理（01 W3；M5 W6）。
 *
 * <p>列表 ADMIN/MANAGER/VIEWER 可读；处理按钮只给 ADMIN/MANAGER（后端第二层也会拦）。
 * 状态筛选只支持未读/已处理——已取代的预警不对外列出（22 §2 D2）。
 */
const auth = useAuthStore()
const status = ref<'' | 'UNREAD' | 'HANDLED'>('')
const list = usePagedList<AlertView>((page, size) =>
  listAlerts({ page, size, status: status.value }),
)
const canHandle = computed(() =>
  auth.roles.some((role) => role === 'ADMIN' || role === 'MANAGER'),
)

const dialogVisible = ref(false)
const target = ref<AlertView | null>(null)
const comment = ref('')
const submission = useSubmit()

function openHandle(row: AlertView): void {
  target.value = row
  comment.value = ''
  submission.clearError()
  dialogVisible.value = true
}

async function confirmHandle(): Promise<void> {
  const current = target.value
  if (!current) {
    return
  }
  const ok = await submission.run(async () => {
    await handleAlert(current.id, comment.value.trim() || undefined)
  })
  if (ok) {
    ElMessage.success('已处理')
    dialogVisible.value = false
    if (list.rows.value.length === 1 && list.page.value > 1) {
      await list.load(list.page.value - 1)
    } else {
      await list.load()
    }
  }
}

function onStatusChange(): void {
  void list.load(1)
}

onMounted(() => void list.load())
</script>

<template>
  <PageHeader
    eyebrow="ALERTS"
    title="预警"
  />

  <SectionCard title="预警列表">
    <div class="alerts__filters">
      <el-radio-group
        v-model="status"
        @change="onStatusChange"
      >
        <el-radio-button value="">
          全部
        </el-radio-button>
        <el-radio-button value="UNREAD">
          未读
        </el-radio-button>
        <el-radio-button value="HANDLED">
          已处理
        </el-radio-button>
      </el-radio-group>
      <span class="alerts__hint">预警在评估单生效/重算时自动生成；已取代的旧预警不在此列出。</span>
    </div>

    <el-alert
      v-if="list.error.value"
      type="error"
      :title="list.error.value"
      :closable="false"
      show-icon
    />
    <el-empty
      v-else-if="!list.loading.value && list.rows.value.length === 0"
      description="没有符合条件的预警"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
    >
      <el-table-column
        label="供应商"
        min-width="170"
      >
        <template #default="{ row }">
          {{ row.supplierCode }} · {{ row.supplierName }}
        </template>
      </el-table-column>
      <el-table-column
        label="周期"
        width="100"
      >
        <template #default="{ row }">
          {{ row.year }} Q{{ row.quarter }}
        </template>
      </el-table-column>
      <el-table-column
        label="类型"
        width="150"
      >
        <template #default="{ row }">
          {{ ALERT_TYPE_LABEL[row.type] ?? row.type }}
        </template>
      </el-table-column>
      <el-table-column
        prop="content"
        label="内容"
        min-width="280"
      />
      <el-table-column
        label="生成时间"
        width="170"
      >
        <template #default="{ row }">
          {{ formatUtcLocal(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'UNREAD' ? 'warning' : 'success'">
            {{ ALERT_STATUS_LABEL[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="处理"
        min-width="170"
      >
        <template #default="{ row }">
          <span v-if="row.status === 'HANDLED'">
            {{ row.handledByName ?? '—' }}
            <span v-if="row.handledComment">· {{ row.handledComment }}</span>
          </span>
          <el-button
            v-else-if="canHandle"
            link
            type="primary"
            @click="openHandle(row as AlertView)"
          >
            处理
          </el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="list.page.value"
      v-model:page-size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="list.load"
      @size-change="list.load(1)"
    />
  </SectionCard>

  <el-dialog
    v-model="dialogVisible"
    title="处理预警"
    width="520px"
  >
    <p class="alerts__target">
      {{ target?.supplierCode }} · {{ target?.supplierName }}（{{ target?.year }} Q{{ target?.quarter }}）
    </p>
    <el-alert
      v-if="submission.errorMessage.value"
      type="error"
      :title="submission.errorMessage.value"
      :closable="false"
      show-icon
    />
    <el-form label-width="90px">
      <el-form-item label="处理备注">
        <el-input
          v-model="comment"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="可选：记录处理方式或结论"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submission.submitting.value"
        :disabled="submission.submitting.value"
        @click="confirmHandle"
      >
        确认处理
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.alerts__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.alerts__hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.alerts__target {
  margin: 0 0 12px;
  font-weight: 600;
}
</style>
