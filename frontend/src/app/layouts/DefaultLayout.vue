<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useViewKey } from '@/app/view-state'
import { useAuthStore } from '@/features/auth/store'
import { APP_NAME } from '@/shared/constants'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const viewKey = useViewKey()

const displayName = computed(() => auth.name || '未登录')

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
      <!-- 侧边菜单为 P0 占位：仅首页可用，业务入口随 P1~P4 接入并按角色渲染（05 §11） -->
      <el-menu
        :default-active="route.path"
        router
      >
        <el-menu-item index="/">
          首页
        </el-menu-item>
        <el-menu-item
          index="/suppliers"
          disabled
        >
          供应商 · P1
        </el-menu-item>
        <el-menu-item
          index="/templates"
          disabled
        >
          评估模板 · P2
        </el-menu-item>
        <el-menu-item
          index="/tasks"
          disabled
        >
          评估任务 · P3
        </el-menu-item>
        <el-menu-item
          index="/my/todo"
          disabled
        >
          我的待办 · P3
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout__header">
        <span>{{ displayName }}</span>
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
</style>
