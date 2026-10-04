<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import {
  assignSheet,
  getTask,
  getTaskProgress,
  listTaskSheets,
} from '@/features/evaluation/api'
import { useAuthStore } from '@/features/auth/store'
import { sheetPermissions } from '@/features/evaluation/sheet-status'
import { listUsers } from '@/features/user/api'
import {
  EVAL_TASK_STATUS_LABEL,
  SHEET_STATUS_LABEL,
  TASK_SUPPLIER_STATUS_LABEL,
} from '@/shared/labels'
import type { SheetBrief, TaskDetail, TaskProgress, UserSummary } from '@/shared/types/dto'

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
const taskId = Number(route.params.id)

const detail = ref<TaskDetail | null>(null)
const progress = ref<TaskProgress | null>(null)
const sheets = ref<SheetBrief[]>([])
const users = ref<UserSummary[]>([])
const loading = ref(false)
const error = ref('')

const assignVisible = ref(false)
const assignTarget = ref<SheetBrief | null>(null)
const assigneeId = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [detailData, progressData, sheetData] = await Promise.all([
      getTask(taskId),
      getTaskProgress(taskId),
      listTaskSheets(taskId),
    ])
    detail.value = detailData
    progress.value = progressData
    sheets.value = sheetData
  } catch (caught) {
    detail.value = null
    progress.value = null
    sheets.value = []
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function openAssign(row: SheetBrief): Promise<void> {
  assignTarget.value = row
  assigneeId.value = row.assigneeId
  assignVisible.value = true
  if (users.value.length === 0) {
    const page = await listUsers({ page: 1, size: 100 })
    users.value = page.list
  }
}

async function confirmAssign(): Promise<void> {
  const target = assignTarget.value
  if (!target || assigneeId.value === null) {
    return
  }
  await assignSheet(target.sheetId, assigneeId.value, target.version)
  ElMessage.success('已改派')
  assignVisible.value = false
  await load()
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
      eyebrow="TASK DETAIL"
      title="任务详情"
    />

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <template v-else-if="detail">
      <SectionCard :title="detail.name">
        <el-descriptions :column="3">
          <el-descriptions-item label="周期">
            {{ detail.year }} 年 Q{{ detail.quarter }}
          </el-descriptions-item>
          <el-descriptions-item label="截止日期">
            {{ detail.deadline }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ EVAL_TASK_STATUS_LABEL[detail.status] }}
          </el-descriptions-item>
          <el-descriptions-item label="参评供应商">
            {{ detail.suppliers.length }} 家
          </el-descriptions-item>
          <el-descriptions-item label="评分单">
            共 {{ detail.sheetCount }} 张，未提交 {{ detail.unsubmittedSheetCount }} 张
          </el-descriptions-item>
          <el-descriptions-item label="审批链">
            {{ detail.approvers.map((item) => item.name).join(' → ') || '未配置' }}
          </el-descriptions-item>
        </el-descriptions>
      </SectionCard>

      <SectionCard title="进度看板">
        <el-table :data="progress?.rows ?? []">
          <el-table-column
            prop="supplierName"
            label="供应商"
            min-width="160"
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
            width="100"
          >
            <template #default="{ row }">
              {{ row.currentNode ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="试算总分"
            width="120"
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
            width="90"
          >
            <template #default="{ row }">
              {{ row.unassignedSheets }}
            </template>
          </el-table-column>
          <el-table-column
            label="未提交"
            width="90"
          >
            <template #default="{ row }">
              {{ row.unsubmittedSheets }}
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <SectionCard title="评分单与指派">
        <el-table :data="sheets">
          <el-table-column
            prop="supplierName"
            label="供应商"
            min-width="150"
          />
          <el-table-column
            prop="dimensionName"
            label="维度"
            width="110"
          />
          <el-table-column
            label="指派给"
            min-width="140"
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
            width="120"
          >
            <template #default="{ row }">
              <el-button
                v-if="canReassign(row as SheetBrief)"
                link
                type="primary"
                @click="openAssign(row as SheetBrief)"
              >
                改派
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </template>

    <el-dialog
      v-model="assignVisible"
      title="改派评分单"
      width="420px"
    >
      <p>
        {{ assignTarget?.supplierName }} · {{ assignTarget?.dimensionName }}
      </p>
      <el-select
        v-model="assigneeId"
        filterable
        placeholder="选择评估人"
      >
        <el-option
          v-for="item in users"
          :key="item.id"
          :label="`${item.name}（${item.role}）`"
          :value="item.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="assignVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          @click="confirmAssign"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

