<script setup lang="ts">
import { computed } from 'vue'

import { visibleMenus } from '@/app/menu'
import { useAuthStore } from '@/features/auth/store'
import { APP_NAME } from '@/shared/constants'
import { DEPARTMENT_LABEL, ROLE_LABEL } from '@/shared/labels'

const auth = useAuthStore()

const roleText = computed(() => auth.roles.map((role) => ROLE_LABEL[role]).join(' / ') || '—')
const departmentText = computed(() =>
  auth.department ? DEPARTMENT_LABEL[auth.department] : '—',
)

/**
 * 快捷入口直接复用菜单表（`app/menu.ts`）：它已经是"谁能看到哪个入口"的唯一来源，
 * 侧栏与路由守卫都吃它。首页这里再抄一份必然漂移——典型症状是"菜单里有、首页没有"。
 */
const shortcuts = computed(() => visibleMenus(auth.roles).filter((menu) => menu.path !== '/'))
</script>

<template>
  <div class="home">
    <!-- 头部：Sentra 的落地页抬头（eyebrow + 点阵大标题） -->
    <header
      class="home__head animate-fade-up"
      style="animation-delay: 0.05s"
    >
      <p class="home__eyebrow">
        SUPPLIER PERFORMANCE EVALUATION
      </p>
      <h1 class="home__title font-display">
        {{ APP_NAME }}
      </h1>
    </header>

    <!-- bento 栅格（照提示词的 Products：3 列、rounded-2xl、延迟阶梯入场） -->
    <div class="home__grid">
      <section
        class="home__card animate-fade-up"
        style="animation-delay: 0.15s"
      >
        <h2 class="home__card-title">
          当前登录
        </h2>
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
      </section>

      <section
        class="home__card animate-fade-up"
        style="animation-delay: 0.3s"
      >
        <h2 class="home__card-title">
          可用功能
        </h2>
        <p
          v-if="shortcuts.length === 0"
          class="home__empty"
        >
          当前角色没有可用的功能入口。
        </p>
        <ul
          v-else
          class="home__links"
        >
          <li
            v-for="menu in shortcuts"
            :key="menu.path"
          >
            <RouterLink
              class="home__link"
              :to="menu.path"
            >
              <span>{{ menu.title }}</span>
              <span class="home__link-arrow">→</span>
            </RouterLink>
          </li>
        </ul>
      </section>

      <section
        class="home__card home__card--muted animate-fade-up"
        style="animation-delay: 0.45s"
      >
        <h2 class="home__card-title">
          说明
        </h2>
        <p class="home__note">
          左侧菜单按角色显示可用功能；没有权限的入口不会出现。
        </p>
        <p class="home__note">
          越权访问仍由后端拒绝并留痕，界面隐藏入口只是体验。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ---- 抬头 ---- */
.home__head {
  padding: 8px 4px 0;
}

.home__eyebrow {
  margin: 0 0 10px;
  font-size: 11px;
  letter-spacing: 0.3em;
  color: rgb(0 0 0 / 55%);
  text-transform: uppercase;
}

.home__title {
  margin: 0;
  font-size: clamp(1.75rem, 4vw, 3rem);
  line-height: 1.05;
  color: #000;
  letter-spacing: -0.01em;
}

/* ---- bento 栅格 ---- */
.home__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

@media (min-width: 900px) {
  .home__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

.home__card {
  padding: 20px;
  background: #fff;
  border-radius: 1rem;
  box-shadow: 0 1px 2px rgb(0 0 0 / 6%);
}

/* 第三格用提示词里的次级卡片色 #EBE8EB */
.home__card--muted {
  background: #ebe8eb;
  box-shadow: none;
}

.home__card-title {
  margin: 0 0 14px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: rgb(0 0 0 / 60%);
  text-transform: uppercase;
}

/* ---- 快捷入口 ---- */
.home__links {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.home__link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  font-size: 14px;
  color: #000;
  text-decoration: none;
  background: #ebe8eb;
  border-radius: 0.75rem;
  transition:
    background-color 0.3s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

.home__link:hover {
  background: #e0dce0;
  transform: translateX(2px);
}

.home__link-arrow {
  color: rgb(0 0 0 / 35%);
}

.home__empty,
.home__note {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  color: rgb(0 0 0 / 65%);
}

.home__empty {
  color: rgb(0 0 0 / 45%);
}
</style>
