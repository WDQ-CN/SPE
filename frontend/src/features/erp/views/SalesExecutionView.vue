<script setup lang="ts">
import { onMounted, ref } from 'vue'

import {
  listCompanies,
  listCustomers,
  listItems,
  listPlants,
  listWarehouses,
  type CompanyOption,
  type CustomerOption,
  type ItemOption,
  type PlantOption,
  type WarehouseOption,
} from '@/features/erp/api'
import ReceiptPanel from '@/features/erp/components/ReceiptPanel.vue'
import ReturnPanel from '@/features/erp/components/ReturnPanel.vue'
import SalesInvoicePanel from '@/features/erp/components/SalesInvoicePanel.vue'
import ShipmentPanel from '@/features/erp/components/ShipmentPanel.vue'

const companies = ref<CompanyOption[]>([])
const customers = ref<CustomerOption[]>([])
const items = ref<ItemOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const plants = ref<PlantOption[]>([])
const optionsLoading = ref(false)
const optionsReady = ref(false)
const optionsError = ref('')

async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const [companyList, customerList, itemList, warehouseList, plantList] = await Promise.all([
      listCompanies(),
      listCustomers(),
      listItems(),
      listWarehouses(),
      listPlants(),
    ])
    companies.value = companyList
    customers.value = customerList
    items.value = itemList
    warehouses.value = warehouseList
    plants.value = plantList
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
    eyebrow="ERP / ORDER TO CASH"
    title="销售执行"
    description="按订单剩余量发货、开票；客户收款核销发票；退货回库并生成贷项"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/sales/orders"
      >
        ← 返回销售订单
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
      label="发货"
      lazy
    >
      <ShipmentPanel
        :companies="companies"
        :customers="customers"
        :items="items"
        :warehouses="warehouses"
        :plants="plants"
      />
    </el-tab-pane>
    <el-tab-pane
      label="销售发票"
      lazy
    >
      <SalesInvoicePanel
        :companies="companies"
        :customers="customers"
      />
    </el-tab-pane>
    <el-tab-pane
      label="收款"
      lazy
    >
      <ReceiptPanel
        :companies="companies"
        :customers="customers"
      />
    </el-tab-pane>
    <el-tab-pane
      label="退货"
      lazy
    >
      <ReturnPanel
        :companies="companies"
        :customers="customers"
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
