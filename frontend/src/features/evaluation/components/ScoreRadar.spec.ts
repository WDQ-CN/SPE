import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'

import type { RadarDimension } from '../radar'

/**
 * 雷达图的**生命周期**（03 §41.3 的 P2-33）：数据从有到空时，模板的 `v-if` 会卸载 host，
 * 若不清掉 chart 实例，数据恢复后 `setOption` 会画在脱管的 DOM 上——图永远不再出现。
 * 纯函数部分（radarPoints / buildRadarOption）在 radar.spec.ts，这里只测实例的 init/dispose。
 */
const mocks = vi.hoisted(() => {
  const dispose = vi.fn()
  const setOption = vi.fn()
  const resize = vi.fn()
  const init = vi.fn(() => ({ dispose, setOption, resize }))
  return { init, dispose, setOption, resize }
})

vi.mock('echarts/core', () => ({ init: mocks.init, use: vi.fn() }))
vi.mock('echarts/charts', () => ({ RadarChart: {} }))
vi.mock('echarts/components', () => ({ RadarComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import ScoreRadar from './ScoreRadar.vue'

const dimension = (score: number | null): RadarDimension => ({
  dimensionName: '质量',
  score,
  complete: score !== null,
})

describe('ScoreRadar（生命周期）', () => {
  it('数据变空时销毁实例，恢复后重新初始化（P2-33）', async () => {
    mocks.init.mockClear()
    mocks.dispose.mockClear()
    const wrapper = mount(ScoreRadar, {
      props: { dimensions: [dimension(80)] },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await nextTick()
    expect(mocks.init).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ dimensions: [dimension(null)] })
    await flushPromises()
    await nextTick()
    expect(mocks.dispose).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ dimensions: [dimension(70)] })
    await flushPromises()
    await nextTick()
    expect(mocks.init).toHaveBeenCalledTimes(2)
  })
})
