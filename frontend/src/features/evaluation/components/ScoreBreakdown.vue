<script setup lang="ts">
import {
  INDICATOR_TYPE_LABEL,
  SCORE_MODE_LABEL,
  SCORE_SOURCE_LABEL,
} from '@/shared/labels'
import { formatScore, formatWeight } from '@/shared/format'
import type { ReportDimension } from '@/shared/types/dto'

/**
 * 维度与指标明细（01 R3）：审批页与报告页共用。
 *
 * <p>null 一律显示 `—`，**不显示 0**——"还没填"与"得 0 分"是两件事（13 §E6）。
 */
defineProps<{ dimensions: ReportDimension[] }>()

function rawText(value: number | null, unit: string | null): string {
  return value == null ? '—' : `${value}${unit ?? ''}`
}

function sourceText(value: string | null): string {
  return value == null ? '—' : (SCORE_SOURCE_LABEL[value] ?? value)
}

function modeText(type: string, scoreMode: string | null): string {
  const typeLabel = INDICATOR_TYPE_LABEL[type as keyof typeof INDICATOR_TYPE_LABEL] ?? type
  if (!scoreMode) {
    return typeLabel
  }
  const modeLabel = SCORE_MODE_LABEL[scoreMode as keyof typeof SCORE_MODE_LABEL] ?? scoreMode
  return `${typeLabel} · ${modeLabel}`
}

function scoreLine(dimension: ReportDimension): string {
  return `得分 ${formatScore(dimension.score)} · ${dimension.grade ?? '—'}`
}
</script>

<template>
  <el-empty
    v-if="dimensions.length === 0"
    description="暂无维度数据"
  />
  <el-collapse
    v-else
    class="breakdown"
  >
    <el-collapse-item
      v-for="dimension in dimensions"
      :key="dimension.dimensionId"
      :name="dimension.dimensionId"
    >
      <template #title>
        <div class="breakdown__title">
          <span class="breakdown__name">{{ dimension.dimensionName }}</span>
          <span class="breakdown__meta">权重 {{ formatWeight(dimension.weight) }}</span>
          <span class="breakdown__score">{{ scoreLine(dimension) }}</span>
          <el-tag
            v-if="!dimension.complete"
            size="small"
            type="warning"
          >
            未完成
          </el-tag>
        </div>
      </template>

      <el-table
        v-if="dimension.indicators.length > 0"
        :data="dimension.indicators"
        size="small"
      >
        <el-table-column
          prop="code"
          label="编码"
          width="150"
        />
        <el-table-column
          prop="name"
          label="指标"
          min-width="160"
        />
        <el-table-column
          label="类型 / 计分"
          width="150"
        >
          <template #default="{ row }">
            {{ modeText(row.type, row.scoreMode) }}
          </template>
        </el-table-column>
        <el-table-column
          label="权重"
          width="80"
        >
          <template #default="{ row }">
            {{ formatWeight(row.weight) }}
          </template>
        </el-table-column>
        <el-table-column
          label="原始值"
          width="110"
        >
          <template #default="{ row }">
            {{ rawText(row.rawValue, row.unit) }}
          </template>
        </el-table-column>
        <el-table-column
          label="换算分"
          width="90"
        >
          <template #default="{ row }">
            {{ formatScore(row.scoreValue) }}
          </template>
        </el-table-column>
        <el-table-column
          label="来源"
          width="100"
        >
          <template #default="{ row }">
            {{ sourceText(row.source) }}
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else
        description="该维度没有指标"
      />
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.breakdown__title {
  display: flex;
  align-items: baseline;
  gap: 14px;
  width: 100%;
  min-width: 0;
  padding-right: 8px;
}
.breakdown__name {
  min-width: 120px;
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 500;
  letter-spacing: -0.2px;
}
.breakdown__meta {
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.02em;
}
.breakdown__score {
  margin-left: auto;
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 500;
}
.breakdown :deep(.el-table td.el-table__cell) {
  height: 42px;
}
.breakdown :deep(.el-table th.el-table__cell) {
  height: 34px;
}
.breakdown :deep(.el-table .cell) {
  padding: 0 10px;
}
</style>
