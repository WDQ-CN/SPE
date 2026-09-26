<script setup lang="ts">
import { computed } from 'vue'

import { useAuthStore } from '@/features/auth/store'
import { DEPARTMENT_LABEL, ROLE_LABEL } from '@/shared/labels'

const auth = useAuthStore()

const roleText = computed(() => auth.roles.map((role) => ROLE_LABEL[role]).join(' / ') || '—')
const departmentText = computed(() =>
  auth.department ? DEPARTMENT_LABEL[auth.department] : '—',
)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      当前登录
    </template>
    <el-descriptions
      :column="1"
      border
    >
      <el-descriptions-item label="姓名">
        {{ auth.name || '—' }}
      </el-descriptions-item>
      <el-descriptions-item label="角色">
        {{ roleText }}
      </el-descriptions-item>
      <el-descriptions-item label="部门">
        {{ departmentText }}
      </el-descriptions-item>
    </el-descriptions>
    <p class="home__hint">
      左侧菜单按角色显示可用功能；没有权限的入口不会出现（越权访问仍由后端拒绝并留日志）。
    </p>
  </el-card>
</template>

<style scoped>
.home__hint {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
