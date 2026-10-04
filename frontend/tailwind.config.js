/**
 * Tailwind 配置（2026-10-04）：把 Sentra 设计提示词里的色板、字体、缓动与三组入场动画
 * 落成 token。之后页面里只写 token 类名（`bg-sentra-products`、`animate-fade-up`），
 * 不再散落硬编码色值——与 `06 §3.2` 的"不硬编码色值"一致，只是把可用的一组从
 * Element Plus 变量（组件级）扩展为「EP 变量 + 本文件的 token（布局与品牌色）」。
 *
 * 用 ESM：package.json 是 `"type": "module"`，与 vite.config.ts / eslint.config.js 保持一种风格。
 */
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  // ⚠️ 关掉 preflight：它会重置 button/input/label 的基础样式，而本项目的表单与表格
  // 全部来自 Element Plus——两者叠加会把组件外观悄悄改掉（纯工具类够用，不需要它）。
  corePlugins: { preflight: false },
  theme: {
    extend: {
      fontFamily: {
        bubbledot: ['BubbledotICG-FinePos', 'monospace'],
        jetbrains: ['JetBrains Mono', 'monospace'],
      },
      colors: {
        // Key palette reference（提示词原文）
        sentra: {
          ink: '#000000', // 页面黑 / 暗壳
          accent: '#F64302', // 强调橙
          about: '#f0eded', // 亮区之一
          products: '#E8E5E9', // 亮区之二（内容区底）
          card: '#EBE8EB', // 亮区里的次级卡片
        },
      },
      keyframes: {
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(30px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(0.95)' },
          to: { opacity: '1', transform: 'scale(1)' },
        },
      },
      animation: {
        // 统一缓动 cubic-bezier(0.16, 1, 0.3, 1)（ease-out-expo 手感）
        'fade-up': 'fade-up 0.8s cubic-bezier(0.16, 1, 0.3, 1) forwards',
        'fade-in': 'fade-in 0.8s cubic-bezier(0.16, 1, 0.3, 1) forwards',
        'scale-in': 'scale-in 0.8s cubic-bezier(0.16, 1, 0.3, 1) forwards',
      },
      transitionTimingFunction: {
        'out-expo': 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
    },
  },
  plugins: [],
}
