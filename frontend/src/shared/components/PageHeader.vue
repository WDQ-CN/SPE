<script setup lang="ts">
/**
 * 页头（eyebrow + 标题）—— **一处定义，十页复用**（2026-10-04）。
 *
 * 为什么抽出来：原先十个页面各写一遍这两个标签 + 同一套 `page-*` 类名，
 * 改一次形态要动十处，而且**必然有人漏改**（这正是"改一处、全项目生效"的反面）。
 * 样式留在 `app/styles/base.css` 的页面工具类里（`.page-head` / `.page-eyebrow` / `.page-title`），
 * 本组件只负责结构，因此改样式仍是改一处。
 *
 * 组件由 unplugin-vue-components 自动导入（见 vite.config.ts 的 `dirs`），页面里不需要写 import。
 */
withDefaults(
  defineProps<{
    /** 英文小标签：宽字距、大写，形态照设计稿的 eyebrow */
    eyebrow: string
    /** 页面主标题 */
    title: string
    /** 入场延迟（如 `0.1s`），用于同页多区块排阶梯；默认 `0s` */
    delay?: string
  }>(),
  { delay: '0s' },
)
</script>

<template>
  <header
    class="page-head page-block"
    :style="{ animationDelay: delay }"
  >
    <p class="page-eyebrow">
      {{ eyebrow }}
    </p>
    <h1 class="page-title">
      {{ title }}
    </h1>
  </header>
</template>
