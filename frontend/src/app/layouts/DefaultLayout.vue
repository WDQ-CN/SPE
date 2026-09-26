<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { visibleMenus } from '@/app/menu'
import { useViewKey } from '@/app/view-state'
import { useAuthStore } from '@/features/auth/store'
import { APP_NAME } from '@/shared/constants'
import { ROLE_LABEL } from '@/shared/labels'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const viewKey = useViewKey()

// 角色驱动的菜单：真正的过滤规则在 app/menu.ts（与路由守卫共用），这里只做渲染
const menus = computed(() => visibleMenus(auth.roles))
const roleText = computed(() => auth.roles.map((role) => ROLE_LABEL[role]).join(' / '))

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
</style>
