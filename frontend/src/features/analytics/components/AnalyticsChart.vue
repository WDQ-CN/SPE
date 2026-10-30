<script setup lang="ts">
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

/**
 * 分析看板的通用图表容器（M5 W6）：按需注册 + 生命周期收敛。
 *
 * <p>生命周期照 `ScoreRadar` 的 P2-33 教训：卸载时 dispose；option 变空由父组件用空态替换本组件，
 * 不在组件里挂一个"有实例但没数据"的中间态。
 */
const props = defineProps<{ option: EChartsCoreOption; height?: string }>()

const host = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof echarts.init> | null = null

function render(): void {
  if (!host.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(host.value)
  }
  chart.setOption(props.option, true)
}

function resize(): void {
  chart?.resize()
}

onMounted(() => {
  void nextTick(render)
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

watch(
  () => props.option,
  () => void nextTick(render),
  { deep: true },
)
</script>

<template>
  <div
    ref="host"
    class="analytics-chart"
    :style="{ height: height ?? '340px' }"
  />
</template>

<style scoped>
.analytics-chart {
  width: 100%;
}
</style>
