<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  assignSheet,
  getTask,
  getTaskProgress,
  listTaskSheets,
} from '@/features/evaluation/api'
import { useAuthStore } from '@/features/auth/store'
import { sheetPermissions } from '@/features/evaluation/sheet-status'
import { listUserOptions } from '@/features/user/api'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { useSubmit } from '@/shared/composables/useSubmit'
import {
  EVAL_TASK_STATUS_LABEL,
  SHEET_STATUS_LABEL,
  TASK_SUPPLIER_STATUS_LABEL,
} from '@/shared/labels'
import type { SheetBrief, TaskDetail, TaskProgress, UserOption } from '@/shared/types/dto'

/**
 * 任务详情：基本信息 + 进度看板 + 评分单清单（含指派调整）。
 *
 * <p>为什么把三份数据一起拉：看板给"每个供应商算到哪一步/试算多少分"，评分单清单给"这一维度指派给谁"，
 * 采购经理要对照着看才决定改派谁——拆成两个页面反而要在脑子里拼。
 *
 * <p>⚠️ 改派一律带**当前** `version`（清单里的值），不推算（04-040）。
 */
const route = useRoute()
const auth = useAuthStore()
/**
 * `taskId` 必须是响应式的（03 §38 存疑 5）。原先写成 `const taskId = Number(route.params.id)`，
 * 站内一旦出现 `/tasks/1 → /tasks/2` 的直接跳转（P4 的"上一张/下一张"），页面会停在旧任务上——
 * 与 TemplateEditorView 当年修过的是同一件事。
 */
const taskId = ref(Number(route.params.id))

const {
  data: loaded,
  loading,
  error,
  run: load,
  reset: resetLoaded,
} = useAsyncData(
  async () => {
    const requested = taskId.value
    const [detailData, progressData, sheetData] = await Promise.all([
      getTask(requested),
      getTaskProgress(requested),
      listTaskSheets(requested),
    ])
    return { detail: detailData, progress: progressData, sheets: sheetData }
  },
  {
    detail: null as TaskDetail | null,
    progress: null as TaskProgress | null,
    sheets: [] as SheetBrief[],
  },
)
const detail = computed(() => loaded.value.detail)
const progress = computed(() => loaded.value.progress)
const sheets = computed(() => loaded.value.sheets)
const approvers = computed(() =>
  [...(detail.value?.approvers ?? [])].sort((left, right) => left.seq - right.seq),
)
const users = ref<UserOption[]>([])
/** 改派是写操作：闸门 + 错误回显一律走 useSubmit（03 §38 的 P1-14——原来没有防重复闸门） */
const assign = useSubmit()

const assignVisible = ref(false)
const assignTarget = ref<SheetBrief | null>(null)
const assigneeId = ref<number | null>(null)
/**
 * 改派/加载选项的失败文案**只显示在对话框里**。不能复用页面顶部的 `error`：模板是
 * `v-if="error"` + `v-else-if="detail"`，把改派的失败写进去会让整个详情页塌成一条横幅。
 */
const assignError = ref('')

/**
 * 两张表在 500 家供应商上限下整表渲染会明显卡顿（03 §41.3 的 P2-39）：
 * 客户端分页（数据本来就已全量取回），默认 20 行。
 */
const progressPage = ref(1)
const progressSize = ref(20)
const pagedProgress = computed(() => {
  const rows = progress.value?.rows ?? []
  const from = (progressPage.value - 1) * progressSize.value
  return rows.slice(from, from + progressSize.value)
})
const sheetsPage = ref(1)
const sheetsSize = ref(20)
const pagedSheets = computed(() => {
  const from = (sheetsPage.value - 1) * sheetsSize.value
  return sheets.value.slice(from, from + sheetsSize.value)
})

watch(
  () => route.params.id,
  (next) => {
    const parsed = Number(next)
    if (Number.isNaN(parsed) || parsed === taskId.value) {
      return
    }
    taskId.value = parsed
    resetLoaded()
    void load()
  },
)

