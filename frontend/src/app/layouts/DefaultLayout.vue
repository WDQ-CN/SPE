<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { visibleMenus } from '@/app/menu'
import { useNotificationStore } from '@/app/notification-store'
import { useViewKey } from '@/app/view-state'
import { useAuthStore } from '@/features/auth/store'
import { listNotifications } from '@/features/evaluation/api'
import { APP_NAME } from '@/shared/constants'
import { ROLE_LABEL } from '@/shared/labels'
import type { NotificationSummary } from '@/shared/types/dto'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const viewKey = useViewKey()
const notifications = useNotificationStore()

// 角色驱动的菜单：真正的过滤规则在 app/menu.ts（与路由守卫共用），这里只做渲染
const menus = computed(() => visibleMenus(auth.roles))
const roleText = computed(() => auth.roles.map((role) => ROLE_LABEL[role]).join(' / '))

/** 下拉里的最近几条（角标只给数字，点开要能"真的看到写了什么"——13 §9.2 的"写了没人消费"问题） */
const recent = ref<NotificationSummary[]>([])
const recentLoading = ref(false)

async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const page = await listNotifications(1, 5)
    recent.value = page.list
  } finally {
    recentLoading.value = false
  }
}

/** 点开下拉时同时刷新列表与角标（别人刚发布任务时这里能立刻看到） */
async function onOpen(): Promise<void> {
  await Promise.all([loadRecent(), notifications.refresh()])
}

async function onReadAll(): Promise<void> {
  await notifications.readAll()
  await loadRecent()
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
            v-if="roleText"
            size="small"
            type="info"
          >
            {{ roleText }}
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
      <el-main>
        <router-view :key="viewKey" />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout__aside {
  border-right: 1px solid var(--el-border-color-light);
}

.layout__brand {
  padding: 16px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-light);
}

.layout__header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  border-bottom: 1px solid var(--el-border-color-light);
}

.layout__user {
  display: flex;
  gap: 8px;
  align-items: center;
}

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
