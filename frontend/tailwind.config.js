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
        paper: '#F6F2E9',
        surface: '#FFFCF6',
        ink: '#171717',
        'ink-secondary': '#3B3833',
        muted: '#5F5B54',
        'muted-2': '#6B665C',
        rule: '#D8D1C3',
        accent: '#6E3B3A',
        success: '#2F5D3A',
        warning: '#7A531C',
        danger: '#9E2F28',
        info: '#3F4A56',
      },
      borderRadius: {
        control: '2px',
        panel: '0px',
      },
    },
  },
  plugins: [],
}
