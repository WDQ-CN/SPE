<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  approveTaskSupplier,
  getTaskSupplierReport,
  rejectTaskSupplier,
} from '@/features/evaluation/api'
import ScoreBreakdown from '@/features/evaluation/components/ScoreBreakdown.vue'
import { useSubmit } from '@/shared/composables/useSubmit'
import { formatGrade, formatScore } from '@/shared/format'
import { TASK_SUPPLIER_STATUS_LABEL } from '@/shared/labels'
import type { TaskSupplierReport } from '@/shared/types/dto'

/**
 * 审批页（01 P1/P3、15 E5/E12）：试算/快照结果 + 维度明细 + 时间线 + 通过/驳回。
 *
 * <p>按钮显隐按 `report.actableNow`（后端判「当前节点是否本人」，`03 §41.3` 的 P2-27）：
 * 非当前审批人也能看页，但不给动作按钮——第二层授权仍在 Service（4003），这里只是不误导。
 */
const route = useRoute()
const router = useRouter()
const taskSupplierId = ref(Number(route.params.id))

const report = ref<TaskSupplierReport | null>(null)
const loading = ref(false)
const error = ref('')
const approve = useSubmit()
const reject = useSubmit()
const rejectVisible = ref(false)
const comment = ref('')
const commentError = ref('')

let loadSeq = 0

async function load(): Promise<void> {
  const requested = taskSupplierId.value
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const data = await getTaskSupplierReport(requested)
    if (seq !== loadSeq) {
      return
    }
    report.value = data
  } catch (caught) {
    if (seq !== loadSeq) {
      return
    }
    report.value = null
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

const canAct = computed(
  () =>
    report.value != null &&
    report.value.actableNow &&
    (report.value.status === 'PENDING_APPROVAL' || report.value.status === 'APPROVING'),
)
const notActableHint = computed(() => {  const current = report.value
  if (!current) {
    return ''
  }
  if (current.status === 'EFFECTIVE') {
    return '该评估单已生效，审批记录仅作查看。'
  }
  if (current.status !== 'PENDING_APPROVAL' && current.status !== 'APPROVING') {
    return '该评估单当前不在审批环节（可能已被驳回或退回评分）。'
  }
  return '当前节点不由你审批：操作按钮只对当前审批人开放（15 §E8）。'
})
// 驳回后 currentNode=0：只显示轮次，不显示「第 0 级」（03 §41.3 的 P2-28）
const roundNodeLabel = computed(() => {
  const current = report.value
  if (!current) {
    return ''
  }
  return current.currentNode > 0
    ? `第 ${current.round} 轮 · 第 ${current.currentNode} 级`
    : `第 ${current.round} 轮`
})
const isTrial = computed(() => report.value?.scoreMode === 'TRIAL')

async function onApprove(): Promise<void> {
  if (!report.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '通过后节点将推进；末级通过会立即定稿并生效，分数与报告以定稿结果为准。',
      '确认通过',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const target = report.value
  await approve.run(async () => {
    await approveTaskSupplier(target.taskSupplierId, target.version)
    ElMessage.success('已通过')
    await load()
  })
}

function openReject(): void {
  comment.value = ''
  commentError.value = ''
  reject.clearError()
  rejectVisible.value = true
}

async function onReject(): Promise<void> {
  const text = comment.value.trim()
  if (!text) {
    commentError.value = '驳回时必须填写意见'
    return
  }
  commentError.value = ''
  if (!report.value) {
    return
  }
  const target = report.value
  await reject.run(async () => {
    await rejectTaskSupplier(target.taskSupplierId, target.version, text)
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await load()
  })
}

watch(
  () => route.params.id,
  () => {
    taskSupplierId.value = Number(route.params.id)
    void load()
  },
)
onMounted(() => void load())
</script>

<template>
  <PageHeader
    eyebrow="APPROVAL"
    title="审批评估单"
  />

  <el-alert
    v-if="error"
    :title="error"
    type="error"
    :closable="false"
    show-icon
  />
  <el-skeleton
    v-else-if="loading && !report"
    :rows="6"
    animated
  />
  <template v-else-if="report">
    <SectionCard title="评估单信息">
      <template #actions>
        <el-button
          link
          type="primary"
          @click="router.push({ name: 'approvals' })"
        >
          返回列表
        </el-button>
      </template>
      <el-descriptions :column="3">
        <el-descriptions-item label="供应商">
          {{ report.supplierCode }} · {{ report.supplierName }}
        </el-descriptions-item>
        <el-descriptions-item label="评估任务">
          {{ report.taskName }}
        </el-descriptions-item>
        <el-descriptions-item label="周期">
          {{ report.year }} 年 Q{{ report.quarter }}
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">
          <el-tag
            :type="report.status === 'EFFECTIVE' ? 'success' : 'warning'"
          >
            {{
              TASK_SUPPLIER_STATUS_LABEL[
                report.status as keyof typeof TASK_SUPPLIER_STATUS_LABEL
              ]
            }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="轮次 / 节点">
          {{ roundNodeLabel }}
        </el-descriptions-item>
        <el-descriptions-item label="总分 / 等级">
          {{ formatScore(report.totalScore) }} / {{ formatGrade(report.totalGrade) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="isTrial"
        class="approval__hint"
        title="当前为试算口径：评分单还在审批中，最终分数以末级通过后的定稿为准（01 R1）。"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-alert
        v-if="approve.errorMessage.value"
        class="approval__hint"
        :title="approve.errorMessage.value"
        type="error"
        :closable="false"
        show-icon
      />

      <div
        v-if="canAct"
        class="approval__actions"
      >
        <el-button
          type="primary"
          :loading="approve.submitting.value"
          :disabled="approve.submitting.value || reject.submitting.value"
          @click="onApprove"
        >
          通过
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="approve.submitting.value || reject.submitting.value"
          @click="openReject"
        >
          驳回
        </el-button>
        <span class="approval__hint">通过/驳回都以本页读到的版本 {{ report.version }} 提交。</span>
      </div>
      <el-alert
        v-else
        class="approval__hint"
        :title="notActableHint"
        type="info"
        :closable="false"
        show-icon
      />
    </SectionCard>

    <SectionCard title="维度与指标明细">
      <ScoreBreakdown :dimensions="report.dimensions" />
    </SectionCard>

    <SectionCard title="审批记录">
      <ApprovalTimeline
        :entries="report.timeline"
        :current-round="report.round"
        :current-node="report.currentNode"
      />
    </SectionCard>
  </template>

  <el-dialog
    v-model="rejectVisible"
    title="驳回评估单"
    width="520px"
  >
    <el-alert
      v-if="reject.errorMessage.value"
      :title="reject.errorMessage.value"
      type="error"
      :closable="false"
      show-icon
    />
    <el-form label-width="90px">
      <el-form-item
        label="驳回意见"
        :error="commentError"
      >
        <el-input
          v-model="comment"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="必填：说明需要评估人修改什么"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="rejectVisible = false">
        取消
      </el-button>
      <el-button
        type="danger"
        :loading="reject.submitting.value"
        :disabled="reject.submitting.value"
        @click="onReject"
      >
        确认驳回
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.approval__hint {
  margin-top: 12px;
}
.approval__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
}
.approval__actions .approval__hint {
  margin-top: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
