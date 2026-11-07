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
    eyebrow="REPORT / SUPPLIER"
    :title="report ? `${report.supplierName ?? '供应商'} · 评估报告` : '评估报告'"
    :description="report ? `${report.taskName} · ${report.year} 年 Q${report.quarter}` : ''"
  >
    <template #actions>
      <template v-if="report">
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
          @click="router.push({ name: 'task-results', params: { id: report.taskId } })"
        >
          返回结果列表
        </el-button>
        <el-button
          v-else
          link
          @click="router.back()"
        >
          返回
        </el-button>
      </template>
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
    :rows="7"
    animated
  />
  <template v-else-if="report">
    <section class="report-hero">
      <div class="report-hero__score">
        <span class="report-kicker">总分 / Total score</span>
        <div class="report-total">
          {{ formatScore(report.totalScore) }}
        </div>
        <div class="report-hero__badges">
          <span class="report-grade">{{ formatGrade(report.totalGrade) }}</span>
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
          <el-tag :type="report.scoreMode === 'SNAPSHOT' ? 'success' : 'warning'">
            {{ report.scoreMode === 'SNAPSHOT' ? '定稿快照' : '试算（未生效）' }}
          </el-tag>
        </div>
        <p class="report-hero__note">
          {{ report.scoreMode === 'SNAPSHOT'
            ? '已生效评估单的定稿快照，分数与维度不可再变。'
            : '试算口径：评估单尚未生效，最终分数以末级通过后的定稿为准。' }}
        </p>
      </div>

      <div class="meta-grid meta-grid--2 report-meta">
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
          <span>轮次 / 节点</span>
          <strong>
            {{ report.currentNode > 0 ? `第 ${report.round} 轮 · 第 ${report.currentNode} 级` : `第 ${report.round} 轮` }}
          </strong>
        </div>
      </div>
    </section>

    <div class="report-notices">
      <el-alert
        v-if="unlockSubmission.errorMessage.value"
        type="error"
        :title="unlockSubmission.errorMessage.value"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="report.scoreMode === 'TRIAL'"
        :title="
          report.complete
            ? '该评估单尚未生效，当前分数为试算口径，仅供审批决策参考（01 R1）。'
            : '该评估单尚未生效且数据不完整，试算分仅供参考。'
        "
        type="warning"
        :closable="false"
        show-icon
      />
    </div>

    <div class="report-layout">
      <SectionCard
        title="维度雷达"
        class="report-radar"
      >
        <ScoreRadar :dimensions="report.dimensions" />
      </SectionCard>

      <SectionCard
        title="维度与指标明细"
        class="report-breakdown"
      >
        <ScoreBreakdown :dimensions="report.dimensions" />
      </SectionCard>

      <SectionCard
        title="审批时间线"
        class="report-timeline"
      >
        <ApprovalTimeline
          :entries="report.timeline"
          :current-round="report.round"
          :current-node="report.currentNode"
        />
      </SectionCard>
    </div>
  </template>
</template>

<style scoped>
.report-hero {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.6fr);
  gap: 40px;
  align-items: stretch;
}
.report-hero__score {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-right: 40px;
  border-right: 1px solid var(--rule);
}
.report-kicker {
  color: var(--muted-2);
  font-size: 11px;
  line-height: 13px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.report-total {
  margin-top: 4px;
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 62px;
  line-height: 66px;
  font-weight: 500;
  letter-spacing: -2.4px;
}
.report-hero__badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.report-grade {
  display: inline-grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--ink);
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1;
}
.report-hero__note {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 18px;
}
.report-meta {
  align-self: center;
}
.report-notices {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 14px;
}
.report-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
  grid-template-areas:
    'radar breakdown'
    'timeline breakdown';
  gap: 0 36px;
  align-items: start;
}
.report-layout .section-card {
  margin-top: 24px;
}
.report-layout .section-card + .section-card {
  padding-top: 0;
  border-top: 0;
}
.report-radar {
  grid-area: radar;
}
.report-breakdown {
  grid-area: breakdown;
}
.report-timeline {
  grid-area: timeline;
  padding-top: 18px !important;
  border-top: 1px solid var(--rule) !important;
}
@media (max-width: 1200px) {
  .report-hero {
    grid-template-columns: 1fr;
    gap: 24px;
  }
  .report-hero__score {
    padding: 0 0 20px;
    border-right: 0;
    border-bottom: 1px solid var(--rule);
  }
  .report-total {
    font-size: 48px;
    line-height: 52px;
  }
  .report-layout {
    grid-template-columns: 1fr;
    grid-template-areas:
      'radar'
      'breakdown'
      'timeline';
  }
}
</style>
