<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { alertActionEntry, actionStatusClass } from '@/features/action/action-status'
import ActionFormDialog from '@/features/action/components/ActionFormDialog.vue'
import { handleAlert, listAlerts } from '@/features/alert/api'
import { useAuthStore } from '@/features/auth/store'
import { formatUtcLocal } from '@/shared/approval-timeline'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import {
  ALERT_STATUS_LABEL,
  ALERT_TYPE_LABEL,
  CORRECTIVE_ACTION_STATUS_LABEL,
} from '@/shared/labels'
import type { AlertView } from '@/shared/types/dto'

/**
 * 预警列表与整改入口（01 W3；M5 W6；W4）。
 *
 * <p>列表 ADMIN/MANAGER/VIEWER 可读；处理与创建整改只给 ADMIN/MANAGER（后端第二层也会拦）。
 * 状态筛选只支持未读/已处理——已取代的预警不对外列出（22 §2 D2）。
 * 旧预警没有结构化基线/目标，只保留原「处理」流程（D8）。
 */
const auth = useAuthStore()
const status = ref<'' | 'UNREAD' | 'HANDLED'>('')
const list = usePagedList<AlertView>((page, size) =>
  listAlerts({ page, size, status: status.value }),
)
const canManage = computed(() =>
  auth.roles.some((role) => role === 'ADMIN' || role === 'MANAGER'),
)

const createDialogVisible = ref(false)
const createTarget = ref<AlertView | null>(null)

function entryOf(row: AlertView) {
  return alertActionEntry(row, auth.roles)
}

function correctiveStatusLabel(row: AlertView): string {
  return row.correctiveActionStatus
    ? (CORRECTIVE_ACTION_STATUS_LABEL[row.correctiveActionStatus] ?? row.correctiveActionStatus)
    : '—'
}

function openCreate(row: AlertView): void {
  createTarget.value = row
  createDialogVisible.value = true
}

async function reloadAfterMutation(): Promise<void> {
  // 只有 UNREAD 筛选下这一行才会从列表消失；默认/已处理筛选应留在当前页。
  if (status.value === 'UNREAD' && list.rows.value.length === 1 && list.page.value > 1) {
    await list.load(list.page.value - 1)
  } else {
    await list.load()
  }
}

async function onActionSaved(): Promise<void> {
  createDialogVisible.value = false
  await reloadAfterMutation()
}

function onPageChange(page: number): void {
  // 先写回 page 再发请求：不依赖 v-model 与 change 事件的先后顺序
  list.page.value = page
  void list.load(page)
}

function onSizeChange(size: number): void {
  list.page.value = 1
  list.size.value = size
  void list.load(1)
}

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
    await reloadAfterMutation()
  }
}

function onStatusChange(): void {
  void list.load(1)
}

onMounted(() => void list.load())
</script>

<template>
  <PageHeader
    eyebrow="ALERTS / MONITORING"
    title="预警"
    :description="`共 ${list.total.value} 条预警 · 生效或重算时自动生成`"
  />

  <SectionCard title="预警列表">
    <div class="list-toolbar">
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
      <span class="list-toolbar__note">已取代的旧预警不在此列出。</span>
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
      description="没有符合条件的预警"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        label="供应商"
        min-width="190"
      >
        <template #default="{ row }">
          <span class="alert-supplier">{{ row.supplierName }}</span>
          <span class="mono alert-code">{{ row.supplierCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="周期"
        width="100"
      >
        <template #default="{ row }">
          <span class="mono">{{ row.year }} Q{{ row.quarter }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="类型"
        width="160"
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
          <span class="mono">{{ formatUtcLocal(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="row.status === 'UNREAD' ? 'is-warning' : 'is-success'"
          >
            {{ ALERT_STATUS_LABEL[row.status] ?? row.status }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="处理 / 整改"
        min-width="220"
      >
        <template #default="{ row }">
          <template v-if="entryOf(row as AlertView).state === 'action'">
            <span class="alert-actions">
              <span
                class="status-mark"
                :class="actionStatusClass((row as AlertView).correctiveActionStatus)"
              >
                {{ correctiveStatusLabel(row as AlertView) }}
              </span>
              <RouterLink
                v-if="entryOf(row as AlertView).showActionLink"
                :to="`/actions/${row.correctiveActionId}`"
                class="alert-action-link"
              >
                查看整改
              </RouterLink>
            </span>
          </template>
          <span
            v-else-if="entryOf(row as AlertView).state === 'create'"
            class="alert-actions"
          >
            <el-button
              link
              type="primary"
              @click="openCreate(row as AlertView)"
            >
              创建整改
            </el-button>
            <el-button
              link
              @click="openHandle(row as AlertView)"
            >
              处理
            </el-button>
          </span>
          <span
            v-else-if="entryOf(row as AlertView).state === 'legacy'"
            class="alert-actions"
          >
            <el-button
              v-if="entryOf(row as AlertView).canHandle"
              link
              @click="openHandle(row as AlertView)"
            >
              处理
            </el-button>
            <el-tooltip content="该预警生成于结构化字段上线前，只能按原流程处理">
              <span class="alert-legacy-hint">旧预警</span>
            </el-tooltip>
          </span>
          <span
            v-else-if="entryOf(row as AlertView).state === 'handled'"
            class="alert-handled"
          >
            {{ row.handledByName ?? '—' }}
            <span v-if="row.handledComment">· {{ row.handledComment }}</span>
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      class="list-pager"
      @change="onPageChange"
      @size-change="onSizeChange"
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

  <ActionFormDialog
    v-model="createDialogVisible"
    :alert="createTarget"
    :manager="canManage"
    @saved="onActionSaved"
  />
</template>

<style scoped>
.alert-supplier {
  color: var(--ink);
  font-weight: 500;
}
.alert-code {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  line-height: 13px;
}
.alert-handled {
  color: var(--muted);
  font-size: 12px;
  line-height: 18px;
}
.alert-actions {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 10px;
}
.alert-action-link {
  color: var(--ink);
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.alert-legacy-hint {
  color: var(--muted-2);
  font-size: 11px;
  letter-spacing: 0.04em;
}
.alerts__target {
  margin: 0 0 12px;
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 500;
}
</style>