async function openAssign(row: SheetBrief): Promise<void> {
  assignTarget.value = row
  assigneeId.value = row.assigneeId
  assignVisible.value = true
  assignError.value = ''
  if (users.value.length === 0) {
    try {
      // 改派的合法指派集合是 {EVALUATOR, MANAGER}（TaskService.requireEligibleAssignee），
      // 与审批链的"限 MANAGER/ADMIN"不同 ⇒ 集合由**调用点**给，不在端点上写死
      users.value = await listUserOptions(['EVALUATOR', 'MANAGER'])
    } catch {
      // 原先这里没有 catch：403/网络错误会变成**未接住的 rejection**，对话框照常打开但选项为空
      users.value = []
      assignError.value = '可指派人员加载失败，请重试或联系管理员'
    }
  }
}

async function confirmAssign(): Promise<void> {
  const target = assignTarget.value
  if (!target || assigneeId.value === null) {
    return
  }
  const ok = await assign.run(async () => {
    await assignSheet(target.sheetId, assigneeId.value as number, target.version)
    ElMessage.success('已改派')
    assignVisible.value = false
    await load()
  })
  if (!ok) {
    // 拦截器对 2xxx 刻意不弹全局提示，原话必须由页面显示（03 §38 的 P1-10 同一条口径）
    assignError.value = assign.errorMessage.value
  }
}

/**
 * 能不能改派：与打分页共用同一份判定（sheet-status.ts），免得两处各写一套状态规则。
 *
 * <p>`isAssignee` 传 false：改派只看「角色 + 状态」两个条件（采购经理可以改派任何一张待指派/评分中的单子），
 * 与"这张单子是不是我的"无关；传 true 也不会改变结果，但会让读代码的人误以为有这个条件。
 */
function canReassign(row: SheetBrief): boolean {
  return sheetPermissions(row.status, true, false, auth.roles).canReassign
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <PageHeader
      eyebrow="EVALUATION TASK / TASK DETAIL"
      :title="detail?.name || '任务详情'"
      :description="detail ? '任务详情 · 任务概况与评分单指派' : ''"
    />

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <template v-else-if="detail">
      <section class="meta-grid">
        <div class="meta-grid__item">
          <span>评估周期</span>
          <strong>{{ detail.year }} 年 Q{{ detail.quarter }}</strong>
        </div>
        <div class="meta-grid__item">
          <span>截止日期</span>
          <strong>{{ detail.deadline }}</strong>
        </div>
        <div class="meta-grid__item">
          <span>任务状态</span>
          <strong>{{ EVAL_TASK_STATUS_LABEL[detail.status] }}</strong>
        </div>
        <div class="meta-grid__item">
          <span>参评供应商</span>
          <strong>{{ detail.suppliers.length }} 家</strong>
        </div>
        <div class="meta-grid__item">
          <span>评分单</span>
          <strong>共 {{ detail.sheetCount }} 张 <small>未提交 {{ detail.unsubmittedSheetCount }}</small></strong>
        </div>
        <div class="meta-grid__item">
          <span>审批链</span>
          <strong>{{ approvers.map((item) => item.name || '未命名').join(' → ') || '未配置' }}</strong>
        </div>
      </section>

      <div class="task-layout">
        <SectionCard title="进度看板">
          <el-table :data="pagedProgress">
            <el-table-column
              prop="supplierName"
              label="供应商"
              min-width="170"
            />
            <el-table-column
              label="评估单状态"
              width="120"
            >
              <template #default="{ row }">
                {{ TASK_SUPPLIER_STATUS_LABEL[row.status as keyof typeof TASK_SUPPLIER_STATUS_LABEL] }}
              </template>
            </el-table-column>
            <el-table-column
              label="当前节点"
              width="96"
            >
              <template #default="{ row }">
                {{ row.currentNode && row.currentNode > 0 ? row.currentNode : '—' }}
              </template>
            </el-table-column>
            <el-table-column
              label="试算总分"
              width="112"
            >
              <template #default="{ row }">
                <span v-if="row.complete">{{ row.trialScore }}（{{ row.trialGrade }}）</span>
                <el-tag
                  v-else
                  type="info"
                  size="small"
                >
                  评分未齐
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              label="未指派"
              width="82"
            >
              <template #default="{ row }">
                {{ row.unassignedSheets }}
              </template>
            </el-table-column>
            <el-table-column
              label="未提交"
              width="82"
            >
              <template #default="{ row }">
                {{ row.unsubmittedSheets }}
              </template>
            </el-table-column>
          </el-table>
          <ListPagination
            v-model:page="progressPage"
            v-model:size="progressSize"
            :total="progress?.rows.length ?? 0"
            :page-sizes="[20, 50, 100]"
            class="list-pager"
          />
        </SectionCard>

        <SectionCard title="审批链">
          <ol
            v-if="approvers.length"
            class="approval-list"
          >
            <li
              v-for="(approver, index) in approvers"
              :key="`${approver.userId}-${approver.seq}`"
              class="approval-item"
            >
              <span class="approval-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <div class="approval-main">
                <span class="approval-name">{{ approver.name || '未命名' }}</span>
                <span class="approval-meta">审批顺序 {{ approver.seq }}</span>
              </div>
            </li>
          </ol>
          <p
            v-else
            class="approval-empty"
          >
            尚未配置审批链。
          </p>
          <div class="approval-note">
            审批按配置顺序串行流转；当前任务状态：{{ EVAL_TASK_STATUS_LABEL[detail.status] }}。
          </div>
        </SectionCard>
      </div>

      <SectionCard
        title="评分单与指派"
        class="detail-sheets"
      >
        <el-table :data="pagedSheets">
          <el-table-column
            prop="supplierName"
            label="供应商"
            min-width="170"
          />
          <el-table-column
            prop="dimensionName"
            label="维度"
            width="110"
          />
          <el-table-column
            label="指派给"
            min-width="150"
          >
            <template #default="{ row }">
              <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
              <el-tag
                v-else
                type="warning"
                size="small"
              >
                未指派
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
          >
            <template #default="{ row }">
              {{ SHEET_STATUS_LABEL[row.status as keyof typeof SHEET_STATUS_LABEL] }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="100"
            align="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="canReassign(row as SheetBrief)"
                link
                @click="openAssign(row as SheetBrief)"
              >
                改派
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <ListPagination
          v-model:page="sheetsPage"
          v-model:size="sheetsSize"
          :total="sheets.length"
          :page-sizes="[20, 50, 100]"
          class="list-pager"
        />
      </SectionCard>
    </template>

    <el-dialog
      v-model="assignVisible"
      title="改派评分单"
      width="420px"
    >
      <p class="assign-summary">
        {{ assignTarget?.supplierName }} · {{ assignTarget?.dimensionName }}
      </p>
      <el-select
        v-model="assigneeId"
        filterable
        placeholder="选择评估人"
        style="width: 100%"
      >
        <el-option
          v-for="item in users"
          :key="item.id"
          :label="`${item.name}（${item.role}）`"
          :value="item.id"
        />
      </el-select>
      <el-alert
        v-if="assignError"
        :title="assignError"
        type="error"
        :closable="false"
        show-icon
      />
      <template #footer>
        <el-button @click="assignVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="assign.submitting.value"
          :disabled="assign.submitting.value"
          @click="confirmAssign"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.task-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr);
  gap: 32px;
  align-items: start;
}

