<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getTaskResults } from '@/features/evaluation/api'
import {
  filterAndSortResults,
  type ResultSortField,
  type SortOrder,
} from '@/features/evaluation/result-sort'
import { formatGrade, formatScore } from '@/shared/format'
import type { TaskResults } from '@/shared/types/dto'

/**
 * 任务结果列表（01 R2）：只展示已生效评估单的总分、总等级与各维度分/等级。
 *
 * <p>后端只返回 `EFFECTIVE` 的评估单（15 §E15）；空列表是合法状态（任务还在进行中），不是错误。
 * 排序/筛选在本地做（端点设计为一次返回全部生效行），规则见 `result-sort.ts`。
 */
const route = useRoute()
const router = useRouter()
const taskId = ref(Number(route.params.id))
const results = ref<TaskResults | null>(null)
const loading = ref(false)
const error = ref('')

const keyword = ref('')
const sortBy = ref<ResultSortField>('totalScore')
const order = ref<SortOrder>('desc')
const visibleRows = computed(() =>
  filterAndSortResults(results.value?.rows ?? [], {
    keyword: keyword.value,
    sortBy: sortBy.value,
    order: order.value,
  }),
)

let loadSeq = 0

async function load(): Promise<void> {
  const requested = taskId.value
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const data = await getTaskResults(requested)
    if (seq !== loadSeq) {
      return
    }
    results.value = data
  } catch (caught) {
    if (seq !== loadSeq) {
      return
    }
    results.value = null
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

watch(
  () => route.params.id,
  () => {
    taskId.value = Number(route.params.id)
    void load()
  },
)
onMounted(() => void load())
</script>

<template>
  <PageHeader
    eyebrow="RESULTS"
    :title="results ? `结果 · ${results.taskName}` : '任务结果'"
  />

  <el-alert
    v-if="error"
    :title="error"
    type="error"
    :closable="false"
    show-icon
  />
  <el-skeleton
    v-else-if="loading && !results"
    :rows="5"
    animated
  />
  <SectionCard
    v-else-if="results"
    title="供应商结果"
  >
    <template #actions>
      <el-button
        link
        type="primary"
        @click="router.push({ name: 'results' })"
      >
        返回任务列表
      </el-button>
    </template>

    <el-empty
      v-if="results.rows.length === 0"
      description="该任务暂无已生效的评估单（末级审批通过并定稿后才会出现在这里）"
    />
    <template v-else>
      <div class="results__filters">
        <el-input
          v-model="keyword"
          placeholder="搜索供应商"
          clearable
          class="results__keyword"
        />
        <el-select
          v-model="sortBy"
          class="results__sort"
        >
          <el-option
            label="按总分"
            value="totalScore"
          />
          <el-option
            label="按总等级"
            value="totalGrade"
          />
          <el-option
            label="按供应商名称"
            value="supplier"
          />
        </el-select>
        <el-select
          v-model="order"
          class="results__order"
        >
          <el-option
            label="降序"
            value="desc"
          />
          <el-option
            label="升序"
            value="asc"
          />
        </el-select>
      </div>
      <el-empty
        v-if="visibleRows.length === 0"
        description="没有匹配的供应商"
      />
      <el-table
        v-else
        v-loading="loading"
        :data="visibleRows"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="results__dimensions">
              <el-table
                :data="row.dimensions"
                size="small"
              >
                <el-table-column
                  prop="dimensionName"
                  label="维度"
                  min-width="140"
                />
                <el-table-column
                  label="权重"
                  width="90"
                >
                  <template #default="scope">
                    {{ scope.row.weight.toFixed(2) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="维度分"
                  width="110"
                >
                  <template #default="scope">
                    {{ formatScore(scope.row.score) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="等级"
                  width="80"
                >
                  <template #default="scope">
                    {{ formatGrade(scope.row.grade) }}
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="供应商"
          min-width="190"
        >
          <template #default="{ row }">
            {{ row.supplierCode }} · {{ row.supplierName }}
          </template>
        </el-table-column>
        <el-table-column
          label="总分"
          width="110"
        >
          <template #default="{ row }">
            {{ formatScore(row.totalScore) }}
          </template>
        </el-table-column>
        <el-table-column
          label="总等级"
          width="90"
        >
          <template #default="{ row }">
            <el-tag>{{ formatGrade(row.totalGrade) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'report', params: { id: row.taskSupplierId } })"
            >
              评估报告
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </SectionCard>
</template>

<style scoped>
.results__filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.results__keyword {
  max-width: 260px;
}
.results__sort {
  width: 150px;
}
.results__order {
  width: 100px;
}
.results__dimensions {
  padding: 8px 18px 8px 54px;
  background: var(--el-fill-color-lighter);
}
</style>
