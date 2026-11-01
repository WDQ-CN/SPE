<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import {
  getAnalyticsCompare,
  getAnalyticsRanking,
  getAnalyticsTrend,
  getGradeDistribution,
  listAnalyticsPeriods,
  listAnalyticsSuppliers,
} from '@/features/analytics/api'
import AnalyticsChart from '@/features/analytics/components/AnalyticsChart.vue'
import { compareOption, gradeOption, periodLabel, trendOption } from '@/features/analytics/charts'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { GRADE_ORDER } from '@/shared/labels'
import { formatScore, gradeClass } from '@/shared/format'
import type {
  AnalyticsCompare,
  AnalyticsGradeCount,
  AnalyticsPeriod,
  AnalyticsRanking,
  AnalyticsSupplierOption,
  AnalyticsTrend,
} from '@/shared/types/dto'

/**
 * 分析看板（01 B1/B2/B4/B5；M5 W6）。
 *
 * <p>数据口径全部在后端（只读 EFFECTIVE、环比只认上一日历季度、维度名并集）；本页只做选择与呈现。
 * 周期默认取 `/periods` 的最新一条；空库/空周期显示空态而不是造数。
 */
const periodKey = ref('')
const category = ref('')
const grade = ref('')
const trendSupplierId = ref<number | null>(null)
const compareIds = ref<number[]>([])

// 四个数据源各自走 useAsyncData：迟到响应由序号栅栏统一拦截，页面不再各写一套 seq + try/catch
const {
  data: baseData,
  loading: baseLoading,
  error: baseError,
  run: loadBase,
} = useAsyncData(
  async () => {
    const [periodList, supplierList] = await Promise.all([
      listAnalyticsPeriods(),
      listAnalyticsSuppliers(),
    ])
    return { periods: periodList, suppliers: supplierList }
  },
  { periods: [] as AnalyticsPeriod[], suppliers: [] as AnalyticsSupplierOption[] },
  {
    onSuccess: (data) => {
      // 周期默认取最新一条、供应商默认取第一家；下面的 watcher 随后各自发起首次加载
      if (data.periods.length > 0) {
        periodKey.value = keyOf(data.periods[0])
      }
      if (data.suppliers.length > 0) {
        trendSupplierId.value = data.suppliers[0].id
      }
    },
  },
)
const periods = computed(() => baseData.value.periods)
const suppliers = computed(() => baseData.value.suppliers)

const selectedPeriod = computed(
  () => periods.value.find((period) => keyOf(period) === periodKey.value) ?? null,
)
const categories = computed(() =>
  [...new Set(suppliers.value.map((supplier) => supplier.category).filter((value): value is string => !!value))].sort(),
)

const {
  data: rankingData,
  loading: loadingRanking,
  error: rankingError,
  run: loadRanking,
} = useAsyncData(
  async () => {
    const period = selectedPeriod.value
    if (!period) {
      return { ranking: null as AnalyticsRanking | null, gradeCounts: [] as AnalyticsGradeCount[] }
    }
    const [rank, distribution] = await Promise.all([
      getAnalyticsRanking({
        year: period.year,
        quarter: period.quarter,
        category: category.value || undefined,
        grade: grade.value || undefined,
      }),
      getGradeDistribution({ year: period.year, quarter: period.quarter }),
    ])
    return { ranking: rank, gradeCounts: distribution }
  },
  { ranking: null as AnalyticsRanking | null, gradeCounts: [] as AnalyticsGradeCount[] },
  {
    onSuccess: (data) => {
      // 对比默认取当前排行前两家：它们必然在本周期有生效结果（避免默认选择打出 400）
      if (compareIds.value.length === 0) {
        compareIds.value = data.ranking?.rows.slice(0, 2).map((row) => row.supplierId) ?? []
      }
    },
  },
)
const ranking = computed(() => rankingData.value.ranking)
const gradeCounts = computed(() => rankingData.value.gradeCounts)
const rankingRows = computed(() => ranking.value?.rows ?? [])
const scoredRows = computed(() => rankingRows.value.filter((row) => row.totalScore != null))
const averageScore = computed(() => {
  if (scoredRows.value.length === 0) {
    return null
  }
  const sum = scoredRows.value.reduce((total, row) => total + (row.totalScore as number), 0)
  return sum / scoredRows.value.length
})
const aGradeCount = computed(
  () => gradeCounts.value.find((item) => item.grade === 'A')?.count ?? 0,
)
const risingCount = computed(
  () => rankingRows.value.filter((row) => row.delta != null && row.delta > 0).length,
)

const {
  data: trendData,
  error: trendError,
  run: loadTrend,
} = useAsyncData(
  async () => {
    const id = trendSupplierId.value
    return id == null ? null : await getAnalyticsTrend(id)
  },
  null as AnalyticsTrend | null,
)
const trend = computed(() => trendData.value)

