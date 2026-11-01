<script setup lang="ts">
import { computed } from 'vue'

import { visibleMenus } from '@/app/menu'
import { useAuthStore } from '@/features/auth/store'
import { DEPARTMENT_LABEL } from '@/shared/labels'

const auth = useAuthStore()

const departmentText = computed(() =>
  auth.department ? DEPARTMENT_LABEL[auth.department] : '—',
)

const shortcuts = computed(() => visibleMenus(auth.roles).filter((menu) => menu.path !== '/'))
</script>

<template>
  <PageHeader
    eyebrow="WORKSPACE / OVERVIEW"
    title="工作台"
    :description="`${auth.roleText || '—'} · ${departmentText}`"
  />

  <section class="home-overview">
    <div class="home-profile">
      <p class="home-kicker">
        当前登录 / Current session
      </p>
      <div class="home-name">
        {{ auth.name || '—' }}
      </div>
      <div class="home-meta-grid">
        <div>
          <span>角色</span>
          <strong>{{ auth.roleText || '—' }}</strong>
        </div>
        <div>
          <span>部门</span>
          <strong>{{ departmentText }}</strong>
        </div>
        <div>
          <span>可用入口</span>
          <strong class="home-number">{{ shortcuts.length }}<small>个模块</small></strong>
        </div>
      </div>
    </div>
    <aside class="home-note">
      <p class="home-kicker">
        说明 / Note
      </p>
      <p>左侧菜单按角色显示可用功能；没有权限的入口不会出现。</p>
      <p>越权访问仍由后端拒绝并留痕，界面隐藏入口只是体验。</p>
    </aside>
  </section>

  <section class="home-index">
    <div class="home-section-head">
      <h2>可用入口</h2>
      <span>{{ String(shortcuts.length).padStart(2, '0') }} modules</span>
    </div>
    <p
      v-if="shortcuts.length === 0"
      class="home-empty"
    >
      当前角色没有可用的功能入口。
    </p>
    <ul
      v-else
      class="home-links"
    >
      <li
        v-for="(menu, index) in shortcuts"
        :key="menu.path"
      >
        <RouterLink
          class="home-link"
          :to="menu.path"
        >
          <span class="home-link-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="home-link-title">{{ menu.title }}</span>
          <span class="home-link-arrow">→</span>
        </RouterLink>
      </li>
    </ul>
  </section>

  <section class="home-conventions">
    <div class="home-section-head">
      <h2>工作台约定</h2>
      <span>SYSTEM NOTES</span>
    </div>
    <div class="home-convention-grid">
      <div class="home-convention">
        <span>01</span>
        <div>
          <h3>数据口径</h3>
          <p>分数以已生效评估结果为准；试算结果只用于预览，不作为最终结论。</p>
        </div>
      </div>
      <div class="home-convention">
        <span>02</span>
        <div>
          <h3>审批留痕</h3>
          <p>审批动作与越权访问都会写入审计日志；界面隐藏入口不等于权限控制。</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.home-overview {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr);
  gap: 52px;
  padding-bottom: 30px;
  border-bottom: 1px solid var(--rule);
}

.home-kicker {
  margin: 0;
  color: var(--muted-2);
  font-size: 8.5px;
  line-height: 13px;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.home-name {
  margin: 9px 0 22px;
  font-family: var(--font-serif);
  font-size: 44px;
  line-height: 50px;
  font-weight: 500;
  letter-spacing: -1.4px;
}

.home-meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--ink);
}

.home-meta-grid > div {
  min-width: 0;
  padding: 12px 18px 0 0;
  border-right: 1px solid var(--rule);
}

.home-meta-grid > div + div {
  padding-left: 18px;
}

.home-meta-grid > div:last-child {
  border-right: 0;
}

.home-meta-grid span {
  display: block;
  color: var(--muted-2);
  font-size: 8.5px;
  line-height: 13px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.home-meta-grid strong {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-meta-grid .home-number {
  font-family: var(--font-serif);
  font-size: 22px;
  line-height: 25px;
  font-weight: 500;
  color: var(--ink);
}

.home-number small {
  margin-left: 4px;
  color: var(--muted);
  font-family: var(--font-sans);
  font-size: 9px;
  font-weight: 400;
}

.home-note {
  padding-left: 26px;
  border-left: 1px solid var(--rule);
}

.home-note p {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 21px;
}

.home-index {
  margin-top: 28px;
}

.home-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.home-section-head h2 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 22px;
  line-height: 29px;
  font-weight: 500;
  letter-spacing: -0.4px;
}

.home-section-head span {
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.02em;
}

.home-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 44px;
  padding: 0;
  margin: 14px 0 0;
  list-style: none;
  border-top: 1px solid var(--ink);
}

.home-link {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 54px;
  color: var(--ink-secondary);
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
  transition: color 160ms ease, padding-left 160ms ease;
}

.home-link:hover {
  color: var(--accent);
  padding-left: 4px;
}

.home-link-index {
  width: 24px;
  flex: none;
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

.home-link-title {
  flex: 1;
  min-width: 0;
  font-family: var(--font-serif);
  font-size: 16px;
  line-height: 23px;
  font-weight: 500;
}

.home-link-arrow {
  color: var(--muted-2);
  font-size: 13px;
}

.home-empty {
  margin: 14px 0 0;
  padding: 16px 0;
  border-top: 1px solid var(--ink);
  border-bottom: 1px solid var(--rule);
  color: var(--muted);
  font-size: 12px;
}

.home-conventions {
  margin-top: 34px;
}

.home-convention-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 14px;
  border-top: 1px solid var(--ink);
}

.home-convention {
  display: flex;
  gap: 16px;
  padding: 16px 32px 4px 0;
}

.home-convention + .home-convention {
  padding-left: 32px;
  border-left: 1px solid var(--rule);
}

.home-convention > span {
  color: var(--muted-2);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 16px;
}

.home-convention h3 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 22px;
  font-weight: 500;
}

.home-convention p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 20px;
}

@media (max-width: 900px) {
  .home-convention-grid {
    grid-template-columns: 1fr;
  }

  .home-convention + .home-convention {
    padding-left: 0;
    border-left: 0;
    border-top: 1px solid var(--rule);
  }
}

@media (max-width: 900px) {
  .home-overview,
  .home-links {
    grid-template-columns: 1fr;
  }

  .home-overview {
    gap: 24px;
  }

  .home-note {
    padding: 18px 0 0;
    border-top: 1px solid var(--rule);
    border-left: 0;
  }

  .home-name {
    font-size: 34px;
    line-height: 40px;
  }
}
</style>
