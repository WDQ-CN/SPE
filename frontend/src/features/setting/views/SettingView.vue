<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { getGradeThresholds, updateGradeThresholds } from '@/features/setting/api'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { GradeThresholds } from '@/shared/types/dto'
import { thresholdOrderingError } from '@/shared/validation'

const thresholds = reactive<GradeThresholds>({ a: 90, b: 80, c: 70, d: 0 })
const loading = ref(false)
const loadError = ref('')
const { submitting: saving, errorMessage: saveError, run: runSave } = useSubmit()

// 即时提示，不是判定：跨字段规则（0 ≤ D ≤ C ≤ B ≤ A ≤ 100，允许相等）最终由后端判并返回 2005
const orderingError = computed(() => thresholdOrderingError(thresholds))

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    Object.assign(thresholds, await getGradeThresholds())
  } catch (caught) {
    loadError.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

async function save(): Promise<void> {
  if (orderingError.value) {
    return
  }
  const ok = await runSave(async () => {
    // 以后端返回的为准写回：规范化（如精度）由后端决定，前端不假设自己算的就是最终值
    Object.assign(thresholds, await updateGradeThresholds({ ...thresholds }))
  })
  if (ok) {
    ElMessage.success('已保存')
  }
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      等级阈值
    </template>

    <el-alert
      v-if="loadError"
      class="page__error"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    >
      <el-button
        link
        type="primary"
        @click="load"
      >
        重试
      </el-button>
    </el-alert>

    <p class="page__hint">
      总分与各维度分按同一套阈值独立定级：得分 ≥ A 为 A 档，否则 ≥ B 为 B 档，依此类推。
      <strong>允许相邻两档取相同值</strong>，相等即表示该档不出现（例如 C 与 D 都填 70，就不会有 C 档）。
    </p>

    <el-form
      v-loading="loading"
      label-width="88px"
      @submit.prevent="save"
    >
      <el-form-item label="A 档阈值">
        <el-input-number
          v-model="thresholds.a"
          :min="0"
          :max="100"
          :step="1"
        />
      </el-form-item>
      <el-form-item label="B 档阈值">
        <el-input-number
          v-model="thresholds.b"
          :min="0"
          :max="100"
          :step="1"
        />
      </el-form-item>
      <el-form-item label="C 档阈值">
        <el-input-number
          v-model="thresholds.c"
          :min="0"
          :max="100"
          :step="1"
        />
      </el-form-item>
      <el-form-item label="D 档阈值">
        <el-input-number
          v-model="thresholds.d"
          :min="0"
          :max="100"
          :step="1"
        />
      </el-form-item>

      <el-alert
        v-if="orderingError"
        class="page__error"
        type="warning"
        :title="orderingError"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="saveError"
        class="page__error"
        type="error"
        :title="saveError"
        :closable="false"
        show-icon
      />

      <el-form-item>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="orderingError !== null"
          @click="save"
        >
          保存
        </el-button>
        <el-button
          :disabled="loading"
          @click="load"
        >
          重新加载
        </el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.page__hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.page__error {
  margin-bottom: 12px;
}
</style>