const {
  data: compareData,
  error: compareError,
  run: loadCompare,
} = useAsyncData(
  async () => {
    const period = selectedPeriod.value
    if (!period || compareIds.value.length === 0) {
      return null
    }
    return await getAnalyticsCompare({
      year: period.year,
      quarter: period.quarter,
      supplierIds: compareIds.value,
    })
  },
  null as AnalyticsCompare | null,
)
const compare = computed(() => compareData.value)

const gradeChart = computed(() =>
  gradeCounts.value.length > 0 ? gradeOption(gradeCounts.value) : null,
)
const trendChart = computed(() =>
  trend.value && trend.value.periods.length > 0 ? trendOption(trend.value) : null,
)
const compareChart = computed(() =>
  compare.value && compare.value.rows.length > 0 ? compareOption(compare.value) : null,
)

function keyOf(period: AnalyticsPeriod): string {
  return `${period.year}-${period.quarter}`
}

onMounted(loadBase)
// loadBase 的 onSuccess 写入 periodKey/trendSupplierId 后，下面的 watcher 会各自发起首次加载；
// 这里不再显式调用一遍，避免首次进入重复请求排行/趋势（03 §44 的 M5R-07）。

watch(periodKey, () => {
  // 周期切换后对比先清空：旧选择在新周期可能没有生效结果（否则会打出一个 400）。
  // 置空会触发下面的 compareIds watcher，它负责把 compare/compareError 一并清掉。
  compareIds.value = []
  void loadRanking()
})
watch([category, grade], () => void loadRanking())
watch(trendSupplierId, () => void loadTrend())
watch(compareIds, () => void loadCompare(), { deep: true })

function deltaText(value: number | null): string {
  if (value == null) {
    return '–'
  }
  if (value > 0) {
    return `+${formatScore(value)}`
  }
  return formatScore(value)
}

function deltaClass(value: number | null): string {
  if (value == null || value === 0) {
    return ''
  }
  return value > 0 ? 'delta--up' : 'delta--down'
}
</script>

