<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  approveTaskSupplier,
  getTaskSupplierReport,
  rejectTaskSupplier,
} from '@/features/evaluation/api'
import ScoreBreakdown from '@/features/evaluation/components/ScoreBreakdown.vue'
import { useAsyncData } from '@/shared/composables/useAsyncData'
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

const {
  data: report,
  loading,
  error,
  run: load,
} = useAsyncData(
  () => getTaskSupplierReport(taskSupplierId.value),
  null as TaskSupplierReport | null,
)
const approve = useSubmit()
const reject = useSubmit()
const rejectVisible = ref(false)
const comment = ref('')
const commentError = ref('')

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
    eyebrow="APPROVAL / DECISION"
    title="审批评估单"
    :description="report ? `${report.supplierName ?? '供应商'} · ${report.taskName}` : '试算/快照口径与审批记录'"
  >
    <template #actions>
      <el-button
        link
        @click="router.push({ name: 'approvals' })"
      >
        返回列表
      </el-button>
    </template>
  </PageHeader>

  <el-alert
    v-if="error"
    type="error"
    :title="error"
    :closable="false"
    show-icon
  />
  <el-skeleton
    v-else-if="loading && !report"
    :rows="6"
    animated
  />
  <template v-else-if="report">
    <section class="meta-grid">
      <div class="meta-grid__item">
        <span>供应商</span>
        <strong>{{ report.supplierCode }} · {{ report.supplierName }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>评估任务</span>
        <strong>{{ report.taskName }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>周期</span>
        <strong>{{ report.year }} 年 Q{{ report.quarter }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>当前状态</span>
        <strong>
          <span
            class="status-mark"
            :class="report.status === 'EFFECTIVE' ? 'is-success' : 'is-warning'"
          >
            {{
              TASK_SUPPLIER_STATUS_LABEL[
                report.status as keyof typeof TASK_SUPPLIER_STATUS_LABEL
              ]
            }}
          </span>
        </strong>
      </div>
      <div class="meta-grid__item">
        <span>轮次 / 节点</span>
        <strong>{{ roundNodeLabel }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>总分 / 等级</span>
        <strong class="approval-score">
          {{ formatScore(report.totalScore) }} <small>{{ formatGrade(report.totalGrade) }}</small>
        </strong>
      </div>
    </section>

    <div class="approval-notices">
      <el-alert
        v-if="isTrial"
        title="当前为试算口径：评分单还在审批中，最终分数以末级通过后的定稿为准（01 R1）。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="approve.errorMessage.value"
        :title="approve.errorMessage.value"
        type="error"
        :closable="false"
        show-icon
      />
    </div>

    <section
      v-if="canAct"
      class="approval-decision"
    >
      <div class="approval-decision__copy">
        <span class="approval-decision__label">决策</span>
        <p>通过/驳回都以本页读到的版本 {{ report.version }} 提交；末级通过立即定稿并生效。</p>
      </div>
      <div class="approval-decision__actions">
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
      </div>
    </section>
    <el-alert
      v-else
      class="approval-notice-single"
      :title="notActableHint"
      type="info"
      :closable="false"
      show-icon
    />

    <div class="approval-layout">
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
    </div>
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
.approval-score {
  font-family: var(--font-serif);
  font-size: 19px;
  line-height: 24px;
  letter-spacing: -0.3px;
}
.approval-score small {
  font-family: var(--font-sans);
  font-size: 10px;
}
.approval-notices {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 14px 0 0;
}
.approval-notice-single {
  margin-top: 14px;
}
.approval-decision {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 18px;
  padding: 14px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}
.approval-decision__label {
  color: var(--muted-2);
  font-size: 8.5px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.approval-decision__copy p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 10.5px;
  line-height: 18px;
}
.approval-decision__actions {
  display: flex;
  flex: none;
  gap: 8px;
}
.approval-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 36px;
  align-items: start;
}
.approval-layout .section-card {
  margin-top: 24px;
}
.approval-layout .section-card + .section-card {
  padding-top: 0;
  border-top: 0;
}
@media (max-width: 1200px) {
  .approval-layout {
    grid-template-columns: 1fr;
  }
  .approval-layout .section-card + .section-card {
    padding-top: 18px;
    border-top: 1px solid var(--rule);
  }
  .approval-decision {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>
