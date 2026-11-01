<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { visibleMenus } from '@/app/menu'
import { useNotificationStore } from '@/app/notification-store'
import { useViewKey } from '@/app/view-state'
import { useAuthStore } from '@/features/auth/store'
import { listNotifications } from '@/features/evaluation/api'
import type { NotificationSummary } from '@/shared/types/dto'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const viewKey = useViewKey()
const notifications = useNotificationStore()

const menus = computed(() => visibleMenus(auth.roles))
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/tasks')) return '/tasks'
  if (path.startsWith('/templates')) return '/templates'
  if (path.startsWith('/approvals')) return '/approvals'
  if (path.startsWith('/results') || path.startsWith('/reports')) return '/results'
  if (path.startsWith('/sheets')) return '/my'
  return path || '/'
})
const avatarText = computed(() => auth.name?.trim().slice(0, 1) || '管')

const recent = ref<NotificationSummary[]>([])
const recentLoading = ref(false)

async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const page = await listNotifications(1, 5)
    recent.value = page.list
  } catch {
    recent.value = []
  } finally {
    recentLoading.value = false
  }
}

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

function navigateFromMobile(command: string): void {
  if (command === '__logout__') {
    logout()
    return
  }
  void router.push(command)
}
</script>

<template>
  <div class="layout">
    <aside class="layout__aside">
      <RouterLink
        class="layout__brand"
        to="/"
      >
        <span class="layout__brand-mark">S</span>
        <span class="layout__brand-copy">
          <strong>SPE</strong>
          <small>Supplier Performance</small>
        </span>
      </RouterLink>

      <p class="layout__nav-label">
        Navigation / 导航
      </p>
      <el-menu
        :key="activeMenu"
        :default-active="activeMenu"
        class="layout__menu"
        router
      >
        <el-menu-item
          v-for="menu in menus"
          :key="menu.path"
          :index="menu.path"
        >
          <span class="layout__menu-mark" />
          <span>{{ menu.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="layout__spacer" />

      <div class="layout__user">
        <span class="layout__avatar">{{ avatarText }}</span>
        <div class="layout__user-meta">
          <div class="layout__user-name">
            {{ auth.name || '未登录' }}
          </div>
          <el-tag
            v-if="auth.roleText"
            size="small"
            type="info"
          >
            {{ auth.roleText }}
          </el-tag>
        </div>
        <button
          class="layout__logout"
          type="button"
          @click="logout"
        >
          退出
        </button>
      </div>
    </aside>

    <div class="layout__main">
      <header class="layout__topbar">
        <div class="layout__crumbs">
          <strong>SPE</strong><span>/</span><span>供应链绩效</span>
        </div>
        <div class="layout__actions">
          <el-dropdown
            class="layout__mobile-nav"
            trigger="click"
            @command="navigateFromMobile"
          >
            <button
              class="layout__icon-btn"
              type="button"
              aria-label="菜单"
            >
              <svg
                class="layout__icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
              >
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="menu in menus"
                  :key="menu.path"
                  :command="menu.path"
                >
                  {{ menu.title }}
                </el-dropdown-item>
                <el-dropdown-item
                  divided
                  command="__logout__"
                >
                  退出
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-popover
            width="360"
            trigger="click"
            @show="onOpen"
          >
            <template #reference>
              <el-badge
                :value="notifications.unread"
                :hidden="notifications.unread === 0"
                :max="99"
              >
                <button
                  class="layout__icon-btn"
                  type="button"
                  aria-label="通知"
                >
                  <svg
                    class="layout__icon"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <path d="M18 8a6 6 0 1 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
                    <path d="M10 21h4" />
                  </svg>
                </button>
              </el-badge>
            </template>
            <div v-loading="recentLoading">
              <el-empty
                v-if="recent.length === 0"
                description="暂无通知"
                :image-size="48"
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
                  :disabled="notifications.unread === 0"
                  @click="onReadAll"
                >
                  全部已读
                </el-button>
              </div>
            </div>
          </el-popover>
        </div>
      </header>

      <main class="layout__content">
        <div class="layout__page">
          <router-view :key="viewKey" />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: var(--sidebar) minmax(0, 1fr);
  height: 100vh;
  min-height: 0;
  background: var(--paper);
}

.layout__aside {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 18px 14px 14px;
  background: var(--surface);
  border-right: 1px solid var(--rule);
  overflow-y: auto;
}

.layout__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  color: inherit;
  text-decoration: none;
}

