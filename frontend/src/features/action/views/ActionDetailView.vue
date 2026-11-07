<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  cancelCorrectiveAction,
  getCorrectiveAction,
  startCorrectiveAction,
  submitCorrectiveAction,
  verifyCorrectiveAction,
} from '@/features/action/api'
import {
  actionPermissions,
  actionStatusClass,
  hasPreviousVerification,
  verifyCommentRequired,
} from '@/features/action/action-status'
import { buildActionTimeline } from '@/features/action/action-timeline'
import ActionFormDialog from '@/features/action/components/ActionFormDialog.vue'
import { useAuthStore } from '@/features/auth/store'
import { formatUtcLocal } from '@/shared/approval-timeline'
import { prettyAuditDetail } from '@/shared/audit'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { useSubmit } from '@/shared/composables/useSubmit'
import { formatScore } from '@/shared/format'
import {
  CORRECTIVE_ACTION_AUDIT_LABEL,
  CORRECTIVE_ACTION_STATUS_LABEL,
  VERIFY_OUTCOME_LABEL,
} from '@/shared/labels'
import type { CorrectiveActionDetail } from '@/shared/types/dto'
import type { CorrectiveActionStatus } from '@/shared/types/enums'

/**
 * 整改详情（W4；29 §3.6）。
 *
 * <p>写操作成功后直接用响应里的最新详情替换本地数据，下一次提交的 version 不会拿着旧值；
 * 3001 由 http 拦截器刷新当前视图，4xxx 由拦截器全局提示，页面不重复弹错。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const action = useAsyncData<CorrectiveActionDetail | null>(
  () => getCorrectiveAction(Number(route.params.id)),
  null,
)
const detail = computed(() => action.data.value)
const loaded = computed(() => detail.value != null && !action.loading.value)
const permissions = computed(() =>
  actionPermissions(
    detail.value?.status ?? null,
    loaded.value,
    auth.roles,
    detail.value?.reverifyPending ?? false,
  ),
)
const isManager = computed(() =>
  auth.roles.some((role) => role === 'ADMIN' || role === 'MANAGER'),
)
const timeline = computed(() => (detail.value ? buildActionTimeline(detail.value) : []))
const previousVerification = computed(() =>
  detail.value ? hasPreviousVerification(detail.value) : false,
)
const headerTitle = computed(() => (detail.value ? `整改任务 #${detail.value.id}` : '整改任务'))
const headerDescription = computed(() =>
  detail.value
    ? `${detail.value.supplierCode} · ${detail.value.supplierName} · ${detail.value.baselineYear} Q${detail.value.baselineQuarter}`
    : '整改执行与下期验证',
)
const verifyConclusionNote = computed(() => {
  const current = detail.value
  if (!current) {
    return ''
  }
  if (current.status === 'VERIFIED') {
    return '已定稿'
  }
  if (current.status === 'CANCELED') {
    return '已取消'
  }
  if (current.reverifyPending) {
    return '上一轮结论，等待新周期'
  }
  if (previousVerification.value) {
    return '上一轮结果'
  }
  if (current.verifyOutcome === 'PENDING') {
    return '需经理人工判断'
  }
  return '经理确认后定稿'
})
const actualNote = computed(() => {
  const current = detail.value
  if (!current) {
    return ''
  }
  if (current.reverifyPending) {
    return '上一轮结果，等待新周期'
  }
  if (previousVerification.value) {
    return '上一轮结果'
  }
  return current.verifyYear
    ? `${current.verifyYear} Q${current.verifyQuarter}`
    : '尚未回填'
})

watch(
  () => route.params.id,
  () => {
    action.reset()
    void action.run()
  },
  { immediate: true },
)

function statusLabel(status: CorrectiveActionStatus): string {
  return CORRECTIVE_ACTION_STATUS_LABEL[status] ?? status
}

function formatOptionalUtc(value: string | null): string {
  return value ? formatUtcLocal(value) : '—'
}

const startSubmission = useSubmit()
const submitSubmission = useSubmit()
const verifySubmission = useSubmit()
const cancelSubmission = useSubmit()
const busy = computed(
  () =>
    startSubmission.submitting.value ||
    submitSubmission.submitting.value ||
    verifySubmission.submitting.value ||
    cancelSubmission.submitting.value,
)

async function onStart(): Promise<void> {
  const current = detail.value
  if (!current) {
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确认开始整改任务 #${current.id}？开始后负责人可提交完成。`,
    '开始整改',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  const ok = await startSubmission.run(async () => {
    action.data.value = await startCorrectiveAction(current.id, current.version)
  })
  if (ok) {
    ElMessage.success('已开始整改')
  }
}

const editVisible = ref(false)

function onEdited(saved: CorrectiveActionDetail): void {
  action.data.value = saved
  editVisible.value = false
}

const submitVisible = ref(false)
const submitComment = ref('')

function openSubmit(): void {
  submitVisible.value = true
  submitComment.value = ''
  submitSubmission.clearError()
}

async function confirmSubmit(): Promise<void> {
  const current = detail.value
  if (!current) {
    return
  }
  const ok = await submitSubmission.run(async () => {
    action.data.value = await submitCorrectiveAction(
      current.id,
      current.version,
      submitComment.value.trim() || undefined,
    )
  })
  if (ok) {
    submitVisible.value = false
    ElMessage.success('已提交，等待验证')
  }
}

const verifyVisible = ref(false)
const verifyPass = ref<boolean | undefined>(undefined)
const verifyComment = ref('')
const verifyCommentError = ref('')

function openVerify(): void {
  verifyVisible.value = true
  verifyPass.value = undefined
  verifyComment.value = ''
  verifyCommentError.value = ''
  verifySubmission.clearError()
}

async function confirmVerify(): Promise<void> {
  const current = detail.value
  const pass = verifyPass.value
  if (!current) {
    return
  }
  verifyCommentError.value = ''
  if (pass === undefined) {
    verifyCommentError.value = '请选择验证结论'
    return
  }
  if (verifyCommentRequired(current.verifyOutcome) && !verifyComment.value.trim()) {
    verifyCommentError.value = '下期结果待人工判断时，验证意见不能为空'
    return
  }
  const ok = await verifySubmission.run(async () => {
    action.data.value = await verifyCorrectiveAction(
      current.id,
      current.version,
      pass,
      verifyComment.value.trim() || undefined,
    )
  })
  if (ok) {
    verifyVisible.value = false
    ElMessage.success(pass ? '验证通过' : '已退回继续整改')
  }
}

const cancelVisible = ref(false)
const cancelReason = ref('')
const cancelError = ref('')

function openCancel(): void {
  cancelVisible.value = true
  cancelReason.value = ''
  cancelError.value = ''
  cancelSubmission.clearError()
}

async function confirmCancel(): Promise<void> {
  const current = detail.value
  if (!current) {
    return
  }
  cancelError.value = ''
  const reason = cancelReason.value.trim()
  if (!reason) {
    cancelError.value = '取消原因不能为空'
    return
  }
  const ok = await cancelSubmission.run(async () => {
    action.data.value = await cancelCorrectiveAction(current.id, current.version, reason)
  })
  if (ok) {
    cancelVisible.value = false
    ElMessage.success('整改已取消')
  }
}
</script>

<template>
  <PageHeader
    eyebrow="CORRECTIVE ACTIONS / DETAIL"
    :title="headerTitle"
    :description="headerDescription"
  >
    <template #actions>
      <el-button
        link
        @click="router.push({ name: 'actions' })"
      >
        返回列表
      </el-button>
    </template>
  </PageHeader>

  <el-alert
    v-if="action.error.value"
    type="error"
    :title="action.error.value"
    :closable="false"
    show-icon
  />
  <el-skeleton
    v-else-if="action.loading.value && !detail"
    :rows="7"
    animated
  />

  <template v-else-if="detail">
    <section class="meta-grid meta-grid--4">
      <div class="meta-grid__item">
        <span>来源预警</span>
        <strong>{{ detail.alertTitle }} <small class="mono">#{{ detail.alertId }}</small></strong>
      </div>
      <div class="meta-grid__item">
        <span>供应商</span>
        <strong>{{ detail.supplierCode }} · {{ detail.supplierName }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>基线周期</span>
        <strong>
          {{ detail.baselineYear }} Q{{ detail.baselineQuarter }}
          <template v-if="detail.dimensionName">
            · {{ detail.dimensionName }}
          </template>
        </strong>
      </div>
      <div class="meta-grid__item">
        <span>负责人</span>
        <strong>{{ detail.ownerName ?? '—' }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>截止日期</span>
        <strong>
          <span class="mono">{{ detail.dueDate }}</span>
          <el-tag
            v-if="detail.overdue"
            type="danger"
            size="small"
            class="action-overdue"
          >
            超期
          </el-tag>
        </strong>
      </div>
      <div class="meta-grid__item">
        <span>状态</span>
        <strong>
          <span
            class="status-mark"
            :class="actionStatusClass(detail.status)"
          >{{ statusLabel(detail.status) }}</span>
        </strong>
      </div>
      <div class="meta-grid__item">
        <span>创建</span>
        <strong>{{ detail.createdByName ?? '—' }} · <span class="mono">{{ formatUtcLocal(detail.createdAt) }}</span></strong>
      </div>
      <div class="meta-grid__item">
        <span>最近更新</span>
        <strong class="mono">{{ formatUtcLocal(detail.updatedAt) }}</strong>
      </div>
    </section>

    <section class="figure-strip action-figures">
      <div class="figure">
        <span class="figure__label">基线分</span>
        <div class="figure__value">
          {{ formatScore(detail.baselineScore) }}
        </div>
        <p class="figure__note">
          {{ detail.baselineYear }} Q{{ detail.baselineQuarter }} · {{ detail.dimensionName ?? '总分' }}
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">目标分</span>
        <div class="figure__value">
          {{ formatScore(detail.targetScore) }}
        </div>
        <p class="figure__note">
          下期不得低于该值
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">下期实际</span>
        <div class="figure__value">
          {{ formatScore(detail.verifyScore) }}
        </div>
        <p class="figure__note">
          {{ actualNote }}
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">验证结论</span>
        <div class="figure__value action-outcome">
          {{ detail.verifyOutcome ? VERIFY_OUTCOME_LABEL[detail.verifyOutcome] : '—' }}
        </div>
        <p class="figure__note">
          {{ verifyConclusionNote }}
        </p>
      </div>
    </section>

    <el-alert
      v-if="detail.status === 'CANCELED' && detail.canceledReason"
      class="action-cancel-alert"
      type="warning"
      :title="`整改已取消：${detail.canceledReason}`"
      :closable="false"
      show-icon
    />

    <section class="action-decisions">
      <div class="action-decisions__copy">
        <span class="action-kicker">操作</span>
        <p>所有写操作都以当前版本 {{ detail.version }} 提交。</p>
        <p
          v-if="permissions.readOnlyHint"
          class="action-decisions__hint"
        >
          {{ permissions.readOnlyHint }}
        </p>
      </div>
      <div class="action-decisions__actions">
        <el-button
          v-if="permissions.canEdit"
          :disabled="busy"
          @click="editVisible = true"
        >
          编辑
        </el-button>
        <el-button
          v-if="permissions.canStart"
          type="primary"
          :loading="startSubmission.submitting.value"
          :disabled="busy"
          @click="onStart"
        >
          开始整改
        </el-button>
        <el-button
          v-if="permissions.canSubmit"
          type="primary"
          :disabled="busy"
          @click="openSubmit"
        >
          提交完成
        </el-button>
        <el-button
          v-if="permissions.canVerify"
          type="primary"
          :disabled="busy"
          @click="openVerify"
        >
          验证
        </el-button>
        <el-button
          v-if="permissions.canCancel"
          type="danger"
          plain
          :disabled="busy"
          @click="openCancel"
        >
          取消整改
        </el-button>
      </div>
    </section>

    <SectionCard title="整改措施">
      <p class="action-measure">
        {{ detail.measure }}
      </p>
      <div
        v-if="detail.expectedResult"
        class="action-expected"
      >
        <span class="action-kicker">预期结果</span>
        <p>{{ detail.expectedResult }}</p>
      </div>
    </SectionCard>

    <SectionCard title="下期验证">
      <el-alert
        v-if="detail.reverifyPending"
        class="action-reverify-alert"
        type="info"
        :closable="false"
        show-icon
        title="已重新提交，等待下一次有效评估定稿后重新验证；下面显示的是上一轮结果。"
      />
      <p
        v-if="detail.verifyYear == null && detail.status === 'WAITING_VERIFICATION'"
        class="action-empty"
      >
        下期结果将在该供应商下一次有效评估定稿后回填；期间无需重复操作。
      </p>
      <p
        v-else-if="detail.verifyYear == null"
        class="action-empty"
      >
        尚未回填下期结果。
      </p>
      <div
        v-else
        class="action-verify"
      >
        <div class="action-verify__row">
          <span>验证周期</span>
          <strong class="mono">{{ detail.verifyYear }} Q{{ detail.verifyQuarter }}</strong>
        </div>
        <div class="action-verify__row">
          <span>实际分</span>
          <strong class="mono">{{ formatScore(detail.verifyScore) }}</strong>
        </div>
        <div class="action-verify__row">
          <span>初步结论</span>
          <strong>{{ detail.verifyOutcome ? VERIFY_OUTCOME_LABEL[detail.verifyOutcome] : '—' }}</strong>
        </div>
        <div
          v-if="detail.verifyComment"
          class="action-verify__row"
        >
          <span>验证意见</span>
          <strong>{{ detail.verifyComment }}</strong>
        </div>
        <div
          v-if="detail.verifiedByName"
          class="action-verify__row"
        >
          <span>验证人 / 时间</span>
          <strong>{{ detail.verifiedByName }} · <span class="mono">{{ formatOptionalUtc(detail.verifiedAt) }}</span></strong>
        </div>
        <el-alert
          v-if="detail.verifyOutcome === 'PENDING'"
          type="warning"
          :closable="false"
          show-icon
          title="同周期存在多份生效评估或下期缺少对应维度，需经理人工判断后再选择通过或继续整改。"
        />
      </div>
    </SectionCard>

    <SectionCard title="状态记录">
      <el-timeline
        v-if="timeline.length > 0"
        class="action-timeline"
      >
        <el-timeline-item
          v-for="entry in timeline"
          :key="entry.key"
          :timestamp="formatUtcLocal(entry.time)"
        >
          <div class="action-timeline__title">
            {{ entry.label }}
          </div>
          <p
            v-if="entry.detail"
            class="action-timeline__detail"
          >
            {{ entry.detail }}
          </p>
        </el-timeline-item>
      </el-timeline>
      <el-empty
        v-else
        description="暂无记录"
      />
    </SectionCard>

    <SectionCard title="操作审计">
      <el-empty
        v-if="detail.auditTrail.length === 0"
        description="暂无审计记录"
      />
      <el-table
        v-else
        :data="detail.auditTrail"
        class="table-list action-audit"
      >
        <el-table-column
          label="时间"
          width="170"
        >
          <template #default="{ row }">
            <span class="mono">{{ formatUtcLocal(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="动作"
          width="110"
        >
          <template #default="{ row }">
            {{ CORRECTIVE_ACTION_AUDIT_LABEL[row.action] ?? row.action }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作人"
          width="120"
        >
          <template #default="{ row }">
            {{ row.operatorName ?? '系统' }}
          </template>
        </el-table-column>
        <el-table-column
          label="详情"
          min-width="280"
        >
          <template #default="{ row }">
            <pre class="action-audit__detail">{{ prettyAuditDetail(row.detail) }}</pre>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <ActionFormDialog
      v-model="editVisible"
      :action="detail"
      :manager="isManager"
      @saved="onEdited"
    />

    <el-dialog
      v-model="submitVisible"
      title="提交完成"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="submitSubmission.errorMessage.value"
        type="error"
        :title="submitSubmission.errorMessage.value"
        :closable="false"
        show-icon
      />
      <p class="action-dialog__meta">
        当前版本 {{ detail.version }} · 提交后进入待验证，等待采购经理确认。
      </p>
      <el-input
        v-model="submitComment"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="可选：说明完成情况"
      />
      <template #footer>
        <el-button @click="submitVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitSubmission.submitting.value"
          :disabled="submitSubmission.submitting.value"
          @click="confirmSubmit"
        >
          确认提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="verifyVisible"
      title="验证整改"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="detail.verifyOutcome == null || detail.verifyOutcome === 'PENDING'"
        type="warning"
        :closable="false"
        show-icon
        title="下期结果尚未回填或需人工判断，验证意见不能为空。"
      />
      <el-alert
        v-if="verifyCommentError"
        class="page-alert"
        type="error"
        :title="verifyCommentError"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="verifySubmission.errorMessage.value"
        class="page-alert"
        type="error"
        :title="verifySubmission.errorMessage.value"
        :closable="false"
        show-icon
      />
      <el-form label-width="90px">
        <el-form-item label="验证结论">
          <el-radio-group v-model="verifyPass">
            <el-radio :value="true">
              验证通过
            </el-radio>
            <el-radio :value="false">
              继续整改
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="验证意见">
          <el-input
            v-model="verifyComment"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="写明确认依据或继续整改的要求"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="verifySubmission.submitting.value"
          :disabled="verifySubmission.submitting.value"
          @click="confirmVerify"
        >
          确认验证
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="cancelVisible"
      title="取消整改"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="cancelError"
        type="error"
        :title="cancelError"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="cancelSubmission.errorMessage.value"
        class="page-alert"
        type="error"
        :title="cancelSubmission.errorMessage.value"
        :closable="false"
        show-icon
      />
      <p class="action-dialog__meta">
        取消后不能恢复；已完成的整改记录仍保留在审计中。
      </p>
      <el-input
        v-model="cancelReason"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="必填：取消原因"
      />
      <template #footer>
        <el-button @click="cancelVisible = false">
          取消
        </el-button>
        <el-button
          type="danger"
          :loading="cancelSubmission.submitting.value"
          :disabled="cancelSubmission.submitting.value"
          @click="confirmCancel"
        >
          确认取消整改
        </el-button>
      </template>
    </el-dialog>
  </template>
</template>

<style scoped>
.action-figures {
  margin: 20px 0;
}
.action-outcome {
  font-family: var(--font-serif);
  font-size: 24px;
}
.action-overdue {
  margin-left: 6px;
}
.action-cancel-alert {
  margin-bottom: 20px;
}
.action-reverify-alert {
  margin-bottom: 14px;
}
.action-kicker {
  display: block;
  color: var(--muted-2);
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.action-measure {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
}
.action-expected {
  padding-top: 14px;
  margin-top: 16px;
  border-top: 1px solid var(--rule);
}
.action-expected p {
  margin: 5px 0 0;
  color: var(--ink-secondary);
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.action-empty {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}
.action-verify {
  display: grid;
  gap: 9px;
}
.action-verify__row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 7px;
  border-bottom: 1px dashed var(--rule);
}
.action-verify__row span {
  color: var(--muted);
  font-size: 11px;
}
.action-verify__row strong {
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 500;
  text-align: right;
}
.action-timeline {
  padding-left: 2px;
}
.action-timeline__title {
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 500;
}
.action-timeline__detail {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.action-timeline :deep(.el-timeline-item__content) {
  color: var(--ink-secondary);
}
.action-audit {
  margin-top: 2px;
}
.action-audit__detail {
  max-height: 120px;
  margin: 0;
  overflow: auto;
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.action-decisions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  margin-top: 22px;
  border: 1px solid var(--rule);
  background: var(--paper-deep);
}
.action-decisions__copy p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
.action-decisions__hint {
  color: var(--ink-secondary) !important;
}
.action-decisions__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.action-dialog__meta {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
