<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { visibleMenus } from '@/app/menu'
import { useNotificationStore } from '@/app/notification-store'
import { useViewKey } from '@/app/view-state'
import { useAuthStore } from '@/features/auth/store'
import { listNotifications } from '@/features/evaluation/api'
import { APP_NAME } from '@/shared/constants'
import type { NotificationSummary } from '@/shared/types/dto'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const viewKey = useViewKey()
const notifications = useNotificationStore()

// 角色驱动的菜单：真正的过滤规则在 app/menu.ts（与路由守卫共用），这里只做渲染
const menus = computed(() => visibleMenus(auth.roles))

/** 下拉里的最近几条（角标只给数字，点开要能"真的看到写了什么"——13 §9.2 的"写了没人消费"问题） */
const recent = ref<NotificationSummary[]>([])
const recentLoading = ref(false)

async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const page = await listNotifications(1, 5)
    recent.value = page.list
  } catch {
    // 下拉失败不该把布局打断（03 §41.3 的 P2-31）：清空列表，错误由 onOpen/onReadAll 提示一次
    recent.value = []
  } finally {
    recentLoading.value = false
  }
}

/** 点开下拉时同时刷新列表与角标（别人刚发布任务时这里能立刻看到） */
async function onOpen(): Promise<void> {
  try {
    await Promise.all([loadRecent(), notifications.refresh()])
  } catch {
    ElMessage.error('站内通知加载失败')
  }
}

async function onReadAll(): Promise<void> {
  try {
    await notifications.readAll()
    await loadRecent()
  } catch {
    ElMessage.error('标记已读失败，请稍后重试')
  }
}

onMounted(() => void notifications.refresh())

function logout(): void {
  auth.clearSession()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside
      width="200px"
      class="layout__aside"
    >
      <div class="layout__brand">
        {{ APP_NAME }}
      </div>
      <el-menu
        :default-active="route.path"
        router
      >
        <el-menu-item
          v-for="menu in menus"
          :key="menu.path"
          :index="menu.path"
        >
          {{ menu.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout__header">
        <el-popover
          width="340"
          trigger="click"
          @show="onOpen"
        >
          <template #reference>
            <el-badge
              :value="notifications.unread"
              :hidden="notifications.unread === 0"
              :max="99"
            >
              <el-button
                link
                type="primary"
              >
                通知
              </el-button>
            </el-badge>
          </template>
          <div v-loading="recentLoading">
            <el-empty
              v-if="recent.length === 0"
              description="暂无通知"
              :image-size="60"
            />
            <div
              v-for="item in recent"
              :key="item.id"
              class="notice"
            >
              <div class="notice__title">
                {{ item.title }}
              </div>
              <div class="notice__content">
                {{ item.content }}
              </div>
            </div>
            <div class="notice__footer">
              <el-button
                link
                type="primary"
                :disabled="notifications.unread === 0"
                @click="onReadAll"
              >
                全部已读
              </el-button>
            </div>
          </div>
        </el-popover>
        <span class="layout__user">
          {{ auth.name || '未登录' }}
          <el-tag
            v-if="auth.roleText"
            size="small"
            type="info"
          >
            {{ auth.roleText }}
          </el-tag>
        </span>
        <el-button
          link
          type="primary"
          @click="logout"
        >
          退出
        </el-button>
      </el-header>
      <el-main class="layout__main">
        <router-view :key="viewKey" />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* ============================================================================
   Sentra 视觉语言落到后台（2026-10-04）
   设计提示词给的是**落地页**：页面黑 #000 + 亮区块 #E8E5E9/#f0eded 交替。
   后台不能整页黑（表格与表单的可读性会垮），所以映射成 **暗壳 + 亮内容区**：
   侧栏与顶栏吃 #000，内容区吃 #E8E5E9，卡片用 #EBE8EB / #fff——
   色板、圆角、缓动、字体全部来自提示词，只是分区方式适配了管理系统。
   ========================================================================== */

.layout {
  height: 100%;
}

/* ---- 暗壳：侧栏 ---- */
.layout__aside {
  display: flex;
  flex-direction: column;
  background: #000;
  border-right: 1px solid rgb(255 255 255 / 8%);
}

.layout__brand {
  padding: 18px 20px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 0.02em;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
}

/* 菜单在暗底上的取值（那组 `--el-menu-*` 覆盖）已提到 `app/styles/base.css`：
   它描述的是"EP 菜单在暗色导航里的取值"，属**全局主题**而不是本布局私有，
   放全局后任何暗底导航都能复用（03 §35 第 6 条）。 */

/* ---- 暗壳：顶栏 ---- */
.layout__header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  color: rgb(255 255 255 / 75%);
  background: #000;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
}

/* 顶栏里的 link 按钮（通知 / 退出）默认是 EP 的主题色，在纯黑上看不清 */
.layout__header :deep(.el-button.is-link) {
  color: rgb(255 255 255 / 70%);
}

.layout__header :deep(.el-button.is-link:hover) {
  color: #f64302;
}

.layout__user {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

/* ---- 亮内容区：Sentra 的亮区块色 ---- */
.layout__main {
  padding: 16px;
  overflow: auto;
  background: #e8e5e9;
}

/* 入场动画改由各页显式标注 `.page-block`（定义在 base.css）。
   原来这里是 `.layout__main :deep(> *)` 一网打尽，两个问题：
   ① 页面改成多根节点后它是否命中并不直观；② 与页面自己写的入场会**双重叠加**
   （同一元素上两个 `animation` 属性只取后者，等于其中一处是白写的）。
   显式标在页面上可读、也能让各页自己排延迟阶梯。 */

/* ---- 通知下拉（在亮底 popover 里，保持可读）---- */
.notice {
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.notice__title {
  font-weight: 600;
}

.notice__content {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.notice__footer {
  padding-top: 8px;
  text-align: right;
}
</style>
