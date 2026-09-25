import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/types/auto-imports.d.ts', 'src/types/components.d.ts'] },
  js.configs.recommended,
  tseslint.configs.recommended,
  pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    // App.vue 是应用根组件，按惯例允许单词名（05 §11 只对业务组件要求多词 PascalCase）
    files: ['src/App.vue'],
    rules: { 'vue/multi-word-component-names': 'off' },
  },
  {
    // 未定义标识符交给 vue-tsc 检查；ElMessage 等由 unplugin 按需自动导入，ESLint 看不到声明
    files: ['**/*.{ts,vue}'],
    rules: { 'no-undef': 'off' },
  },
)
