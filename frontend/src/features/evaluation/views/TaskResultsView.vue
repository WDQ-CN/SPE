<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { downloadFile } from '@/app/http'
import { getTaskResults } from '@/features/evaluation/api'
import {
  filterAndSortResults,
  type ResultSortField,
  type SortOrder,
} from '@/features/evaluation/result-sort'
import { useAsyncData } from '@/shared/composables/useAsyncData'
import { saveBlob } from '@/shared/download'
import { formatGrade, formatScore, formatWeight, gradeClass } from '@/shared/format'
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
const {
  data: results,
  loading,
  error,
  run: load,
} = useAsyncData(() => getTaskResults(taskId.value), null as TaskResults | null)

const keyword = ref('')
const sortBy = ref<ResultSortField>('totalScore')
const order = ref<SortOrder>('desc')
const exporting = ref(false)
const visibleRows = computed(() =>
  filterAndSortResults(results.value?.rows ?? [], {
    keyword: keyword.value,
    sortBy: sortBy.value,
    order: order.value,
  }),
)

async function onExport(): Promise<void> {
  const current = results.value
  if (!current) {
    return
  }
  exporting.value = true
  try {
    const blob = await downloadFile(`/tasks/${current.taskId}/results/export`)
    const today = new Date().toISOString().slice(0, 10).replaceAll('-', '')
    // 任务名里可能有路径分隔符/引号：文件系统不接受，先替换（后端也在响应头里做过同样的清理）
    const safeName = current.taskName.replace(/[\\/:*?"<>|]/g, '_')
    saveBlob(blob, `结果-${safeName}-${today}.xlsx`)
  } catch {
    // downloadFile 已按 blob 错误体提示；这里只保证不产生未接住的 rejection（04-037 同族）
  } finally {
    exporting.value = false
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
    eyebrow="RESULTS / TASK"
    :title="results ? `结果 · ${results.taskName}` : '任务结果'"
    :description="results ? `${results.year} 年 Q${results.quarter} · 已生效评估单的定稿快照` : ''"
  >
    <template #actions>
      <el-button
        v-if="results && results.rows.length > 0"
        type="primary"
        :loading="exporting"
        @click="onExport"
      >
        导出 Excel
      </el-button>
      <el-button
        link
        @click="router.push({ name: 'results' })"
      >
        返回任务列表
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
    v-else-if="loading && !results"
    :rows="5"
    animated
  />
  <template v-else-if="results">
    <section class="meta-grid meta-grid--4 results-meta">
      <div class="meta-grid__item">
        <span>评估任务</span>
        <strong>{{ results.taskName }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>评估周期</span>
        <strong>{{ results.year }} 年 Q{{ results.quarter }}</strong>
      </div>
      <div class="meta-grid__item">
        <span>生效供应商</span>
        <strong>{{ results.rows.length }} 家</strong>
      </div>
      <div class="meta-grid__item">
        <span>结果口径</span>
        <strong>定稿快照（SNAPSHOT）</strong>
      </div>
    </section>

    <SectionCard title="供应商结果">
      <el-empty
        v-if="results.rows.length === 0"
        description="该任务暂无已生效的评估单（末级审批通过并定稿后才会出现在这里）"
      />
      <template v-else>
        <div class="list-toolbar">
          <el-input
            v-model="keyword"
            placeholder="搜索供应商"
            clearable
            class="list-toolbar__search"
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
          <span class="list-toolbar__note">展开行查看各维度分与等级。</span>
        </div>
        <el-empty
          v-if="visibleRows.length === 0"
          description="没有匹配的供应商"
        />
        <el-table
          v-else
          v-loading="loading"
          :data="visibleRows"
          class="table-list"
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
                    min-width="160"
                  />
                  <el-table-column
                    label="权重"
                    width="90"
                    align="right"
                  >
                    <template #default="scope">
                      {{ formatWeight(scope.row.weight) }}
                    </template>
                  </el-table-column>
                  <el-table-column
                    label="维度分"
                    width="110"
                    align="right"
                  >
                    <template #default="scope">
                      <span class="result-dimension-score">{{ formatScore(scope.row.score) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column
                    label="等级"
                    width="80"
                  >
                    <template #default="scope">
                      <span
                        class="status-mark"
                        :class="gradeClass(scope.row.grade)"
                      >
                        {{ formatGrade(scope.row.grade) }}
                      </span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="供应商"
            min-width="220"
          >
            <template #default="{ row }">
              <span class="result-supplier">{{ row.supplierName }}</span>
              <span class="mono result-supplier-code">{{ row.supplierCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="总分"
            width="120"
            align="right"
          >
            <template #default="{ row }">
              <span class="result-score">{{ formatScore(row.totalScore) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="总等级"
            width="90"
          >
            <template #default="{ row }">
              <span
                class="status-mark"
                :class="gradeClass(row.totalGrade)"
              >
                {{ formatGrade(row.totalGrade) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="120"
            align="right"
          >
            <template #default="{ row }">
              <el-button
                link
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
</template>

<style scoped>
.results-meta {
  margin-bottom: 24px;
}
.results__sort {
  width: 150px;
}
.results__order {
  width: 100px;
}
.results__dimensions {
  padding: 10px 18px 10px 54px;
  background: var(--paper-deep);
}
.result-supplier {
  color: var(--ink);
  font-weight: 500;
}
.result-supplier-code {
  display: block;
  margin-top: 1px;
  font-size: 9px;
  line-height: 13px;
}
.result-score {
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 500;
}
.result-dimension-score {
  color: var(--ink-secondary);
  font-family: var(--font-serif);
  font-size: 15px;
}
</style>
