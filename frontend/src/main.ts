import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 字体自托管：JetBrains Mono 走 fontsource（随构建打包，不依赖外网），Bubbledot 在 base.css 里
// @font-face（文件在 public/fonts/）。两者都是为了满足 CSP 的 `font-src 'self'`。
import '@fontsource/jetbrains-mono/300.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/600.css'
import '@fontsource/jetbrains-mono/700.css'

import App from './App.vue'
import { router } from './app/router'
import './app/styles/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