.task-layout .section-card {
  margin-top: 0;
}

.task-layout .section-card + .section-card {
  padding-top: 0;
  border-top: 0;
}

.approval-list {
  padding: 0;
  margin: 0;
  list-style: none;
  border-top: 1px solid var(--ink);
}

.approval-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--rule);
}

.approval-index {
  padding-top: 1px;
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 15px;
}

.approval-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.approval-name {
  color: var(--ink-secondary);
  font-size: 11.5px;
  font-weight: 500;
  line-height: 17px;
}

.approval-meta {
  margin-top: 1px;
  color: var(--muted);
  font-size: 9.5px;
  line-height: 14px;
}

.approval-empty {
  margin: 0;
  padding: 12px 0;
  border-top: 1px solid var(--ink);
  border-bottom: 1px solid var(--rule);
  color: var(--muted);
  font-size: 11px;
}

.approval-note {
  margin-top: 14px;
  padding: 10px 12px;
  border-left: 2px solid var(--accent);
  background: var(--accent-soft);
  color: var(--ink-secondary);
  font-size: 10.5px;
  line-height: 18px;
}

.detail-sheets {
  margin-top: 28px;
  padding-top: 18px;
  border-top: 1px solid var(--rule);
}

.assign-summary {
  margin: 0 0 14px;
  color: var(--muted);
  font-size: 11.5px;
}

@media (max-width: 1300px) {
  .task-layout {
    grid-template-columns: 1fr;
  }
}

</style>
