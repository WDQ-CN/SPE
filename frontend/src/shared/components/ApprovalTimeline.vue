<script setup lang="ts">
import { computed } from 'vue'

import { formatUtcLocal, groupTimelineByRound } from '@/shared/approval-timeline'
import { APPROVAL_ACTION_LABEL } from '@/shared/labels'
import type { ApprovalTimelineEntry } from '@/shared/types/dto'

/**
 * 审批时间线（02 §4 的 D3 裁定）：按轮分组、当前轮高亮。审批页与报告页共用。
 *
 * <p>当前轮取后端返回的 `task_supplier.round`，**不在这里用 `MAX(round)` 现推**——
 * 那会把"刚驳回、还没重提"的轮次判错（CLAUDE 默认约定第 6 条的同族问题）。
 */
const props = defineProps<{
  entries: ApprovalTimelineEntry[]
  currentRound: number
  currentNode?: number
}>()

const rounds = computed(() =>
  groupTimelineByRound(props.entries, props.currentRound, props.currentNode ?? 0),
)

function actionLabel(action: string): string {
  return APPROVAL_ACTION_LABEL[action] ?? action
}

function itemType(action: string): 'success' | 'danger' {
  return action === 'APPROVE' ? 'success' : 'danger'
}

const displayTime = formatUtcLocal
</script>

<template>
  <div class="approval-timeline">
    <el-empty
      v-if="rounds.length === 0"
      description="暂无审批记录"
    />
    <section
      v-for="group in rounds"
      :key="group.round"
      class="approval-timeline__round"
      :class="{ 'approval-timeline__round--current': group.current }"
    >
      <header class="approval-timeline__head">
        <span>第 {{ group.round }} 轮</span>
        <el-tag
          v-if="group.current"
          size="small"
          type="warning"
        >
          当前轮
        </el-tag>
      </header>
      <el-timeline v-if="group.entries.length > 0">
        <el-timeline-item
          v-for="entry in group.entries"
          :key="`${entry.round}-${entry.seq}`"
          :timestamp="displayTime(entry.createdAt)"
          :type="itemType(entry.action)"
        >
          <div class="approval-timeline__line">
            第 {{ entry.seq }} 级 ·
            {{ entry.approverName ?? `用户 #${entry.approverId}` }} ·
            {{ actionLabel(entry.action) }}
          </div>
          <div
            v-if="entry.comment"
            class="approval-timeline__comment"
          >
            {{ entry.comment }}
          </div>
        </el-timeline-item>
      </el-timeline>
      <p
        v-else
        class="approval-timeline__pending"
      >
        本轮尚无审批记录
      </p>
    </section>
  </div>
</template>

<style scoped>
.approval-timeline__round + .approval-timeline__round {
  margin-top: 18px;
}
.approval-timeline__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-weight: 600;
}
.approval-timeline__round--current .approval-timeline__head {
  color: var(--el-color-primary);
}
.approval-timeline__line {
  font-size: 14px;
}
.approval-timeline__comment {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  white-space: pre-wrap;
}
.approval-timeline__pending {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
