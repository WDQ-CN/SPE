<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { listCompanies, listItems, type CompanyOption, type ItemOption } from '@/features/erp/api'
import BomPanel from '@/features/erp/components/BomPanel.vue'

const companies = ref<CompanyOption[]>([])
const items = ref<ItemOption[]>([])
const loading = ref(false)
const ready = ref(false)
const error = ref('')

async function loadOptions(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [companyList, itemList] = await Promise.all([listCompanies(), listItems()])
    companies.value = companyList
    items.value = itemList
    ready.value = true
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '主数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadOptions())
</script>

<template>
  <PageHeader
    eyebrow="ERP / MANUFACTURING"
    title="BOM 维护"
    description="多版本 BOM、组件用量；生效后同物料旧版本停用"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/manufacturing/work-orders"
      >
        ← 返回生产工单
      </RouterLink>
    </template>
  </PageHeader>

  <el-alert
    v-if="error"
    class="page-alert"
    type="error"
    :title="error"
    :closable="false"
    show-icon
  >
    <el-button
      link
      @click="loadOptions"
    >
      重试
    </el-button>
  </el-alert>

  <div
    v-if="loading"
    v-loading="loading"
    class="options-loading"
  />

  <BomPanel
    v-else-if="ready"
    :companies="companies"
    :items="items"
  />
</template>

<style scoped>
.back-link {
  color: var(--muted);
  font-size: 12px;
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
}

.options-loading {
  min-height: 220px;
}
</style>
