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
import { formatScore } from '@/shared/format'
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
    eyebrow="ANALYTICS"
    title="分析看板"
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
    <SectionCard title="筛选">
      <div class="analytics__filters">
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
        <span class="analytics__hint">分类/等级只作用于排行；趋势与对比按所选供应商取数。</span>
      </div>
    </SectionCard>

    <SectionCard title="总分排行（含环比）">
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
          prop="supplierCode"
          label="供应商编码"
          width="140"
        />
        <el-table-column
          prop="supplierName"
          label="供应商"
          min-width="160"
        />
        <el-table-column
          label="分类"
          width="120"
        >
          <template #default="{ row }">
            {{ row.category ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          label="总分"
          width="100"
        >
          <template #default="{ row }">
            {{ formatScore(row.totalScore) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="totalGrade"
          label="等级"
          width="80"
        />
        <el-table-column
          label="上期总分"
          width="100"
        >
          <template #default="{ row }">
            {{ row.previousScore == null ? '–' : formatScore(row.previousScore) }}
          </template>
        </el-table-column>
        <el-table-column
          label="环比"
          width="100"
        >
          <template #default="{ row }">
            <span :class="deltaClass(row.delta)">{{ deltaText(row.delta) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="analytics__hint">
        环比只与<strong>上一个日历季度</strong>比较；该季度没有生效结果时显示「–」（不跨缺口回溯）。
      </p>
    </SectionCard>

    <SectionCard title="等级分布">
      <AnalyticsChart
        v-if="gradeChart"
        :option="gradeChart"
        height="280px"
      />
      <el-empty
        v-else
        description="该周期没有生效结果"
      />
    </SectionCard>

    <SectionCard title="历史趋势">
      <div class="analytics__filters">
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
      </div>
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
      <div class="analytics__filters">
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
      </div>
      <el-alert
        v-if="compareError"
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
.analytics__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.analytics__period {
  width: 150px;
}
.analytics__filter {
  width: 140px;
}
.analytics__supplier {
  width: 260px;
}
.analytics__compare {
  width: 100%;
  max-width: 640px;
}
.analytics__hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.delta--up {
  color: var(--el-color-success);
}
.delta--down {
  color: var(--el-color-danger);
}
</style>
