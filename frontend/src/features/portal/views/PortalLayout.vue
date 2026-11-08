<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { portalLogout, portalUser } from '@/features/portal/api'

const router = useRouter()
const route = useRoute()

const active = computed(() => (route.path.startsWith('/portal/rfqs') ? 'rfqs' : 'auctions'))

async function logout(): Promise<void> {
  await portalLogout().catch(() => undefined)
  await router.replace({ name: 'portal-login' })
}
</script>

<template>
  <div class="portal-shell">
    <header class="portal-header">
      <div class="portal-brand">
        <span class="portal-brand__mark">S</span>
        <div>
          <strong>SPE Supplier Portal</strong>
          <small>{{ portalUser?.supplierName || '供应商门户' }}</small>
        </div>
      </div>
      <nav
        class="portal-nav"
        aria-label="门户导航"
      >
        <RouterLink
          to="/portal/auctions"
          :class="{ 'is-active': active === 'auctions' }"
        >
          竞价
        </RouterLink>
        <RouterLink
          to="/portal/rfqs"
          :class="{ 'is-active': active === 'rfqs' }"
        >
          询价
        </RouterLink>
      </nav>
      <div class="portal-user">
        <span>{{ portalUser?.loginName }}</span>
        <el-button
          link
          @click="logout"
        >
          退出
        </el-button>
      </div>
    </header>
    <main class="portal-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.portal-shell {
  min-height: 100vh;
  background: var(--paper);
}

.portal-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 14px 32px;
  background: var(--surface);
  border-bottom: 1px solid var(--rule);
}

.portal-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.portal-brand__mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--ink);
  border-radius: 999px;
  font-family: var(--font-serif);
}

.portal-brand strong {
  display: block;
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 600;
}

.portal-brand small {
  display: block;
  color: var(--muted);
  font-size: 11px;
}

.portal-nav {
  display: flex;
  gap: 16px;
  margin-left: 24px;
}

.portal-nav a {
  padding: 6px 0;
  border-bottom: 1px solid transparent;
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
}

.portal-nav a.is-active {
  border-color: var(--ink);
  color: var(--ink);
}

.portal-user {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}

.portal-main {
  max-width: 1180px;
  margin: 0 auto;
  padding: 32px;
}

@media (max-width: 720px) {
  .portal-header {
    flex-wrap: wrap;
    padding: 12px 16px;
  }

  .portal-main {
    padding: 20px 16px;
  }
}
</style>
