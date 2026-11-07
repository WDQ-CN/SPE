<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  getAlertRules,
  getGradeThresholds,
  updateAlertRules,
  updateGradeThresholds,
  type AlertRulesPayload,
} from '@/features/setting/api'
import { alertRulesError } from '@/features/setting/alert-rules'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { GradeThresholds } from '@/shared/types/dto'
import { thresholdOrderingError } from '@/shared/validation'

// 占位值不是默认值：真实设置只来自后端（默认值由后端代码持有）。加载成功前禁止保存，
// 避免加载失败时把占位值写回、覆盖用户已定制的设置（03 §51 P2-1）。
const thresholds = reactive<GradeThresholds>({ a: 0, b: 0, c: 0, d: 0 })
const thresholdsLoaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const { submitting: saving, errorMessage: saveError, run: runSave } = useSubmit()

// 即时提示，不是判定：跨字段规则（0 ≤ D ≤ C ≤ B ≤ A ≤ 100，允许相等）最终由后端判并返回 2005
const orderingError = computed(() => thresholdOrderingError(thresholds))

// ---- 预警规则（M5 SM3） ----
// 同上：占位值不是默认值，加载成功前禁止保存
const alertRules = reactive<AlertRulesPayload>({
  enabled: false,
  totalThreshold: 0,
  keyDimensionThreshold: 0,
  trendPeriods: 1,
  trendDrop: 0,
})
const rulesLoaded = ref(false)
const rulesLoading = ref(false)
const rulesLoadError = ref('')
const {
  submitting: rulesSaving,
  errorMessage: rulesSaveError,
  run: runSaveRules,
} = useSubmit()
const rulesError = computed(() => alertRulesError(alertRules))

async function loadAlertRules(): Promise<void> {
  rulesLoading.value = true
  rulesLoadError.value = ''
  try {
    Object.assign(alertRules, await getAlertRules())
    rulesLoaded.value = true
  } catch (caught) {
    rulesLoaded.value = false
    rulesLoadError.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    rulesLoading.value = false
  }
}

async function saveAlertRules(): Promise<void> {
  if (!rulesLoaded.value || rulesError.value) {
    return
  }
  const ok = await runSaveRules(async () => {
    Object.assign(alertRules, await updateAlertRules({ ...alertRules }))
  })
  if (ok) {
    ElMessage.success('已保存预警规则')
  }
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    Object.assign(thresholds, await getGradeThresholds())
    thresholdsLoaded.value = true
  } catch (caught) {
    thresholdsLoaded.value = false
    loadError.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  void loadAlertRules()
})

async function save(): Promise<void> {
  if (!thresholdsLoaded.value || orderingError.value) {
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
  <PageHeader
    eyebrow="SYSTEM / SETTINGS"
    title="系统设置"
    description="等级阈值与预警规则 — 保存后立即作用于后续评估与预警判定"
  />

  <div class="setting-grid">
    <SectionCard title="等级阈值">
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
        class="setting-form"
        label-width="120px"
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
            :disabled="!thresholdsLoaded || orderingError !== null"
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
    </SectionCard>

    <SectionCard title="预警规则">
      <el-alert
        v-if="rulesLoadError"
        class="page__error"
        type="error"
        :title="rulesLoadError"
        :closable="false"
        show-icon
      >
        <el-button
          link
          type="primary"
          @click="loadAlertRules"
        >
          重试
        </el-button>
      </el-alert>

      <p class="page__hint">
        评估单生效时自动判定：① 总分低于阈值；② 每个关键维度得分低于阈值；③ 连续 N 期总分累计下降 ≥ X。
        关闭后不再生成新预警，但结果被重算/解锁时旧预警仍会关闭。
      </p>

      <el-form
        v-loading="rulesLoading"
        class="setting-form"
        label-width="120px"
        @submit.prevent="saveAlertRules"
      >
        <el-form-item label="启用预警">
          <el-switch v-model="alertRules.enabled" />
        </el-form-item>
        <el-form-item label="总分阈值">
          <el-input-number
            v-model="alertRules.totalThreshold"
            :min="0"
            :max="100"
            :step="1"
          />
        </el-form-item>
        <el-form-item label="关键维度阈值">
          <el-input-number
            v-model="alertRules.keyDimensionThreshold"
            :min="0"
            :max="100"
            :step="1"
          />
        </el-form-item>
        <el-form-item label="连续期数">
          <el-input-number
            v-model="alertRules.trendPeriods"
            :min="1"
            :max="8"
            :step="1"
          />
        </el-form-item>
        <el-form-item label="累计降幅">
          <el-input-number
            v-model="alertRules.trendDrop"
            :min="0"
            :max="100"
            :step="1"
          />
        </el-form-item>

        <el-alert
          v-if="rulesError"
          class="page__error"
          type="warning"
          :title="rulesError"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="rulesSaveError"
          class="page__error"
          type="error"
          :title="rulesSaveError"
          :closable="false"
          show-icon
        />

        <el-form-item>
          <el-button
            type="primary"
            :loading="rulesSaving"
            :disabled="!rulesLoaded || rulesError !== null"
            @click="saveAlertRules"
          >
            保存
          </el-button>
          <el-button
            :disabled="rulesLoading"
            @click="loadAlertRules"
          >
            重新加载
          </el-button>
        </el-form-item>
      </el-form>
    </SectionCard>
  </div>
</template>

<style scoped>
.setting-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 48px;
  align-items: start;
}

.setting-grid .section-card {
  margin-top: 0;
}

.setting-grid .section-card + .section-card {
  padding-top: 0;
  border-top: 0;
  border-left: 1px solid var(--rule);
  padding-left: 48px;
}

.setting-form {
  max-width: 520px;
}

.setting-form :deep(.el-form-item) {
  padding: 10px 0;
  margin-bottom: 0;
  border-bottom: 1px solid var(--rule);
}

.setting-form :deep(.el-form-item:last-child) {
  padding-top: 18px;
  border-bottom: 0;
}

.setting-form :deep(.el-input-number) {
  width: 150px;
}

.page__hint {
  margin: 0 0 14px;
  color: var(--muted);
  font-size: 11px;
  line-height: 19px;
}

.page__error {
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .setting-grid {
    grid-template-columns: 1fr;
  }

  .setting-grid .section-card + .section-card {
    margin-top: 24px;
    padding-top: 18px;
    padding-left: 0;
    border-top: 1px solid var(--rule);
    border-left: 0;
  }
}
</style>