.layout__brand-mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid var(--ink);
  border-radius: 999px;
  font-family: var(--font-serif);
  font-size: 13px;
  line-height: 1;
}

.layout__brand-copy strong {
  display: block;
  font-family: var(--font-serif);
  font-size: 22px;
  line-height: 24px;
  font-weight: 600;
  letter-spacing: -0.6px;
}

.layout__brand-copy small {
  display: block;
  margin-top: 1px;
  color: var(--muted-2);
  font-size: 7px;
  line-height: 10px;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.layout__nav-label {
  margin: 22px 8px 7px;
  color: var(--muted-2);
  font-size: 8px;
  line-height: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.layout__menu {
  border-right: 0;
  background: transparent;
  padding: 0;
}

.layout__menu :deep(.el-menu-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  line-height: 34px;
  margin: 1px 0;
  padding: 0 8px !important;
  border-left: 2px solid transparent;
  border-radius: 2px;
  color: var(--muted);
  font-size: 12px;
}

.layout__menu :deep(.el-menu-item:hover) {
  color: var(--ink);
  background: rgb(129 109 112 / 5%);
}

.layout__menu :deep(.el-menu-item.is-active) {
  color: var(--ink);
  background: var(--accent-soft);
  border-left-color: var(--ink);
  font-weight: 500;
}

.layout__menu-mark {
  width: 4px;
  height: 4px;
  flex: none;
  background: currentColor;
  opacity: 0.45;
}

.layout__menu :deep(.el-menu-item.is-active) .layout__menu-mark {
  opacity: 1;
}

.layout__spacer {
  flex: 1;
}

.layout__user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 6px 0;
  border-top: 1px solid var(--rule);
}

.layout__avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid var(--ink);
  border-radius: 999px;
  font-family: var(--font-serif);
  font-size: 10px;
}

.layout__user-meta {
  min-width: 0;
  flex: 1;
}

.layout__user-name {
  overflow: hidden;
  color: var(--ink-secondary);
  font-size: 10.5px;
  line-height: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout__user :deep(.el-tag) {
  height: 17px;
  margin-top: 2px;
  padding: 0 5px;
  border-color: var(--rule);
  color: var(--muted);
  font-size: 8px;
  line-height: 15px;
}

.layout__logout {
  padding: 0;
  border: 0;
  border-bottom: 1px solid var(--rule);
  background: transparent;
  color: var(--muted-2);
  font-size: 9px;
  line-height: 15px;
  cursor: pointer;
}

.layout__logout:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.layout__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.layout__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: var(--topbar);
  flex: none;
  padding: 0 30px;
  background: var(--surface);
  border-bottom: 1px solid var(--rule);
}

.layout__crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 10px;
  line-height: 15px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.layout__crumbs strong {
  color: var(--ink);
  font-weight: 500;
}

.layout__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.layout__mobile-nav {
  display: none;
}

.layout__icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 2px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.layout__icon-btn:hover {
  border-color: var(--rule);
  background: var(--paper);
  color: var(--ink);
}

.layout__icon {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.layout__content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px 32px 32px;
  background: var(--paper);
}

.layout__page {
  max-width: 1440px;
  margin: 0 auto;
}

.notice {
  padding: 9px 0;
  border-bottom: 1px solid var(--rule);
}

.notice__title {
  color: var(--ink);
  font-size: 11.5px;
  font-weight: 500;
}

.notice__content {
  margin-top: 2px;
  color: var(--muted);
  font-size: 10.5px;
  line-height: 17px;
}

.notice__footer {
  padding-top: 10px;
  text-align: right;
}

@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .layout__aside {
    display: none;
  }

  .layout__mobile-nav {
    display: inline-flex;
  }

  .layout__topbar {
    padding: 0 16px;
  }

  .layout__content {
    padding: 18px 16px;
  }
}
</style>
