import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 字体自托管：JetBrains Mono 走 fontsource；Playfair Display、Albert Sans 与 Noto CJK
// 在 base.css 里 @font-face（文件在 public/fonts/）。这样满足 CSP 的 `font-src 'self'`，
// 也不会把拉丁字体用不到的 cyrillic / greek / vietnamese 子集打进产物。
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import '@fontsource/jetbrains-mono/latin-700.css'

import App from './App.vue'
import { router } from './app/router'
import './app/styles/noto-serif.css'
import './app/styles/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
