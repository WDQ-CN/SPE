import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 字体自托管：JetBrains Mono 走 fontsource（随构建打包，不依赖外网），Bubbledot 在 base.css 里
// @font-face（文件在 public/fonts/）。两者都是为了满足 CSP 的 `font-src 'self'`。
// ⚠️ 按**子集 + 字重**精确引入：整包 `@fontsource/jetbrains-mono/400.css` 会把 cyrillic /
// greek / vietnamese / latin-ext 四套子集全带进产物（实测 45 个字体文件、约 500 KB），
// 而本项目界面是中文、只有数字/英文/编码走这套字 ⇒ 那几套子集永远不会被下载，
// 纯属镜像体积浪费（03 §35 第 3 条；运行态实测只请求 latin 子集）。
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import '@fontsource/jetbrains-mono/latin-700.css'

import App from './App.vue'
import { router } from './app/router'
import './app/styles/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
