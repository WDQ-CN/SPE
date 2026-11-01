/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  corePlugins: { preflight: false },
  theme: {
    extend: {
      fontFamily: {
        sans: ['Albert Sans', 'Noto Sans SC', 'Microsoft YaHei', 'sans-serif'],
        serif: ['Playfair Display', 'Noto Serif SC', 'Songti SC', 'SimSun', 'serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace'],
      },
      colors: {
        paper: '#F7F4ED',
        surface: '#FFFDF9',
        ink: '#1C1C1C',
        'ink-secondary': '#3F3B36',
        muted: '#6B6A65',
        rule: '#DED8CC',
        accent: '#816D70',
        success: '#3F6148',
        warning: '#8A6A2F',
        danger: '#A33A32',
        info: '#4B5563',
      },
      borderRadius: {
        control: '2px',
        panel: '0px',
      },
    },
  },
  plugins: [],
}
