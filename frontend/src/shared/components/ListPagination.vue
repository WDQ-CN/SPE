<script setup lang="ts">
withDefaults(
  defineProps<{ page: number; size: number; total: number; pageSizes?: number[] }>(),
  { pageSizes: () => [10, 20, 50] },
)
defineEmits<{
  'update:page': [number]
  'update:size': [number]
  change: [number]
  'size-change': [number]
}>()
</script>

<template>
  <el-pagination
    :current-page="page"
    :page-size="size"
    :total="total"
    :page-sizes="pageSizes"
    layout="total, sizes, prev, pager, next"
    @update:current-page="$emit('update:page', $event)"
    @update:page-size="$emit('update:size', $event)"
    @current-change="$emit('change', $event)"
    @size-change="$emit('size-change', $event)"
  />
</template>
