<script setup lang="ts">
import { onMounted, ref } from 'vue'

import {
  listCompanies,
  listItems,
  listPlants,
  type CompanyOption,
  type ItemOption,
  type PlantOption,
} from '@/features/erp/api'
import GoodsReceiptPanel from '@/features/erp/components/GoodsReceiptPanel.vue'
import RequisitionPanel from '@/features/erp/components/RequisitionPanel.vue'
import InvoicePanel from '@/features/erp/components/InvoicePanel.vue'
import PaymentPanel from '@/features/erp/components/PaymentPanel.vue'

const companies = ref<CompanyOption[]>([])
const plants = ref<PlantOption[]>([])
const items = ref<ItemOption[]>([])
const optionsLoading = ref(false)
const optionsReady = ref(false)
const optionsError = ref('')

async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const [companyList, plantList, itemList] = await Promise.all([
      listCompanies(),
      listPlants(),
      listItems(),
    ])
    companies.value = companyList
    plants.value = plantList
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
    eyebrow="ERP / PROCURE TO PAY"
    title="采购执行"
    description="请购审批/转 PO、收货过账/冲销、供应商发票三单匹配与批准、付款申请/审批/登记"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/procurement/orders"
      >
        ← 返回采购订单
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
      label="请购"
      lazy
    >
      <RequisitionPanel
        :companies="companies"
        :plants="plants"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="收货"
      lazy
    >
      <GoodsReceiptPanel :companies="companies" />
    </el-tab-pane>
    <el-tab-pane
      label="供应商发票"
      lazy
    >
      <InvoicePanel :companies="companies" />
    </el-tab-pane>
    <el-tab-pane
      label="付款"
      lazy
    >
      <PaymentPanel :companies="companies" />
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
