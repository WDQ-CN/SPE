<script setup lang="ts">
/**
 * 区块卡片（卡片 + 标题 + 右侧操作）—— **一处定义，多页复用**（2026-10-04）。
 *
 * 抽它的理由与 PageHeader 同源：原先"卡片标题栏 = 左标题 + 右按钮"这套结构
 * 在 6 个页面里各写一遍，且配了两套类名（`.page__header` 与 `.card-header`）各写一份样式。
 * 改一次间距要动六处，改类名要动十处。
 *
 * 现在：结构在本组件、样式在 `app/styles/base.css` 的 `.section-head`，
 * 各页只提供标题与操作按钮。卡片外观（去边框/16px 圆角/浅投影）由全局皮肤统一。
 */
withDefaults(
  defineProps<{
    /** 卡片标题（全局皮肤会把它渲染成小号大写的 eyebrow 形态） */
    title: string
    /** 入场延迟（如 `0.1s`），与 PageHeader 的 `delay` 同义 */
    delay?: string
    /** 是否给卡片本身加入场动画；同页只有一个区块时可关掉 */
    animate?: boolean
  }>(),
  { delay: '0s', animate: true },
)
</script>

<template>
  <el-card
    class="section-card"
    :class="{ 'page-block': animate }"
    :style="animate ? { animationDelay: delay } : undefined"
  >
    <template #header>
      <div class="section-head">
        <span>{{ title }}</span>
        <div class="section-head__actions">
          <slot name="actions" />
        </div>
      </div>
    </template>
    <slot />
  </el-card>
</template>
