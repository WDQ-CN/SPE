<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { type FormInstance, type FormRules } from 'element-plus'

import {
  createCorrectiveAction,
  updateCorrectiveAction,
  type CreateCorrectiveActionPayload,
} from '@/features/action/api'
import { listUserOptions } from '@/features/user/api'
import { useSubmit } from '@/shared/composables/useSubmit'
import { formatScore } from '@/shared/format'
import { ALERT_TYPE_LABEL, CORRECTIVE_ACTION_STATUS_LABEL } from '@/shared/labels'
import type { AlertView, CorrectiveActionDetail } from '@/shared/types/dto'

/**
 * 创建/编辑整改表单（W4）。创建从预警页发起，编辑从详情页发起，字段完全一致。
 *
 * <p>EVALUATOR 编辑时负责人只读，但仍按后端 `@NotNull ownerId` 回传原值；负责人选项只对
 * ADMIN/MANAGER 加载（`/api/user-options` 不对 EVALUATOR 开放）。
 */
const props = defineProps<{
  modelValue: boolean
  alert?: AlertView | null
  action?: CorrectiveActionDetail | null
  manager: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: CorrectiveActionDetail]
}>()

interface OwnerOption {
  id: number
  name: string
}

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const isEdit = computed(() => props.action != null)
const title = computed(() => (isEdit.value ? '编辑整改' : '创建整改'))

const form = reactive({
  ownerId: null as number | null,
  dueDate: '',
  measure: '',
  expectedResult: '',
})
const formRef = ref<FormInstance>()
const { submitting, errorMessage, run, clearError } = useSubmit()

const owners = ref<OwnerOption[]>([])
const ownersLoading = ref(false)
const ownersError = ref('')

const rules: FormRules = {
  ownerId: [{ required: true, message: '请选择整改负责人', trigger: 'change' }],
  dueDate: [{ required: true, message: '请选择整改截止日期', trigger: 'change' }],
  measure: [
    { required: true, message: '整改措施不能为空', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!String(value ?? '').trim()) {
          callback(new Error('整改措施不能为空'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
    { max: 1000, message: '整改措施不得超过 1000 字', trigger: 'blur' },
  ],
  expectedResult: [{ max: 500, message: '预期结果不得超过 500 字', trigger: 'blur' }],
}

const source = computed(() => {
  if (props.action) {
    return {
      title: `整改任务 #${props.action.id} · ${CORRECTIVE_ACTION_STATUS_LABEL[props.action.status]}`,
      code: props.action.supplierCode,
      name: props.action.supplierName,
      period: `${props.action.baselineYear} Q${props.action.baselineQuarter}`,
      baseline: props.action.baselineScore,
      target: props.action.targetScore,
    }
  }
  if (props.alert) {
    return {
      title: `预警：${ALERT_TYPE_LABEL[props.alert.type] ?? props.alert.type}`,
      code: props.alert.supplierCode,
      name: props.alert.supplierName,
      period: `${props.alert.year} Q${props.alert.quarter}`,
      baseline: props.alert.baselineScore,
      target: props.alert.targetScore,
    }
  }
  return null
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void initForm()
    }
  },
  { immediate: true },
)

async function initForm(): Promise<void> {
  clearError()
  ownersError.value = ''
  owners.value = []
  Object.assign(form, {
    ownerId: props.action?.ownerId ?? null,
    dueDate: props.action?.dueDate ?? '',
    measure: props.action?.measure ?? '',
    expectedResult: props.action?.expectedResult ?? '',
  })
  await nextTick()
  formRef.value?.clearValidate()
  if (props.manager) {
    await loadOwners()
  }
}

async function loadOwners(): Promise<void> {
  ownersLoading.value = true
  ownersError.value = ''
  try {
    const options = await listUserOptions(['EVALUATOR', 'MANAGER'])
    owners.value = options.map((option) => ({ id: option.id, name: option.name }))
    const current = props.action
    if (current && !owners.value.some((owner) => owner.id === current.ownerId)) {
      // 负责人已停用/改名时仍要能显示原值，不能因为不在在职目录里就变成空白
      owners.value.unshift({
        id: current.ownerId,
        name: current.ownerName ?? `用户 #${current.ownerId}`,
      })
    }
  } catch {
    ownersError.value = '负责人列表加载失败，请重试'
  } finally {
    ownersLoading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const ownerId = props.manager ? form.ownerId : props.action?.ownerId
  const measure = form.measure.trim()
  if (ownerId == null || !measure) {
    return
  }
  const payload: CreateCorrectiveActionPayload = {
    ownerId,
    dueDate: form.dueDate,
    measure,
    expectedResult: form.expectedResult.trim() || undefined,
  }
  const ok = await run(async () => {
    const saved =
      props.action != null
        ? await updateCorrectiveAction(props.action.id, {
            ...payload,
            version: props.action.version,
          })
        : await createCorrectiveAction(props.alert!.id, payload)
    emit('saved', saved)
  })
  if (ok) {
    visible.value = false
    ElMessage.success(isEdit.value ? '整改任务已更新' : '整改任务已创建')
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="560px"
    :close-on-click-modal="false"
  >
    <div
      v-if="source"
      class="action-form__source"
    >
      <div class="action-form__source-title">
        {{ source.title }}
      </div>
      <div class="action-form__source-meta">
        <span class="mono">{{ source.code }}</span>
        · {{ source.name }} · {{ source.period }}
      </div>
      <div class="action-form__source-score">
        基线 {{ formatScore(source.baseline) }} → 目标 {{ formatScore(source.target) }}
      </div>
    </div>

    <el-alert
      v-if="errorMessage"
      class="page-alert"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="ownersError"
      class="page-alert"
      type="error"
      :title="ownersError"
      :closable="false"
      show-icon
    >
      <el-button
        link
        @click="loadOwners"
      >
        重试
      </el-button>
    </el-alert>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="92px"
      @submit.prevent="save"
    >
      <el-form-item
        label="整改负责人"
        prop="ownerId"
      >
        <el-select
          v-if="manager"
          v-model="form.ownerId"
          filterable
          placeholder="选择整改负责人"
          :loading="ownersLoading"
          style="width: 100%"
        >
          <el-option
            v-for="owner in owners"
            :key="owner.id"
            :label="owner.name"
            :value="owner.id"
          />
        </el-select>
        <span
          v-else
          class="action-form__readonly"
        >
          {{ action?.ownerName ?? '—' }}
        </span>
      </el-form-item>
      <el-form-item
        label="截止日期"
        prop="dueDate"
      >
        <el-date-picker
          v-model="form.dueDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择整改截止日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item
        label="整改措施"
        prop="measure"
      >
        <el-input
          v-model="form.measure"
          type="textarea"
          :rows="5"
          maxlength="1000"
          show-word-limit
          placeholder="写明具体措施、责任人与完成方式"
        />
      </el-form-item>
      <el-form-item
        label="预期结果"
        prop="expectedResult"
      >
        <el-input
          v-model="form.expectedResult"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="可选：预期达到的分数或业务结果"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="submitting || ownersLoading"
        @click="save"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.action-form__source {
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid var(--rule);
  background: var(--paper-deep);
}
.action-form__source-title {
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 500;
}
.action-form__source-meta {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
.action-form__source-score {
  margin-top: 6px;
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 12px;
}
.action-form__readonly {
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 12px;
}
</style>
