<script setup lang="ts">
import { RadarChart } from 'echarts/charts'
import { RadarComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { buildRadarOption, radarPoints, type RadarDimension } from '../radar'

echarts.use([RadarChart, RadarComponent, TooltipComponent, CanvasRenderer])

/**
 * 维度雷达图（01 B3 / 15 E12）：ECharts **按需引入**（D4），只注册用到的坐标/系列/渲染器。
 *
 * <p>tooltip 的 `richText` 在 `buildRadarOption` 里钉死；这里只负责生命周期与 resize。
 */
const props = defineProps<{ dimensions: RadarDimension[] }>()

const host = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof echarts.init> | null = null
const hasData = computed(() => radarPoints(props.dimensions).length > 0)

function render(): void {
  if (!host.value || !hasData.value) {
    // 数据变空时销毁实例（03 §41.3 的 P2-33）：模板的 v-if 会卸载 host，
    // 留着旧 chart 会让下一次 setOption 画在脱管 DOM 上——图永远不再出现。
    if (!hasData.value) {
      chart?.dispose()
      chart = null
    }
    return
  }
  if (!chart) {
    chart = echarts.init(host.value)
  }
  chart.setOption(buildRadarOption(props.dimensions) as unknown as EChartsCoreOption, true)
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
  () => props.dimensions,
  () => void nextTick(render),
  { deep: true },
)
</script>

<template>
  <div
    v-if="hasData"
    ref="host"
    class="score-radar"
  />
  <el-empty
    v-else
    description="暂无可展示的维度分"
  />
</template>

<style scoped>
.score-radar {
  width: 100%;
  height: 360px;
}
</style>