<template>
  <PageHeader
    eyebrow="ANALYTICS / PERFORMANCE"
    title="分析看板"
    description="已生效评估单的周期口径 — 排行、等级分布、历史趋势与多供应商对比"
  />

  <el-alert
    v-if="baseError"
    type="error"
    :title="baseError"
    :closable="false"
    show-icon
  />
  <el-skeleton
    v-else-if="baseLoading"
    :rows="6"
    animated
  />
  <el-empty
    v-else-if="periods.length === 0"
    description="还没有已生效的评估结果（末级审批通过并定稿后才会出现在这里）"
  />
  <template v-else>
    <section class="analytics-bar">
      <div class="analytics-bar__field">
        <span>评估周期</span>
        <el-select
          v-model="periodKey"
          placeholder="选择周期"
          class="analytics__period"
        >
          <el-option
            v-for="period in periods"
            :key="keyOf(period)"
            :label="periodLabel(period.year, period.quarter)"
            :value="keyOf(period)"
          />
        </el-select>
      </div>
      <div class="analytics-bar__field">
        <span>供应商分类</span>
        <el-select
          v-model="category"
          placeholder="全部分类"
          clearable
          class="analytics__filter"
        >
          <el-option
            v-for="item in categories"
            :key="item"
            :label="item"
            :value="item"
          />
        </el-select>
      </div>
      <div class="analytics-bar__field">
        <span>等级</span>
        <el-select
          v-model="grade"
          placeholder="全部等级"
          clearable
          class="analytics__filter"
        >
          <el-option
            v-for="item in GRADE_ORDER"
            :key="item"
            :label="item"
            :value="item"
          />
        </el-select>
      </div>
      <p class="analytics-bar__note">
        分类/等级只作用于排行；趋势与对比按所选供应商取数。
      </p>
    </section>

    <section class="figure-strip analytics-figures">
      <div class="figure">
        <span class="figure__label">参与供应商</span>
        <div class="figure__value">
          {{ rankingRows.length }}<small>家</small>
        </div>
        <p class="figure__note">
          本周期已生效
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">平均总分</span>
        <div class="figure__value">
          {{ formatScore(averageScore) }}
        </div>
        <p class="figure__note">
          基于 {{ scoredRows.length }} 家有总分记录
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">A 档供应商</span>
        <div class="figure__value">
          {{ aGradeCount }}<small>家</small>
        </div>
        <p class="figure__note">
          等级分布固定 A–D
        </p>
      </div>
      <div class="figure">
        <span class="figure__label">环比上升</span>
        <div class="figure__value">
          {{ risingCount }}<small>家</small>
        </div>
        <p class="figure__note">
          仅与上一日历季度比较
        </p>
      </div>
    </section>

    <div class="analytics-layout">
      <SectionCard title="总分排行">
        <el-alert
          v-if="rankingError"
          type="error"
          :title="rankingError"
          :closable="false"
          show-icon
        />
        <el-empty
          v-else-if="!loadingRanking && ranking && ranking.rows.length === 0"
          description="该周期没有符合条件的生效结果"
        />
        <el-table
          v-else
          v-loading="loadingRanking"
          :data="ranking?.rows ?? []"
        >
          <el-table-column
            label="序"
            width="52"
          >
            <template #default="{ $index }">
              <span class="mono">{{ String($index + 1).padStart(2, '0') }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="供应商"
            min-width="190"
          >
            <template #default="{ row }">
              <span class="analytics-supplier-name">{{ row.supplierName }}</span>
              <span class="analytics-supplier-code">{{ row.supplierCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="分类"
            width="110"
          >
            <template #default="{ row }">
              {{ row.category ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="总分"
            width="100"
            align="right"
          >
            <template #default="{ row }">
              <span class="analytics-score">{{ formatScore(row.totalScore) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="等级"
            width="80"
          >
            <template #default="{ row }">
              <span
                class="status-mark"
                :class="gradeClass(row.totalGrade)"
              >
                {{ row.totalGrade ?? '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="上期"
            width="90"
            align="right"
          >
            <template #default="{ row }">
              {{ row.previousScore == null ? '–' : formatScore(row.previousScore) }}
            </template>
          </el-table-column>
          <el-table-column
            label="环比"
            width="90"
            align="right"
          >
            <template #default="{ row }">
              <span :class="deltaClass(row.delta)">{{ deltaText(row.delta) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="section-note">
          环比只与<strong>上一个日历季度</strong>比较；该季度没有生效结果时显示「–」（不跨缺口回溯）。
        </p>
      </SectionCard>

      <SectionCard title="等级分布">
        <AnalyticsChart
          v-if="gradeChart"
          :option="gradeChart"
          height="300px"
        />
        <el-empty
          v-else
          description="该周期没有生效结果"
        />
      </SectionCard>
    </div>

    <SectionCard title="历史趋势">
      <template #actions>
        <el-select
          v-model="trendSupplierId"
          placeholder="选择供应商"
          filterable
          class="analytics__supplier"
        >
          <el-option
            v-for="item in suppliers"
            :key="item.id"
            :label="`${item.code} ${item.name}`"
            :value="item.id"
          />
        </el-select>
      </template>
      <el-alert
        v-if="trendError"
        type="error"
        :title="trendError"
        :closable="false"
        show-icon
      />
      <AnalyticsChart
        v-else-if="trendChart"
        :option="trendChart"
      />
      <el-empty
        v-else
        description="该供应商还没有生效结果"
      />
    </SectionCard>

    <SectionCard title="多供应商对比">
      <el-select
        v-model="compareIds"
        multiple
        filterable
        collapse-tags
        :multiple-limit="10"
        placeholder="选择 1~10 个供应商"
        class="analytics__compare"
      >
        <el-option
          v-for="item in suppliers"
          :key="item.id"
          :label="`${item.code} ${item.name}`"
          :value="item.id"
        />
      </el-select>
      <el-alert
        v-if="compareError"
        class="page-alert"
        type="error"
        :title="compareError"
        :closable="false"
        show-icon
      />
      <AnalyticsChart
        v-else-if="compareChart"
        :option="compareChart"
      />
      <el-empty
        v-else
        description="所选供应商在该周期没有生效结果（或尚未选择）"
      />
    </SectionCard>
  </template>
</template>

<style scoped>
.analytics-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 22px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--rule);
}
.analytics-bar__field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.analytics-bar__field > span {
  color: var(--muted-2);
  font-size: 8.5px;
  line-height: 13px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.analytics-bar__note {
  margin: 0 0 2px auto;
  color: var(--muted);
  font-size: 10.5px;
  line-height: 18px;
}
.analytics__period {
  width: 150px;
}
.analytics__filter {
  width: 150px;
}
.analytics__supplier {
  width: 280px;
}
.analytics__compare {
  width: 100%;
  max-width: 720px;
  margin-bottom: 16px;
}
.analytics-figures {
  margin-top: 18px;
}
.analytics-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr);
  gap: 36px;
  align-items: start;
}
.analytics-layout .section-card {
  margin-top: 24px;
}
.analytics-layout .section-card + .section-card {
  padding-top: 0;
  border-top: 0;
}
.analytics-supplier-name {
  display: block;
  color: var(--ink);
  font-weight: 500;
}
.analytics-supplier-code {
  display: block;
  margin-top: 1px;
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 13px;
}
.analytics-score {
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 500;
  letter-spacing: -0.2px;
}
.delta--up {
  color: var(--success);
}
.delta--down {
  color: var(--danger);
}
@media (max-width: 1200px) {
  .analytics-layout {
    grid-template-columns: 1fr;
  }
  .analytics-layout .section-card + .section-card {
    padding-top: 18px;
    border-top: 1px solid var(--rule);
  }
}
</style>
