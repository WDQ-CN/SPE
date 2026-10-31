<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useRoute, useRouter } from 'vue-router'

import { canAccess } from '@/app/menu'
import { useAuthStore } from '@/features/auth/store'
import { getTaskSupplierReport, unlockTaskSupplier } from '@/features/evaluation/api'
import ScoreBreakdown from '@/features/evaluation/components/ScoreBreakdown.vue'
import ScoreRadar from '@/features/evaluation/components/ScoreRadar.vue'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { useSubmit } from '@/shared/composables/useSubmit'
import { formatGrade, formatScore } from '@/shared/format'
import { TASK_SUPPLIER_STATUS_LABEL } from '@/shared/labels'
import type { TaskSupplierReport } from '@/shared/types/dto'

/**
 * 评估报告页（01 R3 / 15 E12/E15）：雷达图 + 指标明细（含来源）+ 审批时间线。
 *
 * <p>`scoreMode = TRIAL` 表示评估单尚未生效、这里是试算口径；页面必须明确标出，
 * 不能把试算分当定稿分展示（01 R1）。定稿后同一接口自动切换为 `SNAPSHOT`。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
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

// 结果列表只对 ADMIN/MANAGER/VIEWER 开放（01 §7）；评估人的合法来路是「我的评分单」，
// 固定跳 task-results 会把它送进无权限路由（03 §41.3 的 P2-30）⇒ 用返回上一页。
const canOpenResults = computed(() => canAccess('/results', auth.roles))
/** 解锁重评仅 ADMIN，且只有生效中的评估单可解锁（R5；后端还会再判任务状态与版本）。 */
const canUnlock = computed(
  () => report.value?.status === 'EFFECTIVE' && auth.roles.includes('ADMIN'),
)
const unlockSubmission = useSubmit()

async function onUnlock(): Promise<void> {
  const current = report.value
  if (!current) {
    return
  }
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '解锁后该评估单退回评分中并重走审批；已生效的分数与维度快照会被撤销（审计留痕）。可填写原因（可选）。',
      '解锁重评',
      {
        confirmButtonText: '确认解锁',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '可选：解锁原因',
        inputValidator: (value: string) => (value ?? '').length <= 500 || '最多 500 字',
      },
    )
    comment = value ?? ''
  } catch {
    return
  }
  const ok = await unlockSubmission.run(async () => {
    await unlockTaskSupplier(
      current.taskSupplierId,
      current.version,
      comment.trim() || undefined,
    )
  })
  if (ok) {
    ElMessage.success('已解锁，评估单退回评分中')
    await load()
  }
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
    eyebrow="REPORT"
    :title="report ? `${report.supplierName ?? '供应商'} · 评估报告` : '评估报告'"
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
    :rows="7"
    animated
  />
  <template v-else-if="report">
    <SectionCard title="评估概要">
      <template #actions>
        <el-button
          v-if="canUnlock"
          type="warning"
          plain
          :loading="unlockSubmission.submitting.value"
          @click="onUnlock"
        >
          解锁重评
        </el-button>
        <el-button
          v-if="canOpenResults"
          link
          type="primary"
          @click="router.push({ name: 'task-results', params: { id: report.taskId } })"
        >
          返回结果列表
        </el-button>
        <el-button
          v-else
          link
          type="primary"
          @click="router.back()"
        >
          返回
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
        <el-descriptions-item label="状态">
          <el-tag :type="report.status === 'EFFECTIVE' ? 'success' : 'warning'">
            {{
              TASK_SUPPLIER_STATUS_LABEL[
                report.status as keyof typeof TASK_SUPPLIER_STATUS_LABEL
              ]
            }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="总分 / 等级">
          <strong>{{ formatScore(report.totalScore) }} / {{ formatGrade(report.totalGrade) }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="口径">
          <el-tag :type="report.scoreMode === 'SNAPSHOT' ? 'success' : 'warning'">
            {{ report.scoreMode === 'SNAPSHOT' ? '定稿快照' : '试算（未生效）' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="unlockSubmission.errorMessage.value"
        class="report__hint"
        type="error"
        :title="unlockSubmission.errorMessage.value"
        :closable="false"
        show-icon
      />

      <el-alert
        v-if="report.scoreMode === 'TRIAL'"
        class="report__hint"
        :title="
          report.complete
            ? '该评估单尚未生效，当前分数为试算口径，仅供审批决策参考（01 R1）。'
            : '该评估单尚未生效且数据不完整，试算分仅供参考。'
        "
        type="warning"
        :closable="false"
        show-icon
      />
    </SectionCard>

    <SectionCard title="维度雷达">
      <ScoreRadar :dimensions="report.dimensions" />
    </SectionCard>

    <SectionCard title="维度与指标明细">
      <ScoreBreakdown :dimensions="report.dimensions" />
    </SectionCard>

    <SectionCard title="审批时间线">
      <ApprovalTimeline
        :entries="report.timeline"
        :current-round="report.round"
        :current-node="report.currentNode"
      />
    </SectionCard>
  </template>
</template>

<style scoped>
.report__hint {
  margin-top: 12px;
}
</style>
