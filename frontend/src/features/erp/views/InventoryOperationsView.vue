<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { listCompanies, listItems, listWarehouses, type CompanyOption, type ItemOption, type WarehouseOption } from '@/features/erp/api'
import CountPanel from '@/features/erp/components/CountPanel.vue'
import ReservationFreezePanel from '@/features/erp/components/ReservationFreezePanel.vue'
import TransferPanel from '@/features/erp/components/TransferPanel.vue'

const companies = ref<CompanyOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const items = ref<ItemOption[]>([])
const optionsLoading = ref(false)
const optionsReady = ref(false)
const optionsError = ref('')

async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const [companyList, warehouseList, itemList] = await Promise.all([
      listCompanies(),
      listWarehouses(),
      listItems(),
    ])
    companies.value = companyList
    warehouses.value = warehouseList
    items.value = itemList
    optionsReady.value = true
  } catch (caught) {
    optionsError.value = caught instanceof Error ? caught.message : '主数据加载失败'
  } finally {
    optionsLoading.value = false
  }
}

onMounted(() => void loadOptions())
</script>

<template>
  <PageHeader
    eyebrow="ERP / INVENTORY OPERATIONS"
    title="库存作业"
    description="预留与冻结、跨仓调拨、盘点差异过账；库存移动仍由服务端生成"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/inventory"
      >
        ← 返回库存存量
      </RouterLink>
    </template>
  </PageHeader>

  <el-alert
    v-if="optionsError"
    class="page-alert"
    type="error"
    :title="optionsError"
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
    v-if="optionsLoading"
    v-loading="optionsLoading"
    class="options-loading"
  />

  <el-tabs
    v-else-if="optionsReady"
    class="operations-tabs"
  >
    <el-tab-pane
      label="预留 / 冻结"
      lazy
    >
      <ReservationFreezePanel
        :companies="companies"
        :warehouses="warehouses"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="调拨"
      lazy
    >
      <TransferPanel
        :companies="companies"
        :warehouses="warehouses"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="盘点"
      lazy
    >
      <CountPanel
        :companies="companies"
        :warehouses="warehouses"
        :items="items"
      />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.back-link {
  color: var(--muted);
  font-size: 12px;
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
}

.back-link:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.options-loading {
  min-height: 220px;
}

.operations-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
</style>
