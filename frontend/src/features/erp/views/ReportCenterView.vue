<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'

import {
  downloadReportRun,
  listCompanies,
  listReportDefinitions,
  listReportRuns,
  runReport,
  type CompanyOption,
  type ReportDefinition,
  type ReportRun,
} from '@/features/erp/api'
import { formatDateTime } from '@/features/erp/format'
import { saveBlob } from '@/shared/download'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'

const definitions = ref<ReportDefinition[]>([])
const companies = ref<CompanyOption[]>([])
const definitionsLoading = ref(false)
const definitionsError = ref('')

const {
  rows: runs,
  total: runsTotal,
  page: runsPage,
  size: runsSize,
  loading: runsLoading,
  error: runsError,
  load: loadRuns,
} = usePagedList<ReportRun>((targetPage, pageSize) =>
  listReportRuns({ page: targetPage, size: pageSize }),
)

const runVisible = ref(false)
const currentDefinition = ref<ReportDefinition | null>(null)
const runForm = reactive<{ companyId: number | undefined; asOf: string }>({
  companyId: undefined,
  asOf: '',
})

const { submitting: running, errorMessage: runError, run: runAction, clearError } = useSubmit()

async function loadDefinitions(): Promise<void> {
  definitionsLoading.value = true
  definitionsError.value = ''
  try {
    definitions.value = await listReportDefinitions()
  } catch (caught) {
    definitions.value = []
    definitionsError.value = caught instanceof Error ? caught.message : '报表目录加载失败'
  } finally {
    definitionsLoading.value = false
  }
}

async function loadCompanies(): Promise<void> {
  try {
    companies.value = await listCompanies()
    runForm.companyId = companies.value[0]?.id
  } catch {
    companies.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadDefinitions(), loadCompanies()])
  await loadRuns(1)
})

function openRun(definition: ReportDefinition): void {
  currentDefinition.value = definition
  runForm.companyId = runForm.companyId ?? companies.value[0]?.id
  runForm.asOf = ''
  clearError()
  runVisible.value = true
}

async function submitRun(): Promise<void> {
  if (!currentDefinition.value) {
    return
  }
  if (!runForm.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  const definition = currentDefinition.value
  const ok = await runAction(async () => {
    await runReport(definition.code, runForm.companyId as number, runForm.asOf || null)
  })
  if (ok) {
    runVisible.value = false
    ElMessage.success('报表已运行')
    await loadRuns(1)
  }
}

async function download(run: ReportRun): Promise<void> {
  try {
    const blob = await downloadReportRun(run.id)
    saveBlob(blob, `${run.code}-run${run.id}.csv`)
  } catch {
    // downloadFile 已提示错误
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / REPORTING"
    title="报表中心"
    :description="`${definitions.length} 张固定报表 · 运行结果保存快照，可重复导出 CSV`"
  >
    <template #actions>
      <el-button
        :loading="definitionsLoading"
        @click="loadDefinitions"
      >
        刷新目录
      </el-button>
    </template>
  </PageHeader>

  <el-alert
    v-if="definitionsError"
    class="page-alert"
    type="error"
    :title="definitionsError"
    :closable="false"
    show-icon
  >
    <el-button
      link
      @click="loadDefinitions"
    >
      重试
    </el-button>
  </el-alert>

  <SectionCard
    title="报表目录"
    delay="0s"
  >
    <el-table
      v-loading="definitionsLoading"
      :data="definitions"
      :border="false"
      class="table-list"
    >
      <el-table-column
        label="代码"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as ReportDefinition).code }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="报表"
        min-width="180"
      />
      <el-table-column
        label="说明"
        min-width="300"
      >
        <template #default="{ row }">
          {{ (row as ReportDefinition).description || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="(row as ReportDefinition).status === 'ACTIVE' ? 'is-success' : 'is-off'"
          >
            {{ (row as ReportDefinition).status === 'ACTIVE' ? '可运行' : '停用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="100"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            :disabled="(row as ReportDefinition).status !== 'ACTIVE'"
            @click="openRun(row as ReportDefinition)"
          >
            运行
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </SectionCard>

  <SectionCard
    title="运行记录"
    delay="0.1s"
  >
    <el-alert
      v-if="runsError"
      class="page-alert"
      type="error"
      :title="runsError"
      :closable="false"
      show-icon
    >
      <el-button
        link
        @click="loadRuns()"
      >
        重试
      </el-button>
    </el-alert>
    <el-table
      v-loading="runsLoading"
      :data="runs"
      :border="false"
      class="table-list"
      empty-text="尚未运行报表"
    >
      <el-table-column
        label="报表"
        min-width="190"
      >
        <template #default="{ row }">
          {{ (row as ReportRun).name }} <span class="mono">{{ (row as ReportRun).code }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="公司"
        width="100"
      >
        <template #default="{ row }">
          {{ (row as ReportRun).companyId }}
        </template>
      </el-table-column>
      <el-table-column
        label="截止日"
        width="120"
      >
        <template #default="{ row }">
          {{ (row as ReportRun).asOf || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="行数"
        align="right"
        width="90"
      >
        <template #default="{ row }">
          {{ (row as ReportRun).rowCount }}
        </template>
      </el-table-column>
      <el-table-column
        label="耗时"
        align="right"
        width="100"
      >
        <template #default="{ row }">
          {{ (row as ReportRun).durationMs ?? '—' }} ms
        </template>
      </el-table-column>
      <el-table-column
        label="运行时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as ReportRun).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="100"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="download(row as ReportRun)"
          >
            导出 CSV
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <ListPagination
      v-model:page="runsPage"
      v-model:size="runsSize"
      :total="runsTotal"
      class="list-pager"
      @change="(target: number) => void loadRuns(target)"
      @size-change="() => void loadRuns(1)"
    />
  </SectionCard>

  <el-dialog
    v-model="runVisible"
    :title="`运行报表 · ${currentDefinition?.name ?? ''}`"
    width="440px"
  >
    <el-alert
      v-if="runError"
      class="page-alert"
      type="error"
      :title="runError"
      :closable="false"
      show-icon
    />
    <el-form label-width="84px">
      <el-form-item label="公司">
        <el-select
          v-model="runForm.companyId"
          placeholder="选择公司"
        >
          <el-option
            v-for="company in companies"
            :key="company.id"
            :label="`${company.code} · ${company.name}`"
            :value="company.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="截止日">
        <el-date-picker
          v-model="runForm.asOf"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="默认今天"
          class="date-picker"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="runVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="running"
        @click="submitRun"
      >
        运行
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.date-picker {
  width: 100%;
}
</style>
