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
  {
    /**
     * 03 §38 的 P2-19：把三条安全红线收成**硬门禁**（配合 `package.json` 的 `--max-warnings 0`）。
     *
     * <p>为什么要显式写：`flat/recommended` 里 `vue/no-v-html` 只是 **warn**，而 `no-eval` 管不到
     * `innerHTML` / `document.write` / `new Function` 这些等价写法。P4 要引入 ECharts——它的 tooltip
     * 默认把内容当 HTML 渲染，等于把 `supplier.name` 注进 DOM，而 CSP 的 `script-src 'self'`
     * 挡不住 DOM 注入（`03 §37` 的评审结论 D4 已记这一点）。**在引入依赖之前先把口子封住**。
     */
    files: ['**/*.{ts,vue}'],
    rules: {
      'vue/no-v-html': 'error',
      'vue/no-v-text-v-html-on-component': 'error',
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: 'MemberExpression[property.name="innerHTML"]',
          message: '禁止 innerHTML：DOM 注入绕过 CSP 的 script-src \'self\'（03 §38 P2-19）',
        },
        {
          selector: 'MemberExpression[property.name="outerHTML"]',
          message: '禁止 outerHTML：同上',
        },
        {
          selector: 'CallExpression[callee.property.name="insertAdjacentHTML"]',
          message: '禁止 insertAdjacentHTML：同上',
        },
        {
          selector: 'MemberExpression[object.name="document"][property.name="write"]',
          message: '禁止 document.write：同上',
        },
      ],
    },
  },
)
